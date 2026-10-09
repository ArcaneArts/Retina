package art.arcane.retina.worldgen;

import java.io.IOException;
import java.nio.file.*;

/** Resolves a packaged stock Bend worker, or an explicitly installed preview worker. */
final class BendRuntime {
    private static Path extracted;
    private BendRuntime() { }
    static synchronized Path executable() throws IOException {
        String configured = System.getProperty("retina.bend.executable", System.getenv("RETINA_BEND_EXECUTABLE"));
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        if (extracted != null) return extracted;
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch");
        String platform = (os.contains("mac") ? "macos" : os.contains("win") ? "windows" : "linux")
                + "-" + (arch.equals("arm64") ? "aarch64" : arch.equals("amd64") ? "x86_64" : arch);
        try (var binary = BendRuntime.class.getResourceAsStream("/bend/"+platform+"/engine")) {
            if (binary == null) return Path.of(System.getProperty("user.home"),".local/share/retina-bend/2.0.36/playable/engine");
            var bytes=binary.readAllBytes();
            byte[] archive;
            try(var gpu=BendRuntime.class.getResourceAsStream("/bend/"+platform+"/engine.gpu")) {
                archive=gpu==null?null:gpu.readAllBytes();
            }
            // Metal's source-library cache is tied to executable location. A
            // fresh temporary path on every launch recompiles the entire program,
            // even with Bend's pipeline archive present. Keep a content-addressed
            // executable location across world/client restarts.
            var folder=Path.of(System.getProperty("user.home"),".cache","retina","bend-2.0.36",platform,digest(bytes));
            Files.createDirectories(folder);
            var path=folder.resolve("engine");
            install(path,bytes);
            if(archive!=null)install(folder.resolve("engine.gpu"),archive);
            if (!Files.isExecutable(path) && !path.toFile().setExecutable(true,true)) throw new IOException("Could not make Bend worker executable: "+path);
            return extracted=path;
        }
    }

    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void install(Path path,byte[] bytes) throws IOException {
        if(Files.isRegularFile(path) && digest(Files.readAllBytes(path)).equals(digest(bytes)))return;
        var staged=Files.createTempFile(path.getParent(),".install-", ".tmp");
        try {
            Files.write(staged,bytes);
            Files.move(staged,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged); }
    }
}
