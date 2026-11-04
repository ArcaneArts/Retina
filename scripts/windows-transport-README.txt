Retina Windows x64 lighting transport test (offline, not a Minecraft mod)

1. Extract the entire ZIP to a writable folder. Do not run inside the ZIP viewer.
2. Keep the computer plugged in. Pause Minecraft generation and other benchmarks.
3. Double-click Run-tests.cmd. No Python, Java, admin access or installation needed.
4. Press Enter for Vulkan, then Enter for screening. Press Enter to use vanilla.
   The optional path is a saved retina.dat from a custom/tall datapack world.
   The source file is read only. No Minecraft save or game configuration is edited.
5. Leave the console open. A trial/heartbeat shows jobs completed/remaining.
   A native driver operation can take time; heartbeat is not a compile percentage.
6. At the end, send the printed SEND-THIS-results.zip file, plus any observations.
   It is inside results/<date-id>/ in the extracted folder.
   Ctrl+C stops this runner's own child and saves partial results. Do not close the
   console outright if you want partial packaging. There is no automatic retry.

Screening: two matched pairs for each of seven candidates, in AB/BA order, for
Overworld and Nether. Each fresh process warms one complete region, awaits a final
shader state, then measures two complete lit/decorated/zlib MCA regions.
Qualification: five matched pairs. This is longer; use screening first.

Baseline: dense input, dense output, CPU palettes. Candidates cover every other
combination of dense/compact input/output and CPU/GPU palettes. The parent never
loads the native library. Trials share an isolated application shader cache;
the first trial has an empty application cache. Driver caches are uncontrolled.
Startup, first warmup, readiness and measured generation are recorded separately.
Do not compare mixed cache-state startup medians as a cold-start speedup.

All completed trials must match exact decompressed NBT and chunk membership within
the selected backend. DirectX is an optional separate control run; CPU/Metal/DX12/
Vulkan are not required to be bit-identical to one another. Detailed diagnostics
can be collected in a separate pass with --attribution; do not mix their timings
into ordinary comparisons. No production backend defaults are changed.

Expected completion: status completed, every trial pass, reference corpus recorded
once per dimension and exact same-backend comparison for subsequent trials.
A failure preserves native.log and partial output and stops the matrix. Return
the results ZIP even on failure. It excludes retina.dat, MCA world files, binaries,
shader caches and dumps; logs contain machine information and local file paths.
Successful MCA files are removed after exact comparison to keep disk use bounded.
Failed partial files may remain in that run's folder for local investigation.

Advanced options (append when invoking Run-tests.cmd from a terminal):
  --dimensions minecraft:overworld
  --count 4 --workers 8
  --cache-mode fresh          (fresh application cache for every child)
  --attribution              (separate instrumented runs after headline trials)
  --keep-regions             (retain potentially large offline MCA files)
These are optional. Default double-click screening is sufficient for first tests.

The runtime is the official CPython Windows embeddable package, pinned by SHA-256.
Its original license is runtime/LICENSE.txt; Retina/native component licenses are
under licenses/. BUNDLE.json identifies source, library, definition and file hashes.
Sources:
https://www.python.org/downloads/release/python-31410/
https://docs.python.org/3/using/windows.html#the-embeddable-package
