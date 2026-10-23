//! A blob root emits only unconditional writes of one configured material.
//! Placement and its RNG/predicates still run normally. This set represents
//! only the accepted commands of one exact column prefix, never final metadata.
use super::*;

pub(super) const MAX_HEIGHT: usize = 256;

pub(super) fn material(profile: &WorldProfile, ids: &[u32]) -> Option<u16> {
    let state = |id: u32| match &profile.decorations[id as usize].feature {
        Kind::NetherFeature {
            nether: patch::nether::Feature::ReplaceBlobs { state, .. },
        } => Some(*state),
        _ => None,
    };
    let first = state(*ids.first()?)?;
    ids.iter()
        .all(|&id| state(id) == Some(first))
        .then_some(first)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn check(
        prior: &ColumnState,
        ys: &[usize],
        material: u16,
        profile: &WorldProfile,
        height: usize,
        min_y: i32,
    ) {
        let original = prior.expand(height);
        let mut expected = original.clone();
        let mut edits = Edits::default();
        for &y in ys {
            edits.insert(y);
            expected[y] = material;
        }
        let result = edits.finish(prior, height, material);
        assert_eq!(result.is_none(), expected == original);
        let actual = result.as_ref().unwrap_or(prior);
        let dense = ColumnState::new(expected);
        assert_eq!(actual.runs.slice(), dense.runs.slice());
        assert_eq!(actual.heights(profile, min_y, height), dense.heights(profile, min_y, height));
        assert_eq!(prior.expand(height), original);
    }

    #[test]
    fn blob_edit_sets_match_dense_replay_at_every_supported_fast_path_height() {
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        for height in 1..=MAX_HEIGHT {
            for min_y in [-64, 0, 16] {
                for material in [0, 4, 7] {
                    let base: Vec<_> = (0..height).map(|y| ((y / 7) % 9) as u16).collect();
                    let prior = ColumnState::new(base);
                    let ascending: Vec<_> = (0..height).collect();
                    let descending: Vec<_> = (0..height).rev().collect();
                    let alternating: Vec<_> = (0..height).step_by(2).collect();
                    let repeated: Vec<_> =
                        (0..height * 3).map(|i| (i * 71 + 13) % height).collect();
                    let edges: Vec<_> = [0, 1, 62, 63, 64, 65, 126, 127, 128, 129, 254, 255]
                        .into_iter()
                        .filter(|&y| y < height)
                        .collect();
                    for ys in [
                        &[][..],
                        &ascending,
                        &descending,
                        &alternating,
                        &repeated,
                        &edges,
                    ] {
                        check(&prior, ys, material, &profile, height, min_y);
                    }
                    // Exact command prefixes must retain earlier duplicate and
                    // unordered Ys without including any later command.
                    for limit in [0, 1, repeated.len() / 2, repeated.len() - 1] {
                        check(
                            &prior,
                            &repeated[..limit],
                            material,
                            &profile,
                            height,
                            min_y,
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn blob_edit_sets_preserve_shared_and_cached_bases_and_noop_identity() {
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        let height = MAX_HEIGHT;
        let original = ColumnState::new(
            (0..height).map(|y| ((y / 31) % 9) as u16).collect(),
        );
        let words = Arc::new(original.runs.slice().to_vec());
        let mask = Arc::new(crate::geology::CaveMask {
            origin_x: -32,
            origin_z: -32,
            min_y: -64,
            height: height as u32,
            width: 0,
            air_only: false,
            words: words.as_ref().clone(),
            columns: None,
        });
        let shared = ColumnState::from_runs(
            ColumnRuns::Shared {
                mask: mask.clone(),
                range: 0..words.len(),
            },
        );
        let cached = ColumnState::from_runs(
            ColumnRuns::Cached {
                runs: words.clone(),
                range: 0..words.len(),
            },
        );
        let counts = (Arc::strong_count(&mask), Arc::strong_count(&words));
        for prior in [&original, &shared, &cached] {
            check(
                prior,
                &[0, 63, 64, 127, 128, 191, 192, 255, 64],
                0,
                &profile,
                height,
                -64,
            );
            let mut edits = Edits::default();
            assert!(edits.insert(62));
            assert!(!edits.insert(62));
            assert!(
                edits
                    .finish(prior, height, prior.material(62))
                    .is_none()
            );
        }
        assert_eq!(
            counts,
            (Arc::strong_count(&mask), Arc::strong_count(&words))
        );
        assert_eq!(mask.words, original.runs.slice());
        assert_eq!(words.as_slice(), original.runs.slice());
    }

    #[test]
    fn uniform_blob_selection_requires_actual_matching_definitions() {
        let (mut profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        let blob = |state| {
            serde_json::from_value(json!({"kind":"nether_feature", "nether":{
                "kind":"replace_blobs", "target":{"type":"true"}, "state":state, "radius":1
            }}))
            .unwrap()
        };
        assert_eq!(material(&profile, &[]), None);
        profile.decorations[0].source = "minecraft:basalt_blobs".into();
        assert_eq!(material(&profile, &[0]), None);
        profile.decorations[0].feature = blob(4);
        profile.decorations[0].source = "custom:arbitrary_name".into();
        profile.decorations[1].feature = blob(4);
        assert_eq!(material(&profile, &[0, 1]), Some(4));
        assert_eq!(material(&profile, &[0, 2]), None);
        profile.decorations[1].feature = blob(7);
        assert_eq!(material(&profile, &[0, 1]), None);
        profile.decorations[0].feature = blob(0);
        assert_eq!(material(&profile, &[0]), Some(0));
    }
}

#[derive(Default)]
pub(super) struct Edits([u64; MAX_HEIGHT / 64]);
impl Edits {
    pub(super) fn merge(&mut self, other: &Self) {
        for (word, source) in self.0.iter_mut().zip(other.0.iter()) {
            *word |= *source;
        }
    }
    /// Returns whether this is the first accepted write at this Y.
    pub(super) fn insert(&mut self, y: usize) -> bool {
        let word = &mut self.0[y / 64];
        let bit = 1 << (y % 64);
        let first = *word & bit == 0;
        *word |= bit;
        first
    }
    fn intersects(&self, start: usize, end: usize) -> bool {
        let mut y = start;
        while y < end {
            let upper = end.min((y / 64 + 1) * 64);
            let width = upper - y;
            let mask = u64::MAX >> (64 - width);
            if (self.0[y / 64] >> (y % 64)) & mask != 0 {
                return true;
            }
            y = upper;
        }
        false
    }
    pub(super) fn finish(
        &self,
        prior: &ColumnState,
        height: usize,
        material: u16,
    ) -> Option<ColumnState> {
        #[cfg(test)] prior.height_trace.material();
        let original = prior.runs.slice();
        let end = |i: usize| {
            if i == 0 {
                height
            } else {
                (original[i - 1] >> 16) as usize
            }
        };
        // Repeated writes and writes of the existing value need no allocation.
        if !original.iter().enumerate().any(|(i, &run)| {
            run as u16 != material && self.intersects((run >> 16) as usize, end(i))
        }) {
            return None;
        }
        let mut runs = Vec::<u32>::with_capacity((original.len() + 8).min(height));
        let mut emit = |y: usize, value: u16| {
            if runs.last().is_none_or(|&last| last as u16 != value) {
                runs.push((y as u32) << 16 | u32::from(value));
            }
        };
        for i in (0..original.len()).rev() {
            let run = original[i];
            let mut y = (run >> 16) as usize;
            let top = end(i);
            if run as u16 == material {
                emit(y, material);
                continue;
            }
            while y < top {
                let word = self.0[y / 64] >> (y % 64);
                let edited = word & 1 != 0;
                let length = if edited {
                    word.trailing_ones()
                } else {
                    word.trailing_zeros()
                } as usize;
                emit(y, if edited { material } else { run as u16 });
                y += length.min(top - y).min(64 - y % 64);
            }
        }
        runs.reverse();
        Some(ColumnState::from_runs(
            ColumnRuns::Owned(runs.into_boxed_slice()),
        ))
    }
}
