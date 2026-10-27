package art.arcane.retina.client;

import art.arcane.retina.Retina;
import art.arcane.retina.debug.TerrainDebugReport;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/** Client evidence that real portal travel delivers telemetry for the current dimension. */
final class NetherClientQa {
    private static boolean entered;
    private static int roundtrips;

    private NetherClientQa() {}

    static void reset() { entered = false; roundtrips = 0; }

    static boolean ready(Minecraft minecraft) {
        boolean structures = Boolean.getBoolean("retina.qa.nether.structures");
        boolean progression = Boolean.getBoolean("retina.qa.nether.progression");
        boolean endIntegration = Boolean.getBoolean("retina.qa.end.integrations");
        boolean integrations = endIntegration || Boolean.getBoolean("retina.qa.nether.integrations");
        var target = endIntegration ? Level.END : Level.NETHER;
        if (!Boolean.getBoolean("retina.qa.nether.lifecycle") && !progression && !structures && !integrations) return true;
        if (minecraft.level == null || minecraft.player == null) return false;
        var dimension = minecraft.level.dimension();
        var payload = TerrainDebugEntry.remoteStats();
        if (!payload.activeIn(dimension)) return false;
        if (dimension.equals(target) && !entered) {
            if (TerrainDebugReport.lines(payload).stream().noneMatch(line -> line.contains(target.identifier().toString())))
                throw new IllegalStateException("Nether client QA: F3 does not identify the current dimension");
            entered = true;
            Retina.LOGGER.info("QA_EVT {\"event\":\"{}_client_telemetry\",\"status\":\"pass\",\"context\":{\"dimension\":\"{}\"}}", endIntegration ? "end" : "nether", target.identifier());
        }
        if (entered && dimension.equals(Level.OVERWORLD)) {
            entered = false;
            roundtrips++;
            Retina.LOGGER.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\",\"context\":{\"dimension\":\"minecraft:overworld\",\"trip\":{}}}",
                    endIntegration ? "end_client_integration_return" : integrations ? "nether_client_integration_return" : structures ? "nether_client_structure_return"
                            : progression ? "nether_client_progression_return" : "nether_client_roundtrip", roundtrips);
        }
        return roundtrips >= (progression || structures || integrations ? 1 : 2);
    }
}
