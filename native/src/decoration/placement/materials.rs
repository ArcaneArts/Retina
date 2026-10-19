//! Immutable registered material predicates. Keep the original sorted IDs for
//! validation and shader export; CPU membership does not need a binary search
//! through thousands of states on every rejected placement.
use serde::{Deserialize, Deserializer};
use std::cell::RefCell;
use std::collections::HashMap;
use std::ops::Deref;
use std::sync::{Arc, Weak};

#[derive(Clone)]
pub struct MaterialSet(Arc<Compiled>);

struct Compiled {
    ids: Box<[u16]>,
    first_word: usize,
    bits: Box<[u64]>,
}

// Parsing is single-threaded per profile. Weak values share repeated lists
// across its predicates without retaining retired profiles. Bound the index
// even when several live profiles are registered on the same thread.
const INTERN_LIMIT: usize = 256;
thread_local! {
    static INTERNED: RefCell<HashMap<Box<[u16]>, Weak<Compiled>>> = RefCell::default();
}

impl From<Vec<u16>> for MaterialSet {
    fn from(ids: Vec<u16>) -> Self {
        INTERNED.with(|cache| {
            let mut cache = cache.borrow_mut();
            if let Some(shared) = cache.get(ids.as_slice()).and_then(Weak::upgrade) {
                return Self(shared);
            }
            let first_word = ids.first().copied().unwrap_or(0) as usize / 64;
            let last_word = ids.last().copied().unwrap_or(0) as usize / 64;
            let words = last_word.saturating_sub(first_word) + 1;
            // Do not normalize malformed input: profile validation must still
            // reject duplicates or unsorted IDs. Sparse sets keep binary search;
            // the additional bitset never exceeds the size of the source list.
            let bits = if ids.len() >= 8
                && words * 8 <= ids.len() * 2
                && ids.windows(2).all(|v| v[0] < v[1])
            {
                let mut bits = vec![0; words];
                for id in &ids {
                    bits[*id as usize / 64 - first_word] |= 1 << (*id % 64);
                }
                bits.into_boxed_slice()
            } else {
                Box::default()
            };
            let compiled = Arc::new(Compiled {
                ids: ids.into_boxed_slice(),
                first_word,
                bits,
            });
            cache.retain(|_, value| value.strong_count() != 0);
            if cache.len() < INTERN_LIMIT {
                cache.insert(compiled.ids.clone(), Arc::downgrade(&compiled));
            }
            Self(compiled)
        })
    }
}

impl<'de> Deserialize<'de> for MaterialSet {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        Vec::<u16>::deserialize(deserializer).map(Self::from)
    }
}

impl Deref for MaterialSet {
    type Target = [u16];
    fn deref(&self) -> &Self::Target {
        &self.0.ids
    }
}

