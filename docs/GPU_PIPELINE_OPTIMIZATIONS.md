# Generator optimization steps

## Shared jigsaw entries

Pool entries and accepted pieces share immutable `Arc<Element>` values. Weighted
shuffles borrow those handles, retaining the original expanded slot order,
Fisher–Yates draws and fallback order. Processor programs remain compiled once;
pool shuffles and piece copies no longer deep-copy their input JSON or part lists.

Apple M4 Max/Metal, release build, full vanilla `structure-profile.json`, seed
123456789, three warmups and 20 adjacent regions: the first candidate run reached
6,510 native chunks/s with 14.53 ms/region in structure planning. Two concurrent
callers reached 8,457 chunks/s, with 27.76 ms/region in that phase under contention.
The preceding review's serial baselines reached 3,806–4,360 chunks/s and
99–114 ms/region in structure planning. These are native measurements, excluding
Minecraft loading, lighting and rendering; system activity is uncontrolled.

Both serial and concurrent candidate runs matched all 40,960 decompressed chunk
NBT records against the retained baseline. Twelve native unit tests and the real
Metal parallel chunk/height-query test passed. A dedicated shuffle test verifies
shared identity, weighted order and subsequent random state.

Reproduce with `scripts/native-region-benchmark.py`, `--count 20 --warmups 3`,
then repeat with `--parallel 2`. Preserve each release library before rebuilding
and use `--compare` for behavior-preserving changes. Local results are in
`build/optimization-steps/`; they are intentionally not committed.
