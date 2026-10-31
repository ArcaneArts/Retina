//! Native CPU generation kernels. Both executors consume the saved registry
//! programs; CPU arithmetic may differ within ordinary f32 rounding tolerance.
pub mod columns;
pub(crate) mod features;
pub mod fields;
pub mod noise;
pub mod program;
