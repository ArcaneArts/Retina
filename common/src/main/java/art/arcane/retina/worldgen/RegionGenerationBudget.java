package art.arcane.retina.worldgen;

import art.arcane.retina.config.RetinaConfiguration;
import art.arcane.retina.core.NativeRegionBudget;

/** Uses the configured region budget for all current-version generation paths. */
final class RegionGenerationBudget extends NativeRegionBudget {
    static final RegionGenerationBudget SHARED = new RegionGenerationBudget();

    RegionGenerationBudget() { this(RetinaConfiguration.get().regionParallelism()); }

    RegionGenerationBudget(int limit) { super(limit); }
}
