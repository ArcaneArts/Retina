# Windows Vulkan / DirectX retest

Use the same test JAR, Minecraft version, GPU, driver, world definition, seed,
world settings, datapacks, render distance and generation workload throughout.
Confirm the log's selected adapter is the intended GPU. Keep the PC on the same
power setting. Close each game process before the next launch.

## Four startup runs

Use disposable copies of one test world so each run starts with the same existing
chunks. For example, keep a template copy before a fixed Chunky pregeneration
area, then restore that copy before every run. Use the same area and worker count;
a fixed workload gives a better comparison than flying in different directions.

Set `debug = true` in `config/retina.toml`. Select one backend at a time:

| Backend | `allow_directx` | `allow_vulkan` | `allow_metal` |
| --- | --- | --- | --- |
| Vulkan | false | true | false |
| DirectX 12 | true | false | false |

For cache isolation, use Prism's instance environment-variable setting to add
`RETINA_PIPELINE_CACHE_DIR=retina-cache/backend-retest-20261031`. This keeps the
normal Retina application cache separate. Do not clear the whole instance cache
or GPU-driver directories. Leave other diagnostic environment overrides unset,
including `RETINA_VULKAN_FAST_COMPILE`.

1. Vulkan first launch: start with a new diagnostic cache directory, load the
   world, run the fixed generation area, and save the complete log.
2. Vulkan repeat launch: restore the same world template and repeat, preserving
   the diagnostic pipeline cache and identical settings.
3. DirectX first launch: switch the allow flags, restore the template, and repeat.
4. DirectX repeat launch: restore the template and repeat again.

“Cold” here means a fresh game process and a new Retina application-cache input.
Retina does not clear driver-managed caches. DX12 does not expose Retina's Vulkan
pipeline-cache mechanism, so do not claim its first run has a completely cold
Windows driver cache. Record whether the machine recently ran that JAR/backend.

## Steady generation throughput

After collecting startup diagnostics, set `debug = false`, restart, and compare
the same fixed generation workload again on both backends. Keep concurrency the
same. Let background preparation settle and measure sustained completed chunks
per second over the full area; record the area size and elapsed time. Short peak
values and already-generated/cached chunks do not establish generation speed.

For any remaining large gap, collect a separate debug-enabled run of that same
area. `Region GPU selected` lists each stage's actual interpreter, specialized or
fallback path; `Region GPU completed` uses the same job ID and block origin.
These are GPU-job wall times, including preparation/readback, rather than pure
GPU-device time or the full region-to-disk time. Mixed paths are expected.
Debug also enables detailed shader capture, so compare debug runs with each
other and keep headline throughput measurements separate.

## Send back

- The complete launcher log for each of the four startup runs, labeled by backend
  and first/repeat launch. Keep the interval from adapter selection through world
  readiness and the generation workload; do not send only the last WAIT line.
- Minecraft/Retina version, GPU/driver, pack/seed/settings, worker count, and fixed
  area coordinates/size.
- Approximate world-open time and sustained completed chunks/sec for each backend.
- Any crash report if a run fails.

Useful log lines include `Selected adapter`, `Pipeline cache cold/loaded`,
`Vulkan pipeline feedback`, `Pipeline cache checkpoint/persisted`, source/driver
stage durations, and the paired `Region GPU selected/completed` summaries.
`cache_hit=unknown` means unsupported/invalid feedback. A loaded file, or a faster
second run, does not by itself prove an application-cache hit.

No process attachment, JVM diagnostic utility, or standalone GPU probe is needed.
If a first startup is still progressing slowly, retain the full log and the last
stage/elapsed time; a ten-second heartbeat reports elapsed waiting, not a driver
percentage or a timeout guarantee.
