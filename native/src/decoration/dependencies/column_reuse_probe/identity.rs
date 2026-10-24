use super::*;

impl Session {
    pub(super) fn identity(&self, key: Key, interior: bool) {
        if !self.identity_intervals || key.before == 0 { return; }
        let mut shard = self.shards[key.column().shard()].lock().unwrap();
        if !shard.identities.contains_key(&key) && shard.identities.len() >= self.keys_per_shard {
            shard.counts.entry((key.before, interior)).or_default().identity_skipped_keys += 1;
            return;
        }
        shard.identities.insert(key, ());
        shard.counts.entry((key.before, interior)).or_default().identity_publications += 1;
    }
}
impl Job {
    // Record only an unlimited, successfully completed prefix which returned
    // its predecessor owner. Never demand state, retain owners, or infer proof
    // from equal materials measured after the requesting build.
    pub(in crate::decoration::dependencies) fn identity(&self, before: usize, x: i32, z: i32, limited: bool, unchanged: bool) {
        let Some(session) = &self.session else { return; };
        if limited || !unchanged || !session.identity_intervals || !session.selected(before, x, z) { return; }
        let dx = i64::from(x) - self.origin[0];
        let dz = i64::from(z) - self.origin[1];
        session.identity(Key { before, request: crate::CacheKey { x, z, ..self.key.request }, ..self.key },
            dx >= 0 && dz >= 0 && dx < self.width && dz < self.width);
    }
}
