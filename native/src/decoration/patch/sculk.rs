//! Registry-backed Deep Dark charge spread over the ordered feature overlay.
use super::{Predicate, World};
use crate::decoration::{Rng, attachment, generation::FeatureEntity, placement::IntProvider};
use serde::Deserialize;

const DIRECTIONS: [usize; 6] = [0, 1, 2, 3, 4, 5];
const SPREAD: [u8; 3] = [0, 1, 2];
const MOVEMENT: [[i32; 3]; 18] = [
    [0, -1, -1],
    [-1, 0, -1],
    [0, 0, -1],
    [1, 0, -1],
    [0, 1, -1],
    [-1, -1, 0],
    [0, -1, 0],
    [1, -1, 0],
    [-1, 0, 0],
    [1, 0, 0],
    [-1, 1, 0],
    [0, 1, 0],
    [1, 1, 0],
    [0, -1, 1],
    [-1, 0, 1],
    [0, 0, 1],
    [1, 0, 1],
    [0, 1, 1],
];
// Cursors can move up to 14 blocks on either horizontal axis. Growth
// inspects a further four blocks, including across chunk and region edges.
pub(in crate::decoration) const REACH: i32 = 18;

#[derive(Clone, Deserialize)]
pub struct Recipe {
    veins: attachment::Recipe,
    charge_count: i32,
    amount_per_charge: i32,
    spread_attempts: i32,
    spread_rounds: i32,
    growth_rounds: i32,
    catalyst_chance: f32,
    extra_rare_growths: IntProvider,
    worldgen_replaceable: Predicate,
    runtime_replaceable: Predicate,
    collision_full: Predicate,
    air: Predicate,
    source_water: Predicate,
    wet: Predicate,
    existing_growth: Predicate,
    air_state: u16,
    water_state: u16,
    sculk: u16,
    catalyst: u16,
    sensor: [u16; 2],
    shrieker: [u16; 2],
}

#[derive(Clone, Copy)]
enum Behaviour {
    Default,
    Sculk,
    Vein(u8),
}

#[derive(Clone, Copy)]
struct Cursor {
    pos: [i32; 3],
    charge: i32,
    delay: bool,
    decay: bool,
    faces: Option<u8>,
}

