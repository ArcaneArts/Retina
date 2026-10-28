package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Actual vanilla registries/templates in the mod development runtime; no native or GPU required. */
public final class VanillaCatalogExport {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path output = Path.of(args[0]); Files.createDirectories(output);
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin).forEach(Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            var stems = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL)
                    .value().createWorldDimensions().dimensions();
            var structures = StructureProfile.Context.of(resources);
            var packs = resources.listPacks().map(pack -> pack.packId()).toList();
            CompoundTag catalog = WorldCatalogCompiler.compile(registry, stems, structures, packs);
            Path file = output.resolve(WorldCatalogCompiler.NAME);
            Files.deleteIfExists(file); // This explicit export owns this generated asset.
            WorldDefinitionFile.writeNew(output, WorldCatalogCompiler.NAME, WorldCatalogCompiler.pack(catalog));
            var manifest = new JsonObject();
            manifest.addProperty("minecraft_version", SharedConstants.getCurrentVersion().id());
            manifest.addProperty("data_version", SharedConstants.getCurrentVersion().dataVersion().version());
            manifest.addProperty("catalog_version", WorldCatalogCompiler.CONTAINER_VERSION); manifest.addProperty("materialization_version", 1);
            manifest.addProperty("generation_version", WorldDefinitionCompiler.GENERATION_VERSION);
            manifest.addProperty("exporter", WorldCatalogCompiler.EXPORTER);
            manifest.addProperty("environment", System.getProperty("retina.catalog.environment", "Retina mod-development registry exporter"));
            manifest.add("content_mods", new JsonArray());
            var sourcePacks = new JsonArray(); packs.forEach(sourcePacks::add); manifest.add("source_packs", sourcePacks);
            manifest.addProperty("asset", WorldCatalogCompiler.NAME);
            manifest.addProperty("compressed_bytes", Files.size(file));
            manifest.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            var variants = new JsonObject();
            for (String name : new TreeSet<>(catalog.getCompoundOrEmpty("variants").keySet())) {
                var template = catalog.getCompoundOrEmpty("variants").getCompoundOrEmpty(name).getCompoundOrEmpty("template");
                var entry = new JsonObject();
                entry.addProperty("generate_structures", template.getBooleanOr("generate_structures", false));
                entry.addProperty("bonus_chest", template.getBooleanOr("bonus_chest", false));
                if (name.equals("bukkit-api")) entry.addProperty("delivery_policy",
                        "Public Bukkit API: structures and decoration roots requiring saved scheduled spring updates are disabled before world materialization");
                var dimensions = new JsonArray(); new TreeSet<>(template.getCompoundOrEmpty("dimensions").keySet()).forEach(dimensions::add);
                entry.add("dimensions", dimensions);
                var limitations = new JsonObject();
                for (var dimension : dimensions) {
                    var profile = template.getCompoundOrEmpty("dimensions").getCompoundOrEmpty(dimension.getAsString()).getCompoundOrEmpty("profile");
                    var reports = new JsonObject();
                    for (String field : List.of("decoration_omissions", "geology_omissions", "simple_current_omissions"))
                        if (profile.get(field) != null) reports.add(field, NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, profile.get(field)));
                    reports.add("structures", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, profile.getCompoundOrEmpty("structures").getListOrEmpty("omissions")));
                    reports.add("approximations", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, profile.getCompoundOrEmpty("registry_program").getListOrEmpty("approximations")));
                    limitations.add(dimension.getAsString(), reports);
                }
                entry.add("limitations", limitations); variants.add(name, entry);
            }
            manifest.add("variants", variants);
            Files.writeString(output.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + "\n");
            if (args.length > 1 && args[1].equals("--fixtures")) {
                var references = new JsonArray();
                for (boolean enabled : new boolean[]{true, false}) for (long seed : new long[]{0, -1, Long.MIN_VALUE + 17, Long.MAX_VALUE - 11, 123456789L}) {
                    String variant = enabled ? "normal" : "normal-no-structures";
                    Path root = output.resolve("references").resolve(variant).resolve(Long.toString(seed));
                    Files.createDirectories(root); Files.deleteIfExists(root.resolve(WorldDefinitionFile.NAME));
                    var direct = WorldDefinitionCompiler.compile(registry, new WorldOptions(seed, enabled, false),
                            stems, structures, packs, WorldCatalogCompiler.EXPORTER);
                    WorldDefinitionFile.writeNew(root, direct);
                    var entry = new JsonObject(); entry.addProperty("variant", variant); entry.addProperty("seed", seed);
                    entry.addProperty("definition", output.toAbsolutePath().relativize(root.toAbsolutePath().resolve(WorldDefinitionFile.NAME)).toString()); references.add(entry);
                }
                Files.writeString(output.resolve("references.json"), references.toString());
            }
            System.out.println("QA_EVT {\"event\":\"vanilla_catalog_export\",\"status\":\"pass\",\"compressed_bytes\":" + Files.size(file) + "}");
        }
    }
}
