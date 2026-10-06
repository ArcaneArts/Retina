package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Analytic fixtures retain registered material programs; CPU noise is never sampled. */
final class RegistryShoreIntegrationChecks {
    static void checkCoasts(BiomeTerrainProfile profile, List<Pair<Climate.ParameterPoint, Holder<Biome>>> parameters) {
        var original = JsonParser.parseString(profile.json()).getAsJsonObject();
        int targets = 0;
        for (String shore : List.of("minecraft:beach", "minecraft:snowy_beach", "minecraft:stony_shore")) {
            int index = biome(profile, shore);
            var expected = parameters.stream().filter(p -> p.getSecond().equals(profile.biomes().get(index))).toList();
            var exported = original.getAsJsonArray("climate_targets").asList().stream()
                    .map(JsonElement::getAsJsonObject).filter(t -> t.get("biome").getAsInt() == index).toList();
            require(!expected.isEmpty() && exported.size() == expected.size(), "all registered coastal intervals exported for " + shore);
            for (int i = 0; i < exported.size(); i++) {
                var point = expected.get(i).getFirst(); var interval = exported.get(i);
                var dimensions = List.of(point.temperature(), point.humidity(), point.continentalness(), point.erosion());
                for (int d = 0; d < 4; d++) {
                    require(Math.abs(interval.getAsJsonArray("min").get(d).getAsFloat() - Climate.unquantizeCoord(dimensions.get(d).min())) < .00001,
                            "coastal minimum comes from registry");
                    require(Math.abs(interval.getAsJsonArray("max").get(d).getAsFloat() - Climate.unquantizeCoord(dimensions.get(d).max())) < .00001,
                            "coastal maximum comes from registry");
                }
            }
            boolean selected = false;
            for (var target : exported) {
                var data = flat(original, profile.seaLevel());
                constantClimate(data, target);
                int id = NativeTerrain.instance().registerProfile(data.toString());
                var columns = NativeTerrain.instance().sampleColumns(request(id, 0, 0));
                if (columns.biome(0) != index) continue; // Adjacent interval endpoints can tie.
                for (int c = 0; c < 256; c++) {
                    require(columns.biome(c) == index, "registered coastal biome selected on GPU");
                    var top = profile.materials()[columns.materials()[c] & 65535];
                    require(shore.equals("minecraft:stony_shore") ? top.is(Blocks.STONE) || top.is(Blocks.GRAVEL) : top.is(Blocks.SAND),
                            "registered shore surface on GPU: " + shore + " / " + top);
                }
                // A pack moving this biome's climate intervals must change actual GPU selection.
                for (var t : data.getAsJsonArray("climate_targets")) if (t.getAsJsonObject().get("biome").getAsInt() == index) {
                    t.getAsJsonObject().getAsJsonArray("min").set(2, new JsonPrimitive(2));
                    t.getAsJsonObject().getAsJsonArray("max").set(2, new JsonPrimitive(3));
                }
                int moved = NativeTerrain.instance().registerProfile(data.toString());
                require(NativeTerrain.instance().sampleColumns(request(moved, 0, 0)).biome(0) != index,
                        "changed registered coast interval affects GPU output");
                selected = true; break;
            }
            require(selected, "coastal biome is reachable: " + shore);
            require((original.getAsJsonArray("biomes").get(index).getAsJsonObject().get("flags").getAsInt() & 64) != 0,
                    "registered shore identity exported");
            var lakes=original.getAsJsonArray("biomes").get(index).getAsJsonObject().getAsJsonArray("lakes");
            require(lakes.get(0).getAsDouble() == lakes.get(1).getAsDouble(),
                    "no synthetic water basins are added to registered shoreline recipes");
            targets += exported.size();
        }
        System.out.println("QA_EVT {\"event\":\"registered_gpu_coast_intervals\",\"status\":\"pass\",\"context\":{\"shore_types\":3,\"intervals\":" + targets + "}}");
        checkTerrainAlignment(profile, original);
    }

