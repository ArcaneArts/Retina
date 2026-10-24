//! Bounded derived masks owned by one immutable prior-world view. No mask
//! crosses a root, request, seed, profile, height context or continuation view.
use super::*;

type Bits = [u64; 4];
struct Mask {
    key: (i32, i32),
    material: u16,
    height: u16,
    bits: Bits,
}
#[derive(Default)]
pub(super) struct Cache {
    slots: OnceLock<Box<[OnceLock<Mask>]>>,
}
impl Cache {
    /// A collision or unsupported height keeps the original scalar path.
    /// Loads run outside OnceLock initializers so recursive dependencies cannot
    /// deadlock; racing loads publish only complete immutable masks.
    pub(super) fn read(
        &self,
        key: (i32, i32),
        y: usize,
        height: usize,
        material: u16,
        load: impl FnOnce() -> Result<Bits, String>,
    ) -> Result<(Option<bool>, bool), String> {
        if !(1..=256).contains(&height) || y >= height {
            return Ok((None, false));
        }
        let slots = self.slots.get_or_init(|| {
            (0..VIEW_COLUMN_SLOTS)
                .map(|_| OnceLock::new())
                .collect::<Vec<_>>()
                .into_boxed_slice()
        });
        let slot = &slots[ViewColumns::index(key)];
        if let Some(mask) = slot.get() {
            return if mask.key == key && mask.material == material && mask.height as usize == height
            {
                Ok((Some(mask.bits[y / 64] & (1 << (y % 64)) != 0), false))
            } else {
                Ok((None, false))
            };
        }
        let bits = load()?;
        let value = bits[y / 64] & (1 << (y % 64)) != 0;
        let _ = slot.set(Mask {
            key,
            material,
            height: height as u16,
            bits,
        });
        Ok((Some(value), true))
    }
    pub(super) fn allocated_bytes(&self) -> usize {
        self.slots.get().map_or(0, |slots| {
            slots.len() * std::mem::size_of::<OnceLock<Mask>>()
        })
    }
}

