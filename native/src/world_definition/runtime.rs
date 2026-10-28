//! Shared mod/headless binding. Parsing is GPU-independent; binding starts the
//! engine and registers references to the same immutable loaded profiles.
use super::WorldDefinition;
pub use crate::specialize::Execution as ProgramExecution;
use crate::{
    ChunkRequest, TerrainEngine,
    region::{self, RegionReport, codec::Compression},
};
use std::{collections::BTreeMap, path::Path, sync::Arc};

pub struct WorldSession {
    definition: Arc<WorldDefinition>,
    // Explicit execution variants share generation values but need their own
    // dispatch preference and an owner for the engine's weak registrations.
    _runtime_profiles: Vec<Arc<crate::profile::WorldProfile>>,
    engine: Arc<TerrainEngine>,
    dimensions: BTreeMap<String, u32>,
    compression: Compression,
    execution: Option<ProgramExecution>,
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
        Self::bind_with_execution(engine, definition, compression, None)
    }

    pub fn bind_with_execution(
        engine: Arc<TerrainEngine>,
        definition: Arc<WorldDefinition>,
        compression: Compression,
        execution: Option<ProgramExecution>,
    ) -> Result<Arc<Self>, String> {
        // Construct the owner first so partial registration failure also retires IDs.
        let mut session = Self { engine, definition, _runtime_profiles: Vec::new(),
            dimensions: BTreeMap::new(), compression, execution };
        for (key, dimension) in session.definition.dimensions() {
            if dimension.unsupported_reason().is_some() {
                continue;
            }
            let profile = dispatch_profile(dimension.profile()?, execution);
            let id = session.engine.register_world_profile(
                &profile,
                compression,
                session.definition.data_version(),
                dimension.parameters().storage_bounds(),
                super::residency::GenerationInputs::new(session.definition.seed() as u64, dimension.parameters()),
            )?;
            session.dimensions.insert(key.clone(), id);
            session._runtime_profiles.push(profile);
        }
        Ok(Arc::new(session))
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
    pub fn execution(&self) -> Option<ProgramExecution> {
        self.execution
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

impl Drop for WorldSession {
    fn drop(&mut self) {
        // Dimension leases retain this session for the complete operation.
        // Low-level host requests retain their existing drain-before-close contract.
        self.engine.retire_world_profiles(self.dimensions.values().copied().collect());
    }
}

fn dispatch_profile(
    saved: &Arc<crate::profile::WorldProfile>,
    execution: Option<ProgramExecution>,
) -> Arc<crate::profile::WorldProfile> {
    match execution {
        Some(mode) if mode != saved.program_execution => {
            let mut profile = (**saved).clone();
            profile.program_execution = mode;
            Arc::new(profile)
        }
        _ => saved.clone(),
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

    pub fn generate_chunk(&self, x: i32, z: i32) -> Result<Vec<u8>, String> {
        let request = self.request(x, z)?;
        let biome = &self
            .world
            .definition
            .dimension(&self.key)?
            .profile()?
            .biomes[0]
            .id;
        region::generate_chunk(
            &self.world.engine,
            request,
            self.world.definition.data_version(),
            biome,
        )
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

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn execution_variants_keep_saved_programs_immutable_and_reuse_default_ownership() {
        let document = crate::world_definition::tests::document();
        let world =
            WorldDefinition::from_gzip(crate::world_definition::tests::gzip(&document).as_slice())
                .unwrap();
        let saved = world
            .dimension("minecraft:overworld")
            .unwrap()
            .profile()
            .unwrap();
        let original = world.fingerprint();
        assert!(Arc::ptr_eq(saved, &dispatch_profile(saved, None)));
        assert!(Arc::ptr_eq(
            saved,
            &dispatch_profile(saved, Some(ProgramExecution::Auto))
        ));
        for mode in [ProgramExecution::Interpreter, ProgramExecution::Specialized] {
            let runtime = dispatch_profile(saved, Some(mode));
            assert!(!Arc::ptr_eq(saved, &runtime));
            assert!(runtime.program_execution == mode);
            assert!(saved.program_execution == ProgramExecution::Auto);
            assert_eq!(saved.gpu_bytes(), runtime.gpu_bytes());
            assert_eq!(saved.climate_gpu_bytes(), runtime.climate_gpu_bytes());
            assert_eq!(saved.material_nbt, runtime.material_nbt);
            assert_eq!(saved.encoded, runtime.encoded);
            assert_eq!(world.fingerprint(), original);
        }
    }
}
