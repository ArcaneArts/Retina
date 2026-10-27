//! Process tests. Mod parity uses actual captures; density semantics uses separately
//! compiled generation snapshots. Failed proofs retain isolated artifacts.
use fastnbt::Value;
use flate2::{Compression, read::ZlibDecoder, write::GzEncoder};
use retina_worldgen::world_definition::wire;
use std::{
    collections::BTreeMap,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    process::{Command, Output},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

fn scratch() -> PathBuf {
    let root = std::env::temp_dir().join(format!(
        "retina-headless-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir(&root).unwrap();
    root
}

// Allows parity proofs to exercise the exact release executable packaged for delivery.
fn executable() -> PathBuf {
    std::env::var_os("RETINA_HEADLESS_EXECUTABLE").map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_BIN_EXE_retina")))
}

fn command(root: &Path, dimension: &str, range: &str, output: &str, codec: &str) -> Command {
    let mut process = Command::new(executable());
    process
        .current_dir(root)
        .env_clear()
        .env("RAYON_NUM_THREADS", "2")
        .env("RETINA_REGION_THREADS", "2")
        .args([
            "generate",
            "--definition",
            "retina.dat",
            "--dimension",
            dimension,
            &format!("--region-min={range}"),
            &format!("--region-max={range}"),
            "--output",
            output,
            "--compression",
            codec,
        ]);
    process
}

fn summary(output: Output) -> serde_json::Value {
    assert!(
        output.status.success(),
        "CLI failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    serde_json::from_slice(&output.stdout).unwrap()
}

#[test]
fn executable_help_usage_and_real_definition_errors() {
    let root = scratch();
    let help = Command::new(executable())
        .arg("--help")
        .current_dir(&root)
        .output()
        .unwrap();
    assert!(help.status.success());
    assert!(String::from_utf8_lossy(&help.stdout).contains("inclusive"));
    let usage = Command::new(executable())
        .args(["generate", "--seed=1"])
        .output()
        .unwrap();
    assert_eq!(usage.status.code(), Some(2));
    let absent = command(&root, "minecraft:overworld", "0,0", "out", "zlib")
        .output()
        .unwrap();
    assert_eq!(absent.status.code(), Some(1));
    assert!(!root.join("out").exists());
    fs::write(root.join("retina.dat"), b"not gzip").unwrap();
    let corrupt = command(&root, "minecraft:overworld", "0,0", "out", "zlib")
        .output()
        .unwrap();
    assert_eq!(corrupt.status.code(), Some(1));
    assert!(String::from_utf8_lossy(&corrupt.stderr).contains("gzip"));
    assert_eq!(fs::read(root.join("retina.dat")).unwrap(), b"not gzip");
    let mut document = unsupported();
    for (version, dimension, expected) in [
        (
            3,
            "minecraft:overworld",
            "unsupported world definition format",
        ),
        (1, "minecraft:missing", "no dimension"),
        (1, "minecraft:overworld", "test unsupported generator"),
    ] {
        document.format_version = version;
        let mut gzip = GzEncoder::new(Vec::new(), Compression::best());
        gzip.write_all(&fastnbt::to_bytes(&document).unwrap())
            .unwrap();
        fs::write(root.join("retina.dat"), gzip.finish().unwrap()).unwrap();
        let failure = command(&root, dimension, "0,0", "out", "zlib")
            .output()
            .unwrap();
        assert_eq!(failure.status.code(), Some(1));
        assert!(
            String::from_utf8_lossy(&failure.stderr).contains(expected),
            "{}",
            String::from_utf8_lossy(&failure.stderr)
        );
    }
    assert!(!root.join("out").exists());
    fs::remove_dir_all(root).unwrap();
}

fn unsupported() -> wire::Document {
    wire::Document {
        format_version: 1,
        generation_version: 1,
        minecraft_version: "test".into(),
        data_version: 5023,
        exporter: "process test".into(),
        seed: i64::MIN,
        generate_structures: false,
        bonus_chest: false,
        datapacks: vec![],
        shared_types: wire::SharedTypes {
            materials: vec![],
            biomes: vec![],
        },
        dimensions: BTreeMap::from([(
            "minecraft:overworld".into(),
            wire::Dimension {
                parameters: wire::Parameters {
                    generator: "test".into(),
                    storage: None,
                    min_y: 0,
                    height: 64,
                    generator_depth: 64,
                    sea_level: 32,
                    spawn_height: 40,
                    skylight: true,
                    ceiling: false,
                    logical_height: 64,
                    coordinate_scale: 1.,
                    base_height: 32.,
                    amplitude: 8.,
                    frequency: 0.008,
                },
                material_palette: fastnbt::IntArray::new(vec![]),
                biome_palette: fastnbt::IntArray::new(vec![]),
                profile: None,
                unsupported_reason: Some("test unsupported generator".into()),
            },
        )]),
    }
}

#[derive(serde::Deserialize)]
struct Proof {
    name: String,
    definition: PathBuf,
    regions: PathBuf,
}

#[derive(serde::Deserialize)]
struct DensityProof {
    composed: PathBuf,
    baseline: PathBuf,
}

#[test]
#[ignore = "requires a GPU and RETINA_DENSITY_DEFINITIONS JSON containing separately compiled composed/baseline snapshots"]
fn saved_density_semantics_ignore_environment_overrides() {
    use retina_worldgen::world_definition::WorldDefinition;
    let proof: DensityProof = serde_json::from_slice(
        &fs::read(std::env::var("RETINA_DENSITY_DEFINITIONS").unwrap()).unwrap(),
    ).unwrap();
    let root = scratch();
    eprintln!("Density semantics proof artifacts: {}",root.display());
    let mut references = Vec::new();
    for (name,source,expected) in [("composed",proof.composed,true),("baseline",proof.baseline,false)] {
        let working=root.join(name);fs::create_dir(&working).unwrap();
        fs::copy(source,working.join("retina.dat")).unwrap();
        let before=fs::read(working.join("retina.dat")).unwrap();
        let world=WorldDefinition::read(working.join("retina.dat")).unwrap();
        assert_eq!(world.dimension("minecraft:overworld").unwrap().profile().unwrap()
            .registry_program.as_ref().unwrap().density_composition,expected);
        for mode in ["default","0","1"] {
            let mut process=command(&working,"minecraft:overworld","0,0",mode,"zlib");
            if mode!="default" {process.env("RETINA_DENSITY_COMPOSITION",mode);}
            let report=summary(process.output().unwrap());
            assert_eq!(report["generated_chunks"],1024);
            assert_eq!(report["seed"].as_i64(),Some(world.seed()));
            assert_eq!(report["data_version"].as_i64(),Some(world.data_version() as i64));
            let output=working.join(mode).join("region/r.0.0.mca");
            if mode=="default" {references.push(output);} else {compare(references.last().unwrap(),&output);}
        }
        assert_eq!(fs::read(working.join("retina.dat")).unwrap(),before);
    }
    let composed=fs::read(&references[0]).unwrap();let baseline=fs::read(&references[1]).unwrap();
    let different=(0..1024).filter(|&slot|chunk(&composed,slot)!=chunk(&baseline,slot)).count();
    assert!(different>0,"explicit generation variants must actually change terrain");
    println!("QA_EVT {}",serde_json::json!({"event":"saved_density_semantics","unchanged_chunks":4096,
        "explicitly_changed_chunks":different,"status":"pass"}));
    fs::remove_dir_all(root).unwrap();
}

#[test]
#[ignore = "requires a real GPU and RETINA_HEADLESS_PROOFS JSON listing actual mod definitions/captures"]
fn copied_definition_matches_mod_regions_in_fresh_processes() {
    let proofs: Vec<Proof> = serde_json::from_slice(
        &fs::read(std::env::var("RETINA_HEADLESS_PROOFS").expect("set RETINA_HEADLESS_PROOFS"))
            .unwrap(),
    )
    .unwrap();
    assert!(!proofs.is_empty());
    let root = scratch();
    eprintln!("Headless proof artifacts: {}", root.display());
    for (index, proof) in proofs.iter().enumerate() {
        let working = root.join(index.to_string());
        fs::create_dir(&working).unwrap();
        fs::copy(&proof.definition, working.join("retina.dat")).unwrap();
        assert_eq!(
            fs::read_dir(&working).unwrap().count(),
            1,
            "only retina.dat is supplied"
        );
        let before = fs::read(working.join("retina.dat")).unwrap();
        for (dimension, layout, codec) in [
            ("minecraft:overworld", "region", "zlib"),
            ("minecraft:the_nether", "DIM-1/region", "lz4"),
        ] {
            let report = summary(
                command(&working, dimension, "-2,1", "generated", codec)
                    .output()
                    .unwrap(),
            );
            assert_eq!(report["generated_chunks"], 1024);
            assert_eq!(report["preserved_chunks"], 0);
            let file = Path::new(layout).join("r.-2.1.mca");
            compare(
                &proof.regions.join(&file),
                &working.join("generated").join(&file),
            );
            println!(
                "QA_EVT {}",
                serde_json::json!({"event":"headless_mod_content_parity","case":proof.name,
                "dimension":dimension,"chunks":1024,"codec":codec,"report":report})
            );
        }
        let file = working.join("generated/region/r.-2.1.mca");
        let original = fs::read(&file).unwrap();
        let report = summary(
            command(&working, "minecraft:overworld", "-2,1", "generated", "zlib")
                .output()
                .unwrap(),
        );
        assert_eq!(report["generated_chunks"], 0);
        assert_eq!(report["preserved_chunks"], 1024);
        assert_eq!(
            fs::read(&file).unwrap(),
            original,
            "complete output preserved byte for byte"
        );
        // Remove one location entry, leaving every existing record intact. The
        // same writer must fill only that missing slot and preserve its peers.
        let mut partial = original;
        partial[..4].fill(0);
        fs::write(&file, partial).unwrap();
        let report = summary(
            command(&working, "minecraft:overworld", "-2,1", "generated", "zlib")
                .output()
                .unwrap(),
        );
        assert_eq!(report["generated_chunks"], 1);
        assert_eq!(report["preserved_chunks"], 1023);
        compare(&proof.regions.join("region/r.-2.1.mca"), &file);
        fs::write(working.join("generated/region/r.7.7.mca"), b"damaged").unwrap();
        let failure = command(&working, "minecraft:overworld", "7,7", "generated", "zlib")
            .output()
            .unwrap();
        assert_eq!(failure.status.code(), Some(1));
        assert!(String::from_utf8_lossy(&failure.stderr).contains("truncated MCA header"));
        assert_eq!(
            fs::read(working.join("generated/region/r.7.7.mca")).unwrap(),
            b"damaged"
        );
        assert_eq!(fs::read(working.join("retina.dat")).unwrap(), before);
        #[cfg(unix)]
        if index == 0 {
            cancellation(&working);
        }
    }
    fs::remove_dir_all(root).unwrap();
}

#[test]
#[ignore = "requires a GPU and RETINA_STRUCTURE_PROOFS JSON listing file-backed Java structure fixtures"]
fn copied_definition_matches_structure_regions_in_fresh_processes() {
    let proofs: Vec<Proof> = serde_json::from_slice(
        &fs::read(std::env::var("RETINA_STRUCTURE_PROOFS").expect("set RETINA_STRUCTURE_PROOFS"))
            .unwrap(),
    )
    .unwrap();
    assert!(!proofs.is_empty());
    let root = scratch();
    eprintln!("Structure headless proof artifacts: {}", root.display());
    for (index, proof) in proofs.iter().enumerate() {
        let working = root.join(index.to_string());
        fs::create_dir(&working).unwrap();
        fs::copy(&proof.definition, working.join("retina.dat")).unwrap();
        assert_eq!(
            fs::read_dir(&working).unwrap().count(),
            1,
            "only retina.dat is supplied"
        );
        let before = fs::read(working.join("retina.dat")).unwrap();
        let mut files: Vec<_> = fs::read_dir(&proof.regions)
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .filter(|path| path.extension().is_some_and(|extension| extension == "mca"))
            .collect();
        files.sort();
        assert!(!files.is_empty(), "structure regions must exist");
        for expected in &files {
            let name = expected.file_name().unwrap().to_str().unwrap();
            let fields: Vec<_> = name.split('.').collect();
            assert_eq!(fields.len(), 4);
            assert_eq!((fields[0], fields[3]), ("r", "mca"));
            let x = fields[1].parse::<i32>().unwrap();
            let z = fields[2].parse::<i32>().unwrap();
            let report = summary(
                command(
                    &working,
                    "minecraft:overworld",
                    &format!("{x},{z}"),
                    "generated",
                    "zlib",
                )
                .output()
                .unwrap(),
            );
            assert_eq!(report["generated_chunks"], 1024);
            compare(expected, &working.join("generated/region").join(name));
        }
        assert_eq!(fs::read(working.join("retina.dat")).unwrap(), before);
        println!(
            "QA_EVT {}",
            serde_json::json!({"event":"headless_structure_content_parity",
            "case":proof.name,"regions":files.len(),"chunks":files.len()*1024,
            "definition_only":true,"status":"pass"})
        );
    }
    fs::remove_dir_all(root).unwrap();
}

fn compare(expected: &Path, actual: &Path) {
    let expected_bytes = fs::read(expected).unwrap();
    let actual_bytes = fs::read(actual).unwrap();
    for slot in 0..1024 {
        assert!(
            chunk(&expected_bytes, slot) == chunk(&actual_bytes, slot),
            "decoded chunk mismatch at slot {slot}: {} vs {}",
            expected.display(),
            actual.display()
        );
    }
}

fn chunk(region: &[u8], slot: usize) -> Value {
    let location = u32::from_be_bytes(region[slot * 4..slot * 4 + 4].try_into().unwrap());
    assert_ne!(location, 0, "missing chunk {slot}");
    let offset = (location >> 8) as usize * 4096;
    let length = u32::from_be_bytes(region[offset..offset + 4].try_into().unwrap()) as usize;
    let mut payload = Vec::new();
    match region[offset + 4] {
        2 => {
            ZlibDecoder::new(&region[offset + 5..offset + 4 + length])
                .read_to_end(&mut payload)
                .unwrap();
        }
        4 => payload = lz4(&region[offset + 5..offset + 4 + length]),
        codec => panic!("unexpected test record codec {codec}"),
    }
    fastnbt::from_bytes(&payload).unwrap()
}

fn lz4(mut cursor: &[u8]) -> Vec<u8> {
    let mut output = Vec::new();
    loop {
        assert_eq!(&cursor[..8], b"LZ4Block");
        let method = cursor[8] >> 4;
        let packed = u32::from_le_bytes(cursor[9..13].try_into().unwrap()) as usize;
        let size = u32::from_le_bytes(cursor[13..17].try_into().unwrap()) as usize;
        let checksum = u32::from_le_bytes(cursor[17..21].try_into().unwrap());
        cursor = &cursor[21..];
        if size == 0 {
            assert_eq!((packed, checksum), (0, 0));
            assert!(cursor.is_empty());
            break;
        }
        assert!(size <= 65536);
        let bytes = if method == 1 {
            assert_eq!(packed, size);
            cursor[..packed].to_vec()
        } else {
            assert_eq!(method, 2);
            let mut bytes = vec![0; size];
            // SAFETY: input/output lengths are checked above and passed exactly.
            assert_eq!(
                unsafe {
                    lz4_sys::LZ4_decompress_safe(
                        cursor.as_ptr().cast(),
                        bytes.as_mut_ptr().cast(),
                        packed as i32,
                        size as i32,
                    )
                },
                size as i32
            );
            bytes
        };
        assert_eq!(
            xxhash_rust::xxh32::xxh32(&bytes, 0x9747_b28c) & 0x0fff_ffff,
            checksum
        );
        cursor = &cursor[packed..];
        output.extend(bytes);
    }
    output
}

#[cfg(unix)]
fn cancellation(root: &Path) {
    let stderr = root.join("cancel.stderr");
    let stdout = root.join("cancel.stdout");
    let process = command(root, "minecraft:overworld", "4,4", "cancelled", "zlib");
    // Last option is rejected if repeated, so replace the single-region max.
    let arguments: Vec<_> = process
        .get_args()
        .map(|value| {
            if value == "--region-max=4,4" {
                std::ffi::OsString::from("--region-max=20,20")
            } else {
                value.to_owned()
            }
        })
        .collect();
    let mut process = Command::new(executable());
    process
        .current_dir(root)
        .env_clear()
        .env("RAYON_NUM_THREADS", "2")
        .env("RETINA_REGION_THREADS", "2")
        .args(arguments)
        .stdout(fs::File::create(&stdout).unwrap())
        .stderr(fs::File::create(&stderr).unwrap());
    let mut child = process.spawn().unwrap();
    let deadline = Instant::now() + Duration::from_secs(60);
    let mut signalled = false;
    loop {
        let log = fs::read_to_string(&stderr).unwrap();
        if !signalled && log.lines().any(|line| line.starts_with("Region ")) {
            // SAFETY: this is our live child PID, and SIGINT is a valid signal.
            assert_eq!(unsafe { libc::kill(child.id() as i32, libc::SIGINT) }, 0);
            signalled = true;
        }
        if let Some(status) = child.try_wait().unwrap() {
            assert!(signalled, "process exited before cancellation: {log}");
            assert_eq!(status.code(), Some(130), "{log}");
            break;
        }
        if Instant::now() >= deadline {
            child.kill().unwrap();
            panic!("cancellation timed out: {}", root.display());
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let report: serde_json::Value = serde_json::from_slice(&fs::read(&stdout).unwrap()).unwrap();
    assert_eq!(report["status"], "cancelled");
    let regions = report["regions"].as_u64().unwrap();
    assert!(regions >= 1 && regions < 289);
    for entry in fs::read_dir(root.join("cancelled/region")).unwrap() {
        let entry = entry.unwrap();
        assert_eq!(entry.path().extension().unwrap(), "mca");
        let bytes = fs::read(entry.path()).unwrap();
        for slot in 0..1024 {
            chunk(&bytes, slot);
        }
    }
    println!(
        "QA_EVT {}",
        serde_json::json!({"event":"headless_clean_cancellation","completed_regions":regions,"exit":130})
    );
}
