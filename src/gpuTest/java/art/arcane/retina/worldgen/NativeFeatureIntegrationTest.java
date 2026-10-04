package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Runs real GPU shaders, then checks registered materials and Minecraft's cached MCA plumbing. */
public final class NativeFeatureIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var resources = new net.minecraft.server.packs.resources.FallbackResourceManager(net.minecraft.server.packs.PackType.SERVER_DATA, "minecraft");
        resources.push(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource().fullResources());
        net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(net.minecraft.core.Registry.PendingTags::apply);
        var registry = RegistryIntegrationFixtures.load(resources);
        List<Holder<Biome>> choices = List.of("plains", "badlands").stream().map(id -> (Holder<Biome>) registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(id)))).toList();
        var profile = BiomeTerrainProfile.load(registry, new RetinaBiomeSource(choices, 256, 0.55F), -64, 384, 123456789L);
        var json = JsonParser.parseString(profile.json()).getAsJsonObject();
        var nativeTerrain = NativeTerrain.instance();
        require(json.getAsJsonObject("terrain_features").getAsJsonArray("bands").size() == 192, "actual 26.3 badlands band recipe is exported");
        var mesa = only(json, "minecraft:badlands");
        var mesaBiome = mesa.getAsJsonArray("biomes").get(0).getAsJsonObject();
        mesaBiome.add("ores", new JsonArray()); mesaBiome.add("carvers", new JsonArray()); mesaBiome.add("decorations", new JsonArray());
        int mesaId = nativeTerrain.registerProfile(mesa.toString());
        var colors = new HashSet<String>(); var offsets = new HashSet<Integer>();
        for (int x = 0; x < 8; x++) {
            var r = request(mesaId, x, -1, 218, 0);
            try (var data = nativeTerrain.generate(r)) {
                var blocks = data.blocks().toArray(ValueLayout.JAVA_SHORT);
                var column = nativeTerrain.column(r, 0);
                for (int layer = 0; layer < 384; layer++) {
                    require(column[layer] == blocks[layer * 256], "badlands base-column API agrees with native assembly");
                    String id = id(profile, column[layer]); if (id.endsWith("terracotta")) colors.add(id);
                }
                offsets.add((byte) (data.columns().packed()[0] >>> 16) + 0);
            }
        }
        require(colors.size() == 7 && offsets.size() > 1, "all seven terracotta materials and spatial GPU band offsets are present: " + colors + "/" + offsets);
        event("gpu_badlands_strata", "\"colors\":" + colors.size() + ",\"offsets\":" + offsets.size());

        for (String name : List.of("lush_caves", "dripstone_caves", "deep_dark")) {
            var forced = cave(json, name);
            int id = nativeTerrain.registerProfile(forced.toString());
            var counts = new ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder>();
            try (var workers = Executors.newFixedThreadPool(16)) {
                var futures = new ArrayList<Future<?>>();
                for (int z = -4; z < 4; z++) for (int x = -4; x < 4; x++) {
                    final int cx = x, cz = z;
                    futures.add(workers.submit(() -> {
                        var r = request(id, cx, cz, 92, 30);
                        var biomeSamples = nativeTerrain.sampleBiomes(r);
                        boolean caveSeen = false;
                        for (int layer = 0; layer < 96; layer++) for (int i = 0; i < 16; i++) {
                            int b = Short.toUnsignedInt(biomeSamples[layer * 16 + i]);
                            require(b <= 1, "GPU quart biome references are in range"); caveSeen |= b == 1;
                            if (layer >= 64) require(b == 0, "cave biomes cannot leak into surface sky");
                        }
                        require(caveSeen, "underground biome is reachable");
                        try (var data = nativeTerrain.generate(r)) {
                            for (short block : data.blocks().toArray(ValueLayout.JAVA_SHORT)) counts.computeIfAbsent(id(profile, block), k -> new java.util.concurrent.atomic.LongAdder()).increment();
                        }
                    }));
                }
                for (var future : futures) future.get();
            }
            if (name.equals("lush_caves")) {
                require(count(counts,"moss_block") > 100 && count(counts,"cave_vines") > 10 && count(counts,"cave_vines_plant") > 10 && count(counts,"moss_carpet") > 0, "registered lush floor, plants, and hanging vines generate: " + counts);
            } else if (name.equals("dripstone_caves")) {
                require(count(counts,"dripstone_block") > 100 && count(counts,"pointed_dripstone") > 10, "registered dripstone floor and stalactite/stalagmite materials generate");
            } else require(count(counts,"sculk") > 100, "deep-dark caves have sculk floors");
            event("gpu_" + name + "_rust_features", "\"chunks\":64,\"blocks\":" + new Gson().toJson(counts.entrySet().stream().filter(e -> e.getKey().contains("moss") || e.getKey().contains("vines") || e.getKey().contains("dripstone") || e.getKey().contains("sculk")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e -> e.getValue().sum()))));
        }
        testLake(profile, json, false);
        testLake(profile, json, true);
    }
    private static void testLake(BiomeTerrainProfile source, JsonObject json, boolean lava) throws Exception {
        var forced = only(json, "minecraft:plains");
        var entry = forced.getAsJsonArray("biomes").get(0).getAsJsonObject();
        entry.add("lakes", array(lava ? 0 : 1, lava ? 1 : 0));
        entry.add("terrain", array(0, 0.15, 0.85));
        entry.add("ores", new JsonArray()); entry.add("decorations", new JsonArray());
        // Leave caves enabled to verify basin floors survive carving.
        int id = NativeTerrain.instance().registerProfile(forced.toString());
        var profile = new BiomeTerrainProfile(id, source.seaLevel(), source.materials(), List.of(source.biomes().getFirst()), forced.toString());
        var r = request(id, 0, 0, 112, 12);
        var metrics = new GenerationMetrics();
        int lakeColumns = 0, compared = 0;
        var levels = new HashMap<Long,Integer>();
        var bounds = new HashMap<Long,int[]>();
        try (var cache = new TemporaryRegions(r, "minecraft:plains", profile, metrics, 2)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = 0; cx < 32; cx++) {
                var pos = new ChunkPos(cx, cz); var columns = cache.columns(pos);
                for (int i = 0; i < 256; i++) {
                    int packed = columns.packed()[i];
                    if ((packed & (1 << 29)) == 0) continue;
                    lakeColumns++;
                    int level = columns.heights()[i] + ((packed >>> 24) & 7);
                    int x = cx * 16 + i % 16, z = cz * 16 + i / 16;
                    long key = ChunkPos.pack(Math.floorDiv(x,128),Math.floorDiv(z,128));
                    var box=bounds.computeIfAbsent(key,k->new int[]{x,z,x,z});
                    box[0]=Math.min(box[0],x);box[1]=Math.min(box[1],z);box[2]=Math.max(box[2],x);box[3]=Math.max(box[3],z);
                    var old = levels.putIfAbsent(key, level); require(old == null || old == level, "GPU lake waterline is flat across chunk borders");
                    var generated = cache.baseColumn(pos,columns,i);
                    require(generated[level + 63].is(lava ? Blocks.LAVA : Blocks.WATER), "lake contains registered fluid");
                    require(!generated[columns.heights()[i] + 63].isAir() && generated[columns.heights()[i] + 63].getFluidState().isEmpty(), "lake has a solid basin floor");
                    if (compared < 24) {
                        var request = request(id,cx,cz,112,12);
                        var nativeColumn = NativeTerrain.instance().column(request,i);
                        for (int y = 0; y < 384; y++) require(generated[y].equals(source.materials()[Short.toUnsignedInt(nativeColumn[y])]), "temporary MCA base columns agree with native lake columns");
                        try (var data = NativeTerrain.instance().generate(request)) {
                            var blocks = data.blocks().toArray(ValueLayout.JAVA_SHORT);
                            require(id(source, blocks[(columns.heights()[i]-1+64)*256+i]).equals(id(source,nativeColumn[columns.heights()[i]-1+64])), "GPU cave carving preserves lake floors");
                        }
                        compared++;
                    }
                }
            }
            require(metrics.snapshot().previewRegions() == 1, "lake surface requests use one temporary MCA");
            var samples = cache.biomes(new ChunkPos(0,0));
            require(Arrays.equals(samples, NativeTerrain.instance().sampleBiomes(r)), "cached MCA biome query agrees with GPU quart output");
            require(lakeColumns > 100 && levels.size() > 1 && compared == 24, "GPU lakes are reachable and span multiple global cells");
            int maxWidth=bounds.values().stream().mapToInt(b->b[2]-b[0]+1).max().orElse(0);
            int maxDepth=bounds.values().stream().mapToInt(b->b[3]-b[1]+1).max().orElse(0);
            require(lava ? maxWidth<=20 && maxDepth<=24 : maxWidth>=45 && maxDepth>=45, "lava footprint is quarter width while water lakes retain original size");
            event(lava ? "gpu_lava_lakes" : "gpu_water_lakes", "\"columns\":" + lakeColumns + ",\"lakes\":" + levels.size()+",\"max_width\":"+maxWidth+",\"max_depth\":"+maxDepth);
        }
    }
    private static JsonObject cave(JsonObject json, String name) {
        var result = only(json,"minecraft:plains"); var entries = result.getAsJsonArray("biomes");
        var cave = find(json,"minecraft:" + name).deepCopy();
        cave.add("climate", array(0,-1,-1,1)); cave.add("cave_depth", array(0,2)); entries.add(cave);
        return result;
    }
    private static JsonObject only(JsonObject json, String id) { var r = json.deepCopy(); r.remove("registry_program"); var a = new JsonArray(); a.add(find(json,id).deepCopy()); r.add("biomes",a); return r; }
    private static JsonObject find(JsonObject json, String id) { return json.getAsJsonArray("biomes").asList().stream().map(JsonElement::getAsJsonObject).filter(b -> b.get("id").getAsString().equals(id)).findFirst().orElseThrow(); }
    private static JsonArray array(double... values) { var a = new JsonArray(); for (double v : values) a.add(v); return a; }
    private static TerrainRequest request(int profile, int x, int z, float base, float amplitude) { return new TerrainRequest(123456789L,x,z,-64,384,base,amplitude,0.0035F,profile); }
    private static String id(BiomeTerrainProfile p, short m) { return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(p.materials()[Short.toUnsignedInt(m)].getBlock()).toString(); }
    private static long count(Map<String,java.util.concurrent.atomic.LongAdder> values,String id) { var v = values.get("minecraft:"+id); return v == null ? 0 : v.sum(); }
    private static void require(boolean pass,String message) { if (!pass) throw new AssertionError(message); }
    private static void event(String name,String context) { System.out.println("QA_EVT {\"event\":\""+name+"\",\"status\":\"pass\",\"context\":{"+context+"}}"); }
}
