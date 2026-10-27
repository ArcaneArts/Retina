package art.arcane.retina.worldgen;

/** Fabric defers built-in registry completion; perform it once for the shared test JVM. */
final class EndFixtureBootstrap {
    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.bootStrap();
    }
    static void initialize() { }
    private EndFixtureBootstrap() { }
}
