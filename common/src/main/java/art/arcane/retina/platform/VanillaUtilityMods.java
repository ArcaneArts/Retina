package art.arcane.retina.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Audited utility artifacts, not an assertion that every release of a mod is neutral. */
public final class VanillaUtilityMods {
    private static final Map<String, String> ARTIFACTS = Map.of(
            "fabric:26.3:chunky:1.5.3", "ecdfc55a0f67f3ec6f40850687615c941ae226bd88f4e06188a7b268fd3da942",
            "neoforge:26.3:chunky:1.5.4", "6ed601bd2c2942cd537f1732805bc786e70b57d988039c416c3bc27ba97162b9");
    private record Artifact(Path path, String hash) { }
    // Loader origins are fixed for a running process. Hash each known artifact once.
    private static final Map<Artifact, Boolean> VERIFIED = new ConcurrentHashMap<>();

    private VanillaUtilityMods() { }

    public static boolean supported(String loader, String minecraft, String id, String version, List<Path> origins) {
        String expected = ARTIFACTS.get(loader + ":" + minecraft + ":" + id + ":" + version);
        if (expected == null || origins.size() != 1) return false;
        var artifact = new Artifact(origins.getFirst().toAbsolutePath().normalize(), expected);
        return VERIFIED.computeIfAbsent(artifact, key -> matches(key.path(), key.hash()));
    }

    static boolean matches(Path path, String expected) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest()).equals(expected);
        } catch (IOException | SecurityException unavailable) {
            // This only declines the bundled optimization; normal registry export still runs.
            return false;
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
