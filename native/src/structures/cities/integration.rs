use super::{Piece as CityPiece, plan};
use crate::structures::{self, Element, Part, Piece, Random, Start};
use crate::{
    ChunkRequest,
    end::{IslandNoise, VanillaDensity},
    profile::WorldProfile,
};
use std::sync::Arc;

pub(in crate::structures) fn build(
    request: ChunkRequest,
    profile: &WorldProfile,
    definition: usize,
) -> Result<Start, String> {
    let mut start = structures::empty_start(request, request.seed, definition);
    let end = profile
        .end
        .as_ref()
        .ok_or("End city requires native End terrain")?;
    let p = &profile.structures;
    let kit = p
        .end_cities
        .as_ref()
        .ok_or("missing resident End city templates")?;
    let d = &p.definitions[definition];
    let islands = IslandNoise::new(request.seed);
    let x = request.chunk_x * 16 + 7;
    let z = request.chunk_z * 16 + 7;
    if !d.biomes.contains(&end.biome(&islands, x, z)) {
        return Ok(start);
    }
    let density = VanillaDensity::new(request.seed);
    let tile = density.tile(x - 5, z - 5, 11, 11)?;
    let planned = plan(
        request.seed,
        [request.chunk_x, request.chunk_z],
        &kit.catalog(p),
        |x, z| d.biomes.contains(&end.biome(&islands, x, z)),
        |x, z| {
            tile.column(x, z)
                .unwrap()
                .iter()
                .rposition(|&solid| solid)
                .map_or(-1, |y| y as i32)
        },
    );
    if let Some(planned) = planned {
        for piece in planned.pieces {
            let template = kit.templates[&piece.template].template;
            let palette = Random::new(structures::position_seed(piece.pos))
                .int(p.templates[template].palettes.len() as u32)
                as usize;
            start.pieces.push(Piece {
                element: Arc::new(Element {
                    parts: vec![Part {
                        template,
                        ignore_air: !piece.overwrite,
                        finalize_entities: false,
                        processors: serde_json::json!([]),
                        program: Arc::default(),
                    }],
                    terrain_matching: false,
                    weight: 1,
                }),
                pos: piece.pos,
                rotation: piece.rotation,
                bounds: piece.bounds,
                palette,
                context: 0,
                depth: piece.depth as u32,
                priority: 0,
                fortress: None,
            });
        }
    }
    Ok(start)
}
pub(in crate::structures) fn saved_piece(
    piece: &Piece,
    p: &structures::Profile,
) -> serde_json::Value {
    let part = &piece.element.parts[0];
    CityPiece {
        template: p.templates[part.template]
            .id
            .strip_prefix("minecraft:end_city/")
            .unwrap()
            .into(),
        pos: piece.pos,
        rotation: piece.rotation,
        bounds: piece.bounds,
        depth: piece.depth as i32,
        overwrite: !part.ignore_air,
    }
    .saved_tag()
}
