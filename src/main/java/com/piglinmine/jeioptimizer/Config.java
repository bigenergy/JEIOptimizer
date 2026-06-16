package com.piglinmine.jeioptimizer;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JEIOptimizer config (Forge 1.20.1).
 * <p>
 * Main switch — {@link Mode}. Default = PARALLEL_FULL.
 * Risky tiers (parallel_phases / parallel_creative_tabs) are off by default —
 * in our 1.21.1 testing they crashed mods and/or lost items.
 */
public class Config {

    public enum Mode {
        OFF, BATCH, PARALLEL_PREFIX, PARALLEL_FULL
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.EnumValue<Mode> MODE_VALUE = BUILDER
            .comment("Strategy for accelerating JEI ingredient filter build.",
                    "  OFF              — vanilla JEI",
                    "  BATCH            — pack per-item calls into one addAll (~10-15% faster, safe)",
                    "  PARALLEL_PREFIX  — build each search prefix on its own thread (safe)",
                    "  PARALLEL_FULL    — also parallelize tokenization inside each prefix (best)",
                    "Default: PARALLEL_FULL")
            .defineEnum("filter.mode", Mode.PARALLEL_FULL);

    private static final ForgeConfigSpec.IntValue WORKER_COUNT = BUILDER
            .comment("Number of worker threads. 0 = auto (cores - 2, min 2).")
            .defineInRange("worker_count", 0, 0, 64);

    private static final ForgeConfigSpec.BooleanValue LOG_TIMING = BUILDER
            .comment("Log how long each acceleration phase took.")
            .define("log_timing", true);

    // EXPERIMENTAL — disabled by default
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> PARALLEL_PLUGIN_PHASES = BUILDER
            .comment("EXPERIMENTAL. Phase names of PluginCaller.callOnPlugins to run in parallel.",
                    "Each plugin's handler runs concurrently. Mods NOT thread-safe in their JEI plugins",
                    "WILL crash here (we saw theurgy, ars_nouveau, compactmachines crash in our 1.21.1 test).",
                    "Empty list = disabled (DEFAULT, recommended).",
                    "Try: [\"Registering recipes\"] only if you've verified your mod set is safe.")
            .defineList("plugins.parallel_phases",
                    List.of(),
                    obj -> obj instanceof String);

    private static final ForgeConfigSpec.BooleanValue PARALLEL_CREATIVE_TABS = BUILDER
            .comment("EXPERIMENTAL. Build CreativeModeTab contents in parallel.",
                    "In our 1.21.1 test pack this LOST 22% of JEI items because some mods' tab builders",
                    "race on internal state. Default OFF (safe). Enable only if your mod set is verified.")
            .define("plugins.parallel_creative_tabs", false);

    private static final ForgeConfigSpec.BooleanValue ASYNC_FILTER_BUILD = BUILDER
            .comment("Build IngredientFilter contents in background — player enters world immediately,",
                    "JEI search becomes available 1-2 sec later. If a player opens JEI before then,",
                    "they see an empty grid until ready. Default false.")
            .define("filter.async", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static Mode MODE = Mode.PARALLEL_FULL;
    public static int WORKERS = 0;
    public static boolean LOG_TIMING_ENABLED = true;
    public static Set<String> PARALLEL_PHASES = ConcurrentHashMap.newKeySet();
    public static boolean PARALLEL_TABS = false;
    public static boolean ASYNC_BUILD = false;

    public static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        MODE = MODE_VALUE.get();
        WORKERS = WORKER_COUNT.get();
        LOG_TIMING_ENABLED = LOG_TIMING.get();
        PARALLEL_PHASES.clear();
        PARALLEL_PHASES.addAll(PARALLEL_PLUGIN_PHASES.get().stream().map(String::valueOf).toList());
        PARALLEL_TABS = PARALLEL_CREATIVE_TABS.get();
        ASYNC_BUILD = ASYNC_FILTER_BUILD.get();
    }

    public static boolean enabled() {
        return MODE != Mode.OFF;
    }

    public static int effectiveWorkers() {
        if (WORKERS > 0) return WORKERS;
        return Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    }

    public static boolean shouldParallelizePhase(String phase) {
        return PARALLEL_PHASES.contains(phase);
    }
}
