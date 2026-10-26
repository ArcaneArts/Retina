package art.arcane.retina.worldgen;
import com.google.gson.*;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
/** Export loaded spike definitions and source coordinate arithmetic; seed shuffle stays native. */
final class EndSpikeProfile {
    private EndSpikeProfile() { }
    static JsonObject geometry(EndSpikeFeature feature) {
        var out=new JsonObject();var centers=new JsonArray();var spikes=new JsonArray();
        for(int i=0;i<10;i++) {
            var center=new JsonArray();center.add((int)Math.floor(42.0*Math.cos(2.0*(-Math.PI+(Math.PI/10)*i))));center.add((int)Math.floor(42.0*Math.sin(2.0*(-Math.PI+(Math.PI/10)*i))));centers.add(center);
        }
        int radius=5;
        for(var spike:feature.spikes()) {
            // Larger radii overflow vanilla's int radius predicate; caps outside
            // storage need a separate fire-state/world-boundary adapter.
            if(spike.getRadius()<0 || spike.getRadius()>46340 || spike.getHeight()<0 || spike.getHeight()>254) return null;
            var row=new JsonObject();row.addProperty("x",spike.getCenterX());row.addProperty("z",spike.getCenterZ());row.addProperty("radius",spike.getRadius());row.addProperty("height",spike.getHeight());row.addProperty("guarded",spike.isGuarded());spikes.add(row);
            radius=Math.max(radius,spike.getRadius());
        }
        out.add("centers",centers);out.add("spikes",spikes);out.addProperty("invulnerable",feature.crystalInvulnerable());
        feature.crystalBeamTarget().ifPresent(p->{var target=new JsonArray();target.add(p.getX());target.add(p.getY());target.add(p.getZ());out.add("beam_target",target);});
        out.addProperty("geometry_reach",Math.addExact(15,radius));return out;
    }
}
