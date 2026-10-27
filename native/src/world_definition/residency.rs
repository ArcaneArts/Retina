//! Saved profiles are owned by their world and active jobs, not the process.
//! Slots never change meaning or reuse IDs, so old cache entries cannot alias
//! the next opened world. Legacy registrations retain their existing ownership.
use crate::{profile::WorldProfile, region::codec::Compression};
use std::sync::{Arc, Weak};

#[derive(Default)]
pub(crate) struct Profiles {
    entries: Vec<Entry>,
}

struct Entry {
    owner: Owner,
    compression: Compression,
    data_version: Option<i32>,
    storage: Option<super::wire::Bounds>,
}

enum Owner {
    Legacy(Arc<WorldProfile>),
    World(Weak<WorldProfile>),
}

impl Entry {
    fn profile(&self) -> Result<Arc<WorldProfile>, String> {
        match &self.owner {
            Owner::Legacy(profile) => Ok(profile.clone()),
            Owner::World(profile) => profile
                .upgrade()
                .ok_or_else(|| "world profile is closed".into()),
        }
    }
}

impl Profiles {
    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn legacy(&mut self, profile: Arc<WorldProfile>) -> Result<u32, String> {
        if let Some(index) = self.entries.iter().position(|entry| {
            matches!(&entry.owner,
            Owner::Legacy(p) if p.encoded == profile.encoded)
        }) {
            return Ok(index as u32 + 1);
        }
        self.insert(Entry {
            compression: profile.mca_compression,
            data_version: None,
            storage: None,
            owner: Owner::Legacy(profile),
        })
    }

    pub fn world(
        &mut self,
        profile: &Arc<WorldProfile>,
        compression: Compression,
        data_version: i32,
        storage: super::wire::Bounds,
    ) -> Result<u32, String> {
        self.insert(Entry {
            owner: Owner::World(Arc::downgrade(profile)),
            compression,
            data_version: Some(data_version),
            storage: Some(storage),
        })
    }

    fn insert(&mut self, entry: Entry) -> Result<u32, String> {
        let id = self
            .entries
            .len()
            .checked_add(1)
            .and_then(|n| u32::try_from(n).ok())
            .ok_or("native profile handle space exhausted")?;
        self.entries.push(entry);
        Ok(id)
    }

    fn entry(&self, id: u32) -> Result<&Entry, String> {
        id.checked_sub(1)
            .and_then(|id| self.entries.get(id as usize))
            .ok_or_else(|| "unknown world profile".into())
    }

    pub fn get(&self, id: u32) -> Result<Option<Arc<WorldProfile>>, String> {
        if id == 0 {
            return Ok(None);
        }
        self.entry(id)?.profile().map(Some)
    }

    pub fn compression(&self, id: u32) -> Result<Compression, String> {
        if id == 0 {
            return Ok(Compression::default());
        }
        let entry = self.entry(id)?;
        entry.profile()?;
        Ok(entry.compression)
    }

    pub fn data_version(&self, id: u32) -> Result<Option<i32>, String> {
        if id == 0 {
            return Ok(None);
        }
        let entry = self.entry(id)?;
        entry.profile()?;
        Ok(entry.data_version)
    }

    pub fn storage_bounds(&self, id: u32) -> Result<Option<super::wire::Bounds>, String> {
        if id == 0 {
            return Ok(None);
        }
        let entry = self.entry(id)?;
        entry.profile()?;
        Ok(entry.storage)
    }
}
