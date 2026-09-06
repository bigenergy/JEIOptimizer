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

    // --- Tier D: skip JEI's synthetic anvil/grindstone recipes ---
    // These CHANGE WHAT YOU SEE IN JEI, so both default to false.
    private static final ForgeConfigSpec.BooleanValue SKIP_ENCHANTMENT_RECIPES = BUILDER
            .comment("Skip JEI's generated book-enchanting (anvil) and disenchanting (grindstone) entries.",
                    "JEI builds one entry per enchantable item x enchantment x level, so the count",
                    "explodes on packs with many enchantments and costs seconds of startup plus",
                    "permanent memory.",
                    "TRADE-OFF: those entries disappear from JEI. Real recipes are untouched.",
                    "Default: false.")
            .define("recipes.skip_generated_enchantment_recipes", false);

    private static final ForgeConfigSpec.BooleanValue SKIP_REPAIR_RECIPES = BUILDER
            .comment("Skip JEI's generated anvil and grindstone REPAIR entries.",
                    "The grindstone half walks every damageable item in the pack, so it grows with",
                    "the pack size.",
                    "TRADE-OFF: those entries disappear from JEI. Real recipes are untouched.",
                    "Default: false.")
            .define("recipes.skip_generated_repair_recipes", false);

    private static final ForgeConfigSpec.BooleanValue SKIP_REDUNDANT_MENU_UPDATES_VALUE = BUILDER
            .comment("JEI builds its anvil and grindstone entries by driving a hidden menu once per",
                    "combination. Writing each input slot triggers a full recipe recalculation, and",
                    "only the last one can see both inputs. Skip the redundant ones and recalculate",
                    "once, after both slots are set.",
                    "Transparent: the values JEI reads are identical. Default: true.")
            .define("recipes.skip_redundant_menu_updates", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static Mode MODE = Mode.PARALLEL_FULL;
    public static int WORKERS = 0;
    public static boolean LOG_TIMING_ENABLED = true;
    public static Set<String> PARALLEL_PHASES = ConcurrentHashMap.newKeySet();
    public static boolean PARALLEL_TABS = false;
    public static boolean SKIP_ENCHANT_RECIPES = false;
    public static boolean SKIP_REPAIR = false;
    public static boolean SKIP_REDUNDANT_MENU_UPDATES = true;

    public static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        MODE = MODE_VALUE.get();
        WORKERS = WORKER_COUNT.get();
        LOG_TIMING_ENABLED = LOG_TIMING.get();
        PARALLEL_PHASES.clear();
        PARALLEL_PHASES.addAll(PARALLEL_PLUGIN_PHASES.get().stream().map(String::valueOf).toList());
        PARALLEL_TABS = PARALLEL_CREATIVE_TABS.get();
        SKIP_ENCHANT_RECIPES = SKIP_ENCHANTMENT_RECIPES.get();
        SKIP_REPAIR = SKIP_REPAIR_RECIPES.get();
        SKIP_REDUNDANT_MENU_UPDATES = SKIP_REDUNDANT_MENU_UPDATES_VALUE.get();
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
