package art.arcane.retina.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** Shared pillar layout and validation. The native engine shuffles the sizes using the world seed. */
public final class EndSpikeGeometry {
    public record Spike(int x, int z, int radius, int height, boolean guarded) { }

    private EndSpikeGeometry() { }

    public static JsonObject encode(List<Spike> spikes, boolean invulnerable, JsonArray beamTarget) {
        var result = new JsonObject();
        var centers = new JsonArray();
        for (int i = 0; i < 10; i++) {
            double angle = 2.0 * (-Math.PI + (Math.PI / 10) * i);
            centers.add(FlatProfileModel.array((int) Math.floor(42.0 * Math.cos(angle)),
                    (int) Math.floor(42.0 * Math.sin(angle))));
        }
        var geometry = new JsonArray();
        int radius = 5;
        for (var spike : spikes) {
            if (spike.radius() < 0 || spike.radius() > 46340 || spike.height() < 0 || spike.height() > 254) return null;
            var entry = new JsonObject();
            entry.addProperty("x", spike.x());
            entry.addProperty("z", spike.z());
            entry.addProperty("radius", spike.radius());
            entry.addProperty("height", spike.height());
            entry.addProperty("guarded", spike.guarded());
            geometry.add(entry);
            radius = Math.max(radius, spike.radius());
        }
        result.add("centers", centers);
        result.add("spikes", geometry);
        result.addProperty("invulnerable", invulnerable);
        if (beamTarget != null) result.add("beam_target", beamTarget);
        result.addProperty("geometry_reach", Math.addExact(15, radius));
        return result;
    }
}
