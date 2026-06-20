package com.piglinmine.jeioptimizer;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/** JEIOptimizer config (Forge 1.19.2). Tier 1 only — Tier A/B are not ported. */
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

    private static final ForgeConfigSpec.BooleanValue ASYNC_FILTER_BUILD = BUILDER
            .comment("Build IngredientFilter contents in background — player enters world immediately,",
                    "JEI search becomes available 1-2 sec later. If a player opens JEI before then,",
                    "they see an empty grid until ready. Default false.")
            .define("filter.async", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static Mode MODE = Mode.PARALLEL_FULL;
    public static int WORKERS = 0;
    public static boolean LOG_TIMING_ENABLED = true;
    public static boolean ASYNC_BUILD = false;

    public static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        MODE = MODE_VALUE.get();
        WORKERS = WORKER_COUNT.get();
        LOG_TIMING_ENABLED = LOG_TIMING.get();
        ASYNC_BUILD = ASYNC_FILTER_BUILD.get();
    }

    public static boolean enabled() {
        return MODE != Mode.OFF;
    }

    public static int effectiveWorkers() {
        if (WORKERS > 0) return WORKERS;
        return Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    }
}