    /** A physical coast intentionally disagrees with the continentalness field. */
    static void checkTerrainAlignment(BiomeTerrainProfile profile, JsonObject original) {
        int beach = biome(profile, "minecraft:beach");
        JsonObject target = null;
        for (var element : original.getAsJsonArray("climate_targets")) {
            var candidate = element.getAsJsonObject();
            if (candidate.get("biome").getAsInt() != beach) continue;
            var probe = flat(original, profile.seaLevel()); constantClimate(probe, candidate);
            probe.addProperty("program_execution", "interpreter");
            if (NativeTerrain.instance().sampleColumns(request(NativeTerrain.instance().registerProfile(probe.toString()), 0, 0)).biome(0) == beach) {
                target = candidate; break;
            }
        }
        require(target != null, "registered warm beach fixture is reachable");
        checkShoreWidth(profile, original, target);
        checkSurfaceResolution(profile, original, target);
        for (boolean inlandClimate : new boolean[]{false, true}) {
            var data = original.deepCopy();
            for (var b : data.getAsJsonArray("biomes")) b.getAsJsonObject().add("lakes", JsonParser.parseString("[0,0]"));
            data.addProperty("program_execution", "interpreter");
            constantClimate(data, target);
            if (inlandClimate) data.getAsJsonObject("registry_program").getAsJsonArray("programs").get(0)
                    .getAsJsonObject().getAsJsonArray("nodes").get(2).getAsJsonObject().getAsJsonArray("p").set(0, new JsonPrimitive(.4));
            var registry = data.getAsJsonObject("registry_program");
            registry.add("surface", JsonParser.parseString("[-64,1,0]"));
            var density = new JsonObject(); var nodes = new JsonArray();
            nodes.add(node(0, 0, 0, 0, 0));
            var x = node(3, 0, 0, 0, -64); x.add("p", JsonParser.parseString("[-64,64,"+(profile.seaLevel()-17)+","+(profile.seaLevel()+15)+"]")); nodes.add(x);
            var y = node(3, 1, 0, 0, -64); y.add("p", JsonParser.parseString("[-64,320,-64,320]")); nodes.add(y);
            nodes.add(node(5, 1, 2, 0, 0));
            density.add("nodes", nodes); density.add("roots", JsonParser.parseString("[3]"));
            registry.getAsJsonArray("programs").set(1, density);
            int id = NativeTerrain.instance().registerProfile(data.toString());
            for (int z : new int[]{-33,-32,-1,0,31,32}) {
                var shore = NativeTerrain.instance().sampleColumns(request(id, 0, z));
                for (int row = 0; row < 16; row++) {
                    int column = row*16+3;
                    require(shore.biome(column) == beach, "GPU aligns the registered shore with real water despite mismatched climate: "+inlandClimate);
                    require(profile.materials()[shore.materials()[column]&65535].is(Blocks.SAND), "aligned beach uses registered sand rules");
                }
                for (int cx : new int[]{2,3}) {
                    var inland = NativeTerrain.instance().sampleColumns(request(id, cx, z));
                    for (int c=0;c<256;c++) require((original.getAsJsonArray("biomes").get(inland.biome(c)).getAsJsonObject().get("flags").getAsInt()&64)==0,
                            "disconnected inland slopes cannot select a coastal biome");
                }
            }
            if (inlandClimate) try {java.nio.file.Files.writeString(java.nio.file.Path.of("build/shore-alignment-profile.json"), data.toString());}
                catch (java.io.IOException error) {throw new java.io.UncheckedIOException(error);}
            if (!inlandClimate) for(int flatHeight:new int[]{profile.seaLevel()-1,profile.seaLevel()+1}) {
                var plateau=data.deepCopy();
                plateau.getAsJsonObject("registry_program").getAsJsonArray("programs").get(1).getAsJsonObject()
                        .getAsJsonArray("nodes").set(1,node(0,0,0,0,flatHeight-1));
                int flatId=NativeTerrain.instance().registerProfile(plateau.toString());
                var flatColumns=NativeTerrain.instance().sampleColumns(request(flatId,-32,-32));
                for(int c=0;c<256;c++)require((original.getAsJsonArray("biomes").get(flatColumns.biome(c)).getAsJsonObject().get("flags").getAsInt()&64)==0,
                        "low dry land and open shallow water cannot manufacture a physical shoreline");
            }
        }
        // An explicit datapack with no shore targets receives no manufactured shore.
        var noShore = original.deepCopy();
        noShore.getAsJsonArray("climate_targets").asList().removeIf(t ->
                (noShore.getAsJsonArray("biomes").get(t.getAsJsonObject().get("biome").getAsInt()).getAsJsonObject().get("flags").getAsInt()&64)!=0);
        require(noShore.getAsJsonArray("climate_targets").asList().stream().noneMatch(t ->
                (noShore.getAsJsonArray("biomes").get(t.getAsJsonObject().get("biome").getAsInt()).getAsJsonObject().get("flags").getAsInt()&64)!=0), "no-shore fixture follows the source");
        int without = NativeTerrain.instance().registerProfile(noShore.toString());
        for (int x=-8;x<8;x++) {
            var columns=NativeTerrain.instance().sampleColumns(request(without,x,-1));
            for(int c=0;c<256;c++)require((original.getAsJsonArray("biomes").get(columns.biome(c)).getAsJsonObject().get("flags").getAsInt()&64)==0,"absent coastal placements stay absent");
        }
        System.out.println("QA_EVT {\"event\":\"gpu_physical_shore_alignment\",\"status\":\"pass\",\"context\":{\"climate_cases\":2,\"negative_boundaries\":true}}");
    }

