//! Test-only structural fixture publication. Every engine binding reopens the real file.
use retina_worldgen::{ChunkRequest, TerrainEngine, region::codec::Compression,
    world_definition::{WorldDefinition, runtime::{DimensionLease, ProgramExecution, WorldSession}, wire}};
use fastnbt::IntArray;
use flate2::{Compression as GzipCompression, write::GzEncoder};
use serde_json::Value;
use std::{collections::BTreeMap, io::Write, path::PathBuf, sync::Arc, time::{SystemTime, UNIX_EPOCH}};

// Reuse the codec's existing reference encoder; this module is never part of a production target.
#[allow(dead_code)]
#[path = "../../src/world_definition/value.rs"]
mod value;

pub struct SavedFixture {
    file: PathBuf,
    owned: bool,
    dimension: DimensionLease,
    execution: Option<ProgramExecution>,
}
impl SavedFixture {
    pub fn new(engine: Arc<TerrainEngine>, mut source: Value, identity: ChunkRequest) -> Self {
        let execution = source.as_object_mut().unwrap().remove("program_execution").map(|mode| match mode.as_str().unwrap() {
            "auto" => ProgramExecution::Auto, "interpreter" => ProgramExecution::Interpreter,
            "specialized" => ProgramExecution::Specialized, other => panic!("unknown fixture execution {other}"),
        });
        source.as_object_mut().unwrap().remove("mca_compression");
        let materials: Vec<_> = source.as_object_mut().unwrap().remove("materials").unwrap().as_array().unwrap().iter().map(|v| {
            if let Some(id) = v.as_str() {wire::BlockState {id:id.into(),properties:BTreeMap::new()}}
            else {serde_json::from_value(v.clone()).unwrap()}
        }).collect();
        let biomes: Vec<_> = source["biomes"].as_array().unwrap().iter().map(|b| b["id"].as_str().unwrap().to_string()).collect();
        let storage_min = identity.min_y.div_euclid(16) * 16;
        let storage_max = (identity.min_y + identity.height as i32 + 15).div_euclid(16) * 16;
        let dimension = wire::Dimension {
            parameters: wire::Parameters {generator:"Retina GPU integration fixture".into(),
                min_y:identity.min_y,height:identity.height as i32,
                storage:Some(wire::Bounds {min_y:storage_min,height:storage_max-storage_min}),
                generator_depth:identity.height as i32,sea_level:source["sea_level"].as_i64().unwrap() as i32,
                spawn_height:identity.min_y,skylight:true,ceiling:false,logical_height:identity.height as i32,
                coordinate_scale:1.,base_height:identity.base_height,amplitude:identity.amplitude,frequency:identity.frequency},
            material_palette:IntArray::new((0..materials.len() as i32).collect()),
            biome_palette:IntArray::new((0..biomes.len() as i32).collect()),
            profile:Some(value::encode(&source).unwrap()),unsupported_reason:None,
        };
        let document = wire::Document {format_version:wire::FORMAT_VERSION,generation_version:wire::GENERATION_VERSION,
            minecraft_version:"26.3".into(),data_version:5023,exporter:"Retina native GPU fixture".into(),
            seed:identity.seed as i64,generate_structures:true,bonus_chest:false,datapacks:Vec::new(),
            shared_types:wire::SharedTypes {materials,biomes},
            dimensions:[("minecraft:overworld".into(),dimension)].into_iter().collect()};
        let directory = std::env::temp_dir().join(format!("retina-gpu-definition-{}-{}",std::process::id(),SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&directory).unwrap();
        let file = directory.join("retina.dat");
        let mut gzip = GzEncoder::new(std::fs::File::create(&file).unwrap(),GzipCompression::best());
        gzip.write_all(&fastnbt::to_bytes(&document).unwrap()).unwrap();
        drop(gzip.finish().unwrap());
        let dimension = Self::bind(engine,&file,execution);
        Self {file,owned:true,dimension,execution}
    }
    fn bind(engine: Arc<TerrainEngine>,file: &std::path::Path,execution: Option<ProgramExecution>) -> DimensionLease {
        let source = Arc::new(WorldDefinition::read(file).unwrap());
        WorldSession::bind_with_execution(engine,source,Compression::Zlib,execution).unwrap().dimension("minecraft:overworld").unwrap()
    }
    pub fn reopen(&self,engine: Arc<TerrainEngine>) -> Self {
        Self {file:self.file.clone(),owned:false,dimension:Self::bind(engine,&self.file,self.execution),execution:self.execution}
    }
    pub fn lease(&self) -> DimensionLease {self.dimension.clone()}
    pub fn id(&self) -> u32 {self.dimension.profile_id()}
    pub fn request(&self,x:i32,z:i32) -> ChunkRequest {self.dimension.request(x,z).unwrap()}
}
impl Drop for SavedFixture {
    fn drop(&mut self) {if self.owned {let _ = std::fs::remove_dir_all(self.file.parent().unwrap());}}
}
