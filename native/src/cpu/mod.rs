//! Native CPU generation kernels. Both executors consume the saved registry
//! programs; CPU arithmetic may differ within ordinary f32 rounding tolerance.
pub mod aquifers;
pub mod caves;
pub mod columns;
pub(crate) mod features;
pub mod fields;
pub mod noise;
pub(crate) mod pools;
pub mod program;

pub(crate) mod materials;
pub(crate) mod terrain;
