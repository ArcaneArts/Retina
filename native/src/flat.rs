//! Native flat layers. Base terrain participates in normal feature replay;
//! non-solid fill-layer features run after the other generation stages.
use crate::{COLUMNS, ChunkRequest, profile::Column};
use serde::Deserialize;

#[derive(Clone, Deserialize)]
pub struct Profile {
    pub base_layers: Vec<u16>,
    pub deferred_layers: Vec<[u32; 2]>,
    pub air_materials: Vec<u16>,
}

impl Profile {
    pub fn validate(&self, palette: usize) -> Result<(), String> {
        if self.base_layers.len() > 4096
            || self.base_layers.iter().any(|&id| id as usize >= palette)
            || self
                .deferred_layers
                .iter()
                .any(|&[y, id]| y as usize >= self.base_layers.len() || id as usize >= palette)
            || self.air_materials.iter().any(|&id| id as usize >= palette)
        {
            return Err("invalid native flat layers".into());
        }
        Ok(())
    }

    pub fn material(&self, y: i32, min_y: i32) -> u16 {
        y.checked_sub(min_y)
            .and_then(|layer| usize::try_from(layer).ok())
            .and_then(|layer| self.base_layers.get(layer).copied())
            .unwrap_or(0)
    }

    pub fn column(&self, request: ChunkRequest) -> Column {
        let layers = &self.base_layers[..self.base_layers.len().min(request.height as usize)];
        let top = layers.iter().rposition(|&id| id != 0);
        let material = top.map_or(0, |y| layers[y]);
        let filler = top
            .and_then(|y| y.checked_sub(1))
            .map_or(material, |y| layers[y]);
        Column {
            height: request.min_y + top.map_or(0, |y| y + 1) as i32,
            packed: 0,
            materials: material as u32 | ((filler as u32) << 16),
        }
    }

    pub fn assemble(&self, blocks: &mut [u16]) {
        for (y, row) in blocks.chunks_exact_mut(COLUMNS).enumerate() {
            row.fill(self.base_layers.get(y).copied().unwrap_or(0));
        }
    }

    pub fn finish(&self, blocks: &mut [u16]) {
        for &[y, material] in &self.deferred_layers {
            if let Some(row) = blocks.chunks_exact_mut(COLUMNS).nth(y as usize) {
                for block in row.iter_mut().filter(|id| self.air_materials.contains(id)) {
                    *block = material as u16;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flat_layers_preserve_air_and_deferred_fills_and_clear_reused_buffers() {
        let flat = Profile {
            base_layers: vec![2, 0, 3, 0],
            deferred_layers: vec![[3, 4]],
            air_materials: vec![0, 5],
        };
        flat.validate(6).unwrap();
        let mut blocks = vec![99; 6 * COLUMNS];
        flat.assemble(&mut blocks);
        assert_eq!(
            blocks
                .chunks_exact(COLUMNS)
                .map(|r| r[0])
                .collect::<Vec<_>>(),
            vec![2, 0, 3, 0, 0, 0]
        );
        blocks[3 * COLUMNS] = 1; // FillLayerFeature preserves non-air placed by earlier features.
        blocks[3 * COLUMNS + 1] = 5; // Cave air from a lake is also replaced, as in Minecraft's isAir.
        flat.finish(&mut blocks);
        assert_eq!(blocks[3 * COLUMNS], 1);
        assert!(
            blocks[3 * COLUMNS + 1..4 * COLUMNS]
                .iter()
                .all(|&id| id == 4)
        );
        assert_eq!(flat.material(-62, -64), 3);
        assert_eq!(flat.material(-65, -64), 0);
        assert_eq!(flat.material(-60, -64), 0);
        let mut clipped = vec![99; COLUMNS];
        flat.assemble(&mut clipped);
        flat.finish(&mut clipped);
        assert!(clipped.iter().all(|&id| id == 2));
    }
}
