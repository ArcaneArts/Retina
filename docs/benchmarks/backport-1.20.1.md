# Minecraft 1.20.1 backport evidence

## Minecraft 26.3 baseline

The published `v1.1.511` universal JAR from `d71239ac` is the baseline for the
Java transport work. Its SHA-256 is
`1a57f1e56751ecf099913c42c97534833bc9427d6580f984db89b6a0e7c3e783`.
The packaged macOS ARM64 library SHA-256 is
`d349119235ea0f6d44e6a71844b2d819ece3187cd1186852ee377c5e60743119`.

The Java 25 packaged CPU test exercises both vanilla snapshots and checks two
chunks per dimension, queries and lighting. It reopens the same definitions
and checks the chunks again in reverse order. The 26.3 release packaging
change in [PR #430](https://github.com/ArcaneArts/Retina/pull/430) reproduced
all twelve chunk hashes and both definition hashes with the same native
libraries.

The headless timing sample uses a fresh world per run, seed `12345`, one zlib
region at `0,0` in the End, four Retina region threads and two Rayon threads.
The machine was an Apple M4 Max on AC power running macOS 27.0.1 and JDK 25.0.1.
The CPU and Metal runs each had two warmups and five timed launches. Timings
include process startup, catalog materialization, output hashing and temporary
world cleanup. They are specific to this small, mostly empty End region.

| Backend | Published median | Published p95 |
| --- | ---: | ---: |
| CPU | 1,361.8 ms | 1,388.8 ms |
| Metal | 1,351.4 ms | 1,422.8 ms |

Representative CPU and Metal runs each generated 1,024 new chunks. The Metal
run reported `GPU worker ready: Metal: Apple M4 Max`.

To reproduce generation, use an empty disposable world directory for each run:

```sh
RETINA_REGION_THREADS=4 RAYON_NUM_THREADS=2 java -Dretina.gpuBackend=metal \
  -Djava.awt.headless=true -jar "$jar" generate \
  --world "$world" --seed 12345 --dimension minecraft:the_end --region-radius 0
```

Use `-Dretina.gpuBackend=cpu` for the CPU sample. Run the packaged native and
CPU checks using the [local acceptance commands](../UNIVERSAL_RELEASE.md).
Windows GPU execution remains unverified for this backport.

## Shared definition transport

The Minecraft-independent definition classes in `core/src/shared/java` own
handle lifetime and caller validation. The modern FFM transport still calls
the same Rust functions. A Java 17 JNI provider can implement
`DefinitionTransport` using the same owner code.

The Gradle CLI test and packaged native/CPU smoke checks passed with the
refactored JAR. The CPU smoke matched the published JAR on all twelve chunk
hashes and both definition hashes. Both packaged artifacts used identical
macOS native library bytes.

Five alternating Metal pairs measured 1,376.7 ms published and 1,356.6 ms
refactored median process time, with median generation times of 104 and
102 ms. All twelve Metal launches, including the warmup pair, reported a
ready Metal worker and generated 1,024 new chunks.

CPU process times varied with concurrent host activity. The first five
alternating pairs measured 1,385.5 and 1,464.6 ms median process time. A
separate ten-pair confirmation measured 1,509.2 and 1,552.3 ms median
process time, with median generation times of 154 and 156 ms. The median
paired CPU process-time change was +6.9%, and the median paired generation
change was −1.5%. Individual process-time changes ranged from −19.4% to
+15.4%. The startup result remains inconclusive. Recheck it with the JNI
comparison before final qualification.
