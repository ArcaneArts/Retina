//! Saved profiles are owned by their world and active jobs, not the process.
//! Slots never change meaning or reuse IDs, so old cache entries cannot alias
//! the next opened world. Legacy registration exists only in unit-test migration oracles.
use crate::{ChunkRequest, profile::WorldProfile, region::codec::Compression};
use std::{collections::BTreeMap, sync::{Arc, Weak}};

#[derive(Default)]
pub(crate) struct Profiles {
    entries: BTreeMap<u32, Entry>,
    next_id: u32,
}

struct Entry {
    owner: Owner,
    compression: Compression,
    data_version: Option<i32>,
    storage: Option<super::wire::Bounds>,
    inputs: Option<GenerationInputs>,
}

/// Generation identity copied from the decoded file, never supplied by a host request.
#[derive(Clone, Copy)]
pub(crate) struct GenerationInputs {
    seed: u64,
    min_y: i32,
    height: u32,
    base_height: u32,
    amplitude: u32,
    frequency: u32,
}

impl GenerationInputs {
    pub fn new(seed: u64, p: &super::wire::Parameters) -> Self {
        Self { seed, min_y: p.min_y, height: p.height as u32,
            base_height: p.base_height.to_bits(), amplitude: p.amplitude.to_bits(), frequency: p.frequency.to_bits() }
    }

    fn validate(&self, request: &ChunkRequest) -> Result<(), String> {
        macro_rules! same {
            ($field:ident, $actual:expr) => {
                if self.$field != $actual {
                    return Err(format!("world generation {} differs from retina.dat (saved {:?}, requested {:?})",
                        stringify!($field), self.$field, $actual));
                }
            };
        }
        same!(seed, request.seed);
        same!(min_y, request.min_y);
        same!(height, request.height);
        same!(base_height, request.base_height.to_bits());
        same!(amplitude, request.amplitude.to_bits());
        same!(frequency, request.frequency.to_bits());
        Ok(())
    }
}

enum Owner {
    #[cfg(test)]
    Legacy(Arc<WorldProfile>),
    World(Weak<WorldProfile>),
}

impl Entry {
    fn profile(&self) -> Result<Arc<WorldProfile>, String> {
        match &self.owner {
            #[cfg(test)]
            Owner::Legacy(profile) => Ok(profile.clone()),
            Owner::World(profile) => profile
                .upgrade()
                .ok_or_else(|| "world profile is closed".into()),
        }
    }
}

impl Profiles {
    #[cfg(test)]
    pub(super) fn leave_one_handle_for_failure_test(&mut self) {
        assert!(self.entries.is_empty());
        self.next_id = u32::MAX - 1;
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    #[cfg(test)]
    pub fn legacy(&mut self, profile: Arc<WorldProfile>) -> Result<u32, String> {
        if let Some((&id, _)) = self.entries.iter().find(|(_, entry)| {
            matches!(&entry.owner,
            Owner::Legacy(p) if p.encoded == profile.encoded)
        }) {
            return Ok(id);
        }
        self.insert(Entry {
            compression: profile.mca_compression,
            data_version: None,
            storage: None,
            inputs: None,
            owner: Owner::Legacy(profile),
        })
    }

    pub fn world(
        &mut self,
        profile: &Arc<WorldProfile>,
        compression: Compression,
        data_version: i32,
        storage: super::wire::Bounds,
        inputs: GenerationInputs,
    ) -> Result<u32, String> {
        self.insert(Entry {
            owner: Owner::World(Arc::downgrade(profile)),
            compression,
            data_version: Some(data_version),
            storage: Some(storage),
            inputs: Some(inputs),
        })
    }

    fn insert(&mut self, entry: Entry) -> Result<u32, String> {
        let id = self.next_id.checked_add(1).ok_or("native profile handle space exhausted")?;
        self.next_id = id;
        self.entries.insert(id, entry);
        Ok(id)
    }

    pub fn retire(&mut self, ids: &[u32]) {
        for id in ids { self.entries.remove(id); }
    }

    fn entry(&self, id: u32) -> Result<&Entry, String> {
        self.entries.get(&id).ok_or_else(|| "unknown or closed world profile".into())
    }

    pub fn validate_request(&self, request: &ChunkRequest) -> Result<(), String> {
        #[cfg(test)]
        if request.reserved == 0 { return Ok(()); }
        let entry = self.entry(request.reserved)?;
        entry.profile()?;
        if let Some(inputs) = entry.inputs { inputs.validate(request)?; }
        Ok(())
    }

    pub fn get(&self, id: u32) -> Result<Option<Arc<WorldProfile>>, String> {
        #[cfg(test)]
        if id == 0 {
            return Ok(None);
        }
        self.entry(id)?.profile().map(Some)
    }

    pub fn compression(&self, id: u32) -> Result<Compression, String> {
        #[cfg(test)]
        if id == 0 {
            return Ok(Compression::default());
        }
        let entry = self.entry(id)?;
        entry.profile()?;
        Ok(entry.compression)
    }

    pub fn data_version(&self, id: u32) -> Result<Option<i32>, String> {
        #[cfg(test)]
        if id == 0 {
            return Ok(None);
        }
        let entry = self.entry(id)?;
        entry.profile()?;
        Ok(entry.data_version)
    }

    pub fn storage_bounds(&self, id: u32) -> Result<Option<super::wire::Bounds>, String> {
        #[cfg(test)]
        if id == 0 {
            return Ok(None);
        }
        let entry = self.entry(id)?;
        entry.profile()?;
        Ok(entry.storage)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn saved_inputs_require_exact_seed_bounds_and_float_bits() {
        let request = ChunkRequest { seed: 0xfedc_ba98_7654_3210, chunk_x: -33, chunk_z: 65,
            min_y: -64, height: 384, base_height: -0.0, amplitude: f32::from_bits(1), frequency: 0.0035, reserved: 9 };
        let inputs = GenerationInputs { seed: request.seed, min_y: request.min_y, height: request.height,
            base_height: request.base_height.to_bits(), amplitude: request.amplitude.to_bits(), frequency: request.frequency.to_bits() };
        inputs.validate(&request).unwrap();
        inputs.validate(&ChunkRequest { chunk_x: 1024, chunk_z: -2048, reserved: 42, ..request }).unwrap();
        for (changed, field) in [
            (ChunkRequest { seed: request.seed ^ (1 << 48), ..request }, "seed"),
            (ChunkRequest { min_y: -48, ..request }, "min_y"),
            (ChunkRequest { height: 368, ..request }, "height"),
            (ChunkRequest { base_height: 0.0, ..request }, "base_height"),
            (ChunkRequest { amplitude: 0.0, ..request }, "amplitude"),
            (ChunkRequest { frequency: f32::from_bits(request.frequency.to_bits() + 1), ..request }, "frequency"),
        ] {
            let error = inputs.validate(&changed).unwrap_err();
            assert!(error.contains(field) && error.contains("retina.dat"), "{error}");
        }
    }
}
