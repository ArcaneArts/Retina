use super::*;
use crate::world_definition::tests::{document, gzip};
use std::{
    io::Read,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};

fn temporary() -> PathBuf {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let dir = std::env::temp_dir().join(format!(
        "retina-definition-ffi-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    std::fs::create_dir(&dir).unwrap();
    dir
}

unsafe fn opened(path: &std::path::Path) -> (i32, u64) {
    let path = path.to_str().unwrap().as_bytes();
    let mut handle = 0;
    let status =
        unsafe { retina_open_world_definition(path.as_ptr(), path.len() as u64, &mut handle) };
    (status, handle)
}

unsafe fn metadata(handle: u64) -> serde_json::Value {
    let mut pointer = std::ptr::null_mut();
    let mut length = 0;
    assert_eq!(
        unsafe { retina_world_definition_metadata(handle, &mut pointer, &mut length) },
        0
    );
    let value =
        serde_json::from_slice(unsafe { std::slice::from_raw_parts(pointer, length as usize) })
            .unwrap();
    unsafe { crate::retina_free_bytes(pointer, length) };
    value
}

#[test]
fn opaque_handles_freeze_file_data_and_distinguish_absence_from_decode_errors() {
    let dir = temporary();
    let path = dir.join("retina.dat");
    unsafe {
        assert_eq!(opened(&path), (0, 0));
        std::fs::write(&path, gzip(&document())).unwrap();
        let (status, handle) = opened(&path);
        assert_eq!(status, 0);
        assert_ne!(handle, 0);
        let before = metadata(handle);
        assert_eq!(before["seed"].as_i64(), Some(i64::MIN + 17));
        assert_eq!(
            before["dimensions"]["minecraft:overworld"]["parameters"]["height"],
            384
        );
        assert!(
            before["dimensions"]["minecraft:overworld"]["profile_metadata"]
                .get("noises")
                .is_none(),
            "adapter metadata is not another executable generation profile"
        );
        std::fs::write(&path, b"damaged").unwrap();
        assert_eq!(
            metadata(handle),
            before,
            "metadata reads the already loaded definition"
        );
        assert_eq!(opened(&path), (-1, 0));
        assert_eq!(std::fs::read(&path).unwrap(), b"damaged");
        assert_eq!(retina_close_world_definition(handle), 0);
        let mut pointer = std::ptr::null_mut();
        let mut length = 0;
        assert_eq!(
            retina_world_definition_metadata(handle, &mut pointer, &mut length),
            -1
        );
        assert_eq!(retina_close_world_definition(handle), -1);
    }
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
#[ignore = "run alone to verify that definition I/O never initializes the shared GPU engine"]
fn definition_io_does_not_initialize_the_gpu() {
    assert!(crate::ENGINE.get().is_none());
    opaque_handles_freeze_file_data_and_distinguish_absence_from_decode_errors();
    assert!(crate::ENGINE.get().is_none());
}

#[test]
fn invalid_ffi_arguments_fail_without_starting_generation() {
    unsafe {
        let mut handle = 123;
        assert_eq!(
            retina_open_world_definition(std::ptr::null(), 0, &mut handle),
            -1
        );
        assert_eq!(handle, 0);
        let mut request = std::mem::MaybeUninit::uninit();
        let key = b"minecraft:overworld";
        assert_eq!(
            retina_world_dimension_request(
                u64::MAX,
                key.as_ptr(),
                key.len() as u64,
                0,
                0,
                request.as_mut_ptr()
            ),
            -1
        );
    }
}

#[test]
#[ignore = "requires a real compute device for opaque execution bindings"]
fn execution_bindings_keep_the_file_immutable_and_retire_runtime_profiles() {
    let dir=temporary();let path=dir.join("retina.dat");
    let original=gzip(&document());std::fs::write(&path,&original).unwrap();
    let key=b"minecraft:overworld";
    for (code,mode) in [(0,ProgramExecution::Auto),(1,ProgramExecution::Interpreter),(2,ProgramExecution::Specialized)] {
        unsafe {
            let (status,handle)=opened(&path);assert_eq!(status,0);
            let before=metadata(handle);let mut id=0;
            assert_eq!(retina_bind_world_dimension_execution(handle,key.as_ptr(),key.len() as u64,2,99,&mut id),-1);
            assert!(world(handle).unwrap().session.get().is_none());
            assert_eq!(retina_bind_world_dimension_execution(handle,key.as_ptr(),key.len() as u64,2,code,&mut id),0);
            let engine={
                let owner=world(handle).unwrap();
                let session=owner.session().unwrap();
                let runtime=session.engine().profile(id).unwrap().unwrap();
                assert!(runtime.program_execution==mode);
                assert!(owner.definition.dimension("minecraft:overworld").unwrap().profile().unwrap().program_execution==ProgramExecution::Auto);
                assert_eq!(runtime.gpu_bytes(),owner.definition.dimension("minecraft:overworld").unwrap().profile().unwrap().gpu_bytes());
                session.engine().clone()
            };
            let mut request=std::mem::MaybeUninit::uninit();
            assert_eq!(retina_world_dimension_request(handle,key.as_ptr(),key.len() as u64,-33,7,request.as_mut_ptr()),0);
            assert_eq!(request.assume_init().seed,document().seed as u64);
            assert_eq!(metadata(handle),before);
            let mut same=0;
            assert_eq!(retina_bind_world_dimension(handle,key.as_ptr(),key.len() as u64,2,&mut same),0);
            assert_eq!(same,id);
            assert_eq!(retina_bind_world_dimension_execution(handle,key.as_ptr(),key.len() as u64,2,(code+1)%3,&mut same),-1);
            assert_eq!(retina_close_world_definition(handle),0);
            assert!(engine.profile(id).is_err(),"runtime preference does not leak resident profiles");
        }
        assert_eq!(std::fs::read(&path).unwrap(),original);
    }
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
#[ignore = "requires Java definition fixtures and a real compute device"]
fn saved_file_generates_regions_and_keeps_active_dimension_leases_alive() {
    let root = PathBuf::from(std::env::var("RETINA_WORLD_DEFINITION_FIXTURES").unwrap());
    let definition = Arc::new(WorldDefinition::read(root.join("flat/retina.dat")).unwrap());
    let profile_lifetime = Arc::downgrade(
        definition
            .dimension("minecraft:overworld")
            .unwrap()
            .profile()
            .unwrap(),
    );
    let engine = Arc::new(crate::TerrainEngine::new().unwrap());
    let session =
        WorldSession::bind(engine.clone(), definition.clone(), Compression::Zlib).unwrap();
    let lease = session.dimension("minecraft:overworld").unwrap();
    let request = lease.request(-33, 32).unwrap();
    assert_eq!(request.seed, definition.seed() as u64);
    let legacy = engine
        .register_reference_profile(
            &std::fs::read(root.join("flat/expected-minecraft_overworld.json")).unwrap(),
        )
        .unwrap();
    let mut actual = vec![0; request.block_count()];
    let mut expected = actual.clone();
    let heights = engine.generate_into(request, &mut actual).unwrap();
    let legacy_heights = engine
        .generate_into(
            ChunkRequest {
                reserved: legacy,
                ..request
            },
            &mut expected,
        )
        .unwrap();
    assert_eq!(actual, expected);
    assert_eq!(heights, legacy_heights);
    let data_version = definition.data_version();
    let dir = temporary();
    let path = dir.join("r.-2.1.mca");
    // Deliberately pass a wrong host version: a saved binding owns this input.
    let biome = &definition
        .dimension("minecraft:overworld")
        .unwrap()
        .profile()
        .unwrap()
        .biomes[0]
        .id;
    let report = crate::region::generate_region(&engine, request, &path, 1, biome).unwrap();
    assert_eq!(report.generated, 1024);
    let region = std::fs::read(&path).unwrap();
    let sector = u32::from_be_bytes(region[0..4].try_into().unwrap()) >> 8;
    let offset = sector as usize * 4096;
    let length = u32::from_be_bytes(region[offset..offset + 4].try_into().unwrap()) as usize;
    assert_eq!(region[offset + 4], 2);
    let mut nbt = Vec::new();
    flate2::read::ZlibDecoder::new(&region[offset + 5..offset + 4 + length])
        .read_to_end(&mut nbt)
        .unwrap();
    let nbt: fastnbt::Value = fastnbt::from_bytes(&nbt).unwrap();
    let fastnbt::Value::Compound(fields) = nbt else {
        panic!()
    };
    assert_eq!(fields["DataVersion"], fastnbt::Value::Int(data_version));
    assert_eq!(fields["xPos"], fastnbt::Value::Int(-64));
    assert_eq!(fields["zPos"], fastnbt::Value::Int(32));
    let preserved = lease.generate_region(-2, 1, &path).unwrap();
    assert_eq!((preserved.generated, preserved.preserved), (0, 1024));
    assert_eq!(
        std::fs::read(&path).unwrap(),
        region,
        "existing complete region is preserved"
    );
    assert!(lease.generate_region(i32::MAX, 0, &path).is_err());
    drop(definition);
    drop(session);
    assert!(profile_lifetime.upgrade().is_some());
    assert_eq!(
        engine.sample_heights(lease.request(0, 0).unwrap()).unwrap(),
        heights,
        "a dimension lease can finish after its original world owner is dropped"
    );
    let id = lease.profile_id();
    drop(lease);
    assert!(profile_lifetime.upgrade().is_none());
    assert!(engine.profile(id).is_err());
    // Publishing already encoded records has no dependency on a live profile.
    // In particular, do not gate valid cache I/O on a retired world binding.
    let copied_path = dir.join("copied.mca");
    let copied =
        crate::region::publish_cached_region(&engine, request, &copied_path, &path).unwrap();
    assert_eq!(copied.generated, 1024);
    assert!(!engine.timings.lock().unwrap().contains_key(&id), "cached publication must not resurrect retired diagnostics");
    let copied = std::fs::read(copied_path).unwrap();
    for slot in 0..1024 {
        assert_eq!(region_chunk(&copied, slot), region_chunk(&region, slot));
    }
    std::fs::remove_dir_all(dir).unwrap();
    println!(
        "QA_EVT {{\"event\":\"saved_definition_native_region\",\"generated_chunks\":1024,\"data_version\":{data_version},\"backend\":\"{}\"}}",
        engine.backend()
    );
}

#[test]
#[ignore = "requires Java vanilla definition fixtures and a real compute device; compares complete regions"]
fn saved_overworld_and_nether_regions_match_legacy_generation() {
    let root = PathBuf::from(std::env::var("RETINA_WORLD_DEFINITION_FIXTURES").unwrap());
    let definition = Arc::new(WorldDefinition::read(root.join("vanilla/retina.dat")).unwrap());
    let engine = Arc::new(crate::TerrainEngine::new().unwrap());
    let session =
        WorldSession::bind(engine.clone(), definition.clone(), Compression::Zlib).unwrap();
    let directory = temporary();
    for (key, region_x, region_z) in [
        ("minecraft:overworld", -1, 0),
        ("minecraft:the_nether", 0, -1),
    ] {
        let started = std::time::Instant::now();
        let lease = session.dimension(key).unwrap();
        let original =
            std::fs::read(root.join(format!("vanilla/expected-{}.json", key.replace(':', "_"))))
                .unwrap();
        let legacy = engine.register_reference_profile(&original).unwrap();
        let label = key.replace(':', "_");
        let actual_path = directory.join(format!("{label}-saved.mca"));
        let baseline_path = directory.join(format!("{label}-legacy.mca"));
        println!("generating saved region {key} {region_x},{region_z}");
        let report = lease
            .generate_region(region_x, region_z, &actual_path)
            .unwrap();
        assert_eq!(report.generated, 1024);
        let request = lease.request(region_x * 32, region_z * 32).unwrap();
        let biome = &definition.dimension(key).unwrap().profile().unwrap().biomes[0].id;
        println!("generating legacy comparison region {key}");
        crate::region::generate_region(
            &engine,
            ChunkRequest {
                reserved: legacy,
                ..request
            },
            &baseline_path,
            definition.data_version(),
            biome,
        )
        .unwrap();
        let actual = std::fs::read(&actual_path).unwrap();
        let baseline = std::fs::read(&baseline_path).unwrap();
        for slot in 0..1024 {
            let actual = region_chunk(&actual, slot);
            let baseline = region_chunk(&baseline, slot);
            if actual != baseline {
                let receipt = serde_json::json!({"dimension":key,"slot":slot,"region":[region_x,region_z],
                    "actual_file":actual_path,"baseline_file":baseline_path});
                std::fs::write(directory.join("mismatch.json"), receipt.to_string()).unwrap();
                panic!("saved/legacy region NBT differs: {receipt}");
            }
        }
        println!(
            "QA_EVT {{\"event\":\"saved_definition_region_parity\",\"dimension\":\"{key}\",\"chunks\":1024,\"seconds\":{}}}",
            started.elapsed().as_secs_f64()
        );
    }
    std::fs::remove_dir_all(directory).unwrap();
}

fn region_chunk(region: &[u8], slot: usize) -> fastnbt::Value {
    let location = u32::from_be_bytes(region[slot * 4..slot * 4 + 4].try_into().unwrap());
    assert_ne!(location, 0, "missing generated chunk");
    let offset = (location >> 8) as usize * 4096;
    let length = u32::from_be_bytes(region[offset..offset + 4].try_into().unwrap()) as usize;
    assert_eq!(region[offset + 4], 2, "comparison fixtures use zlib output");
    let mut nbt = Vec::new();
    flate2::read::ZlibDecoder::new(&region[offset + 5..offset + 4 + length])
        .read_to_end(&mut nbt)
        .unwrap();
    fastnbt::from_bytes(&nbt).unwrap()
}

#[test]
#[ignore = "requires Java procedural definition fixtures and a real compute device"]
fn saved_procedural_dimensions_match_the_original_kernel() {
    let root = PathBuf::from(std::env::var("RETINA_WORLD_DEFINITION_FIXTURES").unwrap());
    let definition = Arc::new(WorldDefinition::read(root.join("procedural/retina.dat")).unwrap());
    let engine = Arc::new(crate::TerrainEngine::new().unwrap());
    let session = WorldSession::bind(engine.clone(), definition.clone(), Compression::Zlib).unwrap();
    let directory = temporary();
    for key in ["minecraft:overworld", "minecraft:the_nether"] {
        let lease = session.dimension(key).unwrap();
        let saved = lease.request(-64,32).unwrap();
        let original = ChunkRequest { reserved:0, ..saved };
        let mut actual = vec![0; saved.block_count()];
        let mut expected = actual.clone();
        assert_eq!(engine.generate_into(saved,&mut actual).unwrap(), engine.generate_into(original,&mut expected).unwrap());
        assert_eq!(actual,expected);
        assert!(engine.profile(lease.profile_id()).unwrap().is_none(), "explicit procedural dispatch");
        let biome = &definition.dimension(key).unwrap().profile().unwrap().biomes[0].id;
        let label = key.replace(':',"_");
        let path = directory.join(format!("{label}-saved.mca"));
        let baseline = directory.join(format!("{label}-original.mca"));
        lease.generate_region(-2,1,&path).unwrap();
        crate::region::generate_region(&engine,original,&baseline,definition.data_version(),biome).unwrap();
        let actual = std::fs::read(path).unwrap();
        let expected = std::fs::read(baseline).unwrap();
        for slot in 0..1024 {
            assert!(region_chunk(&actual,slot)==region_chunk(&expected,slot), "{key}: procedural NBT mismatch at {slot}");
        }
        println!("QA_EVT {{\"event\":\"saved_procedural_parity\",\"dimension\":\"{key}\",\"chunks\":1024}}");
    }
    drop(session);
    assert!(engine.profile(1).unwrap().is_none()); // Definition still owns its palette.
    drop(definition);
    assert!(engine.profile(1).is_err());
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
#[ignore = "requires cropped Java definition fixtures and a real compute device"]
fn cropped_native_ranges_preserve_generation_and_encode_dimension_storage() {
    let root=PathBuf::from(std::env::var("RETINA_WORLD_DEFINITION_FIXTURES").unwrap());
    let engine=Arc::new(crate::TerrainEngine::new().unwrap());
    let directory=temporary();
    for case in ["cropped","cropped_registry"] {
        let definition=Arc::new(WorldDefinition::read(root.join(case).join("retina.dat")).unwrap());
        let session=WorldSession::bind(engine.clone(),definition.clone(),Compression::Zlib).unwrap();
        let lease=session.dimension("minecraft:overworld").unwrap();
        let request=lease.request(-64,32).unwrap();
        assert_eq!((request.min_y,request.height),(0,64));
        let baseline_id=if case=="cropped" { 0 } else {
            engine.register_reference_profile(&std::fs::read(root.join(case).join("expected-minecraft_overworld.json")).unwrap()).unwrap()
        };
        let original=ChunkRequest { reserved:baseline_id,..request };
        let mut actual=vec![0;request.block_count()];
        let mut expected=actual.clone();
        assert_eq!(engine.generate_into(request,&mut actual).unwrap(),engine.generate_into(original,&mut expected).unwrap());
        assert_eq!(actual,expected,"{case}: native generation changed");
        let stored=directory.join(format!("{case}-stored.mca"));
        let baseline=directory.join(format!("{case}-original.mca"));
        let biome=&definition.dimension("minecraft:overworld").unwrap().profile().unwrap().biomes[0].id;
        lease.generate_region(-2,1,&stored).unwrap();
        crate::region::generate_region(&engine,original,&baseline,definition.data_version(),biome).unwrap();
        let stored=std::fs::read(stored).unwrap(); let original=std::fs::read(baseline).unwrap();
        for slot in 0..1024 {
            let fastnbt::Value::Compound(new)=region_chunk(&stored,slot) else {panic!()};
            let fastnbt::Value::Compound(old)=region_chunk(&original,slot) else {panic!()};
            assert_eq!(new["yPos"],fastnbt::Value::Int(-4));
            let fastnbt::Value::List(sections)=&new["sections"] else {panic!()};
            assert_eq!(sections.iter().filter(|section|matches!(section,fastnbt::Value::Compound(fields) if fields.contains_key("block_states"))).count(),24);
            for section in sections {
                let fastnbt::Value::Compound(section)=section else {panic!()};
                let fastnbt::Value::Byte(y)=section["Y"] else {panic!()};
                if !(0..4).contains(&y) {
                    if let Some(fastnbt::Value::Compound(states))=section.get("block_states") {
                        let fastnbt::Value::List(palette)=&states["palette"] else {panic!()};
                        assert_eq!(palette.len(),1,"{case}/{slot}/{y}: padding is not air");
                        let id=match &palette[0] {fastnbt::Value::String(id)=>id,
                            fastnbt::Value::Compound(fields)=>match &fields["id"] {fastnbt::Value::String(id)=>id,_=>panic!()},_=>panic!()};
                        assert_eq!(id,"minecraft:air");
                    }
                } else {
                    let fastnbt::Value::List(old_sections)=&old["sections"] else {panic!()};
                    let old_section=old_sections.iter().find_map(|value|match value {
                        fastnbt::Value::Compound(fields) if fields.get("Y")==Some(&fastnbt::Value::Byte(y))=>Some(fields),_=>None}).unwrap();
                    for key in ["block_states","biomes","SkyLight","BlockLight"] {
                        assert!(section.get(key)==old_section.get(key),"{case}/{slot}/{y}: {key} changed");
                    }
                }
            }
            let fastnbt::Value::Compound(new_maps)=&new["Heightmaps"] else {panic!()};
            let fastnbt::Value::Compound(old_maps)=&old["Heightmaps"] else {panic!()};
            for (name,values) in new_maps {
                let fastnbt::Value::LongArray(values)=values else {panic!()};
                let fastnbt::Value::LongArray(old_values)=&old_maps[name] else {panic!()};
                for i in 0..256 {
                    let new=(values[i/7] as u64>>((i%7)*9))&511;
                    let old=(old_values[i/9] as u64>>((i%9)*7))&127;
                    assert_eq!(new,if old==0 {0}else{old+64},"{case}/{slot}/{name}/{i}: world height changed");
                }
            }
            for (key,value) in &old {
                if !matches!(key.as_str(),"sections"|"Heightmaps"|"yPos") {
                    assert!(new.get(key)==Some(value),"{case}/{slot}: metadata {key} changed");
                }
            }
        }
        println!("QA_EVT {{\"event\":\"cropped_storage_parity\",\"case\":\"{case}\",\"chunks\":1024}}");
    }
    std::fs::remove_dir_all(directory).unwrap();
}
