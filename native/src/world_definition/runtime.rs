//! Shared mod/headless binding. Parsing is GPU-independent; binding starts the
//! engine and registers references to the same immutable loaded profiles.
use super::WorldDefinition;
use crate::{
    ChunkRequest, TerrainEngine,
    region::{self, RegionReport, codec::Compression},
};
use std::{collections::BTreeMap, path::Path, sync::Arc};

pub struct WorldSession {
    definition: Arc<WorldDefinition>,
    engine: Arc<TerrainEngine>,
    dimensions: BTreeMap<String, u32>,
    compression: Compression,
}

/// Keeping a dimension lease alive keeps its world and generation data alive.
/// Hosts must drain their work before dropping their last world/session owner.
#[derive(Clone)]
pub struct DimensionLease {
    world: Arc<WorldSession>,
    key: String,
    profile: u32,
}

impl WorldSession {
    pub fn bind(
        engine: Arc<TerrainEngine>,
        definition: Arc<WorldDefinition>,
        compression: Compression,
    ) -> Result<Arc<Self>, String> {
        let mut dimensions = BTreeMap::new();
        for (key, dimension) in definition.dimensions() {
            if dimension.unsupported_reason().is_some() {
                continue;
            }
            let id = engine.register_world_profile(
                dimension.profile()?,
                compression,
                definition.data_version(),
                dimension.parameters().storage_bounds(),
            )?;
            dimensions.insert(key.clone(), id);
        }
        Ok(Arc::new(Self {
            engine,
            definition,
            dimensions,
            compression,
        }))
    }

    pub fn definition(&self) -> &Arc<WorldDefinition> {
        &self.definition
    }
    pub fn engine(&self) -> &Arc<TerrainEngine> {
        &self.engine
    }
    pub fn compression(&self) -> Compression {
        self.compression
    }

    pub fn dimension(self: &Arc<Self>, key: &str) -> Result<DimensionLease, String> {
        self.definition.dimension(key)?.profile()?;
        let profile = *self
            .dimensions
            .get(key)
            .ok_or("dimension profile is not bound")?;
        Ok(DimensionLease {
            world: self.clone(),
            key: key.into(),
            profile,
        })
    }
}

impl DimensionLease {
    pub fn profile_id(&self) -> u32 {
        self.profile
    }
    pub fn definition(&self) -> &WorldDefinition {
        &self.world.definition
    }
    pub fn engine(&self) -> &TerrainEngine {
        &self.world.engine
    }
    pub fn key(&self) -> &str {
        &self.key
    }

    pub fn request(&self, x: i32, z: i32) -> Result<ChunkRequest, String> {
        self.world.definition.request(&self.key, self.profile, x, z)
    }

    pub fn generate_chunk(&self,x:i32,z:i32) -> Result<Vec<u8>,String> {
        let request=self.request(x,z)?;
        let biome=&self.world.definition.dimension(&self.key)?.profile()?.biomes[0].id;
        region::generate_chunk(&self.world.engine,request,self.world.definition.data_version(),biome)
    }

    /// Region coordinates, not chunk coordinates. Saved seed/bounds/DataVersion
    /// and the saved biome palette supply every generation input.
    pub fn generate_region(
        &self,
        x: i32,
        z: i32,
        destination: &Path,
    ) -> Result<RegionReport, String> {
        let x = x
            .checked_mul(32)
            .ok_or("region X overflows chunk coordinates")?;
        let z = z
            .checked_mul(32)
            .ok_or("region Z overflows chunk coordinates")?;
        let request = self.request(x, z)?;
        let dimension = self.world.definition.dimension(&self.key)?;
        let biome = &dimension.profile()?.biomes[0].id;
        region::generate_region(
            &self.world.engine,
            request,
            destination,
            self.world.definition.data_version(),
            biome,
        )
    }
}
