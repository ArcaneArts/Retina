package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;

/** Opt-in real loader checks; the saved expectation file belongs only to QA. */
final class TerrainEntityQa {
    private static int started;
    private static boolean checked;
    private static ListTag expected;
    private static BlockPos edit;

    static void check(MinecraftServer server) {
        if (!Boolean.getBoolean("retina.qa.entities") || checked || server.getTickCount() < 40) return;
        try { run(server); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }

    private static void run(MinecraftServer server) throws IOException {
        var level = server.overworld();
        if (!(level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator generator))
            throw new IllegalStateException("Entity QA requires Retina");
        boolean reopen = Boolean.getBoolean("retina.qa.entities.reopen");
        if (started == 0) {
            var set = level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).getValue(Retina.id("qa_entities"));
            if (set == null || !(set.placement() instanceof RandomSpreadStructurePlacement placement))
                throw new IllegalStateException("Entity QA requires its registered fixture");
            int cell = Integer.getInteger("retina.qa.entities.cell", 10);
            var position = placement.getPotentialStructureChunk(level.getSeed(), cell * placement.spacing(), cell * placement.spacing());
            var chunk = level.getChunk(position.x(), position.z());
            var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Retina.id("qa_entities"));
            var start = chunk.getAllStarts().get(structure);
            if (start == null || !start.isValid()) throw new IllegalStateException("Loaded entity QA structure is missing");
            var bounds = start.getBoundingBox();
            var positions = new ArrayList<ChunkPos>();
            for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ(), 16); z++)
                for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX(), 16); x++)
                    positions.add(new ChunkPos(x, z));
            var saved = server.getWorldPath(LevelResource.ROOT).resolve("retina-qa-entities.nbt");
            edit = new BlockPos(position.getMinBlockX() + 8, 90, position.getMinBlockZ() + 8);
            if (reopen) {
                var fixture = NbtIo.readCompressed(saved, NbtAccounter.unlimitedHeap());
                if (fixture.getLongOr("seed", 0) != level.getSeed() || !fixture.getStringOr("mode", "").equals(generator.mode())
                        || fixture.getIntOr("chunk_x", 0) != position.x() || fixture.getIntOr("chunk_z", 0) != position.z())
                    throw new IllegalStateException("Entity QA expectation belongs to another world");
                expected = fixture.getListOrEmpty("entities");
                if (!level.getBlockState(edit).is(Blocks.DIAMOND_BLOCK)) throw new IllegalStateException("Entity QA block edit was lost");
            } else {
                expected = new ListTag();
                for (var at : positions) {
                    var data = generator.regionMode() ? generator.previewCache().read(at)
                            : NativeTerrain.instance().structureData(generator.request(at.x(), at.z()));
                    for (var value : data.getListOrEmpty("entities")) expected.add(value.copy());
                }
                var fixture = new CompoundTag();
                fixture.putLong("seed", level.getSeed()); fixture.putString("mode", generator.mode());
                fixture.putInt("chunk_x", position.x()); fixture.putInt("chunk_z", position.z());
                fixture.put("entities", expected.copy());
                // QA metadata only: all generated chunk NBT/compression still comes from its selected backend.
                NbtIo.writeCompressed(fixture, saved);
                level.setBlockAndUpdate(edit, Blocks.DIAMOND_BLOCK.defaultBlockState());
            }
            if (expected.size() != 3) throw new IllegalStateException("Expected frame, glow frame and painting, got " + expected.size());
            TerrainRegistryQa.check(generator);
            for (var at : positions) {
                level.getChunk(at.x(), at.z());
                level.getChunkSource().addTicketWithRadius(TicketType.FORCED, at, 2);
            }
            started = server.getTickCount();
            return;
        }
        // Entity storage loads asynchronously; allow normal survival ticks before asserting.
        if (server.getTickCount() < started + 120) return;
        var uuids = new HashSet<java.util.UUID>();
        var anchors = new HashSet<BlockPos>();
        for (var value : expected) {
            var tag = (CompoundTag)value;
            var uuid = UUIDUtil.CODEC.parse(NbtOps.INSTANCE, tag.get("UUID")).getOrThrow();
            if (!uuids.add(uuid)) throw new IllegalStateException("Duplicate template entity UUID");
            var entity = level.getEntity(uuid);
            if (!(entity instanceof HangingEntity hanging)) throw new IllegalStateException("Loaded attached entity is missing: " + uuid);
            if (!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(tag.getStringOr("id", "")))
                throw new IllegalStateException("Loaded template entity changed type");
            var anchor = BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get("block_pos")).getOrThrow();
            anchors.add(anchor);
            boolean painting = tag.getStringOr("id", "").equals("minecraft:painting");
            var codec = painting ? Direction.LEGACY_ID_CODEC_2D : Direction.LEGACY_ID_CODEC;
            var direction = codec.parse(NbtOps.INSTANCE, tag.get(painting ? "facing" : "Facing")).getOrThrow();
            if (!hanging.getPos().equals(anchor) || hanging.getDirection() != direction || !hanging.survives())
                throw new IllegalStateException("Loaded attachment differs: " + anchor + "/" + direction + " vs "
                        + hanging.getPos() + "/" + hanging.getDirection() + ", support=" + level.getBlockState(anchor.relative(direction.getOpposite())));
            if (entity instanceof net.minecraft.world.entity.decoration.ItemFrame frame
                    && (!frame.getItem().is(net.minecraft.world.item.Items.DIAMOND) || frame.getItem().getCount() != 1))
                throw new IllegalStateException("Loaded frame lost its template item");
            if (entity instanceof net.minecraft.world.entity.decoration.painting.Painting picture
                    && !picture.getVariant().unwrapKey().orElseThrow().identifier().toString().equals("minecraft:kebab"))
                throw new IllegalStateException("Loaded painting lost its template variant");
        }
        int occupants = 0;
        for (var entity : level.getAllEntities())
            if (entity instanceof HangingEntity hanging && anchors.contains(hanging.getPos())) occupants++;
        if (occupants != expected.size()) throw new IllegalStateException("Duplicate hanging entities at fixture anchors: " + occupants);
        checked = true;
        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_live_template_entities\",\"status\":\"pass\",\"context\":{\"mode\":\"{}\",\"backend\":\"{}\",\"entities\":{},\"reopen\":{},\"generated_regions\":{},\"edit_preserved\":{}}}",
                generator.mode(), generator.backend(), expected.size(), reopen, generator.metrics().snapshot().previewRegions(), reopen);
        if (server.isDedicatedServer()) server.halt(false);
    }

}
