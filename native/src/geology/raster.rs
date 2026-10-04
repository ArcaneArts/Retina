//! Compact GPU vein geometry and stable per-chunk recipe replay.
//! Exposure-sensitive recipes retain CPU random/traversal order. GPU bulk masks
//! are consumed directly, with uncertain floating boundaries resolved in Rust.
use super::*;

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
pub struct Descriptor {
    pub minimum: [i32; 4],
    /// X/Y/Z sizes and offset into output u32 words.
    pub dimensions: [u32; 4],
    /// Sphere offset/count, recipe ID, unused.
    pub source: [u32; 4],
}
enum Prepared {
    Regular {
        origin: [i32; 3],
        recipe: u32,
        initial_random: u64,
        exposure_random: u64,
        minimum: [i32; 3],
        sizes: [u32; 3],
        /// Range within this anchor's shared sphere vector.
        spheres: [usize; 2],
    },
    Scattered {
        recipe: u32,
        points: Vec<[i32; 4]>,
    },
}
#[derive(Default)]
struct PreparedAnchor {
    attempts: Vec<Prepared>,
    spheres: Vec<[f32; 4]>,
}
enum Attempt {
    Regular(usize),
    Scattered { recipe: u32, points: Vec<[i32; 4]> },
}
enum Contribution {
    Regular(usize),
    Cpu(Vec<OrePlacement>),
}
struct Host {
    origin: [i32; 3],
    initial_random: u64,
    exposure_random: u64,
}
pub struct Batch {
    pub descriptors: Vec<Descriptor>,
    /// Center X/Y/Z and original CPU radius (also used for boundary repair).
    pub spheres: Vec<[f32; 4]>,
    pub words: usize,
    pub ordered: bool,
    pub guarded: bool,
    anchors: Vec<Vec<Attempt>>,
    hosts: Vec<Host>,
    request: ChunkRequest,
    side: usize,
    targets: Vec<Vec<Contribution>>,
}
impl Batch {
    pub fn prepare(
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        side: usize,
        mask: Option<&CaveMask>,
        ordered: bool,
    ) -> Self {
        Self::prepare_inner(field, profile, request, side, mask, ordered, false)
    }
    /// Direct-mask assembly. Only zero-discard recipes go to the GPU;
    /// exposure-sensitive recipes retain their exact CPU traversal/random draws.
    pub fn prepare_compact(
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        side: usize,
        mask: Option<&CaveMask>,
    ) -> Self {
        Self::prepare_inner(field, profile, request, side, mask, false, true)
    }
    fn prepare_inner(
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        side: usize,
        mask: Option<&CaveMask>,
        ordered: bool,
        compact: bool,
    ) -> Self {
        assert_eq!(profile.geology.ore_layout, 2);
        let tops: Vec<_> = field
            .columns
            .chunks_exact(COLUMNS)
            .map(|c| {
                c.iter()
                    .map(|c| c.surface_height(Some(profile)))
                    .max()
                    .unwrap()
            })
            .collect();
        let replaces_air = profile
            .geology
            .ores
            .iter()
            .any(|r| r.replacement_bands.iter().any(|b| b.materials[0] != 0));
        // Mirror the production attempt visitor and culling. Indexed collection
        // keeps anchor/recipe/attempt priority stable, including scattered recipes.
        let prepared: Vec<PreparedAnchor> = (0..field.side * field.side)
            .into_par_iter()
            .map_init(VeinScratch::default, |scratch, index| {
                let cx = field.origin_x + (index % field.side) as i32;
                let cz = field.origin_z + (index / field.side) as i32;
                let mut output = PreparedAnchor::default();
                for (id, recipe) in profile.geology.ores.iter().enumerate() {
                    let mut random = Random(seed(request, cx, cz, id as u32));
                    if random.next() % recipe.rarity != 0 {
                        continue;
                    }
                    let tries = random.int(recipe.count_min as i32, recipe.count_max as i32);
                    for attempt in 0..tries {
                        let x = cx * 16 + random.int(0, 15);
                        let z = cz * 16 + random.int(0, 15);
                        let y = random.height(&recipe.height);
                        let Some(column) = field.column(x, z) else {
                            continue;
                        };
                        let biome = mask
                            .and_then(|m| m.biome(x, y, z))
                            .map_or(column.biome(), |b| b as usize);
                        if !profile.ore_membership[biome][id]
                            || !vein_may_intersect(
                                recipe,
                                [x, y, z],
                                field,
                                &tops,
                                request,
                                side,
                                replaces_air,
                            )
                        {
                            continue;
                        }
                        let initial_random = seed(request, cx, cz, id as u32)
                            ^ (attempt as u64 + 1).wrapping_mul(0xd6e8feb86659fd93);
                        let mut local = Random(initial_random);
                        if recipe.scattered || (compact && recipe.discard > 0.) {
                            let mut points = Vec::new();
                            vein(recipe, x, y, z, &mut local, scratch, |x, y, z, r| {
                                points.push([x, y, z, r as i32])
                            });
                            output.attempts.push(Prepared::Scattered {
                                recipe: id as u32,
                                points,
                            });
                        } else {
                            vein_spheres(recipe, x, y, z, &mut local, &mut scratch.spheres);
                            let mut minimum = [i32::MAX; 3];
                            let mut maximum = [i32::MIN; 3];
                            let first = output.spheres.len();
                            for s in scratch.spheres.iter().filter(|s| s[3] > 0.) {
                                for i in 0..3 {
                                    minimum[i] = minimum[i].min((s[i] - s[3]).floor() as i32);
                                    maximum[i] = maximum[i].max((s[i] + s[3]).floor() as i32);
                                }
                                output.spheres.push(*s);
                            }
                            let count = output.spheres.len() - first;
                            if count != 0 {
                                let sizes =
                                    std::array::from_fn(|i| (maximum[i] - minimum[i] + 1) as u32);
                                output.attempts.push(Prepared::Regular {
                                    origin: [x, y, z],
                                    recipe: id as u32,
                                    initial_random,
                                    exposure_random: local.0,
                                    minimum,
                                    sizes,
                                    spheres: [first, count],
                                });
                            }
                        }
                    }
                }
                output
            })
            .collect();
        let regular_count = prepared
            .iter()
            .map(|a| {
                a.attempts
                    .iter()
                    .filter(|a| matches!(a, Prepared::Regular { .. }))
                    .count()
            })
            .sum();
        let sphere_count = prepared.iter().map(|a| a.spheres.len()).sum();
        let mut batch = Self {
            descriptors: Vec::with_capacity(regular_count),
            spheres: Vec::with_capacity(sphere_count),
            words: 0,
            ordered,
            guarded: compact,
            anchors: Vec::new(),
            hosts: Vec::new(),
            request,
            side,
            targets: (0..side * side).map(|_| Vec::new()).collect(),
        };
        for anchor in prepared {
            let sphere_base = batch.spheres.len();
            batch.spheres.extend_from_slice(&anchor.spheres);
            let mut attempts = Vec::new();
            for prepared in anchor.attempts {
                match prepared {
                    Prepared::Scattered { recipe, points } => {
                        if compact {
                            let mut buckets = AnchorBuckets::default();
                            for p in &points {
                                buckets.emit(request, side, recipe, p[0], p[1], p[2], p[3] as u32);
                            }
                            for (target, points) in buckets.0 {
                                batch.targets[target].push(Contribution::Cpu(points));
                            }
                        }
                        if !compact {
                            attempts.push(Attempt::Scattered { recipe, points });
                        }
                    }
                    Prepared::Regular {
                        origin,
                        recipe,
                        initial_random,
                        exposure_random,
                        minimum,
                        sizes,
                        spheres,
                    } => {
                        let index = batch.descriptors.len();
                        batch.descriptors.push(Descriptor {
                            minimum: [minimum[0], minimum[1], minimum[2], 0],
                            dimensions: [sizes[0], sizes[1], sizes[2], batch.words as u32],
                            source: [
                                (sphere_base + spheres[0]) as u32,
                                spheres[1] as u32,
                                recipe,
                                0,
                            ],
                        });
                        batch.words += (sizes[0] as usize * sizes[1] as usize * sizes[2] as usize)
                            .div_ceil(if ordered { 4 } else { 32 })
                            * if compact { 2 } else { 1 };
                        if !compact {
                            batch.hosts.push(Host {
                                origin,
                                initial_random,
                                exposure_random,
                            });
                        }
                        if compact {
                            let x0 = minimum[0].div_euclid(16).max(request.chunk_x);
                            let z0 = minimum[2].div_euclid(16).max(request.chunk_z);
                            let x1 = (minimum[0] + sizes[0] as i32 - 1)
                                .div_euclid(16)
                                .min(request.chunk_x + side as i32 - 1);
                            let z1 = (minimum[2] + sizes[2] as i32 - 1)
                                .div_euclid(16)
                                .min(request.chunk_z + side as i32 - 1);
                            for z in z0..=z1 {
                                for x in x0..=x1 {
                                    batch.targets[((z - request.chunk_z) as usize * side)
                                        + (x - request.chunk_x) as usize]
                                        .push(Contribution::Regular(index));
                                }
                            }
                        }
                        if !compact {
                            attempts.push(Attempt::Regular(index));
                        }
                    }
                }
            }
            if !compact {
                batch.anchors.push(attempts);
            }
        }
        batch
    }

