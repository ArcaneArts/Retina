//! Exact world-seeded End island root: placement branches share one vanilla RNG.
use super::super::placement::{Modifier, Overlay};
use super::super::*;
use crate::end::DecorationRandom;

#[derive(Clone, Deserialize)]
pub struct Recipe {
    pub material: u16,
    pub step: u32,
    pub index: u32,
    pub biomes: Vec<u16>,
    pub placement: Vec<Modifier>,
}
impl Recipe {
    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        self.material != 0
            && (self.material as usize) < palette
            && super::placement::validate(&self.placement, &self.biomes, palette)
    }
    pub(in crate::decoration) fn place(
        &self,
        origin: [i32; 3],
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        overlay: &Overlay,
        output: &mut Vec<WorldBlock>,
    ) {
        super::placement::walk(
            &self.placement,
            &self.biomes,
            self.index,
            self.step,
            origin,
            field,
            profile,
            request,
            overlay,
            output,
            |at, random, live, output| {
                let start = output.len();
                geometry(self.material, at, random, request, output);
                live.commit(output, start, field, profile, request);
            },
        );
    }
}
fn geometry(
    material: u16,
    origin: [i32; 3],
    random: &mut DecorationRandom,
    request: ChunkRequest,
    output: &mut Vec<WorldBlock>,
) {
    let mut size = random.below(3) as f32 + 4.0;
    let mut y = 0;
    while size > 0.5 {
        let low = (-size).floor() as i32;
        let high = size.ceil() as i32;
        for x in low..=high {
            for z in low..=high {
                if (x * x + z * z) as f32 <= (size + 1.0) * (size + 1.0) {
                    let at = [origin[0] + x, origin[1] + y, origin[2] + z];
                    if at[1] >= request.min_y && at[1] < request.min_y + request.height as i32 {
                        output.push(WorldBlock {
                            root_order: 0,
                            tick: 0,
                            x: at[0],
                            y: at[1],
                            z: at[2],
                            material,
                            upper: 0,
                            role: FEATURE,
                        });
                    }
                }
            }
        }
        size -= random.below(2) as f32 + 0.5;
        y -= 1;
    }
}
