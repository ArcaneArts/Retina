//! Exact-key reuse for live owners plus a bounded set of recently used values.
use std::{collections::{HashMap, VecDeque}, hash::Hash, sync::{Arc, Weak}};

pub(crate) struct Cache<K, V> {
    entries: HashMap<K, Weak<V>>,
    warm: VecDeque<Arc<V>>,
    capacity: usize,
}
impl<K: Eq + Hash, V> Cache<K, V> {
    pub fn new(capacity: usize) -> Self {
        Self { entries: HashMap::new(), warm: VecDeque::new(), capacity }
    }
    pub fn get(&mut self, key: &K) -> Option<Arc<V>> {
        let value = self.entries.get(key)?.upgrade()?;
        self.touch(value.clone());
        Some(value)
    }
    pub fn insert(&mut self, key: K, value: Arc<V>) {
        self.entries.insert(key, Arc::downgrade(&value));
        self.touch(value);
    }
    fn touch(&mut self, value: Arc<V>) {
        self.warm.retain(|old| !Arc::ptr_eq(old, &value));
        self.warm.push_back(value);
        while self.warm.len() > self.capacity { self.warm.pop_front(); }
        self.prune();
    }
    #[cfg(test)]
    pub fn inventory(&self) -> serde_json::Value {
        serde_json::json!({"keys":self.entries.len(), "live":self.entries.values().filter(|v| v.strong_count() != 0).count(),
            "warm":self.warm.len(), "capacity":self.capacity})
    }
    pub fn prune(&mut self) {
        self.entries.retain(|_, value| value.strong_count() != 0);
    }
    /// Pending jobs are owned by actual profiles, not merely by idle reuse.
    pub fn retain_warm(&mut self, mut keep: impl FnMut(&V) -> bool) {
        self.warm.retain(|value| keep(value));
        self.prune();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_idle_reuse_preserves_live_owners_and_exact_keys() {
        let mut cache = Cache::new(2);
        let live = Arc::new(10);
        cache.insert("live", live.clone());
        for key in ["a", "b", "c"] { cache.insert(key, Arc::new(20)); }
        assert_eq!(cache.warm.len(), 2);
        assert!(cache.get(&"a").is_none());
        assert!(Arc::ptr_eq(&cache.get(&"live").unwrap(), &live));
        assert!(cache.get(&"missing").is_none());
        drop(live);
        for key in ["d", "e"] { cache.insert(key, Arc::new(30)); }
        assert!(cache.get(&"live").is_none());
        assert_eq!(cache.entries.len(), 2);
    }
    #[test]
    fn recently_used_value_survives_eviction_and_weak_jobs_do_not_own_it() {
        let mut cache = Cache::new(2);
        let a = Arc::new(1);
        let queued = Arc::downgrade(&a);
        cache.insert(1, a);
        cache.insert(2, Arc::new(2));
        assert_eq!(*cache.get(&1).unwrap(), 1);
        cache.insert(3, Arc::new(3));
        assert!(cache.get(&2).is_none());
        cache.insert(4, Arc::new(4));
        assert!(queued.upgrade().is_none());
    }
}
