# Vulkan cache lifecycle follow-up

Completed cache data is disposable. wgpu 30 deliberately treats a different
adapter as an avoidable validation error, even with `fallback=true`. Retina now
attempts the actual load under a validation scope and creates an empty cache if
that attempt fails. No synthetic compatibility gate prevents pipeline creation.

The default cache identity is preserved. Explicit worker/policy variants receive
distinct filenames and independent cache objects, locks and writers. Upcoming
parallel and tiered compilation uses those identities. wgpu's public cache API
provides creation, persistence and reuse but no merge operation; independent
worker files avoid introducing raw Vulkan handle ownership for merging.

The headless region runner now registers the existing native cache flush at
process exit, matching the Java shutdown hook and native CLI. It retains the
last completed checkpoint when a compiler is active; abrupt termination cannot
flush work which never completed. A child-process test exits without destructors
after flushing a complete checkpoint and leaving an incomplete temporary file.
The parent reloads the checkpoint and rejects the partial file.

Validation: 400 native tests passed (106 opt-in tests ignored) in the optional
Vulkan portability build. An actual Vulkan test reloaded cache data, supplied a
foreign-adapter header, observed wgpu's rejection, recovered an empty cache and
created the same shader pipeline twice successfully. Three Python tests passed.

Two separate MoltenVK processes each passed four deterministic GPU queries. The
first loaded a new private Retina cache; the second loaded its 96,433-byte terrain
cache. Driver pipeline creation measured 1626.047 ms then 0.457 ms. The driver
reported valid feedback and `cache_hit=no` in both processes, so the warm speedup
is not attributed specifically to Retina's cache. Driver caches are uncontrolled.
These are Apple portability results, not NVIDIA startup measurements.