impl MaterialSet {
    #[inline]
    pub(super) fn accepts(&self, material: u16) -> bool {
        match self.0.ids.as_ref() {
            [] => false,
            [a] => material == *a,
            [a, b] => material == *a || material == *b,
            [a, b, c] => material == *a || material == *b || material == *c,
            [a, b, c, d] => material == *a || material == *b || material == *c || material == *d,
            ids if self.0.bits.is_empty() => ids.binary_search(&material).is_ok(),
            _ => {
                let word = (material as usize / 64).wrapping_sub(self.0.first_word);
                self.0
                    .bits
                    .get(word)
                    .is_some_and(|bits| bits & (1 << (material % 64)) != 0)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::decoration::placement::Predicate;

    #[test]
    fn membership_matches_sorted_reference_over_every_material_id() {
        for ids in [
            vec![],
            vec![0],
            vec![0, 65535],
            vec![2, 3, 6],
            vec![0, 1, 64, 65535],
            (0..20).map(|v| v * 3000).collect(),
            (128..1024).filter(|v| v % 7 != 0).collect(),
            (65000..=65535).filter(|v| v % 3 == 0).collect(),
        ] {
            let set = MaterialSet::from(ids.clone());
            for id in 0..=u16::MAX {
                assert_eq!(set.accepts(id), ids.binary_search(&id).is_ok(), "{id}");
            }
            assert!(set.0.bits.len() * 8 <= ids.len() * 2);
        }
    }

    #[test]
    fn shared_sets_do_not_retain_retired_profile_data_or_change_validation() {
        let a: MaterialSet = (190..800).collect::<Vec<_>>().into();
        let b: MaterialSet = (190..800).collect::<Vec<_>>().into();
        assert!(Arc::ptr_eq(&a.0, &b.0));
        let weak = Arc::downgrade(&a.0);
        drop(a);
        drop(b);
        assert!(weak.upgrade().is_none());
        let held: Vec<MaterialSet> = (0..400).map(|n| vec![n, n + 1].into()).collect();
        INTERNED.with(|cache| assert!(cache.borrow().len() <= INTERN_LIMIT));
        assert_eq!(held.len(), 400);
        for ids in [vec![4, 2], vec![2, 2], vec![20]] {
            let p = Predicate::Material {
                offset: [0; 3],
                allowed: ids.into(),
            };
            assert!(!p.validate(10));
        }
    }

    #[test]
    fn empty_sets_still_query_unknown_material_and_preserve_offsets() {
        let p = Predicate::Material {
            offset: [1, -1, 0],
            allowed: vec![].into(),
        };
        assert_eq!(
            p.test_with([-10000, -64, 30000000], &|at| {
                assert_eq!(at, [-9999, -65, 30000000]);
                None
            }),
            None
        );
        assert_eq!(p.test_with([0; 3], &|_| Some(0)), Some(false));
    }

    #[test]
    #[ignore = "bounded material membership benchmark using an exported profile"]
    fn registered_material_lookup_benchmark() {
        use std::hint::black_box;
        use std::time::Instant;
        fn gather(v: &serde_json::Value, out: &mut Vec<Vec<u16>>) {
            match v {
                serde_json::Value::Object(v) => {
                    if v.get("type").and_then(|v| v.as_str()) == Some("material") {
                        out.push(serde_json::from_value(v["allowed"].clone()).unwrap());
                    }
                    for v in v.values() {
                        gather(v, out);
                    }
                }
                serde_json::Value::Array(v) => {
                    for v in v {
                        gather(v, out);
                    }
                }
                _ => {}
            }
        }
        let path = std::env::var("RETINA_MATERIAL_PROFILE").expect("exported profile path");
        let profile: serde_json::Value =
            serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
        let palette = profile["materials"].as_array().unwrap().len();
        let mut reference = Vec::new();
        gather(&profile, &mut reference);
        let compiled: Vec<MaterialSet> = reference.iter().cloned().map(MaterialSet::from).collect();
        // Reproducible hit/miss mix across all registered sets. This is a lookup
        // microbenchmark, not a captured placement trace or a throughput claim.
        let queries: Vec<Vec<u16>> = reference
            .iter()
            .enumerate()
            .map(|(i, ids)| {
                (0..256)
                    .map(|n| {
                        if n % 4 == 0 && !ids.is_empty() {
                            ids[(n + i) % ids.len()]
                        } else {
                            ((n * 157 + i * 991) % palette) as u16
                        }
                    })
                    .collect()
            })
            .collect();
        let mut samples = [Vec::new(), Vec::new()];
        let mut expected = None;
        for iteration in 0..25 {
            for variant in [iteration % 2, 1 - iteration % 2] {
                let begin = Instant::now();
                let mut hits = 0usize;
                if variant == 0 {
                    for (ids, queries) in black_box(&reference).iter().zip(black_box(&queries)) {
                        for id in queries {
                            hits += ids.binary_search(id).is_ok() as usize;
                        }
                    }
                } else {
                    for (set, queries) in black_box(&compiled).iter().zip(black_box(&queries)) {
                        for id in queries {
                            hits += set.accepts(*id) as usize;
                        }
                    }
                }
                black_box(hits);
                let ms = begin.elapsed().as_secs_f64() * 1000.;
                assert_eq!(*expected.get_or_insert(hits), hits);
                if iteration >= 5 {
                    samples[variant].push(ms);
                }
            }
        }
        println!(
            "{}",
            serde_json::json!({"predicates": reference.len(),
            "queries": reference.len() * 256, "hits": expected,
            "warmups":5,"iterations":20,"binary_search_ms":samples[0],"compiled_ms":samples[1]})
        );
    }
}