    /** Shallow slopes must not turn a whole lowland shelf into a beach. */
    private static void checkShoreWidth(BiomeTerrainProfile profile, JsonObject original, JsonObject target) {
        long checked = 0, beachColumns = 0, nearDryColumns = 0;
        double maxDryDistance = 0;
        // Axis and diagonal shores around a negative MCA boundary expose both
        // over-wide bands and the previous square (rather than radial) reach.
        float diagonal = (float) (-1 / Math.sqrt(2));
        for (String execution : List.of("interpreter", "specialized"))
            for (float slope : new float[]{1F / 16, 1})
                for (float[] direction : new float[][]{{1, 0}, {0, 1}, {diagonal, diagonal}}) {
                    var data = original.deepCopy(); data.addProperty("program_execution", execution);
                    for (var b : data.getAsJsonArray("biomes")) b.getAsJsonObject().add("lakes", JsonParser.parseString("[0,0]"));
                    constantClimate(data, target);
                    var registry = data.getAsJsonObject("registry_program");
                    // This fixture checks coast selection, not material rules.
                    // Keep the loaded climate table but isolate unused graphs;
                    // actual rules have separate reference/parity checks below.
                    registry.remove("aquifer");
                    var programs = registry.getAsJsonArray("programs");
                    while (programs.size() > data.getAsJsonArray("biomes").size() + 3) programs.remove(programs.size() - 1);
                    for (int b = 0; b < data.getAsJsonArray("biomes").size(); b++) programs.set(3 + b, constant(1));
                    registry.add("surface", JsonParser.parseString("[-64,1,0]"));
                    registry.getAsJsonArray("programs").get(0).getAsJsonObject().getAsJsonArray("nodes").get(2)
                            .getAsJsonObject().getAsJsonArray("p").set(0, new JsonPrimitive(.4));
                    float crossing = -512 * (direction[0] + direction[1]) + .375F;
                    var density = new JsonObject(); var nodes = new JsonArray();
                    nodes.add(node(0, 0, 0, 0, profile.seaLevel() - 1 - crossing * slope));
                    for (int axis : new int[]{0, 2}) {
                        float gradient = direction[axis / 2] * slope;
                        var n = node(3, axis, 0, 0, 0);
                        n.add("p", JsonParser.parseString("[-32768,32768," + (-32768 * gradient) + "," + (32768 * gradient) + "]"));
                        nodes.add(n);
                    }
                    nodes.add(node(4, 0, 1, 0, 0)); nodes.add(node(4, 3, 2, 0, 0));
                    var vertical = node(3, 1, 0, 0, 0); vertical.add("p", JsonParser.parseString("[-64,320,-64,320]"));
                    nodes.add(vertical); nodes.add(node(5, 4, 5, 0, 0));
                    density.add("nodes", nodes); density.add("roots", JsonParser.parseString("[6]"));
                    registry.getAsJsonArray("programs").set(1, density);
                    int id = NativeTerrain.instance().registerProfile(data.toString());
                    long nearby = 0, selectedNearby = 0, selectedDry = 0;
                    for (int cz = -34; cz < -30; cz++) for (int cx = -34; cx < -30; cx++) {
                        var columns = NativeTerrain.instance().sampleColumns(request(id, cx, cz));
                        for (int c = 0; c < 256; c++) {
                            int x = cx * 16 + (c & 15), z = cz * 16 + (c >> 4);
                            double distance = x * (double) direction[0] + z * (double) direction[1] - crossing;
                            boolean shore = (original.getAsJsonArray("biomes").get(columns.biome(c)).getAsJsonObject().get("flags").getAsInt() & 64) != 0;
                            if (shore) {
                                require(Math.abs(distance) <= 6.05, "registered beach stays beside its physical waterline: "
                                        + execution + " slope=" + slope + " at " + x + "," + z + " distance=" + distance);
                                require(columns.heights()[c] <= profile.seaLevel() + 3, "shore treatment cannot climb high banks");
                                beachColumns++;
                                if (distance > 0) { selectedDry++; maxDryDistance = Math.max(maxDryDistance, distance); }
                            }
                            if (distance >= .1 && distance <= 2.5) {
                                nearby++; if (shore) selectedNearby++;
                            }
                            checked++;
                        }
                    }
                    require(selectedDry > 0 && nearby > 0 && nearby == selectedNearby,
                            "narrowing the shore retains its registered beach at the waterline");
                    nearDryColumns += nearby;
                }
        System.out.println("QA_EVT {\"event\":\"gpu_narrow_shore_width\",\"status\":\"pass\",\"context\":{\"columns\":"
                + checked + ",\"shore_columns\":" + beachColumns + ",\"near_dry_columns\":" + nearDryColumns
                + ",\"max_dry_distance\":" + maxDryDistance + ",\"negative_region_edge\":true}}");
    }

