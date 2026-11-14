//! Bounded MCA preservation and atomic publication. The caller serializes world I/O.
use super::{
    HEADER, SECTOR,
    codec::{Compression, MAX_INLINE_BYTES, Record},
};
use std::{
    fs::{self, File, OpenOptions},
    io::{self, BufReader, BufWriter, Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};

const BUFFER: usize = 64 * 1024;
const WRITE_BUFFER: usize = 1024 * 1024;
const EXTERNAL: u8 = 0x80;
static TEMP_ID: AtomicU64 = AtomicU64::new(0);

fn external_path(parent: &Path, region: (i32, i32), slot: usize) -> PathBuf {
    let x = region.0 * 32 + (slot % 32) as i32;
    let z = region.1 * 32 + (slot / 32) as i32;
    parent.join(format!("c.{x}.{z}.mcc"))
}

pub(super) struct Source {
    reader: Option<BufReader<File>>,
    header: [u8; HEADER],
    length: u64,
    position: u64,
}
#[derive(Clone, Copy)]
pub(super) struct StoredRecord {
    offset: u64,
    payload: usize,
    codec: u8,
}
impl Source {
    pub fn open(path: &Path, allow_empty: bool) -> Result<Self, String> {
        let file = match File::open(path) {
            Ok(file) => Some(file),
            Err(error) if allow_empty && error.kind() == io::ErrorKind::NotFound => None,
            Err(error) => return Err(format!("read region {}: {error}", path.display())),
        };
        let mut source = Self {
            reader: file.map(|f| BufReader::with_capacity(BUFFER, f)),
            header: [0; HEADER],
            length: 0,
            position: 0,
        };
        if let Some(reader) = &mut source.reader {
            source.length = reader.seek(SeekFrom::End(0)).map_err(|e| e.to_string())?;
            reader.rewind().map_err(|e| e.to_string())?;
            if source.length != 0 || !allow_empty {
                reader
                    .read_exact(&mut source.header)
                    .map_err(|e| format!("truncated MCA header {}: {e}", path.display()))?;
                source.position = HEADER as u64;
            }
        }
        Ok(source)
    }
    pub fn len(&self) -> u64 {
        self.length.max(HEADER as u64)
    }
    fn seek(&mut self, offset: u64) -> Result<&mut BufReader<File>, String> {
        let reader = self.reader.as_mut().ok_or("missing MCA source")?;
        let delta = offset as i128 - self.position as i128;
        if let Ok(delta) = i64::try_from(delta) {
            reader.seek_relative(delta).map_err(|e| e.to_string())?;
        } else {
            reader
                .seek(SeekFrom::Start(offset))
                .map_err(|e| e.to_string())?;
        }
        self.position = offset;
        Ok(reader)
    }
    pub fn record(&mut self, slot: usize) -> Result<Option<StoredRecord>, String> {
        let location = u32::from_be_bytes(self.header[slot * 4..slot * 4 + 4].try_into().unwrap());
        if location == 0 {
            return Ok(None);
        }
        let offset = (location >> 8) as u64 * SECTOR as u64;
        let length = (location & 255) as u64 * SECTOR as u64;
        if offset < HEADER as u64 || length == 0 || offset + length > self.length {
            return Err(format!("invalid MCA sector location at slot {slot}"));
        }
        let mut prefix = [0; 5];
        self.seek(offset)?
            .read_exact(&mut prefix)
            .map_err(|e| e.to_string())?;
        self.position += prefix.len() as u64;
        let payload = u32::from_be_bytes(prefix[..4].try_into().unwrap()) as usize;
        if payload < 1 || payload + 4 > length as usize {
            return Err(format!("invalid MCA record length at slot {slot}"));
        }
        Ok(Some(StoredRecord {
            offset,
            payload,
            codec: prefix[4],
        }))
    }
    fn copy(&mut self, offset: u64, length: u64, output: &mut impl Write) -> Result<(), String> {
        let copied =
            io::copy(&mut self.seek(offset)?.take(length), output).map_err(|e| e.to_string())?;
        self.position += copied;
        if copied != length {
            return Err("MCA source truncated during publication".into());
        }
        Ok(())
    }
}

pub(super) enum Records {
    Generated(Vec<Record>),
    Cached {
        source: Source,
        entries: Vec<(StoredRecord, Option<PathBuf>)>,
    },
}
impl Records {
    pub fn cached(path: &Path, region: (i32, i32), slots: &[usize]) -> Result<Self, String> {
        let mut source = Source::open(path, false)?;
        let mut entries = Vec::with_capacity(slots.len());
        for &slot in slots {
            let record = source
                .record(slot)?
                .ok_or_else(|| format!("invalid cached MCA location at slot {slot}"))?;
            let external =
                if record.codec == (EXTERNAL | Compression::None.id()) && record.payload == 1 {
                    let parent = path
                        .parent()
                        .ok_or("cached MCA path needs a parent directory")?;
                    Some(external_path(parent, region, slot))
                } else {
                    Compression::from_id(record.codec)?;
                    None
                };
            entries.push((record, external));
        }
        Ok(Self::Cached { source, entries })
    }
    pub fn len(&self) -> usize {
        match self {
            Self::Generated(records) => records.len(),
            Self::Cached { entries, .. } => entries.len(),
        }
    }
    fn payload_len(&self, index: usize) -> usize {
        match self {
            Self::Generated(records) => {
                let record = &records[index];
                record.bytes.len() + 1
            }
            Self::Cached { entries, .. } => entries[index].0.payload,
        }
    }
    fn is_external(&self, index: usize) -> bool {
        match self {
            Self::Generated(records) => {
                let record = &records[index];
                record.codec == Compression::None && record.bytes.len() > MAX_INLINE_BYTES
            }
            Self::Cached { entries, .. } => entries[index].1.is_some(),
        }
    }
    fn stage_external(
        &mut self,
        index: usize,
        destination: &Path,
    ) -> Result<Option<PendingFile>, String> {
        let mut cached = match self {
            Self::Generated(_) => None,
            Self::Cached { entries, .. } => {
                let path = entries[index]
                    .1
                    .as_ref()
                    .expect("external cache record has a sidecar");
                let source = File::open(path)
                    .map_err(|error| format!("read cached chunk {}: {error}", path.display()))?;
                if path
                    .parent()
                    .map(Path::canonicalize)
                    .transpose()
                    .map_err(|e| e.to_string())?
                    == destination
                        .parent()
                        .map(Path::canonicalize)
                        .transpose()
                        .map_err(|e| e.to_string())?
                {
                    return Ok(None);
                }
                Some(source)
            }
        };
        let (pending, mut file) = PendingFile::create(destination, "mcc")?;
        if let Some(source) = &mut cached {
            io::copy(source, &mut file).map_err(|e| e.to_string())?;
        } else if let Self::Generated(records) = self {
            file.write_all(&std::mem::take(&mut records[index].bytes))
                .map_err(|e| e.to_string())?;
        }
        file.sync_all().map_err(|e| e.to_string())?;
        Ok(Some(pending))
    }
    fn write(
        &mut self,
        index: usize,
        external: bool,
        output: &mut impl Write,
    ) -> Result<(), String> {
        if external {
            return output
                .write_all(&[0, 0, 0, 1, EXTERNAL | Compression::None.id()])
                .map_err(|e| e.to_string());
        }
        match self {
            Self::Generated(records) => {
                let record = &mut records[index];
                let bytes = std::mem::take(&mut record.bytes);
                output
                    .write_all(&((bytes.len() + 1) as u32).to_be_bytes())
                    .map_err(|e| e.to_string())?;
                output
                    .write_all(&[record.codec.id()])
                    .map_err(|e| e.to_string())?;
                output.write_all(&bytes).map_err(|e| e.to_string())
            }
            Self::Cached { source, entries } => {
                let record = entries[index].0;
                output
                    .write_all(&(record.payload as u32).to_be_bytes())
                    .map_err(|e| e.to_string())?;
                output
                    .write_all(&[record.codec])
                    .map_err(|e| e.to_string())?;
                source.copy(record.offset + 5, record.payload as u64 - 1, output)
            }
        }
    }
}
struct PendingFile {
    path: PathBuf,
    published: bool,
}
impl PendingFile {
    fn create(destination: &Path, extension: &str) -> Result<(Self, File), String> {
        let path = destination.with_file_name(format!(
            ".retina-{}-{}.{extension}.tmp",
            std::process::id(),
            TEMP_ID.fetch_add(1, Ordering::Relaxed)
        ));
        let file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .map_err(|e| e.to_string())?;
        Ok((
            Self {
                path,
                published: false,
            },
            file,
        ))
    }

    fn rename(&mut self, destination: &Path) -> Result<(), String> {
        fs::rename(&self.path, destination)
            .map_err(|e| format!("publish {}: {e}", destination.display()))?;
        self.path = destination.to_owned();
        Ok(())
    }
}
impl Drop for PendingFile {
    fn drop(&mut self) {
        if !self.published {
            let _ = fs::remove_file(&self.path);
        }
    }
}

pub(super) fn publish(
    mut original: Source,
    region: (i32, i32),
    slots: &[usize],
    mut records: Records,
    path: &Path,
) -> Result<u64, String> {
    if slots.len() != records.len() {
        return Err("MCA record count does not match missing slots".into());
    }
    let original_end = original.len().div_ceil(SECTOR as u64) * SECTOR as u64;
    let mut end = original_end;
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_secs() as u32;
    let mut layout = Vec::with_capacity(records.len());
    for (index, &slot) in slots.iter().enumerate() {
        let offset = end / SECTOR as u64;
        let external = records.is_external(index);
        let length = if external {
            5
        } else {
            records.payload_len(index) as u64 + 4
        };
        let sectors = length.div_ceil(SECTOR as u64);
        if sectors > 255 || offset > 0x00ff_ffff {
            return Err("generated MCA record exceeds the inline Anvil sector limit".into());
        }
        original.header[slot * 4..slot * 4 + 4]
            .copy_from_slice(&(((offset as u32) << 8) | sectors as u32).to_be_bytes());
        original.header[SECTOR + slot * 4..SECTOR + slot * 4 + 4]
            .copy_from_slice(&timestamp.to_be_bytes());
        layout.push((external, (sectors * SECTOR as u64 - length) as usize));
        end += sectors * SECTOR as u64;
    }
    let parent = path.parent().ok_or("MCA path needs a parent directory")?;
    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let (mut pending, file) = PendingFile::create(path, "mca")?;
    let mut output = BufWriter::with_capacity(WRITE_BUFFER, file);
    output
        .write_all(&original.header)
        .map_err(|e| e.to_string())?;
    if original.length > HEADER as u64 {
        original.copy(HEADER as u64, original.length - HEADER as u64, &mut output)?;
    }
    let zeroes = [0; SECTOR];
    output
        .write_all(&zeroes[..(original_end - original.len()) as usize])
        .map_err(|e| e.to_string())?;
    let mut sidecars = Vec::new();
    for (index, (external, pad)) in layout.into_iter().enumerate() {
        if external {
            let destination = external_path(parent, region, slots[index]);
            if let Some(staged) = records.stage_external(index, &destination)? {
                sidecars.push((staged, destination));
            }
        }
        records.write(index, external, &mut output)?;
        output
            .write_all(&zeroes[..pad])
            .map_err(|e| e.to_string())?;
    }
    let file = output.into_inner().map_err(|e| e.to_string())?;
    file.sync_all().map_err(|e| e.to_string())?;
    drop(file);
    drop(original);
    drop(records);
    // Missing slots cannot reference their sidecars. Publish sidecars first so
    // any visible MCA stub refers to complete data. Interrupted writes can be retried.
    for (staged, destination) in &mut sidecars {
        staged.rename(destination)?;
    }
    #[cfg(unix)]
    if !sidecars.is_empty() {
        File::open(parent)
            .and_then(|directory| directory.sync_all())
            .map_err(|e| e.to_string())?;
    }
    pending.rename(path)?;
    pending.published = true;
    for (staged, _) in &mut sidecars {
        staged.published = true;
    }
    #[cfg(unix)]
    File::open(parent)
        .and_then(|directory| directory.sync_all())
        .map_err(|e| e.to_string())?;
    Ok(end)
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Directory(PathBuf);
    impl Directory {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "retina-stream-{}-{}",
                std::process::id(),
                TEMP_ID.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }
    }
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    fn records(size: usize) -> Records {
        Records::Generated(vec![Record {
            codec: Compression::Zlib,
            bytes: (0..size).map(|i| (i % 251) as u8).collect(),
        }])
    }

    #[test]
    fn raw_sidecars_survive_promotion_and_retry() {
        let directory = Directory::new();
        let preview = directory.0.join("preview/r.-2.1.mca");
        let saved = directory.0.join("saved/r.-2.1.mca");
        let region = (-2, 1);
        let raw = vec![19; MAX_INLINE_BYTES + 1];
        let inline = vec![37; MAX_INLINE_BYTES];
        publish(
            Source::open(&preview, true).unwrap(),
            region,
            &[0, 33, 34],
            Records::Generated(vec![
                Record {
                    codec: Compression::None,
                    bytes: inline.clone(),
                },
                Record {
                    codec: Compression::None,
                    bytes: raw.clone(),
                },
                Record {
                    codec: Compression::None,
                    bytes: raw.clone(),
                },
            ]),
            &preview,
        )
        .unwrap();
        let mut source = Source::open(&preview, false).unwrap();
        assert_eq!(source.record(0).unwrap().unwrap().codec, 3);
        let stub = source.record(33).unwrap().unwrap();
        assert_eq!((stub.codec, stub.payload), (0x83, 1));
        let sidecar = preview.with_file_name("c.-63.33.mcc");
        assert_eq!(fs::read(&sidecar).unwrap(), raw);
        assert!(!preview.with_file_name("c.-64.32.mcc").exists());
        publish(
            Source::open(&saved, true).unwrap(),
            region,
            &[5],
            records(8),
            &saved,
        )
        .unwrap();
        let before = fs::read(&saved).unwrap();
        let second_sidecar = preview.with_file_name("c.-62.33.mcc");
        fs::remove_file(&second_sidecar).unwrap();
        assert!(
            publish(
                Source::open(&saved, true).unwrap(),
                region,
                &[33, 34],
                Records::cached(&preview, region, &[33, 34]).unwrap(),
                &saved
            )
            .is_err()
        );
        assert_eq!(fs::read(&saved).unwrap(), before);
        let destination_sidecar = saved.with_file_name("c.-63.33.mcc");
        assert!(!destination_sidecar.exists());
        fs::write(&second_sidecar, &raw).unwrap();
        fs::write(&destination_sidecar, b"orphan from interrupted publish").unwrap();
        publish(
            Source::open(&saved, true).unwrap(),
            region,
            &[0, 33, 34],
            Records::cached(&preview, region, &[0, 33, 34]).unwrap(),
            &saved,
        )
        .unwrap();
        assert_eq!(fs::read(&destination_sidecar).unwrap(), raw);
        assert_eq!(fs::read(&sidecar).unwrap(), raw);
        let mut saved_source = Source::open(&saved, false).unwrap();
        assert_eq!(saved_source.record(5).unwrap().unwrap().codec, 2);
        assert_eq!(saved_source.record(33).unwrap().unwrap().codec, 0x83);
        assert_eq!(
            saved_source.record(0).unwrap().unwrap().payload,
            inline.len() + 1
        );
    }
    fn no_temps(directory: &Directory) {
        assert!(fs::read_dir(&directory.0).unwrap().all(|entry| {
            !entry
                .unwrap()
                .file_name()
                .to_string_lossy()
                .starts_with(".retina-")
        }));
    }
    #[test]
    fn generated_partial_preserves_payload_timestamp_and_unaligned_tail() {
        let directory = Directory::new();
        let path = directory.0.join("region.mca");
        assert_eq!(
            publish(
                Source::open(&path, true).unwrap(),
                (0, 0),
                &[3],
                records(5000),
                &path
            )
            .unwrap(),
            4 * SECTOR as u64
        );
        let mut output = OpenOptions::new().append(true).open(&path).unwrap();
        output.write_all(&[17, 31, 47]).unwrap();
        drop(output);
        let before = fs::read(&path).unwrap();
        let length = publish(
            Source::open(&path, true).unwrap(),
            (0, 0),
            &[8],
            records(7),
            &path,
        )
        .unwrap();
        let after = fs::read(&path).unwrap();
        assert_eq!(after.len() as u64, length);
        assert_eq!(&after[3 * 4..3 * 4 + 4], &before[3 * 4..3 * 4 + 4]);
        assert_eq!(
            &after[SECTOR + 3 * 4..SECTOR + 3 * 4 + 4],
            &before[SECTOR + 3 * 4..SECTOR + 3 * 4 + 4]
        );
        assert_eq!(&after[HEADER..before.len()], &before[HEADER..]);
        assert!(after[before.len()..5 * SECTOR].iter().all(|&v| v == 0));
        let mut source = Source::open(&path, false).unwrap();
        assert_eq!(source.record(3).unwrap().unwrap().payload, 5001);
        assert_eq!(source.record(8).unwrap().unwrap().payload, 8);
        assert!(source.record(9).unwrap().is_none());
        no_temps(&directory);
    }
    #[test]
    fn cached_promotion_copies_records_and_read_failure_preserves_destination() {
        let directory = Directory::new();
        let cached = directory.0.join("cached.mca");
        let path = directory.0.join("region.mca");
        publish(
            Source::open(&cached, true).unwrap(),
            (0, 0),
            &[0],
            records(200_000),
            &cached,
        )
        .unwrap();
        let incoming = Records::cached(&cached, (0, 0), &[0]).unwrap();
        publish(
            Source::open(&path, true).unwrap(),
            (0, 0),
            &[0],
            incoming,
            &path,
        )
        .unwrap();
        assert_eq!(
            &fs::read(&cached).unwrap()[HEADER..],
            &fs::read(&path).unwrap()[HEADER..]
        );
        // Reload then truncate after validation. The payload exceeds the read buffer.
        let incoming = Records::cached(&cached, (0, 0), &[0]).unwrap();
        let before = fs::read(&path).unwrap();
        OpenOptions::new()
            .write(true)
            .truncate(true)
            .open(&cached)
            .unwrap();
        assert!(
            publish(
                Source::open(&path, true).unwrap(),
                (0, 0),
                &[1],
                incoming,
                &path
            )
            .is_err()
        );
        assert_eq!(fs::read(&path).unwrap(), before);
        no_temps(&directory);
    }
    #[test]
    fn failed_record_writes_propagate_and_oversized_records_preserve_destination() {
        struct FailsAfter(usize);
        impl Write for FailsAfter {
            fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
                if self.0 == 0 {
                    return Err(io::Error::other("injected write failure"));
                }
                let count = self.0.min(bytes.len());
                self.0 -= count;
                Ok(count)
            }
            fn flush(&mut self) -> io::Result<()> {
                Ok(())
            }
        }
        let directory = Directory::new();
        let path = directory.0.join("region.mca");
        publish(
            Source::open(&path, true).unwrap(),
            (0, 0),
            &[0],
            records(200_000),
            &path,
        )
        .unwrap();
        let before = fs::read(&path).unwrap();
        for mut incoming in [
            records(200_000),
            Records::cached(&path, (0, 0), &[0]).unwrap(),
        ] {
            let error = incoming.write(0, false, &mut FailsAfter(100)).unwrap_err();
            assert!(error.contains("injected write failure"));
            assert_eq!(fs::read(&path).unwrap(), before);
        }
        assert!(
            publish(
                Source::open(&path, true).unwrap(),
                (0, 0),
                &[1],
                records(256 * SECTOR),
                &path
            )
            .is_err()
        );
        assert_eq!(fs::read(&path).unwrap(), before);
        no_temps(&directory);
    }

    #[test]
    fn malformed_sources_and_failed_rename_leave_existing_data_intact() {
        let directory = Directory::new();
        let path = directory.0.join("region.mca");
        fs::write(&path, [0; 7]).unwrap();
        assert!(Source::open(&path, true).is_err());
        fs::write(&path, []).unwrap();
        assert_eq!(Source::open(&path, true).unwrap().len(), HEADER as u64);
        assert!(Source::open(&path, false).is_err());
        publish(
            Source::open(&path, true).unwrap(),
            (0, 0),
            &[0],
            records(10),
            &path,
        )
        .unwrap();
        let good = fs::read(&path).unwrap();
        let mut bad = good.clone();
        bad[..4].copy_from_slice(&((100u32 << 8) | 1).to_be_bytes());
        fs::write(&path, &bad).unwrap();
        assert!(Source::open(&path, false).unwrap().record(0).is_err());
        assert_eq!(fs::read(&path).unwrap(), bad);
        bad = good.clone();
        bad[HEADER..HEADER + 4].copy_from_slice(&0u32.to_be_bytes());
        fs::write(&path, &bad).unwrap();
        assert!(Source::open(&path, false).unwrap().record(0).is_err());
        bad = good.clone();
        bad[HEADER + 4] = 0x82;
        fs::write(&path, &bad).unwrap();
        assert!(Source::open(&path, false).unwrap().record(0).is_ok()); // Existing external records are opaque.
        assert!(Records::cached(&path, (0, 0), &[0]).is_err()); // Preview codec contract is unchanged.
        fs::write(&path, &good).unwrap();
        let destination = directory.0.join("occupied");
        fs::create_dir(&destination).unwrap();
        fs::write(destination.join("keep"), b"player data").unwrap();
        assert!(
            publish(
                Source::open(&path, false).unwrap(),
                (0, 0),
                &[1],
                records(8),
                &destination
            )
            .is_err()
        );
        assert_eq!(fs::read(&path).unwrap(), good);
        assert_eq!(fs::read(destination.join("keep")).unwrap(), b"player data");
        no_temps(&directory);
    }
}
