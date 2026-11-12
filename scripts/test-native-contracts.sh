#!/usr/bin/env bash
# Local headless CPU and shader contract coverage for the shared native engine.
set -euo pipefail
cd "$(dirname "$0")/.."
export RETINA_REGION_THREADS="${RETINA_REGION_THREADS:-2}"

cargo test --manifest-path native/Cargo.toml --locked --no-default-features --lib --target-dir build/cpu-contract-target cpu:: -- --test-threads=2
cargo test --manifest-path native/Cargo.toml --locked --no-default-features --lib --target-dir build/cpu-contract-target program::opcode::tests
cargo test --manifest-path native/Cargo.toml --locked --no-default-features --lib --target-dir build/cpu-contract-target gpu_readback::tests
cargo test --manifest-path native/Cargo.toml --locked --no-default-features --lib --target-dir build/cpu-contract-target specialize::material_dispatch::tests
cargo test --manifest-path native/Cargo.toml --locked --no-default-features --lib --target-dir build/cpu-contract-target gpu::policy::tests
cargo test --manifest-path native/Cargo.toml --locked --no-default-features --lib --target-dir build/cpu-contract-target gpu_backend::tests
cargo test --manifest-path native/Cargo.toml --locked --no-default-features --bin retina --target-dir build/cpu-contract-target
