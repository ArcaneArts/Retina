# Incremental specialization experiment

`RETINA_INCREMENTAL_SPECIALIZATION=1` publishes validated specialization
cohorts before the complete bundle finishes. The default remains disabled;
Windows measurements are needed to choose its startup/runtime tradeoff.
The native region benchmark accepts `--incremental-specialization enabled`.

The fixed three-cohort sequence is:

1. Horizontal and interpolation field producers.
2. Caves, aquifers, materials, and their requested cached-density variants.
3. Remaining world/query stages, sparse-lake and point-cache variants.

Every cohort completes its validation scope before publication. Missing stages
resolve through the loaded profile's compatible interpreter and then the
established base fallback. Dispatch selectors travel with the selected pipeline.
Submitted jobs retain immutable pipeline handles, including material emission
continuations; later publication cannot change a job already submitted.
Forced-specialization mode still waits for the complete result. Optional failure
keeps the last validated partial bundle available to automatic generation.

The root world shader module is parsed once across the first and third groups
when their transformed source is identical. Other templates and source variants
retain separate modules. This does not add a global source cache or another
permutation dimension.

Debug output includes `Specialization cohort` JSON. The additional version-1
`retina_gpu_specialization_snapshot` ABI is 32 bytes and reports ready/total groups
separately from final status. The existing 64-byte program snapshot is unchanged.
Benchmark JSON includes `specialization_groups`; the startup report records
cohorts without treating them as completed startup or summing nested wall times.

## Validation

The headless GPU fixture deliberately stops the compiler after groups one and
two, generates while each partial bundle is the only published specialization,
then resumes and compares the complete bundle. It compares columns, cave masks,
biomes and material runs against a separate forced-interpreter GPU. It uses
negative coordinates and an expanded 448-block height in Overworld and Nether;
the Overworld interior covers a full 32-by-32 chunk region plus a border.
The fixture retains old snapshots through later publication and retires both
profile owners afterward. Unit tests cover optional failure and forced waiting.
The fixture passed all three phases for 295,936 Overworld columns and 2,304
Nether columns, including material runs. The cycle's initial native suite passed
423 tests and the startup-report suite passed four tests. After rebasing onto
the concurrent CPU backend changes, the native suite passed 428 tests.

This validates adoption and output correctness on Metal, not NVIDIA speed.
Default-mode performance and wider datapack coverage remain part of the final
cross-cycle regression run. MoltenVK's baseline workgroup-array translation
limitation is documented in the interpreter-promotion report.
