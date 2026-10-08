package art.arcane.retina.worldgen;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs without Minecraft or the native library; Python/Bend independently decode the files. */
public final class BendProfileWireTest {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        String fixture = """
            {"null":null,"false":false,"true":true,"empty":[],"object":{},
             "text":"NUL\\u0000 \\ud83c\\udf0d \\ud800", "long":[-9223372036854775808,9223372036854775807],
             "int":[-2147483648,2147483647,0,-1],"real":[-0.0,1.25,1e-300,1e300],
             "flags":[true,false,true],"mixed":[1,2.0,"hello"],"nested":{"x":[{"y":42}]}}
            """;
        Files.writeString(output.resolve("fixture.json"), fixture);
        encode(output, "fixture", fixture);
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--worker")) { worker(output.resolve("fixture.rbp"), Path.of(args[++i])); continue; }
            try (var source = Files.newBufferedReader(Path.of(args[i]))) {
                System.out.println("profile-" + i + " " + BendProfileWire.write(output.resolve("profile-" + i + ".rbp"), source));
            }
        }
        for (String bad : new String[]{"[9223372036854775808]", "[1e400]", "{\"x\":1,\"x\":2}", "{}{}", "[".repeat(130) + "0" + "]".repeat(130)}) {
            try { BendProfileWire.write(output.resolve("invalid.rbp"), bad); throw new AssertionError("invalid profile accepted"); }
            catch (IllegalArgumentException | java.io.IOException expected) { }
        }
    }
    private static void worker(Path profile, Path executable) throws Exception {
        for (var mode : BendWorker.Execution.values()) try (var worker = new BendWorker(executable, mode, 2)) {
            long pid = worker.processId();
            if (worker.loadProfile(profile).get(30, java.util.concurrent.TimeUnit.SECONDS).length != 16)
                throw new AssertionError("profile acknowledgement");
            var requests = new java.util.ArrayList<java.util.concurrent.CompletableFuture<byte[]>>();
            for (int i = 0; i < 12; i++) requests.add(worker.request(6, new byte[4]));
            for (var request : requests) {
                var root = java.nio.ByteBuffer.wrap(request.get(10, java.util.concurrent.TimeUnit.SECONDS));
                if (root.getInt() != 7 || root.getInt() != 12) throw new AssertionError("resident registry root");
            }
            if (worker.processId() != pid) throw new AssertionError("worker replaced");
            System.out.println("Java registry worker " + mode + " pass");
        }
    }
    private static void encode(Path output, String name, String source) throws Exception {
        var summary = BendProfileWire.write(output.resolve(name + ".rbp"), source);
        if (Files.size(output.resolve(name + ".rbp")) != summary.bytes()) throw new AssertionError("wire byte count");
        System.out.println(name + " " + summary);
    }
}
