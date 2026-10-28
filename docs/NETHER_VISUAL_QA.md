# Nether visual inspection

The images inspected for this report were Minecraft 26.3 framebuffer captures of Retina
terrain, plus independently decoded MCA cross-sections. The saved world uses the
ordinary Normal preset, seed 123456789, enabled structures and the original
Minecraft generator definitions. The capture fixture opens a disposable copy;
it does not place terrain or structures for the images.

The image assets have been removed from the repository. The observations below
record the original inspection; capture metadata and reproduction instructions
remain linked for reference.

## Capture method

The opt-in `NetherVisualQa` client scenario loads an existing world, prepares each
destination asynchronously, and moves a spectator to the supplied camera pose.
It removes player effects, uses normal brightness 0.5, hides the HUD and captures
the real framebuffer through Minecraft's `Screenshot` API. The runs use 1280×720,
eight-chunk render distance, five-chunk simulation distance and no optional mods,
resource packs or shaders. Native terrain generation uses Metal on the M4 Max;
these screenshots do not establish results on other native GPU backends.

Capture waits for the real client dimension/position, actual chunk-cache entries
around the camera, a nonempty rendered scene and a drained section-render queue,
then allows five stable seconds for interpolation and chunk fading. The first
trial only checked the render queue and caught incomplete chunk delivery after
teleport. Those images were rejected. A drained renderer alone does not mean
that all required packets have arrived.

Each PNG has a JSON sidecar with the camera pose, dimension, seed, camera biome
and block, block/sky light, brightness, viewport and loaded/rendered counts.
`nether_visual_capture` confirms a saved framebuffer, not a visual quality pass.
The images were then opened and inspected directly. Early obstructed camera
angles remain diagnostic evidence; the published tour uses the revised views.

## Landscapes and light

| Scene | Inspected result |
| --- | --- |
| Nether Wastes | Netherrack cavern terraces, exposed quartz/gold, gravel, mushrooms, fire, magma and lava; illumination fades into the cavern. |
| Basalt Deltas | Dense basalt columns, irregular floors, lava channels and an enclosed ceiling. The heavy gray fog and particles are the biome's ordinary rendering. |
| Crimson Forest | Crimson nylium, stems/crowns, roots, hanging vines and a visible shroomlight, with a bastion in the background. |
| Soul Sand Valley | Soul surfaces, ceiling-to-floor basalt pillars, fossils, soul fire and the normal blue-green fog. |
| Warped Forest | Warped nylium, fungi/crowns and sprouts beside a lava sea, with ceiling glowstone and a lava fall. |

All captured camera positions report air and zero skylight. Lava, glowstone,
shroomlights, fire and soul fire are visible in the collection. These ordinary
brightness views complement the earlier numerical light-array and source-removal
checks; a screenshot alone cannot prove propagation values. The dark fortress
and fogged distant scenery are retained as rendered, without exposure editing
or night vision.

## Structures and a region boundary

| Scene | Inspected result |
| --- | --- |
| Bastion | Ruined blackstone construction, connected walkways and lanterns; terrain surrounds the assembled pieces. |
| Fortress | Elevated Nether-brick bridges and supports across the cavern. Live spawner, loot, locate and progression checks are documented separately. |
| Fossil | Exposed bone blocks rooted in the Soul Sand Valley floor, with more fossils beyond. |
| Ruined portal | Broken obsidian/crying-obsidian frame, chest, blackstone platform, netherrack/magma spread and adjacent lava. |
| Region boundary | View east across X=-10240 from the adjacent saved region: continuous lava and cavern space in the sampled view. |

The captured boundary is one sample. The
decoded saved-region section spans
X=-10752 through -9729 at Z=-12032, including that boundary, bedrock, cavities,
lava and fortress construction. It shows no abrupt region-aligned terrain break.
This is not a promise that pre-Retina terrain will join newly generated terrain
without seams.

## Vertical bounds

The decorated terrain sections show all five
fixed-biome fixtures at seed 42, with structures disabled. The
full storage sections show the same samples
through Y=255. Every sampled column retains floor bedrock at Y=0 and roof bedrock
at Y=127, with caverns, lava and biome-specific surfaces below.

The sampled space above the roof is air except for a red mushroom at
(-199,128,256) in the Nether Wastes and Basalt Deltas fixtures. This is the
previously validated loaded mushroom heightmap/offset placement at the roof,
not density terrain extended to the storage ceiling. Those exceptions remain
explicit in the audit. Existing builds above the roof are covered by the saved
diamond edit at Y=200.

## Reproduction and limits

Fabric and NeoForge each completed ten captures and normal save/shutdown. All
twenty final images were inspected. The formerly published images were the Fabric set;
both sets' hashes, camera metadata and log warnings are in the
[capture audit](benchmarks/nether-visual-2026-10-20.json). Original generation
definitions and the saved Overworld/above-roof Nether edits remain intact.

Logs contain optional-mod class notices, shader-link warnings, server catch-up
warnings and, on the NeoForge copy of the Fabric save, missing Fabric-pack notices.
Neither final run has an error-level entry or a visual-harness exception.
The tours establish rendered samples, not smooth loading or travel latency.

The [camera specification](../qa/nether-visual-tour.json) records the ten poses.
Use it with a disposable copy of the retained seed's world; different terrain
revisions or customized settings can require different views. See
[the runtime instructions](RUNTIME_QA.md#nether-framebuffer-tour).

Visual inspection is sample-based. The source-driven generation, block/biome
comparisons, structure metadata, lighting values, lifecycle and preservation
checks remain the evidence for those broader requirements. Minecraft ticks can
change flowing lava, fire, vegetation and entity positions between captures;
pixel-identical screenshots are not expected.
