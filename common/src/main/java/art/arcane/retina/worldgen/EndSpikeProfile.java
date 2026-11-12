package art.arcane.retina.worldgen;
import art.arcane.retina.definition.EndSpikeGeometry;
import com.google.gson.JsonObject;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;

import static art.arcane.retina.definition.FlatProfileModel.array;

/** Export loaded spike definitions and source coordinate arithmetic; seed shuffle stays native. */
final class EndSpikeProfile {
    private EndSpikeProfile() { }
    static JsonObject geometry(EndSpikeFeature feature) {
        var spikes = feature.spikes().stream().map(spike -> new EndSpikeGeometry.Spike(spike.getCenterX(),
                spike.getCenterZ(), spike.getRadius(), spike.getHeight(), spike.isGuarded())).toList();
        var target = feature.crystalBeamTarget().map(pos -> array(pos.getX(), pos.getY(), pos.getZ())).orElse(null);
        return EndSpikeGeometry.encode(spikes, feature.crystalInvulnerable(), target);
    }
}
