package art.arcane.retina.worldgen;

import art.arcane.retina.config.RetinaConfiguration;
import java.nio.file.Files;

/** Fresh JVM: configuration must reach the real shared budget before its first use. */
public final class ConfiguredRegionBudgetTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("retina-budget-test-");
        var file = directory.resolve("retina.toml");
        try {
            Files.writeString(file, "mca_parallelism = " + args[0] + "\n");
            RetinaConfiguration.initialize(directory);
            int expected = Integer.parseInt(args[1]);
            var budget = RegionGenerationBudget.SHARED;
            if (budget.limit() != expected || budget.admissionCapacity() != 2 * expected) {
                throw new AssertionError("Configured shared budget/admission capacity: " + budget.limit());
            }
            RegionAheadPumpTest.verifyBudget(budget);
            System.out.println("PASS: configured shared region budget " + args[0] + " resolves to " + expected);
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
    }
}
