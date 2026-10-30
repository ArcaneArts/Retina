package art.arcane.retina.core;

import java.nio.file.Path;
import java.util.*;

/** JDK-only parser checks; real packaged-native checks live in the process harness. */
public final class RetinaCliTest {
    public static void main(String[] args) {
        if (NativeLibrary.backendCode(" DX12 ") != 2
                || NativeLibrary.backendCode("Vulkan") != 1
                || NativeLibrary.backendCode("metal") != 3
                || NativeLibrary.backendCode("auto") != 0) throw new AssertionError("Backend selection");
        for (int allowed = 0; allowed < 8; allowed++) {
            if (NativeLibrary.backendSelection(allowed, null) != (0x100 | allowed)) throw new AssertionError("Allowed set");
            if (NativeLibrary.backendSelection(allowed, "auto") != (0x100 | allowed)) throw new AssertionError("Auto set");
            for (String backend : List.of("vulkan", "dx12", "metal")) {
                int bit = 1 << (NativeLibrary.backendCode(backend) - 1);
                if (NativeLibrary.backendSelection(allowed, backend) != (0x100 | (allowed & bit)))
                    throw new AssertionError("Override escaped allowed set");
            }
        }
        // Configuration itself must not extract/load natives (none are on this test classpath).
        NativeLibrary.allowGpuBackends(false, false, false);
        NativeLibrary.allowGpuBackends(true, true, true);
        bad(() -> NativeLibrary.backendCode("gles"));
        bad(() -> NativeLibrary.backendCode(""));
        equal(RetinaCli.bounds(Map.of("region-radius", "0")), new int[]{0,0,0,0});
        equal(RetinaCli.bounds(Map.of("region-min", "-2,1", "region-max", "0,3")), new int[]{-2,1,0,3});
        equal(RetinaCli.bounds(Map.of("region-min", "-67108864,-67108864", "region-max", "67108863,67108863")),
                new int[]{-67108864,-67108864,67108863,67108863});
        bad(() -> RetinaCli.bounds(Map.of("region-radius", "-1")));
        bad(() -> RetinaCli.bounds(Map.of("region-radius", "67108864")));
        bad(() -> RetinaCli.bounds(Map.of("region-min", "2,0", "region-max", "1,0")));
        bad(() -> RetinaCli.bounds(Map.of("region-min", "1", "region-max", "1,0")));
        bad(() -> RetinaCli.bounds(Map.of("region-radius", "1", "region-min", "0,0")));
        if (!RetinaCli.parse(new String[]{"generate", "--seed", "-7", "--region-min=-1,0"})
                .equals(Map.of("seed", "-7", "region-min", "-1,0"))) throw new AssertionError();
        bad(() -> RetinaCli.parse(new String[]{"generate", "--seed", "1", "--seed=2"}));
        bad(() -> RetinaCli.parse(new String[]{"generate", "--world", "--seed", "1"}));
        Path root=Path.of("world");
        if (!RetinaCli.regionDirectory(root,"minecraft:the_end").equals(root.resolve("DIM1/region"))) throw new AssertionError();
        if (!RetinaCli.regionDirectory(root,"pack:caves/lower").equals(root.resolve("dimensions/pack/caves/lower/region"))) throw new AssertionError();
        for (String key : List.of("x:../outside", "..:outside", "x:/absolute", "x:a//b", "x:a/./b", "x:a\\b", "x:", ":a"))
            bad(() -> RetinaCli.regionDirectory(root,key));
        System.out.println("CLI parser/range/dimension checks PASS");
    }
    private static void bad(Runnable call) {
        try { call.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid arguments");
    }
    private static void equal(int[] actual,int[] expected) {
        if (!Arrays.equals(actual,expected)) throw new AssertionError(Arrays.toString(actual));
    }
}
