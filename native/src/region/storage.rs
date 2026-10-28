//! Bounded MCA preservation and atomic publication. The caller serializes world I/O.
use super::{
    HEADER, SECTOR,
    codec::{Compression, Record},
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
static TEMP_ID: AtomicU64 = AtomicU64::new(0);

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
        entries: Vec<StoredRecord>,
    },
}
impl Records {
    pub fn cached(path: &Path, slots: &[usize]) -> Result<Self, String> {
        let mut source = Source::open(path, false)?;
        let mut entries = Vec::with_capacity(slots.len());
        for &slot in slots {
            let record = source
                .record(slot)?
                .ok_or_else(|| format!("invalid cached MCA location at slot {slot}"))?;
            Compression::from_id(record.codec)?;
            entries.push(record);
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
            Self::Generated(records) => records[index].bytes.len() + 1,
            Self::Cached { entries, .. } => entries[index].payload,
        }
    }
    fn write(&mut self, index: usize, output: &mut impl Write) -> Result<(), String> {
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
                let record = entries[index];
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
impl Drop for PendingFile {
    fn drop(&mut self) {
        if !self.published {
            let _ = fs::remove_file(&self.path);
        }
    }
}

pub(super) fn publish(
    mut original: Source,
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
    let mut padding = Vec::with_capacity(records.len());
    for (index, &slot) in slots.iter().enumerate() {
        let offset = end / SECTOR as u64;
        let length = records.payload_len(index) as u64 + 4;
        let sectors = length.div_ceil(SECTOR as u64);
        if sectors > 255 || offset > 0x00ff_ffff {
            return Err("generated MCA record exceeds the inline Anvil sector limit".into());
        }
        original.header[slot * 4..slot * 4 + 4]
            .copy_from_slice(&(((offset as u32) << 8) | sectors as u32).to_be_bytes());
        original.header[SECTOR + slot * 4..SECTOR + slot * 4 + 4]
            .copy_from_slice(&timestamp.to_be_bytes());
        padding.push((sectors * SECTOR as u64 - length) as usize);
        end += sectors * SECTOR as u64;
    }
    let parent = path.parent().ok_or("MCA path needs a parent directory")?;
    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let temp = parent.join(format!(
        ".retina-{}-{}.mca.tmp",
        std::process::id(),
        TEMP_ID.fetch_add(1, Ordering::Relaxed)
    ));
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp)
        .map_err(|e| e.to_string())?;
    let mut pending = PendingFile {
        path: temp,
        published: false,
    };
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
    for (index, pad) in padding.into_iter().enumerate() {
        records.write(index, &mut output)?;
        output
            .write_all(&zeroes[..pad])
            .map_err(|e| e.to_string())?;
    }
    let file = output.into_inner().map_err(|e| e.to_string())?;
    file.sync_all().map_err(|e| e.to_string())?;
    drop(file);
    drop(original);
    drop(records);
    fs::rename(&pending.path, path)
        .map_err(|e| format!("publish region {}: {e}", path.display()))?;
    pending.published = true;
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
        let length = publish(Source::open(&path, true).unwrap(), &[8], records(7), &path).unwrap();
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
            &[0],
            records(200_000),
            &cached,
        )
        .unwrap();
        let incoming = Records::cached(&cached, &[0]).unwrap();
        publish(Source::open(&path, true).unwrap(), &[0], incoming, &path).unwrap();
        assert_eq!(
            &fs::read(&cached).unwrap()[HEADER..],
            &fs::read(&path).unwrap()[HEADER..]
        );
        // Reload then truncate after validation. The payload exceeds the read buffer.
        let incoming = Records::cached(&cached, &[0]).unwrap();
        let before = fs::read(&path).unwrap();
        OpenOptions::new()
            .write(true)
            .truncate(true)
            .open(&cached)
            .unwrap();
        assert!(publish(Source::open(&path, true).unwrap(), &[1], incoming, &path).is_err());
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
            &[0],
            records(200_000),
            &path,
        )
        .unwrap();
        let before = fs::read(&path).unwrap();
        for mut incoming in [records(200_000), Records::cached(&path, &[0]).unwrap()] {
            let error = incoming.write(0, &mut FailsAfter(100)).unwrap_err();
            assert!(error.contains("injected write failure"));
            assert_eq!(fs::read(&path).unwrap(), before);
        }
        assert!(
            publish(
                Source::open(&path, true).unwrap(),
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
        publish(Source::open(&path, true).unwrap(), &[0], records(10), &path).unwrap();
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
        assert!(Records::cached(&path, &[0]).is_err()); // Preview codec contract is unchanged.
        fs::write(&path, &good).unwrap();
        let destination = directory.0.join("occupied");
        fs::create_dir(&destination).unwrap();
        fs::write(destination.join("keep"), b"player data").unwrap();
        assert!(
            publish(
                Source::open(&path, false).unwrap(),
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