    /** Diagonal shoreline transitions must not borrow a neighboring quart's surface rule. */
    private static void checkSurfaceResolution(BiomeTerrainProfile profile, JsonObject original, JsonObject target) {
        int sand = material(profile, Blocks.SAND), grass = material(profile, Blocks.GRASS_BLOCK);
        long checked = 0; int mixedQuarts = 0;
        for (String execution : List.of("interpreter", "specialized")) for (int phase : new int[]{0, 1, 2, 3}) {
            int direction = phase < 2 ? 1 : -1;
            var data = original.deepCopy();
            data.addProperty("program_execution", execution); data.remove("structures");
            data.add("cave_noises", new JsonArray()); data.add("ores", new JsonArray()); data.add("decorations", new JsonArray());
            for (var element : data.getAsJsonArray("biomes")) {
                var b = element.getAsJsonObject();
                for (String key : List.of("carvers", "ores", "decorations")) b.add(key, new JsonArray());
                b.addProperty("flags", b.get("flags").getAsInt() & 64); b.addProperty("cave_kind", 0);
                b.add("lakes", JsonParser.parseString("[0,0]")); b.remove("cave_features");
            }
            constantClimate(data, target);
            var registry = data.getAsJsonObject("registry_program"); var programs = registry.getAsJsonArray("programs");
            // Inland climate deliberately disagrees with the physical sea-level crossing.
            programs.get(0).getAsJsonObject().getAsJsonArray("nodes").get(2).getAsJsonObject()
                    .getAsJsonArray("p").set(0, new JsonPrimitive(.4));
            registry.remove("aquifer"); registry.addProperty("material_layers", true);
            while (programs.size() > data.getAsJsonArray("biomes").size()+3) programs.remove(programs.size()-1);
            registry.add("surface", JsonParser.parseString("[-64,1,0]"));
            var density = new JsonObject(); var nodes = new JsonArray();
            nodes.add(node(0, 0, 0, 0, profile.seaLevel()-1 + phase*.25F));
            // Reverse the gradient in half the phases: quart sampling can both
            // extend sand inland and leave grass holes on the coastal side.
            var x = node(3, 0, 0, 0, 0); x.add("p", JsonParser.parseString("[-4096,4096,"+(-1024*direction)+","+(1024*direction)+"]")); nodes.add(x);
            var z = node(3, 2, 0, 0, 0); z.add("p", JsonParser.parseString("[-4096,4096,"+(-512*direction)+","+(512*direction)+"]")); nodes.add(z);
            nodes.add(node(4, 0, 1, 0, 0)); nodes.add(node(4, 3, 2, 0, 0));
            var vertical = node(3, 1, 0, 0, 0); vertical.add("p", JsonParser.parseString("[-64,320,-64,320]")); nodes.add(vertical);
            nodes.add(node(5, 4, 5, 0, 0)); density.add("nodes", nodes); density.add("roots", JsonParser.parseString("[6]"));
            programs.set(1, density); programs.set(2, constant(1));
            // Distinct constant rules expose biome sampling errors independently of noise,
            // stone-depth, water, feature placement or cave classification.
            for (int b = 0; b < data.getAsJsonArray("biomes").size(); b++) {
                boolean coast = (data.getAsJsonArray("biomes").get(b).getAsJsonObject().get("flags").getAsInt() & 64) != 0;
                programs.set(3+b, constant((coast ? sand : grass)+1));
            }
            int id = NativeTerrain.instance().registerProfile(data.toString());
            for (int[] pos : new int[][]{{0,0},{1,0},{0,1},{1,-1}}) {
                int cx = direction > 0 ? pos[0] : -pos[0]-1;
                int cz = direction > 0 ? pos[1] : -pos[1]-1;
                var request = request(id, cx, cz);
                var selected = NativeTerrain.instance().sampleColumns(request);
                try (var chunk = NativeTerrain.instance().generate(request)) {
                    for (int c = 0; c < 256; c++) {
                        int expected = selected.materials()[c] & 65535;
                        int top = selected.heights()[c]-1;
                        for (int y = top; y > top-4; y--) {
                            int actual = Short.toUnsignedInt(chunk.blocks().getAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT, (y+64)*256L+c));
                            require(actual == expected, "surface uses its own GPU column biome, not a neighboring quart: "
                                    +execution+" phase="+phase+" at "+(cx*16+(c&15))+","+y+","+(cz*16+(c>>4))
                                    +" expected="+profile.materials()[expected]+" actual="+profile.materials()[actual]);
                            checked++;
                        }
                    }
                    for (int z0 = 0; z0 < 16; z0 += 4) for (int x0 = 0; x0 < 16; x0 += 4) {
                        int first = selected.biome(z0*16+x0);
                        boolean mixed = false;
                        for (int z1=0;z1<4;z1++) for (int x1=0;x1<4;x1++) mixed |= selected.biome((z0+z1)*16+x0+x1) != first;
                        if (mixed) mixedQuarts++;
                    }
                }
            }
            if (execution.equals("specialized") && phase == 0) checkShoreRegion(profile, id);
        }
        require(mixedQuarts > 0, "diagonal coast crosses quart boundaries in the fixture");
        System.out.println("QA_EVT {\"event\":\"gpu_shore_material_resolution\",\"status\":\"pass\",\"context\":{\"surface_voxels\":"+checked+",\"mixed_quarts\":"+mixedQuarts+"}}");
    }

    private static JsonObject constant(float value) {
        var result = new JsonObject(); var nodes = new JsonArray(); nodes.add(node(0,0,0,0,value));
        result.add("nodes",nodes); result.add("roots",JsonParser.parseString("[0]")); return result;
    }

    private static void checkShoreRegion(BiomeTerrainProfile profile, int id) {
        var terrain = NativeTerrain.instance();
        var positions = List.of(new net.minecraft.world.level.ChunkPos(-1,0),new net.minecraft.world.level.ChunkPos(-1,1),
                new net.minecraft.world.level.ChunkPos(-2,2),new net.minecraft.world.level.ChunkPos(-8,14),
                new net.minecraft.world.level.ChunkPos(-8,15),new net.minecraft.world.level.ChunkPos(-16,30),
                new net.minecraft.world.level.ChunkPos(-16,31),new net.minecraft.world.level.ChunkPos(-32,0),
                new net.minecraft.world.level.ChunkPos(-32,31));
        var expected = new LinkedHashMap<net.minecraft.world.level.ChunkPos,short[]>();
        for (var pos : positions) try (var chunk = terrain.generate(request(id,pos.x(),pos.z()))) {
            expected.put(pos,chunk.blocks().toArray(java.lang.foreign.ValueLayout.JAVA_SHORT));
        }
        java.nio.file.Path directory = null;
        try {
            directory = java.nio.file.Files.createTempDirectory("retina-shore-resolution-");
            var report = terrain.generateRegion(request(id,-32,0),directory.resolve("r.-1.0.mca"),
                    net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:beach");
            require(report.generated() == 1024,"all shoreline MCA slots generated");
            var codec = net.minecraft.world.level.chunk.PalettedContainer.codecRW(net.minecraft.world.level.block.state.BlockState.CODEC,
                    net.minecraft.world.level.chunk.Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
            try (var storage = new net.minecraft.world.level.chunk.storage.RegionFileStorage(
                    new net.minecraft.world.level.chunk.storage.RegionStorageInfo("retina-shore-resolution",net.minecraft.world.level.Level.OVERWORLD,"chunk"),directory,false)) {
                for (var entry : expected.entrySet()) {
                    var sections = storage.read(entry.getKey()).getListOrEmpty("sections");
                    for (int section=0;section<24;section++) {
                        var blocks = codec.parse(net.minecraft.nbt.NbtOps.INSTANCE,sections.getCompound(section).orElseThrow().getCompoundOrEmpty("block_states")).getOrThrow();
                        for (int y=0;y<16;y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++) {
                            var state = profile.materials()[Short.toUnsignedInt(entry.getValue()[(section*16+y)*256+z*16+x])];
                            require(blocks.get(x,y,z).equals(state),"MCA shore material equals independent GPU chunk at "+entry.getKey()+" / "+x+","+(section*16+y-64)+","+z);
                        }
                    }
                }
            }
            System.out.println("QA_EVT {\"event\":\"gpu_shore_mca_resolution\",\"status\":\"pass\",\"context\":{\"chunks\":9,\"voxels\":884736}}");
        } catch (java.io.IOException error) {throw new java.io.UncheckedIOException(error);}
        finally {
            if (directory != null) try (var paths = java.nio.file.Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
            } catch (java.io.IOException error) {throw new java.io.UncheckedIOException(error);}
        }
    }

    static void checkWaterCondition(BiomeTerrainProfile profile) {
        int grass = material(profile, Blocks.GRASS_BLOCK), dirt = material(profile, Blocks.DIRT);
        var data = single(JsonParser.parseString(profile.json()).getAsJsonObject(), 0);
        data = flat(data, profile.seaLevel() + 1);
        // On a dry column, vanilla WaterCondition returns true even below sea level.
        // The filler query is three blocks down and must not invent an ocean here.
        var program = new JsonObject(); var nodes = new JsonArray();
        nodes.add(node(0, 0, 0, 0, 0)); nodes.add(node(46, 0, 0, 0, 0));
        nodes.add(node(0, 0, 0, 0, grass + 1)); nodes.add(node(0, 0, 0, 0, dirt + 1));
        nodes.add(node(40, 1, 2, 0, 0)); nodes.add(node(41, 4, 3, 0, 0));
        program.add("nodes", nodes); program.add("roots", JsonParser.parseString("[5]"));
        data.getAsJsonObject("registry_program").getAsJsonArray("programs").set(3, program);
        int dry = NativeTerrain.instance().registerProfile(data.toString());
        var dryColumns = NativeTerrain.instance().sampleColumns(request(dry, -1, -1));
        for (int p : dryColumns.materials()) require((p >>> 16) == grass, "dry filler does not fail the water predicate");
        data = flat(data, profile.seaLevel() - 1);
        int wet = NativeTerrain.instance().registerProfile(data.toString());
        for (int p : NativeTerrain.instance().sampleColumns(request(wet, -1, -1)).materials())
            require((p & 65535) == dirt, "submerged query still uses its registered water condition");
        System.out.println("QA_EVT {\"event\":\"gpu_surface_water_condition\",\"status\":\"pass\"}");
    }

    static void checkSediments(BiomeTerrainProfile profile, boolean terralith) {
        var original = JsonParser.parseString(profile.json()).getAsJsonObject();
        var registry = original.getAsJsonObject("registry_program");
        var exported = new HashSet<String>();
        for (var id : registry.getAsJsonArray("shore_features")) exported.add(id.getAsString());
        var disks=new HashSet<String>();
        for(var id:registry.getAsJsonArray("registered_disks"))disks.add(id.getAsString());
        var features = terralith ? List.of("terralith:alpha/sand_beaches", "terralith:sakura/clay_beaches", "terralith:forest/flower/beaches")
                : List.of("minecraft:disk_gravel", "minecraft:disk_clay");
        int changed = 0, nativeDisks = 0;
        for (String feature : features) {
            if(disks.contains(feature)) {
                require(!exported.contains(feature),"registered disk is not duplicated by GPU coverage: "+feature);
                require(original.getAsJsonArray("decorations").asList().stream().map(JsonElement::getAsJsonObject).anyMatch(r->r.get("kind").getAsString().equals("disk") && r.get("source").getAsString().equals(feature)),"registered disk has an ordered native recipe: "+feature);
                nativeDisks++;continue;
            }
            require(exported.contains(feature), "registered sediment recipe projected: " + feature);
            int salt = feature.hashCode() & Integer.MAX_VALUE;
            boolean reached = false;
            // A feature can occur in several biomes with different replacement materials.
            for (int b = 0; b < profile.biomes().size() && !reached; b++) {
                var sourceProgram = registry.getAsJsonArray("programs").get(3 + b).getAsJsonObject();
                boolean present = sourceProgram.getAsJsonArray("nodes").asList().stream().anyMatch(v ->
                        v.getAsJsonObject().get("op").getAsInt() == 53 && v.getAsJsonObject().get("b").getAsInt() == salt);
                if (!present) continue;
                var data = single(original, b);
                // Exercise the registered replacement predicate on an actual soil input,
                // rather than requiring an already-gravel ocean floor to change to gravel.
                var materialProgram = data.getAsJsonObject("registry_program").getAsJsonArray("programs").get(3).getAsJsonObject();
                int baseRoot = materialProgram.getAsJsonArray("roots").get(1).getAsInt();
                materialProgram.getAsJsonArray("nodes").set(baseRoot, node(0, 0, 0, 0, material(profile, Blocks.DIRT) + 1));
                boolean waterOnly = false;
                for (var v : data.getAsJsonObject("registry_program").getAsJsonArray("programs").get(3).getAsJsonObject().getAsJsonArray("nodes")) {
                    var n = v.getAsJsonObject();
                    if (n.get("op").getAsInt() != 53) continue;
                    if (n.get("b").getAsInt() == salt) waterOnly = (n.get("c").getAsInt() & 1) != 0;
                    else { n.addProperty("op", 0); n.add("p", JsonParser.parseString("[0,0,0,0]")); }
                }
                var baseline = data.deepCopy();
                for (var v : baseline.getAsJsonObject("registry_program").getAsJsonArray("programs").get(3).getAsJsonObject().getAsJsonArray("nodes"))
                    if (v.getAsJsonObject().get("op").getAsInt() == 53) { v.getAsJsonObject().addProperty("op", 0); v.getAsJsonObject().add("p", JsonParser.parseString("[0,0,0,0]")); }
                var protectedSoil = data.deepCopy();
                int bedrock = material(profile, Blocks.BEDROCK);
                protectedSoil.getAsJsonObject("registry_program").getAsJsonArray("programs").get(3).getAsJsonObject()
                        .getAsJsonArray("nodes").set(baseRoot, node(0, 0, 0, 0, bedrock + 1));
                int protectedId = NativeTerrain.instance().registerProfile(flat(protectedSoil, 61).toString());
                for (int p : NativeTerrain.instance().sampleColumns(request(protectedId, -17, -7)).materials())
                    require((p & 65535) == bedrock && (p >>> 16) == bedrock, "sediment target predicates protect non-soil blocks");
                var zeroNoise = data.deepCopy(); boolean noiseCount = false;
                for (var v : zeroNoise.getAsJsonObject("registry_program").getAsJsonArray("programs").get(3).getAsJsonObject().getAsJsonArray("nodes"))
                    if (v.getAsJsonObject().get("op").getAsInt() == 52) {
                        v.getAsJsonObject().getAsJsonArray("p").set(2, new JsonPrimitive(0)); noiseCount = true;
                    }
                if (noiseCount) {
                    int zeroId = NativeTerrain.instance().registerProfile(flat(zeroNoise, 59).toString());
                    int baseId = NativeTerrain.instance().registerProfile(flat(baseline, 59).toString());
                    require(Arrays.equals(NativeTerrain.instance().sampleColumns(request(zeroId, -17, -7)).materials(),
                            NativeTerrain.instance().sampleColumns(request(baseId, -17, -7)).materials()), "zero noise-based count disables actual GPU coverage");
                }
                for (int height : new int[]{59, 61, 63, 90}) {
                    int coated = NativeTerrain.instance().registerProfile(flat(data, height).toString());
                    int plain = NativeTerrain.instance().registerProfile(flat(baseline, height).toString());
                    for (int x : new int[]{-61, -17, -1, 13, 57}) {
                        var a = NativeTerrain.instance().sampleColumns(request(coated, x, -7));
                        var c = NativeTerrain.instance().sampleColumns(request(plain, x, -7));
                        for (int i = 0; i < 256; i++) {
                            if (a.materials()[i] != c.materials()[i]) {
                                require(height != 90, "coastal sediment stays within its registered height or water bounds: " + feature);
                                if (waterOnly) require(height < profile.seaLevel(), "water sediment cannot coat dry land: " + feature);
                                changed++; reached = true;
                            }
                            require(a.heights()[i] == c.heights()[i], "sediment projection changes materials, not terrain shape");
                        }
                    }
                }
            }
            require(reached, "registered sediment affects actual GPU surfaces: " + feature);
        }
        System.out.println("QA_EVT {\"event\":\"registered_gpu_surface_sediments\",\"status\":\"pass\",\"context\":{\"pack\":\"" + (terralith ? "terralith" : "vanilla") + "\",\"features\":" + features.size() + ",\"native_disks\":"+nativeDisks+",\"changed_columns\":" + changed + "}}");
    }

    private static JsonObject flat(JsonObject original, int height) {
        var data = original.deepCopy();
        for (var b : data.getAsJsonArray("biomes")) b.getAsJsonObject().add("lakes", JsonParser.parseString("[0,0]"));
        var registry = data.getAsJsonObject("registry_program"); registry.add("surface", JsonParser.parseString("[-64,1,1]"));
        var surface = new JsonObject(); var nodes = new JsonArray(); nodes.add(node(0, 0, 0, 0, height - 1));
        surface.add("nodes", nodes); surface.add("roots", JsonParser.parseString("[0,0]"));
        registry.getAsJsonArray("programs").set(1, surface);
        return data;
    }
    private static JsonObject single(JsonObject original, int biome) {
        var data = original.deepCopy(); var selected = data.getAsJsonArray("biomes").get(biome).getAsJsonObject();
        for (String key : List.of("carvers", "decorations", "ores")) selected.add(key, new JsonArray());
        selected.addProperty("cave_kind", 0); data.remove("structures");
        var biomes = new JsonArray(); biomes.add(selected); data.add("biomes", biomes); data.add("climate_targets", new JsonArray());
        var registry = data.getAsJsonObject("registry_program"); var old = registry.getAsJsonArray("programs");
        var programs = new JsonArray(); for (int i = 0; i < 3; i++) programs.add(old.get(i)); programs.add(old.get(3 + biome));
        registry.add("programs", programs); registry.remove("aquifer"); return data;
    }
    private static void constantClimate(JsonObject data, JsonObject target) {
        var program = new JsonObject(); var nodes = new JsonArray(); var roots = new JsonArray();
        for (int i = 0; i < 6; i++) {
            float value = i < 4 ? (target.getAsJsonArray("min").get(i).getAsFloat() + target.getAsJsonArray("max").get(i).getAsFloat()) / 2
                    : i == 4 ? (target.getAsJsonArray("weirdness").get(0).getAsFloat() + target.getAsJsonArray("weirdness").get(1).getAsFloat()) / 2 : 0;
            nodes.add(node(0, 0, 0, 0, value)); roots.add(i);
        }
        program.add("nodes", nodes); program.add("roots", roots); data.getAsJsonObject("registry_program").getAsJsonArray("programs").set(0, program);
    }
    private static JsonObject node(int op, int a, int b, int c, float value) {
        var n = new JsonObject(); n.addProperty("op", op); n.addProperty("a", a); n.addProperty("b", b); n.addProperty("c", c);
        var p = new JsonArray(); p.add(value); p.add(0); p.add(0); p.add(0); n.add("p", p); return n;
    }
    private static TerrainRequest request(int profile, int x, int z) { return new TerrainRequest(123456789L, x, z, -64, 384, 64, 48, .008F, profile); }
    private static int biome(BiomeTerrainProfile profile, String id) {
        for (int i = 0; i < profile.biomes().size(); i++) if (profile.biomes().get(i).unwrapKey().orElseThrow().identifier().toString().equals(id)) return i;
        throw new AssertionError("Missing shore biome " + id);
    }
    private static int material(BiomeTerrainProfile profile, net.minecraft.world.level.block.Block block) {
        for (int i = 0; i < profile.materials().length; i++) if (profile.materials()[i].equals(block.defaultBlockState())) return i;
        throw new AssertionError("Missing fixture material " + block);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
