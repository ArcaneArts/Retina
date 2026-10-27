package art.arcane.retina.worldgen;

import java.nio.file.Files;
import java.nio.file.Path;

/** Compiled/run on a JDK-only classpath: no Minecraft, Gson, or registry classes. */
public final class NativeWorldDefinitionBridgeTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("retina.native.path", args[0]);
        Path fixtures = Path.of(args[1]);
        boolean bind = args.length > 2 && args[2].equals("--bind");
        try { Class.forName("net.minecraft.SharedConstants"); throw new AssertionError("Minecraft is on the bridge test classpath"); }
        catch (ClassNotFoundException expected) { }
        var directory = Files.createTempDirectory("retina-definition-bridge-");
        var missing = directory.resolve("missing.dat");
        require(NativeWorldDefinition.tryOpen(missing) == null,"actual absence is reported separately");
        String[] cases = {"vanilla","flat","datapack","procedural"};
        long[] seeds = {Long.MIN_VALUE+17,Long.MAX_VALUE-11,9007199254740993L,Long.MIN_VALUE+19};
        for (int i=0;i<cases.length;i++) {
            try (var world = NativeWorldDefinition.open(fixtures.resolve(cases[i]).resolve("retina.dat"))) {
                require(world.metadata().contains("\"seed\":"+seeds[i]),"signed seed fidelity: "+cases[i]);
                require(world.metadata().contains("\"minecraft:overworld\"")
                        && world.metadata().contains("\"minecraft:the_nether\"")
                        && world.metadata().contains("\"minecraft:the_end\""),"all dimension records");
                if (cases[i].equals("datapack")) require(world.metadata().contains("definition_test:empty"),"datapack biome metadata");
                expectFailure(() -> world.request("minecraft:overworld",0,0),"has not been bound");
                expectFailure(() -> world.bindDimension("definition_test:missing",2),"has no dimension");
                expectFailure(() -> world.bindDimension("minecraft:overworld",123),"unsupported MCA compression");
                if (cases[i].equals("procedural")) {
                    require(world.metadata().contains("\"terrain_kind\":\"procedural\""),"explicit procedural definition");
                    if (bind) {
                        int id = world.bindDimension("minecraft:overworld",2);
                        var request = world.request("minecraft:overworld",-33,32);
                        require(request.seed() == Long.MIN_VALUE+19 && request.profile() == id
                                && request.baseHeight() == 37.5F && request.amplitude() == 19.25F
                                && request.frequency() == .0041F,"procedural request from file without Minecraft");
                    }
                }
            }
        }
        var copied = directory.resolve("retina.dat");
        Files.copy(fixtures.resolve("flat/retina.dat"),copied);
        var world = NativeWorldDefinition.open(copied);
        try {
            String profile = world.diagnosticProfile("minecraft:overworld");
            require(profile.contains("\"flat\"") && profile.contains("\"materials\""),"saved profile diagnostics");
            Files.writeString(copied,"damaged");
            require(world.diagnosticProfile("minecraft:overworld").equals(profile),"reads the loaded snapshot after the source changes");
            if (bind) {
                int id = world.bindDimension("minecraft:overworld",2);
                require(id != 0,"saved profile handle");
                require(world.bindDimension("minecraft:overworld",2) == id,"one resident binding per dimension");
                var request = world.request("minecraft:overworld",-33,32);
                require(request.seed() == Long.MAX_VALUE-11 && request.profile() == id,"request belongs to saved world");
                require(request.chunkX() == -33 && request.chunkZ() == 32 && request.minY() == -64 && request.height() == 384,"saved geometry and supplied coordinates");
                expectFailure(() -> world.bindDimension("minecraft:overworld",4),"different output compression");
            }
        } finally { world.close(); }
        world.close();
        expectFailure(() -> world.request("minecraft:overworld",0,0),"closed");
        expectFailure(() -> NativeWorldDefinition.tryOpen(copied),"gzip");
        require(Files.readString(copied).equals("damaged"),"failed open preserves file");
        Files.delete(copied); Files.delete(directory);
        System.out.println("QA_EVT {\"event\":\"jdk_only_world_definition_bridge\",\"status\":\"pass\",\"cases\":4,\"bound\":"+bind+",\"minecraft_classes\":false}");
    }

    private static void expectFailure(Runnable action,String message) {
        try { action.run(); throw new AssertionError("Expected failure: "+message); }
        catch (IllegalStateException expected) { require(expected.getMessage().contains(message),expected.toString()); }
    }
    private static void require(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }
}