    /// Apply union bits directly to one chunk, in anchor/recipe/attempt order.
    /// No intermediate vector of millions of (index, recipe, random) records.
    pub fn apply_compact(
        &self,
        words: &[u32],
        field: &Field,
        profile: &WorldProfile,
        mask: Option<&CaveMask>,
        target: usize,
        blocks: &mut [u16],
    ) {
        let request = ChunkRequest {
            chunk_x: self.request.chunk_x + (target % self.side) as i32,
            chunk_z: self.request.chunk_z + (target / self.side) as i32,
            ..self.request
        };
        for contribution in &self.targets[target] {
            match contribution {
                Contribution::Cpu(ores) => apply_ores(request, field, profile, mask, ores, blocks),
                Contribution::Regular(index) => {
                    let d = &self.descriptors[*index];
                    let recipe = &profile.geology.ores[d.source[2] as usize];
                    assert_eq!(recipe.discard, 0.);
                    let low_x = d.minimum[0].max(request.chunk_x * 16);
                    let high_x =
                        (d.minimum[0] + d.dimensions[0] as i32).min(request.chunk_x * 16 + 16);
                    let low_z = d.minimum[2].max(request.chunk_z * 16);
                    let high_z =
                        (d.minimum[2] + d.dimensions[2] as i32).min(request.chunk_z * 16 + 16);
                    let low_y = d.minimum[1].max(request.min_y);
                    let high_y = (d.minimum[1] + d.dimensions[1] as i32)
                        .min(request.min_y + request.height as i32);
                    for y in low_y..high_y {
                        let Some(band) = recipe
                            .replacement_bands
                            .iter()
                            .find(|b| b.min <= y && y <= b.max)
                        else {
                            continue;
                        };
                        for z in low_z..high_z {
                            let start = (((y - d.minimum[1]) as usize * d.dimensions[2] as usize
                                + (z - d.minimum[2]) as usize)
                                * d.dimensions[0] as usize)
                                + (low_x - d.minimum[0]) as usize;
                            let stride = if self.guarded { 2 } else { 1 };
                            let offset = d.dimensions[3] as usize + start / 32 * stride;
                            let shift = start % 32;
                            let length = (high_x - low_x) as usize;
                            let mut bits = words[offset] >> shift;
                            let mut uncertain = if self.guarded {
                                words[offset + 1] >> shift
                            } else {
                                0
                            };
                            if shift + length > 32 {
                                bits |= words[offset + stride] << (32 - shift);
                                if self.guarded {
                                    uncertain |= words[offset + stride + 1] << (32 - shift);
                                }
                            }
                            bits |= uncertain;
                            bits &= (1 << length) - 1;
                            let row = (y - request.min_y) as usize * COLUMNS
                                + z.rem_euclid(16) as usize * 16
                                + low_x.rem_euclid(16) as usize;
                            while bits != 0 {
                                let dx = bits.trailing_zeros() as usize;
                                let i = row + dx;
                                bits &= bits - 1;
                                if uncertain & (1 << dx) != 0 {
                                    // Match production CPU arithmetic at float boundaries,
                                    // including negative/far world-coordinate rounding.
                                    let at = [low_x + dx as i32, y, z];
                                    let p = at.map(|v| v as f32 + 0.5);
                                    let spheres = &self.spheres[d.source[0] as usize
                                        ..(d.source[0] + d.source[1]) as usize];
                                    if !spheres.iter().any(|s| {
                                        if (0..3).any(|k| {
                                            at[k] < (s[k] - s[3]).floor() as i32
                                                || at[k] > (s[k] + s[3]).floor() as i32
                                        }) {
                                            return false;
                                        }
                                        let inverse = 1. / s[3];
                                        let delta: [f32; 3] =
                                            std::array::from_fn(|k| (p[k] - s[k]) * inverse);
                                        (delta[1] * delta[1] + delta[2] * delta[2])
                                            + delta[0] * delta[0]
                                            < 1.
                                    }) {
                                        continue;
                                    }
                                }
                                let replacement = band.materials[blocks[i] as usize];
                                if replacement != 0 {
                                    blocks[i] = replacement;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    /// Exact production geometry reference, including original sphere traversal.
    pub fn reference(&self, profile: &WorldProfile) -> Vec<u32> {
        assert!(
            !self.guarded,
            "guarded masks use direct assembly, not ordered diagnostics"
        );
        let parts: Vec<Vec<u32>> = self
            .descriptors
            .par_iter()
            .zip(&self.hosts)
            .map(|(d, h)| {
                let size =
                    d.dimensions[0] as usize * d.dimensions[1] as usize * d.dimensions[2] as usize;
                let per_word = if self.ordered { 4 } else { 32 };
                let mut bits = vec![0u32; size.div_ceil(per_word)];
                let mut random = Random(h.initial_random);
                vein(
                    &profile.geology.ores[d.source[2] as usize],
                    h.origin[0],
                    h.origin[1],
                    h.origin[2],
                    &mut random,
                    &mut VeinScratch::default(),
                    |x, y, z, _| {
                        let i = ((y - d.minimum[1]) as usize * d.dimensions[2] as usize
                            + (z - d.minimum[2]) as usize)
                            * d.dimensions[0] as usize
                            + (x - d.minimum[0]) as usize;
                        let value = if self.ordered {
                            let point = [x, y, z].map(|v| v as f32 + 0.5);
                            let spheres = &self.spheres
                                [d.source[0] as usize..(d.source[0] + d.source[1]) as usize];
                            let first = spheres
                                .iter()
                                .position(|s| {
                                    let delta: [f32; 3] =
                                        std::array::from_fn(|k| (point[k] - s[k]) * (1. / s[3]));
                                    (delta[1] * delta[1] + delta[2] * delta[2])
                                        + delta[0] * delta[0]
                                        < 1.
                                })
                                .expect("production hit missing from prepared spheres");
                            (first as u32 + 1) << ((i % 4) * 8)
                        } else {
                            1 << (i % 32)
                        };
                        bits[i / per_word] |= value;
                    },
                );
                bits
            })
            .collect();
        parts.into_iter().flatten().collect()
    }
    /// Includes CPU decode and stable anchor/recipe/attempt merge. Ordered bytes
    /// restore first-sphere / Y / Z / X traversal and exact layout-2 exposure RNG.
    pub fn decode(&self, words: &[u32]) -> Vec<Vec<OrePlacement>> {
        assert!(
            !self.guarded,
            "guarded masks use direct assembly, not ordered diagnostics"
        );
        assert_eq!(words.len(), self.words);
        let anchors: Vec<AnchorBuckets> = self
            .anchors
            .par_iter()
            .map(|attempts| {
                let mut blocks = AnchorBuckets::default();
                for attempt in attempts {
                    match attempt {
                        Attempt::Scattered { recipe, points } => {
                            for p in points {
                                blocks.emit(
                                    self.request,
                                    self.side,
                                    *recipe,
                                    p[0],
                                    p[1],
                                    p[2],
                                    p[3] as u32,
                                );
                            }
                        }
                        Attempt::Regular(index) => {
                            let d = &self.descriptors[*index];
                            let size = d.dimensions[0] as usize
                                * d.dimensions[1] as usize
                                * d.dimensions[2] as usize;
                            let offset = d.dimensions[3] as usize;
                            let mut random = Random(self.hosts[*index].exposure_random);
                            if self.ordered {
                                let words = &words[offset..offset + size.div_ceil(4)];
                                // Counting buckets avoid sorting millions of
                                // (sphere, voxel) pairs. Lexical input traversal
                                // keeps Y/Z/X order stable inside each sphere.
                                let mut counts = [0usize; 64];
                                for &word in words {
                                    for byte in 0..4 {
                                        let first = (word >> (byte * 8)) & 255;
                                        if first != 0 {
                                            assert!(first <= d.source[1]);
                                            counts[first as usize - 1] += 1;
                                        }
                                    }
                                }
                                let mut total = 0;
                                for count in &mut counts {
                                    let next = total + *count;
                                    *count = total;
                                    total = next;
                                }
                                let mut hits = vec![0usize; total];
                                for (wi, &word) in words.iter().enumerate() {
                                    for byte in 0..4 {
                                        let first = (word >> (byte * 8)) & 255;
                                        if first != 0 {
                                            let i = wi * 4 + byte;
                                            assert!(i < size);
                                            let cursor = &mut counts[first as usize - 1];
                                            hits[*cursor] = i;
                                            *cursor += 1;
                                        }
                                    }
                                }
                                for i in hits {
                                    let x = d.minimum[0] + (i % d.dimensions[0] as usize) as i32;
                                    let z = d.minimum[2]
                                        + (i / d.dimensions[0] as usize % d.dimensions[2] as usize)
                                            as i32;
                                    let y = d.minimum[1]
                                        + (i / (d.dimensions[0] as usize
                                            * d.dimensions[2] as usize))
                                            as i32;
                                    blocks.emit(
                                        self.request,
                                        self.side,
                                        d.source[2],
                                        x,
                                        y,
                                        z,
                                        random.next(),
                                    );
                                }
                                continue;
                            }
                            for (wi, &bits) in
                                words[offset..offset + size.div_ceil(32)].iter().enumerate()
                            {
                                let mut remaining = bits;
                                while remaining != 0 {
                                    let i = wi * 32 + remaining.trailing_zeros() as usize;
                                    assert!(i < size);
                                    remaining &= remaining - 1;
                                    let x = d.minimum[0] + (i % d.dimensions[0] as usize) as i32;
                                    let z = d.minimum[2]
                                        + (i / d.dimensions[0] as usize % d.dimensions[2] as usize)
                                            as i32;
                                    let y = d.minimum[1]
                                        + (i / (d.dimensions[0] as usize
                                            * d.dimensions[2] as usize))
                                            as i32;
                                    blocks.emit(
                                        self.request,
                                        self.side,
                                        d.source[2],
                                        x,
                                        y,
                                        z,
                                        random.next(),
                                    );
                                }
                            }
                        }
                    }
                }
                blocks
            })
            .collect();
        (0..self.side * self.side)
            .into_par_iter()
            .map(|target| {
                let buckets: Vec<_> = anchors
                    .iter()
                    .filter_map(|anchor| {
                        anchor
                            .0
                            .binary_search_by_key(&target, |b| b.0)
                            .ok()
                            .map(|i| &anchor.0[i].1)
                    })
                    .collect();
                let mut output = Vec::with_capacity(buckets.iter().map(|b| b.len()).sum());
                for bucket in buckets {
                    output.extend_from_slice(bucket);
                }
                output
            })
            .collect()
    }
}
