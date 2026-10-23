//! Exact local storage for roots whose recipes only write one blob material.
//! Ordered emitted commands remain untouched. Any unexpected insertion promotes
//! all existing entries to the original map before applying that insertion.
use super::*;

const MAX_HEIGHT: u32 = 256;
const MAX_RADIUS: i32 = 12;

#[derive(Clone, Default)]
pub(super) struct Blocks {
    map: Map<[i32; 3], u16>,
    uniform: Option<Box<Uniform>>,
    // Diagnostic only: zero means no promotion, otherwise previous payload +1.
    retired_payload: usize,
}
#[derive(Clone)]
struct Uniform {
    origin: [i32; 3],
    width: u32,
    height: u32,
    words_per_column: usize,
    material: u16,
    words: Vec<u64>,
}
impl Uniform {
    fn for_recipes(
        profile: &WorldProfile,
        request: ChunkRequest,
        x: i32,
        z: i32,
        ids: &[u32],
    ) -> Option<Self> {
        if !profile.ordered_decorations
            || ids.is_empty()
            || !(1..=MAX_HEIGHT).contains(&request.height)
        {
            return None;
        }
        let mut material = None;
        let mut lo = 0i64;
        let mut hi = 0i64;
        for &id in ids {
            let recipe = &profile.decorations[id as usize];
            let Kind::NetherFeature {
                nether: patch::nether::Feature::ReplaceBlobs { state, radius, .. },
            } = &recipe.feature
            else {
                return None;
            };
            if material.is_some_and(|old| old != *state) {
                return None;
            }
            material = Some(*state);
            let (minimum, maximum) = radius.bounds().ok()?;
            if minimum < 0 || maximum > MAX_RADIUS {
                return None;
            }
            let mut squares = 0;
            for op in recipe.placement.as_ref()?.iter() {
                match op {
                    Modifier::InSquare => squares += 1,
                    Modifier::Count { .. } | Modifier::HeightRange { .. } | Modifier::Biome => {}
                    _ => return None,
                }
            }
            if squares > 1 {
                return None;
            }
            lo = lo.min(-i64::from(maximum));
            hi = hi.max(i64::from(squares * 15 + maximum));
        }
        let origin = [
            i32::try_from(i64::from(x) * 16 + lo).ok()?,
            request.min_y,
            i32::try_from(i64::from(z) * 16 + lo).ok()?,
        ];
        let width = u32::try_from(hi - lo + 1).ok()?;
        // Checked geometry keeps wrapping-difference bounds tests exact even
        // at signed coordinate limits. Unsupported geometry uses the map.
        origin[0].checked_add(width as i32 - 1)?;
        origin[2].checked_add(width as i32 - 1)?;
        origin[1].checked_add(request.height as i32 - 1)?;
        Some(Self {
            origin,
            width,
            height: request.height,
            words_per_column: request.height.div_ceil(64) as usize,
            material: material?,
            words: Vec::new(),
        })
    }
    fn index(&self, at: [i32; 3]) -> Option<(usize, u64)> {
        let x = at[0].wrapping_sub(self.origin[0]) as u32;
        let y = at[1].wrapping_sub(self.origin[1]) as u32;
        let z = at[2].wrapping_sub(self.origin[2]) as u32;
        if x >= self.width || z >= self.width || y >= self.height {
            return None;
        }
        Some((
            ((z * self.width + x) as usize * self.words_per_column) + (y / 64) as usize,
            1u64 << (y % 64),
        ))
    }
    fn get(&self, at: [i32; 3]) -> Option<&u16> {
        if self.words.is_empty() {
            return None;
        }
        let (word, bit) = self.index(at)?;
        (self.words[word] & bit != 0).then_some(&self.material)
    }
    fn insert(&mut self, word: usize, bit: u64) -> Option<u16> {
        if self.words.is_empty() {
            // At most40*40*4 words (51,200 bytes), allocated on the first write.
            self.words = vec![0; self.width as usize * self.width as usize * self.words_per_column];
        }
        let previous = self.words[word] & bit != 0;
        self.words[word] |= bit;
        previous.then_some(self.material)
    }
    fn remove(&mut self, at: [i32; 3]) -> Option<u16> {
        let (word, bit) = self.index(at)?;
        let word = self.words.get_mut(word)?;
        let previous = *word & bit != 0;
        *word &= !bit;
        previous.then_some(self.material)
    }
    fn iter(&self) -> impl Iterator<Item = ([i32; 3], u16)> + '_ {
        self.words
            .iter()
            .enumerate()
            .flat_map(move |(index, &word)| {
                let mut remaining = word;
                std::iter::from_fn(move || {
                    if remaining == 0 {
                        return None;
                    }
                    let bit = remaining.trailing_zeros() as usize;
                    remaining &= remaining - 1;
                    let column = index / self.words_per_column;
                    let y = (index % self.words_per_column) * 64 + bit;
                    Some((
                        [
                            self.origin[0] + (column % self.width as usize) as i32,
                            self.origin[1] + y as i32,
                            self.origin[2] + (column / self.width as usize) as i32,
                        ],
                        self.material,
                    ))
                })
            })
    }
}
impl Blocks {
    pub(super) fn for_recipes(
        profile: &WorldProfile,
        request: ChunkRequest,
        x: i32,
        z: i32,
        ids: &[u32],
    ) -> Self {
        Self {
            uniform: Uniform::for_recipes(profile, request, x, z, ids).map(Box::new),
            ..Self::default()
        }
    }
    pub(super) fn get(&self, at: &[i32; 3]) -> Option<&u16> {
        match &self.uniform {
            Some(uniform) => uniform.get(*at),
            None => self.map.get(at),
        }
    }
    pub(super) fn insert(&mut self, at: [i32; 3], material: u16) -> Option<u16> {
        if let Some(uniform) = self.uniform.as_mut() {
            if material == uniform.material {
                if let Some((word, bit)) = uniform.index(at) {
                    return uniform.insert(word, bit);
                }
            }
            self.promote();
        }
        self.map.insert(at, material)
    }
    fn promote(&mut self) {
        if let Some(uniform) = self.uniform.take() {
            self.retired_payload = uniform.words.len() * 8 + 1;
            self.map.extend(uniform.iter());
        }
    }
    pub(super) fn remove(&mut self, at: &[i32; 3]) -> Option<u16> {
        match self.uniform.as_mut() {
            Some(uniform) => uniform.remove(*at),
            None => self.map.remove(at),
        }
    }
    pub(super) fn iter(&self) -> impl Iterator<Item = ([i32; 3], u16)> + '_ {
        self.map
            .iter()
            .map(|(at, material)| (*at, *material))
            .chain(self.uniform.iter().flat_map(|u| u.iter()))
    }
    #[cfg(test)]
    pub(super) fn contains_key(&self, at: &[i32; 3]) -> bool {
        self.get(at).is_some()
    }
    pub(super) fn retained_bytes(&self) -> usize {
        self.map.capacity() * (std::mem::size_of::<([i32; 3], u16)>() + 16)
            + self.uniform.as_ref().map_or(0, |u| {
                std::mem::size_of::<Uniform>() + u.words.capacity() * 8
            })
    }
    pub(super) fn observation(&self) -> Option<(usize, usize, bool)> {
        match &self.uniform {
            Some(u) => Some((
                u.words.len() * 8,
                u.words.iter().map(|w| w.count_ones() as usize).sum(),
                false,
            )),
            None if self.retired_payload > 0 => Some((self.retired_payload - 1, 0, true)),
            None => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::collections::BTreeMap;
    fn profile() -> (WorldProfile, Field<'static>, ChunkRequest) {
        let (mut p, field, request) = crate::decoration::replay_tests::fixture(42, -17);
        p.ordered_decorations = true;
        p.decorations.truncate(1);
        p.decorations[0].source = "test:any_name".into();
        p.decorations[0].feature = serde_json::from_value(json!({"kind":"nether_feature",
            "nether":{"kind":"replace_blobs","target":{"type":"true"},"state":4,"radius":7}}))
        .unwrap();
        p.decorations[0].placement = Some(serde_json::from_value(json!([
            {"type":"count","count":3},{"type":"in_square"},
            {"type":"height_range","height":{"type":"uniform","min_inclusive":{"absolute":1},"max_inclusive":{"absolute":80}}},
            {"type":"biome"}])).unwrap());
        (p, field, request)
    }
    fn entries(blocks: &Blocks) -> BTreeMap<[i32; 3], u16> {
        blocks.iter().collect()
    }
    #[test]
    fn blob_bitmap_eligibility_uses_actual_kinds_materials_geometry_and_bounds() {
        let (mut p, _, mut r) = profile();
        r.height = 256;
        let b = Blocks::for_recipes(&p, r, -17, 16, &[0]);
        let u = b.uniform.as_ref().unwrap();
        assert_eq!(u.width, 30);
        assert!(u.words.is_empty());
        assert_eq!(u.origin, [-279, r.min_y, 249]);
        assert!(Blocks::for_recipes(&p, r, -17, 16, &[]).uniform.is_none());
        for height in [0, 257, 4096] {
            assert!(
                Blocks::for_recipes(&p, ChunkRequest { height, ..r }, -17, 16, &[0])
                    .uniform
                    .is_none()
            );
        }
        assert!(
            Blocks::for_recipes(&p, r, i32::MAX, 0, &[0])
                .uniform
                .is_none()
        );
        assert!(
            Blocks::for_recipes(
                &p,
                ChunkRequest {
                    min_y: i32::MAX,
                    ..r
                },
                0,
                0,
                &[0]
            )
            .uniform
            .is_none()
        );
        let valid = p.decorations[0].clone();
        p.decorations.push(valid.clone());
        assert!(Blocks::for_recipes(&p, r, 0, 0, &[0, 1]).uniform.is_some());
        if let Kind::NetherFeature {
            nether: patch::nether::Feature::ReplaceBlobs { state, .. },
        } = &mut p.decorations[1].feature
        {
            *state = 7;
        }
        assert!(Blocks::for_recipes(&p, r, 0, 0, &[0, 1]).uniform.is_none());
        for program in [
            json!([{"type":"in_square"},{"type":"in_square"}]),
            json!([{"type":"offset","x":1,"y":0,"z":0}]),
        ] {
            p.decorations[0].placement = Some(serde_json::from_value(program).unwrap());
            assert!(Blocks::for_recipes(&p, r, 0, 0, &[0]).uniform.is_none());
        }
        p.decorations[0] = valid;
        p.ordered_decorations = false;
        assert!(Blocks::for_recipes(&p, r, 0, 0, &[0]).uniform.is_none());
    }
    #[test]
    fn blob_bitmap_matches_map_at_all_heights_and_promotes_without_losing_entries() {
        let (p, _, r) = profile();
        for height in 1..=256 {
            let r = ChunkRequest {
                min_y: -64,
                height,
                ..r
            };
            let mut b = Blocks::for_recipes(&p, r, -17, -17, &[0]);
            let mut expected = Map::default();
            for i in 0..512 {
                let at = [
                    -279 + i % 30,
                    -64 + (i * 37) % (height as i32),
                    -279 + (i * 13) % 30,
                ];
                assert_eq!(b.insert(at, 4), expected.insert(at, 4));
                assert_eq!(b.get(&at), expected.get(&at));
                if i % 3 == 0 {
                    assert_eq!(b.remove(&at), expected.remove(&at));
                }
            }
            assert_eq!(
                entries(&b),
                expected.iter().map(|(&k, &v)| (k, v)).collect()
            );
            let copy = b.clone();
            let far = [i32::MIN, 0, i32::MAX];
            assert_eq!(b.insert(far, 4), expected.insert(far, 4));
            assert!(b.uniform.is_none());
            assert_eq!(
                entries(&b),
                expected.iter().map(|(&k, &v)| (k, v)).collect()
            );
            assert!(!copy.contains_key(&far));
            let mut b = copy;
            assert_eq!(
                b.insert([-272, -64, -272], 0),
                expected.get(&[-272, -64, -272]).copied()
            );
            assert!(b.uniform.is_none());
            assert_eq!(b.get(&[-272, -64, -272]), Some(&0));
        }
        let mut b = Blocks::for_recipes(&p, ChunkRequest { height: 256, ..r }, -17, -17, &[0]);
        assert_eq!(b.observation(), Some((0, 0, false)));
        b.insert([-272, 0, -272], 4);
        assert_eq!(b.observation(), Some((28_800, 1, false)));
        b.remove(&[-272, 0, -272]);
        assert_eq!(b.get(&[-272, 0, -272]), None);
    }
    #[test]
    fn blob_bitmap_overlay_preserves_snapshots_undo_heights_ground_and_gpu_entries() {
        let (p, field, mut r) = profile();
        r.height = 256;
        for material in [0, 4, 7] {
            let mut p = p.clone();
            if let Kind::NetherFeature {
                nether: patch::nether::Feature::ReplaceBlobs { state, .. },
            } = &mut p.decorations[0].feature
            {
                *state = material;
            }
            let mut actual = Overlay::for_recipes(&p, r, -17, -17, &[0]);
            let mut expected = Overlay::default();
            let check = |a: &Overlay, b: &Overlay| {
                assert_eq!(entries(&a.blocks), entries(&b.blocks));
                for x in -274..=-270 {
                    for z in -274..=-270 {
                        for map in 0..6 {
                            assert_eq!(
                                a.height(&field, &p, r, x, z, map),
                                b.height(&field, &p, r, x, z, map)
                            );
                        }
                        for layer in 0..3 {
                            assert_eq!(
                                a.ground_layer(&field, &p, r, x, z, layer),
                                b.ground_layer(&field, &p, r, x, z, layer)
                            );
                        }
                    }
                }
            };
            for y in [-32, 0, 63, 64, 127, 128] {
                actual.write(&p, [-272, y, -272], material);
                expected.write(&p, [-272, y, -272], material);
            }
            check(&actual, &expected);
            check(&actual.snapshot(), &expected.snapshot());
            actual.record_feature();
            expected.record_feature();
            for m in [material, material, 0, 4, 7] {
                for x in -274..=-270 {
                    actual.write(&p, [x, 63, x], m);
                    expected.write(&p, [x, 63, x], m);
                    check(&actual, &expected);
                }
            }
            check(&actual.before_feature(), &expected.before_feature());
            actual.finish_feature();
            expected.finish_feature();
            check(&actual, &expected);
        }
    }
}