impl Recipe {
    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        self.veins.is_sculk()
            && self.veins.validate(palette)
            && (1..=32).contains(&self.charge_count)
            && (1..=500).contains(&self.amount_per_charge)
            && (1..=64).contains(&self.spread_attempts)
            && (0..=8).contains(&self.spread_rounds)
            && (0..=8).contains(&self.growth_rounds)
            && (0.0..=1.0).contains(&self.catalyst_chance)
            && self.extra_rare_growths.bounds().is_ok()
            && [self.air_state, self.water_state, self.sculk, self.catalyst]
                .into_iter()
                .chain(self.sensor)
                .chain(self.shrieker)
                .all(|id| (id as usize) < palette)
            && [
                &self.worldgen_replaceable,
                &self.runtime_replaceable,
                &self.collision_full,
                &self.air,
                &self.source_water,
                &self.wet,
                &self.existing_growth,
            ]
            .into_iter()
            .all(|p| p.validate(palette))
    }

    pub(super) fn place(&self, world: &mut World<'_>, origin: [i32; 3], rng: &mut Rng) -> bool {
        use attachment::relative;
        if matches!(self.behaviour(world, origin), Behaviour::Default)
            && (!(world.test(&self.air, origin) || world.test(&self.source_water, origin))
                || !DIRECTIONS
                    .into_iter()
                    .any(|d| world.test(&self.collision_full, relative(origin, d))))
        {
            return false;
        }

        let cursor = Cursor {
            pos: origin,
            charge: self.amount_per_charge,
            delay: false,
            decay: true,
            faces: None,
        };
        let mut cursors = [cursor; 32];
        for round in 0..self.spread_rounds + self.growth_rounds {
            let spreading = round < self.spread_rounds;
            let mut active = self.charge_count as usize;
            cursors[..active].fill(cursor);
            for _ in 0..self.spread_attempts {
                let mut remaining = 0;
                for i in 0..active {
                    let mut cursor = cursors[i];
                    self.update(world, origin, &mut cursor, spreading, rng);
                    if cursor.charge > 0 {
                        cursors[remaining] = cursor;
                        remaining += 1;
                    }
                }
                active = remaining;
                if active == 0 {
                    break;
                }
            }
        }

        if (rng.unit() as f32) <= self.catalyst_chance
            && world.test(&self.collision_full, relative(origin, 0))
        {
            world.write_block_entity(origin, self.catalyst, FeatureEntity::SculkCatalyst);
        }
        for _ in 0..self.extra_rare_growths.sample(rng) {
            let pos = [
                origin[0] + rng.below(5) - 2,
                origin[1],
                origin[2] + rng.below(5) - 2,
            ];
            if world.test(&self.air, pos)
                && self
                    .veins
                    .sturdy(relative(pos, 0), 1, &|p| world.material(p))
            {
                world.write_block_entity(pos, self.shrieker[0], FeatureEntity::SculkShrieker);
            }
        }
        true
    }

    fn behaviour(&self, world: &World<'_>, pos: [i32; 3]) -> Behaviour {
        match world.material(pos) {
            Some(id) if id == self.sculk => Behaviour::Sculk,
            Some(id) => self
                .veins
                .faces(id)
                .map_or(Behaviour::Default, Behaviour::Vein),
            None => Behaviour::Default,
        }
    }

    fn update(
        &self,
        world: &mut World<'_>,
        origin: [i32; 3],
        cursor: &mut Cursor,
        spreading: bool,
        rng: &mut Rng,
    ) {
        if cursor.delay {
            cursor.delay = false;
            return;
        }
        let pos = cursor.pos;
        let mut behaviour = self.behaviour(world, pos);
        if spreading {
            match behaviour {
                Behaviour::Sculk => self.spread_all(world, pos, None, &SPREAD),
                Behaviour::Vein(faces) => self.spread_all(world, pos, Some(faces), &SPREAD),
                Behaviour::Default => match cursor.faces {
                    None => self.spread_all(world, pos, None, &SPREAD[..1]),
                    Some(0) => self.spread_all(world, pos, None, &SPREAD),
                    Some(faces) if world.test(&self.air, pos) || world.test(&self.wet, pos) => {
                        let supported = DIRECTIONS
                            .into_iter()
                            .filter(|&d| {
                                faces & (1 << d) != 0
                                    && self.veins.supports(pos, d, &|p| world.material(p))
                            })
                            .fold(0, |mask, d| mask | (1 << d));
                        if supported != 0 {
                            let wet = world.test(&self.wet, pos);
                            world.write(pos, self.veins.with_faces(supported, wet));
                            world.schedule_fluid(pos);
                        }
                    }
                    Some(_) => (),
                },
            }
            if !matches!(behaviour, Behaviour::Sculk) {
                behaviour = self.behaviour(world, pos);
            }
        }

        cursor.charge = match behaviour {
            Behaviour::Default => {
                if cursor.decay {
                    cursor.charge
                } else {
                    0
                }
            }
            Behaviour::Sculk => self.use_sculk_charge(world, origin, pos, cursor.charge, rng),
            Behaviour::Vein(faces) => {
                if spreading && self.convert(world, pos, faces, rng) {
                    cursor.charge - 1
                } else if rng.below(5) == 0 {
                    cursor.charge / 2
                } else {
                    cursor.charge
                }
            }
        };

        if cursor.charge <= 0 {
            self.discharge(world, pos, behaviour);
            return;
        }
        if let Some(next) = self.movement(world, pos, rng) {
            self.discharge(world, pos, behaviour);
            cursor.pos = next;
            if distance_squared([origin[0], next[1], origin[2]], next) >= 15 * 15 {
                cursor.charge = 0;
                return;
            }
        }
        match self.behaviour(world, cursor.pos) {
            Behaviour::Sculk => cursor.faces = Some(0),
            Behaviour::Vein(faces) => cursor.faces = Some(faces),
            Behaviour::Default => (),
        }
        cursor.decay = !matches!(behaviour, Behaviour::Default);
        cursor.delay = true;
    }

    fn spread_all(&self, world: &mut World<'_>, pos: [i32; 3], faces: Option<u8>, order: &[u8]) {
        for from in DIRECTIONS
            .into_iter()
            .filter(|&d| faces.is_none_or(|mask| mask & (1 << d) != 0))
        {
            for toward in DIRECTIONS
                .into_iter()
                .filter(|&d| from / 2 != d / 2 && faces.is_none_or(|mask| mask & (1 << d) == 0))
            {
                if let Some((at, state)) = self
                    .veins
                    .spread_state(pos, from, toward, order, &|p| world.material(p))
                {
                    world.write(at, state);
                    world.schedule_fluid(at);
                }
            }
        }
    }

    fn discharge(&self, world: &mut World<'_>, pos: [i32; 3], behaviour: Behaviour) {
        let Behaviour::Vein(faces) = behaviour else {
            return;
        };
        let remaining = DIRECTIONS
            .into_iter()
            .filter(|&d| {
                faces & (1 << d) != 0
                    && world.material(attachment::relative(pos, d)) != Some(self.sculk)
            })
            .fold(0, |mask, d| mask | (1 << d));
        if matches!(self.behaviour(world, pos), Behaviour::Vein(current) if current == remaining && current != 0)
        {
            return;
        }
        let wet = world.test(&self.wet, pos);
        let state = if remaining == 0 {
            if wet {
                self.water_state
            } else {
                self.air_state
            }
        } else {
            self.veins.with_faces(remaining, wet)
        };
        world.write(pos, state);
        world.schedule_fluid(pos);
    }

    fn convert(&self, world: &mut World<'_>, pos: [i32; 3], faces: u8, rng: &mut Rng) -> bool {
        let mut directions = DIRECTIONS;
        attachment::shuffle(&mut directions, rng);
        for d in directions {
            let target = attachment::relative(pos, d);
            if faces & (1 << d) == 0 || !world.test(&self.worldgen_replaceable, target) {
                continue;
            }
            world.write(target, self.sculk);
            self.spread_all(world, target, None, &SPREAD);
            for other in DIRECTIONS.into_iter().filter(|&other| other != d ^ 1) {
                let adjacent = attachment::relative(target, other);
                self.discharge(world, adjacent, self.behaviour(world, adjacent));
            }
            return true;
        }
        false
    }

    fn movement(&self, world: &World<'_>, pos: [i32; 3], rng: &mut Rng) -> Option<[i32; 3]> {
        let mut offsets = MOVEMENT;
        attachment::shuffle(&mut offsets, rng);
        let mut candidate = None;
        for offset in offsets {
            let at = std::array::from_fn(|i| pos[i] + offset[i]);
            let behaviour = self.behaviour(world, at);
            if matches!(behaviour, Behaviour::Default) {
                continue;
            }
            if offset.iter().filter(|&&v| v != 0).count() > 1
                && !DIRECTIONS
                    .into_iter()
                    .filter(|&d| {
                        attachment::DIRECTIONS[d]
                            .iter()
                            .zip(offset)
                            .any(|(&step, offset)| step != 0 && step == offset)
                    })
                    .any(|d| {
                        !self
                            .veins
                            .sturdy(attachment::relative(pos, d), d ^ 1, &|p| world.material(p))
                    })
            {
                continue;
            }
            candidate = Some(at);
            if let Behaviour::Vein(faces) = behaviour {
                if DIRECTIONS.into_iter().any(|d| {
                    faces & (1 << d) != 0
                        && world.test(&self.runtime_replaceable, attachment::relative(at, d))
                }) {
                    break;
                }
            }
        }
        candidate
    }

    fn use_sculk_charge(
        &self,
        world: &mut World<'_>,
        origin: [i32; 3],
        pos: [i32; 3],
        charge: i32,
        rng: &mut Rng,
    ) -> i32 {
        if rng.below(5) != 0 {
            return charge;
        }
        let distance = distance_squared(origin, pos);
        let above = attachment::relative(pos, 1);
        let wet = world.test(&self.source_water, above);
        if distance > 0
            && (wet || world.test(&self.air, above))
            && self.growth_count(world, pos) <= 2
        {
            if rng.below(50) < charge {
                let (state, entity) = if rng.below(11) == 0 {
                    (self.shrieker, FeatureEntity::SculkShrieker)
                } else {
                    (self.sensor, FeatureEntity::SculkSensor)
                };
                world.write_block_entity(above, state[usize::from(wet)], entity);
                world.schedule_fluid(above);
            }
            return (charge - 50).max(0);
        }
        if rng.below(10) != 0 {
            return charge;
        }
        let factor = ((distance as f32).sqrt() - 1.0).powi(2) / (23.0 * 23.0);
        charge - ((charge as f32 * factor.min(1.0) * 0.5) as i32).max(1)
    }

    fn growth_count(&self, world: &World<'_>, pos: [i32; 3]) -> usize {
        let mut count = 0;
        for z in pos[2] - 4..=pos[2] + 4 {
            for y in pos[1]..=pos[1] + 2 {
                for x in pos[0] - 4..=pos[0] + 4 {
                    if world.test(&self.existing_growth, [x, y, z]) {
                        count += 1;
                        if count > 2 {
                            return count;
                        }
                    }
                }
            }
        }
        count
    }
}

fn distance_squared(a: [i32; 3], b: [i32; 3]) -> i32 {
    a.into_iter().zip(b).map(|(a, b)| (a - b).pow(2)).sum()
}
