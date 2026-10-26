package art.arcane.retina.worldgen;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.*;
import java.nio.file.*;

/** Actual WorldgenRandom wrapper outputs, seed construction and biome zoom. */
public final class EndDecorationRandomTest {
    public static void main(String[] args) throws Exception {
        var report = new JsonObject(); var streams = new JsonArray(); var zooms = new JsonArray();
        try (var f = new EndReferenceFixtures()) {
            for (long seed : new long[]{0,1,-1,Long.MIN_VALUE,Long.MAX_VALUE,42,123456789}) {
                for (int[] at : new int[][]{{0,0},{496,2048},{-528,-2048}}) for (int[] feature : new int[][]{{0,0},{3,9}}) {
                    var random = new WorldgenRandom(new XoroshiroRandomSource(0));
                    long decoration = random.setDecorationSeed(seed,at[0],at[1]);
                    random.setFeatureSeed(decoration,feature[0],feature[1]);
                    var row = new JsonObject(); row.addProperty("seed",seed); row.add("origin",EndReferenceFixtures.ints(at));
                    row.add("feature",EndReferenceFixtures.ints(feature)); row.addProperty("decoration_seed",decoration);
                    var draws = new JsonArray();
                    for (int round=0;round<2;round++) for (int bound : new int[]{1,2,3,14,700,1073741825}) {
                        var draw = new JsonObject(); draw.addProperty("bound",bound);draw.addProperty("value",random.nextInt(bound));draws.add(draw);
                    }
                    row.add("integers",draws);var rest = new JsonArray();
                    for (int i=0;i<3;i++) {
                        var d=new JsonObject();d.addProperty("float_bits",Float.floatToRawIntBits(random.nextFloat()));
                        d.addProperty("double_bits",Double.doubleToRawLongBits(random.nextDouble()));d.addProperty("long",random.nextLong());rest.add(d);
                    }
                    row.add("tail",rest);streams.add(row);
                }
                int[][] points={{0,0,0},{-1,55,-1},{15,70,16},{511,63,-513},{-16777217,127,29999999},{-30000000,-64,30000000}};
                for(var point:points) {
                    int[] chosen=new int[3];var holder=f.biomes.possibleBiomes().iterator().next();
                    var manager=new BiomeManager((x,y,z)->{chosen[0]=x;chosen[1]=y;chosen[2]=z;return holder;},BiomeManager.obfuscateSeed(seed));
                    manager.getBiome(new BlockPos(point[0],point[1],point[2]));
                    var row=new JsonObject();row.addProperty("seed",seed);row.add("position",EndReferenceFixtures.ints(point));row.add("quart",EndReferenceFixtures.ints(chosen));zooms.add(row);
                }
            }
        }
        report.addProperty("minecraft","26.3");report.add("streams",streams);report.add("biome_zoom",zooms);
        report.addProperty("payload_sha256",EndReferenceFixtures.hash(report));var path=Path.of(args[0]);Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        System.out.println("QA_EVT {\"event\":\"end_decoration_random_reference\",\"streams\":"+streams.size()+",\"biome_zoom\":"+zooms.size()+"}");
    }
}
