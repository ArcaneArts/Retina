package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.world.level.biome.Biome;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual Minecraft placement noise compared with the production GPU count sampler. */
public final class NativeDecorationCountIntegrationTest {
    public static void main(String[] args) throws Exception {
        var vanilla = "build/decoration-count-vanilla.json";
        NativeProfileExport.main(new String[]{vanilla});
        check(Path.of(vanilla), false);
        if (args.length > 0) {
            var packed = "build/decoration-count-terralith.json";
            NativeProfileExport.main(new String[]{packed, args[0]});
            check(Path.of(packed), true);
        }
    }
    private record Rule(int recipe, int op, JsonObject data) { }
    private static void check(Path path, boolean datapack) throws Exception {
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var recipes = json.getAsJsonArray("decorations");
        var rules = new ArrayList<Rule>(); int based = 0, threshold = 0;
        for (int id = 0; id < recipes.size(); id++) {
            var program = recipes.get(id).getAsJsonObject().getAsJsonArray("placement");
            for (int op = 0; op < program.size(); op++) {
                var data = program.get(op).getAsJsonObject();
                String kind = data.get("type").getAsString();
                if (kind.equals("noise_based_count") || kind.equals("noise_threshold_count")) {
                    rules.add(new Rule(id, op, data));
                    if (kind.equals("noise_based_count")) based++; else threshold++;
                }
            }
        }
        require(threshold > 0, "actual registered threshold counts survive export");
        if (datapack) require(based > 10, "actual Terralith spatial count recipes survive export");
        // Exercise signed ratios, offset defaults and arbitrary thresholds even
        // when a provider/geometry omission excludes that particular vanilla feature.
        for (double level : new double[]{-.8, -.02, .02, .35}) {
            var op = new JsonObject(); op.addProperty("type", "noise_threshold_count");
            op.addProperty("noise_level", level); op.addProperty("below_noise", 5); op.addProperty("above_noise", 11);
            add(recipes, rules, op);
        }
        for (int ratio : new int[]{-9, -1, 0, 1, 20, 160}) for (double factor : new double[]{30, 80, 200, 400, 900}) {
            var op = new JsonObject(); op.addProperty("type", "noise_based_count");
            op.addProperty("noise_to_count_ratio", ratio); op.addProperty("noise_factor", factor);
            if (ratio != 1) op.addProperty("noise_offset", .3);
            add(recipes, rules, op);
        }
        var nativeTerrain = NativeTerrain.instance();
        int id = nativeTerrain.registerProfile(json.toString());
        var request = new TerrainRequest(123456789L, -32, -32, -64, 384, 64, 48, .008F, id);
        int n = rules.size() * 256;
        int[] points = new int[n * 4]; var random = new Random(931717L);
        for (int i = 0; i < n; i++) {
            var rule = rules.get(i / 256);
            points[i * 4] = random.nextInt(8193) - 4096; points[i * 4 + 1] = random.nextInt(8193) - 4096;
            points[i * 4 + 2] = rule.recipe; points[i * 4 + 3] = rule.op;
        }
        var counts = nativeTerrain.decorationCounts(request, points);
        int nearBoundary = 0, positive = 0, zero = 0;
        for (int i = 0; i < n; i++) {
            var op = rules.get(i / 256).data;
            double factor = op.has("noise_factor") ? op.get("noise_factor").getAsDouble() : 200;
            double noise = Biome.BIOME_INFO_NOISE.get(points[i * 4] / factor, points[i * 4 + 1] / factor);
            int expected; double margin, tolerance;
            double coordinate = Math.max(Math.abs(points[i * 4] / factor), Math.abs(points[i * 4 + 1] / factor));
            double error = 32 * Math.ulp((float) coordinate) + .000005;
            if (op.get("type").getAsString().equals("noise_threshold_count")) {
                double level = op.get("noise_level").getAsDouble();
                expected = op.get(noise < level ? "below_noise" : "above_noise").getAsInt();
                margin = Math.abs(noise - level); tolerance = error;
            } else {
                double offset = op.has("noise_offset") ? op.get("noise_offset").getAsDouble() : 0;
                int ratio = op.get("noise_to_count_ratio").getAsInt();
                double value = (noise + offset) * ratio;
                expected = Math.max(0, (int) Math.ceil(value));
                margin = Math.abs(value - Math.rint(value)); tolerance = (error + Math.ulp((float) offset)) * Math.abs(ratio);
            }
            if (counts[i] != expected) {
                require(margin <= tolerance, "registered GPU count differs beyond f32 boundary tolerance at " + i + ": " + counts[i] + " vs " + expected + "/" + op);
                nearBoundary++;
            }
            if (counts[i] > 0) positive++; else zero++;
        }
        require(positive > n / 8 && zero > n / 8, "spatial counts contain populated and empty patches");
        int[] small = Arrays.copyOfRange(points, points.length - 28, points.length);
        var before = nativeTerrain.gpuDiagnostics(id);
        require(Arrays.equals(Arrays.copyOfRange(counts, counts.length - 7, counts.length), nativeTerrain.decorationCounts(request, small)), "small reused GPU buffer ignores old active points");
        var after = nativeTerrain.gpuDiagnostics(id);
        require(after.uploadBytes() - before.uploadBytes() == small.length * 4L, "permutation and rules stay resident across repeated sparse batches");
        require(after.readbackBytes() - before.readbackBytes() <= 7 * 4L + 16, "readback contains integer counts and optional timestamps only");
        var timings = nativeTerrain.timings(id);
        if (timings.gpuMeasured()) require(timings.nanos(NativeTimings.FEATURE_COUNTS) > 0, "feature count device timings cross the C ABI");
        require(nativeTerrain.decorationCounts(request, new int[0]).length == 0, "empty count batch completes");
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 8; i++) {
                final long seed = i;
                jobs.add(() -> Arrays.equals(counts, nativeTerrain.decorationCounts(new TerrainRequest(seed, 31, -33, -64, 384, 64, 48, .008F, id), points)));
            }
            for (var result : workers.invokeAll(jobs)) require(result.get(), "parallel/request-order count results are stable and placement noise is world-seed independent");
        }
        System.out.println("QA_EVT {\"event\":\"registered_gpu_feature_counts\",\"status\":\"pass\",\"context\":{\"datapack\":" + datapack + ",\"registered_based\":" + based + ",\"registered_threshold\":" + threshold + ",\"queries\":" + n + ",\"f32_boundaries\":" + nearBoundary + "}}");
    }
    private static void add(JsonArray recipes, List<Rule> rules, JsonObject op) {
        var recipe = JsonParser.parseString("{\"source\":\"test:counts\",\"salt\":0,\"density\":1,\"low_density\":1,\"noise_count\":false,\"rarity\":1,\"tries\":1,\"spread\":[0,0,0],\"kind\":\"plant\",\"states\":[{\"lower\":0,\"upper\":0,\"weight\":1,\"band\":0,\"dry\":false}]}").getAsJsonObject();
        var program = new JsonArray(); program.add(op); recipe.add("placement", program);
        rules.add(new Rule(recipes.size(), 0, op)); recipes.add(recipe);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
