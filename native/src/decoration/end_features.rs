//! End-specific deterministic feature geometry. Random End features get their
//! own vanilla world-seeded stream rather than changing other dimensions' RNG.
pub mod islands;
pub mod chorus;
mod placement;
use super::*;
use super::placement::Overlay;

pub(super) fn platform(
    materials: [u16; 2],
    origin: [i32; 3],
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    overlay: &Overlay,
    output: &mut Vec<WorldBlock>,
) {
    // EndPlatformFeature.createEndPlatform: Z, X, Y; floor is originY-1.
    for dz in -2..=2 { for dx in -2..=2 { for dy in -1..3 {
        let at = [origin[0] + dx, origin[1] + dy, origin[2] + dz];
        if at[1] < request.min_y || at[1] >= request.min_y + request.height as i32 { continue; }
        let material = materials[usize::from(dy != -1)];
        let Some(current) = overlay.material(field, profile, request, at) else { continue; };
        if current == material { continue; }
        output.push(WorldBlock { root_order: 0, tick: 0, x: at[0], y: at[1], z: at[2],
            material, upper: 0, role: FEATURE });
    }}}
}
