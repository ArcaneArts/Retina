package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class NetherPortalVerification {
    private static final String NETHER = "minecraft:the_nether";
    private static final String SET = "minecraft:ruined_portals";
    private static final String PORTAL = "minecraft:ruined_portal_nether";

    private NetherPortalVerification() { }

    static void verify(Path world, RegistryExport.Loaded loaded, long seed) throws Exception {
        boolean custom = loaded.packs().contains("file/retina-nether-portals");
        var placement = (RandomSpreadStructurePlacement) loaded.registries().registryOrThrow(Registries.STRUCTURE_SET)
                .get(new ResourceLocation(SET)).placement();
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var profile = JsonParser.parseString(definition.diagnosticProfile(NETHER)).getAsJsonObject();
            var structures = profile.getAsJsonObject("structures");
            var sets = structures.getAsJsonArray("sets").asList();
            var set = sets.stream().map(value -> value.getAsJsonObject()).filter(row -> row.get("id").getAsString().equals(SET))
                    .findFirst().orElseThrow();
            var members = set.getAsJsonArray("entries");
            var definitions = structures.getAsJsonArray("definitions");
            require(members.size() == 7, "retain all original portal placement slots");
            int index = -1;
            for (var member : members) {
                int candidate = member.getAsJsonObject().get("definition").getAsInt();
                var entry = definitions.get(candidate).getAsJsonObject();
                if (entry.get("id").getAsString().equals(PORTAL)) index = candidate;
                else require(entry.get("unimplemented").getAsBoolean(), "reserved non-Nether portal placement");
            }
            require(index >= 0, "Nether portal definition");
            var config = definitions.get(index).getAsJsonObject().getAsJsonObject("config");
            var setups = config.getAsJsonArray("setups");
            require(setups.size() == (custom ? 2 : 1), "loaded portal setups");
            var kit = structures.getAsJsonObject("portals");
            require(kit.getAsJsonArray("templates").size() == 13
                    && kit.getAsJsonArray("templates").asList().stream().allMatch(pair -> pair.getAsJsonArray().size() == 2),
                    "loaded normal and mirrored portal templates");
            if (custom) {
                require(set.getAsJsonObject("placement").get("salt").getAsInt() == 7310037
                        && setups.get(0).getAsJsonObject().get("overgrown").getAsBoolean(), "custom portal placement and growth");
            }
            var start = placement.getPotentialStructureChunk(seed, -placement.spacing(), 0);
            int rx = custom ? -1 : start.getRegionX();
            int rz = custom ? 0 : start.getRegionZ();
            var candidates = new ArrayList<ChunkPos>();
            int spacing = placement.spacing();
            for (int z = Math.floorDiv(rz * 32, spacing) * spacing; z < (rz + 1) * 32; z += spacing) {
                for (int x = Math.floorDiv(rx * 32, spacing) * spacing; x < (rx + 1) * 32; x += spacing) {
                    var candidate = placement.getPotentialStructureChunk(seed, x, z);
                    if (candidate.getRegionX() == rx && candidate.getRegionZ() == rz) candidates.add(candidate);
                }
            }
            var expected = new HashSet<ChunkPos>();
            definition.bindDimension(NETHER, 2);
            for (var candidate : candidates) {
                int hit = definition.structureStarts(NETHER, new int[] {candidate.x, candidate.z, sets.indexOf(set)})[0];
                require(hit == index, "registered portal starts at " + candidate);
                expected.add(candidate);
            }
            require(!expected.isEmpty(), "portal start sample");
            var folder = Files.createDirectories(world.resolve("DIM-1/region"));
            var region = folder.resolve("r." + rx + "." + rz + ".mca");
            long started = System.nanoTime();
            require(definition.generateRegion(NETHER, rx, rz, region).generated() == 1024, "full Nether portal region");
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            var result = inspect(region, expected, rx, rz, custom);
            require(definition.generateRegion(NETHER, rx, rz, region).preserved() == 1024, "portal region reopen");
            result.addProperty("event", "nether_ruined_portal_1201");
            result.addProperty("status", "pass");
            result.addProperty("region_x", rx);
            result.addProperty("region_z", rz);
            result.addProperty("milliseconds", elapsed);
            System.out.println("QA_EVT " + result);
        }
    }

    private static JsonObject inspect(Path region, Set<ChunkPos> expected, int rx, int rz, boolean custom) throws Exception {
        var seen = new HashSet<ChunkPos>();
        var templates = new HashSet<String>();
        int references = 0;
        int chests = 0;
        int obsidian = 0;
        int leaves = 0;
        int vines = 0;
        try (var file = new RegionFile(region, region.getParent(), true)) {
            for (int slot = 0; slot < 1024; slot++) {
                var pos = new ChunkPos(rx * 32 + (slot & 31), rz * 32 + (slot >> 5));
                try (var input = file.getChunkDataInputStream(pos)) {
                    require(input != null, "portal chunk " + pos);
                    var chunk = NbtIo.read(input);
                    var structures = chunk.getCompound("structures");
                    var starts = structures.getCompound("starts");
                    if (starts.contains(PORTAL)) {
                        var start = starts.getCompound(PORTAL);
                        var children = start.getList("Children", Tag.TAG_COMPOUND);
                        var piece = children.getCompound(0);
                        String template = piece.getString("template");
                        require(start.getString("id").equals(PORTAL) && start.getInt("ChunkX") == pos.x && start.getInt("ChunkZ") == pos.z
                                && children.size() == 1 && piece.getString("id").equals("retina:template")
                                && template.startsWith("minecraft:ruined_portal/") && piece.getIntArray("BB").length == 6,
                                "saved ruined portal " + pos);
                        templates.add(template);
                        seen.add(pos);
                    }
                    var refs = structures.getCompound("References").getLongArray(PORTAL);
                    if (Arrays.stream(refs).noneMatch(key -> expected.contains(new ChunkPos(key)))) continue;
                    references++;
                    for (var value : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
                        var chest = (CompoundTag) value;
                        if (chest.getString("LootTable").equals("minecraft:chests/ruined_portal") && chest.contains("LootTableSeed", Tag.TAG_LONG)) {
                            chests++;
                        }
                    }
                    for (var value : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                        var section = (CompoundTag) value;
                        for (var state : section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)) {
                            switch (((CompoundTag) state).getString("Name")) {
                                case "minecraft:obsidian", "minecraft:crying_obsidian" -> obsidian++;
                                case "minecraft:jungle_leaves" -> leaves++;
                                case "minecraft:vine" -> vines++;
                                default -> { }
                            }
                        }
                    }
                }
            }
        }
        require(seen.equals(expected) && references > seen.size(), "portal starts and references");
        require(chests > 0 && obsidian > 0, "portal frame and chest loot");
        if (custom) require(leaves > 0 && vines > 0 && templates.size() > 1, "custom overgrown and vine placement");
        var result = new JsonObject();
        result.addProperty("starts", seen.size());
        result.addProperty("templates", templates.size());
        result.addProperty("references", references);
        result.addProperty("loot_chests", chests);
        result.addProperty("sections_with_obsidian_palette", obsidian);
        result.addProperty("sections_with_leaves_palette", leaves);
        result.addProperty("sections_with_vines_palette", vines);
        return result;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
