package art.arcane.retina.worldgen;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs without Minecraft or the native library; Python/Bend independently decode the files. */
public final class BendProfileWireTest {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        String fixture = """
            {"null":null,"false":false,"true":true,"empty":[],"object":{},
             "text":"NUL\\u0000 \\ud83c\\udf0d \\ud800", "long":[-9223372036854775808,9223372036854775807],
             "int":[-2147483648,2147483647,0,-1],"real":[-0.0,1.25,1e-300,1e300],
             "flags":[true,false,true],"mixed":[1,2.0,"hello"],"nested":{"x":[{"y":42}]}}
            """;
        Files.writeString(output.resolve("fixture.json"), fixture);
        encode(output, "fixture", fixture);
        String noise = "{\"frequency\":0.0035,\"amplitude\":0.8,\"modifiers\":[1.0,0.0,0.25,1.0,-1.0,0.5]}";
        encode(output, "noise", "{\"noises\":[" + String.join(",", java.util.Collections.nCopies(4, noise))
                + "],\"weirdness_noise\":" + noise + "}");
        encode(output, "climate", """
            {"biomes":[{"flags":0},{"flags":64},{"flags":16}],"climate_targets":[
              {"biome":0,"min":[0,0,0,0],"max":[1,1,1,1],"weirdness":[0,1],"offset":0},
              {"biome":1,"min":[0,0,-0.3,0],"max":[1,1,-0.1,1],"weirdness":[0,1],"offset":0},
              {"biome":2,"min":[0,0,0,0],"max":[1,1,1,1],"weirdness":[0,1],"depth":[0,1],"offset":0}]}
            """);
        encode(output, "density", """
            {"geology_min_y":-16,"geology_height":32,"sea_level":4,"materials":[{}, {}, {}],"terrain_features":{"bands":[1,2]},"biomes":[{"flags":0}],"climate_targets":[],"registry_program":{"material_layers":true,"surface":[-16,8,0],"terrain_cell":[4,8],"noises":[],"points":[],"programs":[
             {"nodes":[{"op":0,"a":0,"b":0,"c":0,"p":[0.25,0,0,0]}],"roots":[]},
             {"nodes":[{"op":29,"a":0,"b":0,"c":0,"p":[0,0,0,0]},
                       {"op":0,"a":0,"b":0,"c":0,"p":[30000000,0,0,0]},
                       {"op":5,"a":0,"b":1,"c":0,"p":[0,0,0,0]}],"roots":[2]},
             {"nodes":[{"op":29,"a":1,"b":0,"c":0,"p":[0,0,0,0]}],"roots":[0]},
             {"nodes":[{"op":42,"a":0,"b":0,"c":0,"p":[0,0,0,0]}],"roots":[0]}]}}
            """);
        encode(output, "blocks", """
            {"geology_min_y":-16,"geology_height":32,"sea_level":4,"stone":1,"water":2,
             "materials":["minecraft:air","minecraft:stone","minecraft:water","minecraft:grass_block","minecraft:dirt"],
             "terrain_features":{"bands":[]},"biomes":[{"flags":0}],"climate_targets":[
             {"biome":0,"min":[-1,-1,-1,-1],"max":[1,1,1,1],"weirdness":[-1,1],"depth":[0,0],"offset":0}],
             "registry_program":{"material_layers":true,"surface":[-16,8,0],"terrain_cell":[4,8],"surface_noises":[0,0,0],
              "noises":[{"frequency":0.0035,"amplitude":0,"salt":0,"coefficients":[1]}],"points":[],"programs":[
              {"nodes":[{"op":0,"a":0,"b":0,"c":0,"p":[0,0,0,0]}],"roots":[0]},
              {"nodes":[{"op":0,"a":0,"b":0,"c":0,"p":[8,0,0,0]},{"op":29,"a":1,"b":0,"c":0,"p":[0,0,0,0]},
                        {"op":5,"a":0,"b":1,"c":0,"p":[0,0,0,0]}],"roots":[2]},
              {"nodes":[{"op":0,"a":0,"b":0,"c":0,"p":[0,0,0,0]}],"roots":[0]},
              {"nodes":[{"op":45,"a":1,"b":0,"c":0,"p":[0,0,0,0]},{"op":0,"a":0,"b":0,"c":0,"p":[4,0,0,0]},
                        {"op":40,"a":0,"b":1,"c":0,"p":[0,0,0,0]},{"op":45,"a":1,"b":1,"c":0,"p":[0,0,0,0]},
                        {"op":0,"a":0,"b":0,"c":0,"p":[5,0,0,0]},{"op":40,"a":3,"b":4,"c":0,"p":[0,0,0,0]},
                        {"op":41,"a":2,"b":5,"c":0,"p":[0,0,0,0]}],"roots":[6]}]}}
            """);
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--worker")) { worker(output.resolve("fixture.rbp"), Path.of(args[++i])); continue; }
            try (var source = Files.newBufferedReader(Path.of(args[i]))) {
                System.out.println("profile-" + i + " " + BendProfileWire.write(output.resolve("profile-" + i + ".rbp"), source));
            }
        }
        for (String bad : new String[]{"[9223372036854775808]", "[1e400]", "{\"x\":1,\"x\":2}", "{}{}", "[".repeat(130) + "0" + "]".repeat(130)}) {
            try { BendProfileWire.write(output.resolve("invalid.rbp"), bad); throw new AssertionError("invalid profile accepted"); }
            catch (IllegalArgumentException | java.io.IOException expected) { }
        }
    }
    private static void worker(Path profile, Path executable) throws Exception {
        for (var mode : BendWorker.Execution.values()) try (var worker = new BendWorker(executable, mode, 2)) {
            long pid = worker.processId();
            if (worker.loadProfile(profile).get(30, java.util.concurrent.TimeUnit.SECONDS).length != 16)
                throw new AssertionError("profile acknowledgement");
            var requests = new java.util.ArrayList<java.util.concurrent.CompletableFuture<byte[]>>();
            for (int i = 0; i < 12; i++) requests.add(worker.request(6, new byte[4]));
            for (var request : requests) {
                var root = java.nio.ByteBuffer.wrap(request.get(10, java.util.concurrent.TimeUnit.SECONDS));
                if (root.getInt() != 7 || root.getInt() != 12) throw new AssertionError("resident registry root");
            }
            worker.loadProfile(profile.resolveSibling("noise.rbp")).get(30, java.util.concurrent.TimeUnit.SECONDS);
            var grid = java.nio.ByteBuffer.allocate(20).putInt(-33).putInt(63).putInt(16).putInt(16).putInt(1).array();
            long seed = 0x8000000112345678L;
            for (int channel : new int[]{0, 4}) {
                worker.prepareProfileNoise(seed, channel).get(10, java.util.concurrent.TimeUnit.SECONDS);
                byte[] loaded = worker.request(2, grid).get(10, java.util.concurrent.TimeUnit.SECONDS);
                var explicit = java.nio.ByteBuffer.allocate(48).putInt((int) seed).putInt((int) (seed >>> 32))
                        .putFloat(0.0035f).putFloat(0.8f).putInt(channel).putInt(6);
                for (float value : new float[]{1, 0, .25f, 1, -1, .5f}) explicit.putFloat(value);
                worker.request(1, explicit.array()).get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (!java.util.Arrays.equals(loaded, worker.request(2, grid).get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    throw new AssertionError("typed registry stack/seed transport");
            }
            worker.loadProfile(profile.resolveSibling("climate.rbp")).get(30, java.util.concurrent.TimeUnit.SECONDS);
            var counts = java.nio.ByteBuffer.wrap(worker.prepareProfileClimate().get(30, java.util.concurrent.TimeUnit.SECONDS));
            if (counts.getInt() != 3 || counts.getInt() != 3 || counts.hasRemaining())
                throw new AssertionError("resident climate acknowledgement");
            var climate = java.nio.ByteBuffer.allocate(60).putInt(2);
            for (int queryMode : new int[]{0, 16}) {
                for (int axis = 0; axis < 6; axis++) climate.putFloat(0);
                climate.putInt(queryMode);
            }
            requests.clear();
            for (int i = 0; i < 12; i++) requests.add(worker.request(10, climate.array()));
            for (var request : requests) {
                var rows = java.nio.ByteBuffer.wrap(request.get(10, java.util.concurrent.TimeUnit.SECONDS));
                for (int expected : new int[]{0, 1}) {
                    if (rows.getInt() != 1 || rows.getInt() != expected || rows.getInt() != expected || rows.getFloat() != 0)
                        throw new AssertionError("typed resident climate selection");
                    float midpoint = rows.getFloat();
                    if (Math.abs(midpoint - (expected == 0 ? 0 : -0.2f)) > 0.000001f)
                        throw new AssertionError("resident coastal interval");
                }
                if (rows.hasRemaining()) throw new AssertionError("climate response length");
            }
            worker.loadProfile(profile).get(30, java.util.concurrent.TimeUnit.SECONDS);
            try {
                worker.request(10, climate.array()).get(10, java.util.concurrent.TimeUnit.SECONDS);
                throw new AssertionError("reloaded profile retained stale climate");
            } catch (java.util.concurrent.ExecutionException expected) {
                if (!expected.getCause().getMessage().contains("code 714")) throw expected;
            }
            worker.loadProfile(profile.resolveSibling("density.rbp")).get(30, java.util.concurrent.TimeUnit.SECONDS);
            counts = java.nio.ByteBuffer.wrap(worker.prepareProfileDensity().get(30, java.util.concurrent.TimeUnit.SECONDS));
            for (int count : new int[]{3, 0, 0, 0}) if (counts.getInt() != count)
                throw new AssertionError("resident numeric program acknowledgement");
            if (counts.hasRemaining()) throw new AssertionError("density acknowledgement length");
            worker.prepareProfileClimate().get(30, java.util.concurrent.TimeUnit.SECONDS);
            var density = java.nio.ByteBuffer.allocate(76).putInt(3);
            for (int program = 0; program < 3; program++) density.putInt(program).putInt(29999999).putInt(127)
                    .putInt(-30000001).putInt((int) seed).putInt((int) (seed >>> 32));
            requests.clear();
            for (int i = 0; i < 12; i++) requests.add(worker.request(12, density.array()));
            for (var request : requests) {
                var values = java.nio.ByteBuffer.wrap(request.get(30, java.util.concurrent.TimeUnit.SECONDS));
                for (int program = 0; program < 3; program++) for (int channel = 0; channel < 6; channel++) {
                    float expected = program == 0 ? .25f : program == 2 ? 127 : channel == 0 ? -1 : (float) 29999999;
                    if (values.getFloat() != expected) throw new AssertionError("resident density/coordinate transport");
                }
                if (values.hasRemaining()) throw new AssertionError("density response length");
            }
            counts = java.nio.ByteBuffer.wrap(worker.prepareDensityLattice(1,
                    29999991, 120, -30000007, 30000007, 136, -29999991, 4, 8, seed)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS));
            for (int count : new int[]{6, 3, 6, 108}) if (counts.getInt() != count)
                throw new AssertionError("density lattice acknowledgement");
            if (counts.hasRemaining()) throw new AssertionError("lattice acknowledgement length");
            requests.clear();
            for (int i = 0; i < 12; i++) requests.add(worker.request(14, density.array()));
            for (var request : requests) {
                var values = java.nio.ByteBuffer.wrap(request.get(30, java.util.concurrent.TimeUnit.SECONDS));
                for (int program = 0; program < 3; program++) for (int channel = 0; channel < 6; channel++) {
                    float expected = program == 0 ? .25f : program == 2 ? 127 : channel == 0 ? -1 : (float) 29999999;
                    if (values.getFloat() != expected) throw new AssertionError("resident lattice/fallback transport");
                }
                if (values.hasRemaining()) throw new AssertionError("lattice response length");
            }
            counts = java.nio.ByteBuffer.wrap(worker.prepareProfileSurface().get(30, java.util.concurrent.TimeUnit.SECONDS));
            for (int count : new int[]{-16, 32, 4, 4, 8, 0}) if (counts.getInt() != count)
                throw new AssertionError("surface profile acknowledgement");
            if (counts.hasRemaining()) throw new AssertionError("surface profile length");
            byte[] descriptor = worker.surfaceDensityDescriptor(29999995, -30000003, 7, 5, seed)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            var planned = java.nio.ByteBuffer.wrap(descriptor);
            for (int value : new int[]{1, 29999995, -16, -30000003, 30000001, 16, -29999999, 4, 8,
                    (int) seed, (int) (seed >>> 32)}) if (planned.getInt() != value)
                throw new AssertionError("Bend surface density descriptor");
            if (planned.hasRemaining()) throw new AssertionError("surface descriptor length");
            worker.request(13, descriptor).get(30, java.util.concurrent.TimeUnit.SECONDS);
            counts = java.nio.ByteBuffer.wrap(worker.generateSurfaceColumns(29999995, -30000003, 7, 5, seed)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS));
            for (int count : new int[]{7, 5, 35}) if (counts.getInt() != count)
                throw new AssertionError("resident surface tile acknowledgement");
            if (counts.hasRemaining()) throw new AssertionError("surface tile length");
            var surface = java.nio.ByteBuffer.allocate(36).putInt(2);
            for (int x : new int[]{29999999, 30000001}) surface.putInt(x).putInt(-30000001)
                    .putInt((int) seed).putInt((int) (seed >>> 32));
            requests.clear();
            for (int i = 0; i < 12; i++) requests.add(worker.request(17, surface.array()));
            for (var request : requests) {
                var values = java.nio.ByteBuffer.wrap(request.get(30, java.util.concurrent.TimeUnit.SECONDS));
                for (float height : new float[]{-15, 16}) {
                    if (values.getInt() != 1 || values.getFloat() != height)
                        throw new AssertionError("resident surface height");
                    for (int channel = 0; channel < 6; channel++) if (values.getFloat() != .25f)
                        throw new AssertionError("surface climate transport");
                    if (values.getInt() != -1) throw new AssertionError("empty surface target table");
                }
                if (values.hasRemaining()) throw new AssertionError("surface response length");
            }
            counts = java.nio.ByteBuffer.wrap(worker.prepareProfileMaterials().get(30, java.util.concurrent.TimeUnit.SECONDS));
            for (int count : new int[]{1, 2, 4, 1}) if (counts.getInt() != count)
                throw new AssertionError("material profile acknowledgement");
            if (counts.hasRemaining()) throw new AssertionError("material profile length");
            var materials = java.nio.ByteBuffer.allocate(116).putInt(2);
            for (int y : new int[]{-3, 4}) {
                materials.putInt(0).putInt(30000001).putInt(y).putInt(-30000001)
                        .putInt((int) seed).putInt((int) (seed >>> 32));
                for (float value : new float[]{1, 3, 0, -2.5f, 4, .5f, 4, 0}) materials.putFloat(value);
            }
            requests.clear();
            for (int i = 0; i < 12; i++) requests.add(worker.request(20, materials.array()));
            for (var request : requests) {
                var values = java.nio.ByteBuffer.wrap(request.get(30, java.util.concurrent.TimeUnit.SECONDS));
                for (float expected : new float[]{3, 2}) for (int channel = 0; channel < 6; channel++) {
                    if (values.getFloat() != expected) throw new AssertionError("material bands/coordinate/context transport");
                }
                if (values.hasRemaining()) throw new AssertionError("material response length");
            }
            worker.prepareProfileDensity().get(30, java.util.concurrent.TimeUnit.SECONDS);
            try {
                worker.request(14, density.array()).get(10, java.util.concurrent.TimeUnit.SECONDS);
                throw new AssertionError("reprepared density retained stale lattice");
            } catch (java.util.concurrent.ExecutionException expected) {
                if (!expected.getCause().getMessage().contains("code 718")) throw expected;
            }
            worker.loadProfile(profile).get(30, java.util.concurrent.TimeUnit.SECONDS);
            try {
                worker.request(12, density.array()).get(10, java.util.concurrent.TimeUnit.SECONDS);
                throw new AssertionError("reloaded profile retained stale numeric model");
            } catch (java.util.concurrent.ExecutionException expected) {
                if (!expected.getCause().getMessage().contains("code 716")) throw expected;
            }
            worker.loadProfile(profile.resolveSibling("blocks.rbp")).get(30, java.util.concurrent.TimeUnit.SECONDS);
            worker.prepareProfileDensity().get(30, java.util.concurrent.TimeUnit.SECONDS);
            worker.prepareProfileClimate().get(30, java.util.concurrent.TimeUnit.SECONDS);
            worker.prepareProfileSurface().get(30, java.util.concurrent.TimeUnit.SECONDS);
            worker.prepareProfileMaterials().get(30, java.util.concurrent.TimeUnit.SECONDS);
            counts = java.nio.ByteBuffer.wrap(worker.prepareProfileBlocks().get(30, java.util.concurrent.TimeUnit.SECONDS));
            for (int count : new int[]{1, 2, 5, 0, 0, 0}) if (counts.getInt() != count)
                throw new AssertionError("block profile acknowledgement");
            descriptor = worker.surfaceDensityDescriptor(29999994, -30000004, 9, 7, seed)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            worker.request(13, descriptor).get(30, java.util.concurrent.TimeUnit.SECONDS);
            worker.generateSurfaceColumns(29999995, -30000003, 7, 5, seed).get(30, java.util.concurrent.TimeUnit.SECONDS);
            counts = java.nio.ByteBuffer.wrap(worker.generateBlockColumns().get(30, java.util.concurrent.TimeUnit.SECONDS));
            for (int count : new int[]{7, 5, 35, 140}) if (counts.getInt() != count)
                throw new AssertionError("resident block-run acknowledgement");
            var voxels = java.nio.ByteBuffer.allocate(104).putInt(5);
            for (int y : new int[]{3, 4, 7, 8, -17}) voxels.putInt(30000001).putInt(y).putInt(-30000001)
                    .putInt((int) seed).putInt((int) (seed >>> 32));
            requests.clear();
            for (int i = 0; i < 12; i++) requests.add(worker.request(23, voxels.array()));
            for (var request : requests) {
                var values = java.nio.ByteBuffer.wrap(request.get(30, java.util.concurrent.TimeUnit.SECONDS));
                for (int expected : new int[]{1, 4, 3, 0, -1}) {
                    if (values.getInt() != (expected == -1 ? 0 : 1) || values.getInt() != expected)
                        throw new AssertionError("resident block/height/material transport");
                }
                if (values.hasRemaining()) throw new AssertionError("block response length");
            }
            if (worker.processId() != pid) throw new AssertionError("worker replaced");
            System.out.println("Java registry worker " + mode + " pass");
        }
    }
    private static void encode(Path output, String name, String source) throws Exception {
        var summary = BendProfileWire.write(output.resolve(name + ".rbp"), source);
        if (Files.size(output.resolve(name + ".rbp")) != summary.bytes()) throw new AssertionError("wire byte count");
        System.out.println(name + " " + summary);
    }
}
