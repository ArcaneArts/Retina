# Reuse complete End material coverage

Ordered decoration previously normalized every terrain request to a 16-chunk
tile. The initial region already contained many requested columns and runs,
but an outer tile could not be cropped in its entirety. Mostly-void generation
therefore built twelve extra terrain tiles before decoration, then scanned
their otherwise unused columns.

End jobs now retain one shared field containing the initial mask's complete
material-backed interior. A chunk reuses that field only when its material
coverage is complete. Its mask supplies a further biome/height-column guard.
End views distinguish those cached column values from material coverage;
material reads outside the interior use the original tile generation path.
The unbounded fallback map remains intact, including for custom/far reads.
Other terrain strategies keep their existing path.

Full End regions have 42 chunks of column coverage and a 40-chunk material
interior around the 32-chunk output. This covers the usual placement/read
neighborhood, including chorus support queries and preceding gateway
producers. Demand tiles retain their existing guard geometry. This is a cache
coverage choice, never a maximum feature/structure reach or output cutoff.

The copied material field owns its columns and retains the immutable source
mask. Job lifetime and cleanup remain synchronous. Tests explicitly distinguish
valid outer biome columns from unavailable material runs, check the surrounding
anchor neighborhood, and compare negative/boundary columns to the exact End
evaluator.

Uniform cell states also use packed solid-height and uncertain-cell bitsets.
Only uncertain cells execute interpolation. Storage per coarse X/Z cell column
falls from 32 to 24 bytes. A separate bitset-only probe showed no meaningful
latency improvement; the measured overall improvement comes from avoiding
duplicate terrain work.

Spike producers with no center in their anchor chunk now return their exact
empty result before loading terrain. This uses the validated placement
ownership rule and actual loaded centers (or explicit spike positions), not
the affected block radius. Cross-chunk writes still originate from their
proper producer. Independent seeded-layout fixtures and an explicit negative
center with a large radius verify that distinction. Skipped producers do not
share random state with other anchors/features.

## Validation and bounded results

The native suite passes 342 tests, zero failures, with 82 optional tests ignored.
Four complete raw-record hashes match the original dense baseline: mostly void
(1,0), islands (8,8), city (-2,-3), and arena (0,0). Valid records for all 1,024
chunks still include biomes, heightmaps, lighting, structures, and entities.

Two warmups and eight measured regions per category use seed 123456789, Metal,
ZLIB, eight workers and one caller. Timing covers complete production region
generation, publication, and synchronous generation-owned cleanup. Hashing and
deletion happen afterward. Known Bukkit workloads were excluded from the
window; other machine activity was not certified absent. Medians are the
conventional central-pair average, and p95 with eight samples is the maximum.

| Workload | Median ms | P95/max ms | Chunks/second |
| --- | ---: | ---: | ---: |
| Mostly void | 99.0 | 117.7 | 10,093 |
| Outer islands | 153.3 | 166.5 | 6,847 |
| Cities/central arena | 111.6 | 158.1 | 8,614 |

The fresh matched void baseline was 156.9 ms median and 167.4 ms p95/max,
giving a 36.9% median reduction. Peak process RSS for that comparison falls
from 922.6 MB to 815.9 MB. Candidate category peaks are 806.6–887.7 MB, including
startup/driver state; this is not a per-region allocation measurement. Cold
startup is separately reported at about 188–192 ms for these End inputs.

An opt-in diagnostic region reduces actual End terrain builds from thirteen to
two, and reuses the shared field for 276,222 requests. The first decoration root
falls from about 75 ms to 3.5 ms. Additive lattice/material construction costs
are 19.3/18.6 ms; aggregate mask bytes fall from 696,192 in the bitset-only probe
to 215,808 by constructing fewer masks. The initial material-field copy is
4,915,200 bytes. These counters include required halo work and are not additive
wall-time shares. City stair-shape scanning still takes about 35 ms in that
probe, but the requested region targets are met in this development workload.

Matched normal-world controls (seed 731, same eight-worker budget, two warmups
and eight measurements each) show no regression: Overworld median 421.5 to
322.0 ms, Nether 814.3 to 715.4 ms. Their complete record hashes match. These
unrelated speedups are not attributed to End-only reuse; the control is a
regression check, with limited tail confidence. All three 5% comparison guards
pass. The density-composition environment override was unset.

All newly generated disposable MCA files were removed incrementally after
evidence capture. These are development results, not shipping-default,
multi-caller, cold-portal-readiness, or gameplay acceptance. Production End
activation remains deferred until progression and compatibility are validated.
