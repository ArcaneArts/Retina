//! Overworld fossils are paired feature templates with independent processor lists.
use super::{Predicate, Rng, World};
use crate::{
    structure_processors::{self, Program},
    structures::{Random, Template, rotate},
};
use serde::Deserialize;
use serde_json::Value;
use std::sync::{Arc, OnceLock};

type Pos = [i32; 3];

#[derive(Clone)]
struct Processors {
    rules: Value,
    program: Arc<OnceLock<Program>>,
}

impl Processors {
    fn program(&self, materials: &[Value]) -> &Program {
        self.program.get_or_init(|| Program::compile(&self.rules, materials))
    }
}

#[derive(Clone, Deserialize)]
pub struct Recipe {
    pairs: Vec<[Arc<Template>; 2]>,
    #[serde(deserialize_with = "processors")]
    base_processors: Processors,
    #[serde(deserialize_with = "processors")]
    overlay_processors: Processors,
    empty_corners: usize,
    empty: Predicate,
    min_y: i32,
    floor_map: u8,
}

fn processors<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Processors, D::Error> {
    Ok(Processors { rules: Value::deserialize(d)?, program: Default::default() })
}

fn plus(a: Pos, b: Pos) -> Pos {
    std::array::from_fn(|i| a[i] + b[i])
}

impl Recipe {
    pub(in crate::decoration) fn reach(&self) -> i32 {
        self.pairs.iter().flat_map(|[base, overlay]| {
            [0, 2].map(|axis| (base.size[axis] + 1) / 2 + (overlay.size[axis] - base.size[axis]).max(0))
        }).max().unwrap_or(0)
    }

    pub(in crate::decoration) fn validate(&self, materials: usize) -> bool {
        !self.pairs.is_empty() && self.floor_map < 6
            && (-4096..=4096).contains(&self.min_y)
            && self.pairs.iter().flatten().all(|t| {
                t.validate(materials).is_ok() && t.tags.is_empty() && t.entities.is_empty() && t.joints.is_empty()
            })
            && self.empty.validate(materials)
            && [&self.base_processors, &self.overlay_processors].into_iter().all(|p| {
                structure_processors::validate(&p.rules, materials).is_ok()
            })
    }

    pub(super) fn place(&self, world: &mut World, at: Pos, rng: &mut Rng) -> bool {
        let mut rng = Random::new(rng.next());
        let rotation = rng.int(4) as usize;
        let [base, overlay] = &self.pairs[rng.int(self.pairs.len() as u32) as usize];
        let edge = rotate(base.size.map(|size| size - 1), rotation);
        let size = edge.map(|v| v.abs() + 1);
        let min = plus(at, [-size[0] / 2, 0, -size[2] / 2]);
        let mut ground = at[1];
        for dz in 0..size[2] {
            for dx in 0..size[0] {
                let height = world.overlay.height(world.field, world.profile, world.request,
                    min[0] + dx, min[2] + dz, self.floor_map);
                let Some(height) = height else { return false; };
                ground = ground.min(height);
            }
        }
        let bottom = (ground - 15 - rng.int(10) as i32).max(self.min_y + 10);
        let min = [min[0], bottom, min[2]];
        let mut empty = 0;
        for dx in [0, size[0] - 1] {
            for dy in [0, size[1] - 1] {
                for dz in [0, size[2] - 1] {
                    if world.test(&self.empty, plus(min, [dx, dy, dz])) {
                        empty += 1;
                        if empty > self.empty_corners {
                            return false;
                        }
                    }
                }
            }
        }
        let origin = plus(min, edge.map(|v| -v.min(0)));
        let chunk = [at[0].div_euclid(16) * 16, at[2].div_euclid(16) * 16];
        for (template, processors) in [(base, &self.base_processors), (overlay, &self.overlay_processors)] {
            place_template(world, template, processors, origin, chunk, rotation, &mut rng);
        }
        true
    }
}

fn place_template(world: &mut World, template: &Template, processors: &Processors,
                  origin: Pos, chunk: [i32; 2], rotation: usize, rng: &mut Random) {
    let palette = &template.palettes[rng.int(template.palettes.len() as u32) as usize];
    let program = processors.program(&world.profile.materials);
    let mut context = program.context(origin);
    context.feature_placement = true;
    let mut writes = Vec::with_capacity(template.blocks.len() / 4);
    for b in template.blocks.chunks_exact(4) {
        let p = plus(origin, rotate([b[0], b[1], b[2]], rotation));
        let Some(current) = world.material(p) else { continue; };
        let mut material = palette[b[3] as usize][rotation];
        context.position = p;
        let mut tag = None;
        if program.apply(&mut material, current, &mut tag, rotation, world.profile, rng, &mut context)
            && (-16..=31).contains(&(p[0] - chunk[0]))
            && (-16..=31).contains(&(p[2] - chunk[1]))
        {
            writes.push((p, material));
        }
    }
    for (at, material) in writes {
        world.write(at, material);
    }
}