pub(super) fn from_column(column: &ColumnState, height: usize, material: u16) -> Bits {
    #[cfg(test)] column.height_trace.material();
    let mut bits = [0; 4];
    let mut top = height;
    for &run in column.runs.slice() {
        let bottom = (run >> 16) as usize;
        if run as u16 == material {
            let mut y = bottom;
            while y < top {
                let end = top.min((y / 64 + 1) * 64);
                bits[y / 64] |= (u64::MAX >> (64 - (end - y))) << (y % 64);
                y = end;
            }
        }
        top = bottom;
    }
    bits
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn target_masks_match_every_voxel_for_all_supported_heights_and_run_owners() {
        let (profile, _, _) = super::super::super::replay_tests::fixture(42, 0);
        for height in 1..=256 {
            for pattern in 0..4 {
                let blocks: Vec<u16> = (0..height)
                    .map(|y| match pattern {
                        0 => 0,
                        1 => 1,
                        2 => (y % profile.materials.len()) as u16,
                        _ => ((y / 17) % profile.materials.len()) as u16,
                    })
                    .collect();
                let owned = ColumnState::new(blocks.clone());
                let mut words = vec![u32::MAX; 5];
                words.extend_from_slice(owned.runs.slice());
                let end = words.len();
                words.push(u32::MAX);
                let cached = ColumnState::from_runs(
                    ColumnRuns::Cached {
                        runs: Arc::new(words.clone()),
                        range: 5..end,
                    },
                );
                let shared = ColumnState::from_runs(
                    ColumnRuns::Shared {
                        mask: Arc::new(crate::geology::CaveMask {
                            origin_x: -32,
                            origin_z: 16,
                            min_y: -64,
                            height: height as u32,
                            width: 0,
                            air_only: false,
                            words,
                            columns: None,
                        }),
                        range: 5..end,
                    },
                );
                for column in [&owned, &cached, &shared] {
                    for material in (0..profile.materials.len() as u16).chain([u16::MAX]) {
                        let bits = from_column(column, height, material);
                        for y in 0..256 {
                            assert_eq!(
                                bits[y / 64] & (1 << (y % 64)) != 0,
                                blocks.get(y) == Some(&material),
                                "height={height} y={y} material={material}"
                            );
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn mask_cache_preserves_collisions_material_height_and_independent_view_keys() {
        for key in [(0, 0), (-33, 17), (i32::MIN, i32::MAX)] {
            let cache = Cache::default();
            assert_eq!(cache.allocated_bytes(), 0);
            assert_eq!(
                cache.read(key, 127, 128, 7, || Ok([u64::MAX; 4])).unwrap(),
                (Some(true), true)
            );
            assert_eq!(
                cache
                    .read(key, 0, 128, 7, || panic!("mask already present"))
                    .unwrap(),
                (Some(true), false)
            );
            let collision = (key.0.wrapping_add(32), key.1);
            for (key, height, material) in [(collision, 128, 7), (key, 128, 8), (key, 256, 7)] {
                assert_eq!(
                    cache
                        .read(key, 0, height, material, || panic!(
                            "collision must use scalar path"
                        ))
                        .unwrap(),
                    (None, false)
                );
            }
            assert_eq!(
                cache.allocated_bytes(),
                VIEW_COLUMN_SLOTS * std::mem::size_of::<OnceLock<Mask>>()
            );
            let other_view = Cache::default();
            assert_eq!(
                other_view.read(key, 0, 128, 7, || Ok([0; 4])).unwrap(),
                (Some(false), true)
            );
        }
        let cache = Cache::default();
        for (height, y) in [(0, 0), (257, 0), (4096, 4095), (256, 256)] {
            assert_eq!(
                cache
                    .read((0, 0), y, height, 0, || panic!("unsupported geometry"))
                    .unwrap(),
                (None, false)
            );
        }
        assert_eq!(cache.allocated_bytes(), 0);
    }

    #[test]
    fn mask_loads_propagate_failure_and_allow_recursive_and_concurrent_publication() {
        let cache = Cache::default();
        assert_eq!(
            cache.read((-1, -1), 63, 256, 1, || Err("original column error".into())),
            Err("original column error".into())
        );
        assert_eq!(
            cache
                .read((-1, -1), 63, 256, 1, || Ok([u64::MAX; 4]))
                .unwrap(),
            (Some(true), true)
        );
        let cache = Cache::default();
        assert_eq!(
            cache
                .read((0, 0), 64, 256, 1, || {
                    assert_eq!(
                        cache.read((32, 0), 64, 256, 1, || Ok([0; 4])).unwrap(),
                        (Some(false), true)
                    );
                    Ok([u64::MAX; 4])
                })
                .unwrap(),
            (Some(true), true)
        );
        assert_eq!(
            cache
                .read((0, 0), 64, 256, 1, || panic!("recursive collision"))
                .unwrap(),
            (None, false)
        );
        assert_eq!(
            cache
                .read((32, 0), 64, 256, 1, || panic!("published result lost"))
                .unwrap(),
            (Some(false), false)
        );
        let cache = Cache::default();
        (0..4096).into_par_iter().for_each(|i| {
            let key = (i % 32 - 16, i / 32 % 32 - 16);
            let expected = key.0 & 1 == 0;
            let bits = if expected { [u64::MAX; 4] } else { [0; 4] };
            assert_eq!(
                cache
                    .read(key, i as usize % 256, 256, 1, || Ok(bits))
                    .unwrap()
                    .0,
                Some(expected)
            );
        });
    }

    #[test]
    fn derived_masks_do_not_retain_their_column_owner() {
        let column = Arc::new(ColumnState::from_runs(
            ColumnRuns::Owned(vec![7].into_boxed_slice()),
        ));
        let weak = Arc::downgrade(&column);
        let cache = Cache::default();
        assert_eq!(
            cache
                .read((0, 0), 63, 128, 7, || Ok(from_column(&column, 128, 7)))
                .unwrap(),
            (Some(true), true)
        );
        drop(column);
        assert!(weak.upgrade().is_none());
        assert_eq!(
            cache
                .read((0, 0), 127, 128, 7, || panic!(
                    "derived result is independent"
                ))
                .unwrap(),
            (Some(true), false)
        );
    }
}
