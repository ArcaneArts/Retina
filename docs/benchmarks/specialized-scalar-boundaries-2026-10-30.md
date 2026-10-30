# Specialized function boundaries and device-loss handling

## Reported Windows runs

Retina 1.1.441 on DX12 / RTX 3060 reached the world. The compact world pipeline
completed in 13.627 seconds, versus more than 300 seconds in the earlier report.
Engine initialization took 31.543 seconds and spawn preparation 33.316 seconds.
Background specialization still took 56.452 seconds in DXC for `main`; its bundle
was running after 230 seconds when the log ended.

The subsequent Vulkan run on the same GPU initialized in 136.565 seconds and
compiled its first compact world/cave bundle in 137.280 seconds. Roughly four
seconds later it reported `Parent device is lost`. Repeated poll panics and an
invalid map request followed; Java received only a generic completion-panic
message. The log does not establish why the device was lost. A timeout or driver
fault remains possible; this pass does not claim to have reproduced it locally.

## Change

Extend retained function boundaries to the generated specialized graph,
interpolation-graph, and raw-column bodies. Apply the transformation after
constant-call and cached-field rewrites. Preserve arithmetic, register clearing,
request scheduling, all existing generation policy defaults, and End's separate
path.

Use scalar parameters and distinct local scalar output pointers for **both** the
interpreter and specialized bodies. This replaces the previous module-private
input/output exchange. DXIL accepts this ABI without disabling validation. It
also removes global aliasing from the Vulkan/Metal compiler inputs. An initial
experiment extending private exchange storage caused very long Metal compiles;
it was rejected. The final scalar version passed complete generation comparisons.
The underlying NVIDIA Vulkan device-loss behavior still needs a Windows retest.

Register a real device-lost callback, preserve the first error and last submission
details, catch wgpu poll panics, and drain queued requests with that error after a
confirmed failure. Later generation requests and queued background compilation jobs fail before
starting work on the failed device. A driver call already in progress cannot be
cancelled by this check. Normal destruction is not logged as a crash. No fabricated
fallback terrain or driver-timeout setting changes are involved. The first eight
submissions log backend, request dimensions, and workload sizes; a loss callback
also reports the last submission even after that logging limit.

## Compiler measurements

Exact final WGSL captured by the production test path; Naga 30.0.1 and bundled
DXC 0.1.7+2026.10.26-141dd7f.1 on Apple Silicon. HLSL binding numbers are synthetic;
no NVIDIA driver runs in this host audit. Single sequential measurements, with
normal background build activity, rather than a statistical performance test.

| Entry | DXC seconds | DXIL bytes |
|---|---:|---:|
| Original specialized main | 20.082 | 1,229,028 |
| Scalar outlined specialized main | 4.304 | 497,796 |
| Scalar outlined lake density | 2.205 | 49,708 |
| Scalar outlined interpolation prepass | 1.609 | 304,308 |
| Scalar outlined material dispatch | 1.124 | 243,636 |
| Scalar outlined compact interpreter | 1.737 | 239,984 |

All six outputs passed DXC validation and `spirv-val --target-env vulkan1.1`.
The compiler audit no longer assumes identical function counts between HLSL and
SPIR-V: the emitters remove unused functions differently. Source dumps now occur
after constant-call selection, making specialized audits match production input.

## Validation

- Native release suite: 387 passed, 100 opt-in tests ignored.
- Backend transformation tests: 2 passed.
- Real Metal specialized comparison: 12 cases, 27,648 columns plus material masks,
  compared bitwise with original generated code. Both default mixed mode and full
  specialized terrain were exercised in Overworld and Nether, including clipped
  height ranges and distant coordinates. Repeated once after compilation.
- Warm host generation samples ranged from 1.58–7.32 ms for the original and
  1.69–6.38 ms for outlined code. This is a small regression check, not evidence of
  a general throughput improvement.
- Real Metal interpreter comparison: 8 cases, 14,336 columns and 6 material masks
  matched the original interpreter.
- Real device destruction test: polling the destroyed device returns the same
  preserved failure repeatedly, without reusing it.
- Windows x64 release DLL and standalone probe cross-built with bundled DXC and
  static CRT. Windows startup, generation, and device-loss behavior require the
  next user test.
