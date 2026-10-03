//! Registry processor JSON compiled once into typed rules and material lookup tables.
use crate::structures::{Random, sub};
use crate::{nbt, profile::WorldProfile};
use serde_json::Value;
use std::borrow::Cow;

#[derive(Default)]
pub struct Program {
    processors: Vec<Processor>,
    pub capped: bool,
    counters: usize,
}
enum Processor {
    Rot {
        rottable: Option<Vec<bool>>,
        integrity: f64,
    },
    Rules(Vec<Rule>),
    Capped {
        id: usize,
        limit: u32,
        delegate: Box<Processor>,
    },
    Protected(Vec<bool>),
    List(Vec<Processor>),
    Noop,
}
struct Rule {
    input: Predicate,
    location: Predicate,
    position: Position,
    output: [Option<u16>; 4],
    entity: Option<Value>,
    modifier: Modifier,
    loot: Option<String>,
}
struct Predicate {
    matching: [Vec<bool>; 4],
    probability: Option<f64>,
}
enum Position {
    Always,
    Linear {
        axis: Option<usize>,
        min: f64,
        max: f64,
        low: f64,
        high: f64,
    },
    Never,
}
enum Modifier {
    None,
    Clear,
    Append(Value),
}
pub struct Context {
    pub position: [i32; 3],
    pub origin: [i32; 3],
    counts: Vec<u32>,
}
impl Program {
    pub fn compile(v: &Value, materials: &[Value]) -> Self {
        let mut out = Self::default();
        let processor = compile(v, materials, &mut out.counters);
        out.capped = out.counters != 0;
        out.processors.push(processor);
        out
    }
    pub fn context(&self, origin: [i32; 3]) -> Context {
        Context {
            origin,
            position: origin,
            counts: vec![0; self.counters],
        }
    }
    pub fn apply<'a>(
        &'a self,
        material: &mut u16,
        world: u16,
        tag: &mut Option<Cow<'a, Value>>,
        rotation: usize,
        profile: &WorldProfile,
        r: &mut Random,
        ctx: &mut Context,
    ) -> bool {
        self.processors
            .iter()
            .all(|p| p.apply(material, world, tag, rotation, profile, r, ctx))
    }
}
fn name(v: &Value) -> &str {
    v.as_str().or_else(|| v["id"].as_str()).unwrap_or("")
}
fn ids(v: &Value, materials: &[Value]) -> Vec<bool> {
    let mut ids = vec![false; materials.len()];
    if let Some(a) = v.as_array() {
        for id in a {
            if let Some(id) = id.as_u64() {
                if let Some(e) = ids.get_mut(id as usize) {
                    *e = true;
                }
            }
        }
    }
    ids
}
impl Predicate {
    fn compile(v: &Value, materials: &[Value]) -> Self {
        let kind = v["predicate_type"]
            .as_str()
            .unwrap_or("minecraft:always_true")
            .trim_start_matches("minecraft:");
        let matching = std::array::from_fn(|rotation| {
            materials
                .iter()
                .enumerate()
                .map(|(i, m)| match kind {
                    "always_true" => true,
                    "block_match" | "random_block_match" => {
                        name(m) == v["block"].as_str().unwrap_or("")
                    }
                    "blockstate_match" | "random_blockstate_match" => {
                        v["block_state"]["retina_rotations"][rotation].as_u64() == Some(i as u64)
                    }
                    "tag_match" => v["retina_matching"]
                        .as_array()
                        .is_some_and(|a| a.iter().any(|n| n.as_u64() == Some(i as u64))),
                    _ => false,
                })
                .collect()
        });
        Self {
            matching,
            probability: kind
                .starts_with("random_")
                .then(|| v["probability"].as_f64().unwrap_or(1.0)),
        }
    }
    fn matches(&self, material: u16, rotation: usize, r: &mut Random) -> bool {
        self.matching[rotation][material as usize] && self.probability.is_none_or(|p| r.float() < p)
    }
}
impl Position {
    fn compile(v: &Value) -> Self {
        match v["predicate_type"]
            .as_str()
            .unwrap_or("minecraft:always_true")
            .trim_start_matches("minecraft:")
        {
            "always_true" => Self::Always,
            kind @ ("linear_pos" | "axis_aligned_linear_pos") => Self::Linear {
                axis: (kind == "axis_aligned_linear_pos").then(|| {
                    match v["axis"].as_str().unwrap_or("y") {
                        "x" => 0,
                        "z" => 2,
                        _ => 1,
                    }
                }),
                min: v["min_dist"].as_f64().unwrap_or(0.0),
                max: v["max_dist"].as_f64().unwrap_or(100.0),
                low: v["min_chance"].as_f64().unwrap_or(0.0),
                high: v["max_chance"].as_f64().unwrap_or(0.0),
            },
            _ => Self::Never,
        }
    }
    fn matches(&self, ctx: &Context, r: &mut Random) -> bool {
        match self {
            Self::Always => true,
            Self::Never => false,
            Self::Linear {
                axis,
                min,
                max,
                low,
                high,
            } => {
                let delta = sub(ctx.position, ctx.origin);
                let distance = axis
                    .map_or_else(|| delta.iter().map(|n| n.abs()).sum(), |a| delta[a].abs())
                    as f64;
                let blend = ((distance - min) / (max - min).max(1.0)).clamp(0.0, 1.0);
                r.float() < low * (1.0 - blend) + high * blend
            }
        }
    }
}
fn compile(v: &Value, materials: &[Value], counters: &mut usize) -> Processor {
    if let Some(list) = v.as_array() {
        return Processor::List(
            list.iter()
                .map(|v| compile(v, materials, counters))
                .collect(),
        );
    }
    if let Some(list) = v.get("processors") {
        return compile(list, materials, counters);
    }
    match v["processor_type"]
        .as_str()
        .unwrap_or("")
        .trim_start_matches("minecraft:")
    {
        "block_rot" => Processor::Rot {
            rottable: v.get("retina_rottable").map(|v| ids(v, materials)),
            integrity: v["integrity"].as_f64().unwrap_or(1.0),
        },
        "protected_blocks" => Processor::Protected(ids(&v["retina_protected"], materials)),
        "capped" => {
            let id = *counters;
            *counters += 1;
            Processor::Capped {
                id,
                limit: v["limit"]
                    .as_u64()
                    .or_else(|| v["limit"]["value"].as_u64())
                    .unwrap_or(1) as u32,
                delegate: Box::new(compile(&v["delegate"], materials, counters)),
            }
        }
        "rule" => Processor::Rules(v["rules"].as_array().map_or_else(Vec::new, |rules| {
            rules
                .iter()
                .map(|rule| {
                    let m = &rule["block_entity_modifier"];
                    Rule {
                        input: Predicate::compile(&rule["input_predicate"], materials),
                        location: Predicate::compile(&rule["location_predicate"], materials),
                        position: Position::compile(&rule["position_predicate"]),
                        output: std::array::from_fn(|i| {
                            rule["output_state"]["retina_rotations"][i]
                                .as_u64()
                                .map(|id| id as u16)
                        }),
                        entity: rule["output_state"].get("retina_entity").cloned(),
                        modifier: match m["type"]
                            .as_str()
                            .unwrap_or("")
                            .trim_start_matches("minecraft:")
                        {
                            "clear" => Modifier::Clear,
                            "append_static" => m
                                .get("retina_nbt")
                                .map_or(Modifier::None, |v| Modifier::Append(v.clone())),
                            _ => Modifier::None,
                        },
                        loot: m["loot_table"].as_str().map(str::to_owned),
                    }
                })
                .collect()
        })),
        _ => Processor::Noop,
    }
}
impl Processor {
    fn apply<'a>(
        &'a self,
        material: &mut u16,
        world: u16,
        tag: &mut Option<Cow<'a, Value>>,
        rotation: usize,
        profile: &WorldProfile,
        r: &mut Random,
        ctx: &mut Context,
    ) -> bool {
        match self {
            Self::Noop => {}
            Self::List(list) => {
                return list
                    .iter()
                    .all(|p| p.apply(material, world, tag, rotation, profile, r, ctx));
            }
            Self::Rot {
                rottable,
                integrity,
            } => {
                if rottable.as_ref().is_none_or(|ids| ids[*material as usize])
                    && r.float() > *integrity
                {
                    return false;
                }
            }
            Self::Protected(ids) => {
                if ids[world as usize] {
                    return false;
                }
            }
            Self::Capped {
                id,
                limit,
                delegate,
            } => {
                if ctx.counts[*id] < *limit {
                    let before = (*material, tag.clone());
                    let keep = delegate.apply(material, world, tag, rotation, profile, r, ctx);
                    if !keep || before != (*material, tag.clone()) {
                        ctx.counts[*id] += 1;
                    }
                    return keep;
                }
            }
            Self::Rules(rules) => {
                for rule in rules {
                    if rule.input.matches(*material, rotation, r)
                        && rule.location.matches(world, rotation, r)
                        && rule.position.matches(ctx, r)
                    {
                        if let Some(id) = rule.output[rotation] {
                            if profile.structures.names[*material as usize]
                                != profile.structures.names[id as usize]
                            {
                                *tag = rule.entity.as_ref().map(Cow::Borrowed);
                            }
                            *material = id;
                        }
                        match &rule.modifier {
                            Modifier::Clear => *tag = None,
                            Modifier::Append(append) => {
                                let t = tag
                                    .get_or_insert_with(|| Cow::Owned(nbt::compound()))
                                    .to_mut();
                                for (key, value) in append[1].as_object().unwrap() {
                                    nbt::put(t, key, value.clone());
                                }
                            }
                            Modifier::None => {}
                        }
                        if let Some(loot) = &rule.loot {
                            nbt::put(
                                tag.get_or_insert_with(|| Cow::Owned(nbt::compound()))
                                    .to_mut(),
                                "LootTable",
                                nbt::string(loot),
                            );
                        }
                        break;
                    }
                }
            }
        }
        true
    }
}
