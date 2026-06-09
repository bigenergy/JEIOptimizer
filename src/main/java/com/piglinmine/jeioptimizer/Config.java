package com.piglinmine.jeioptimizer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Конфиг JEIOptimizer.
 *
 * <h3>Tier 1 — filter build (готов с v1.0)</h3>
 * {@link Mode} — параллельный {@code ElementSearch.addAll}. Дефолт PARALLEL_PREFIX.
 *
 * <h3>Tier A — Parallel Plugin Registration (v1.1)</h3>
 * {@code parallel_plugin_phases} — список фаз PluginCaller.callOnPlugins,
 * которые крутить параллельно. По дефолту только "Registering recipes" —
 * самый жирный (~5 сек последовательно).
 *
 * <h3>Tier B — Parallel Creative Tabs (v1.1)</h3>
 * {@code parallel_creative_tabs} — параллельное построение CreativeModeTab'ов
 * внутри VanillaPlugin.registerIngredients. Дефолт включён. Жирный профит
 * (~3 сек), но риск зависит от того насколько thread-safe мод-табы.
 *
 * <h3>Tier C — Async Filter Build (v1.1)</h3>
 * {@code async_filter_build} — IngredientFilter строится в фоне после конструктора.
 * Игрок попадает в мир сразу, JEI поиск становится доступен через пару секунд.
 * Дефолт false (психологически странно если игрок открывает JEI и видит пусто).
 */
@EventBusSubscriber(modid = Jeioptimizer.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Config {

    public enum Mode {
        OFF, BATCH, PARALLEL_PREFIX, PARALLEL_FULL
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // --- Tier 1: filter build ---
    private static final ModConfigSpec.EnumValue<Mode> MODE_VALUE = BUILDER
            .comment("Strategy for accelerating JEI ingredient filter build.",
                    "  OFF              — vanilla JEI",
                    "  BATCH            — pack per-item calls into one addAll (~10-15% faster, safe)",
                    "  PARALLEL_PREFIX  — build each search prefix on its own thread (safe)",
                    "  PARALLEL_FULL    — also parallelize tokenization inside each prefix (best)",
                    "Default: PARALLEL_FULL")
            .defineEnum("filter.mode", Mode.PARALLEL_FULL);

    private static final ModConfigSpec.IntValue WORKER_COUNT = BUILDER
            .comment("Number of worker threads. 0 = auto (cores - 2, min 2).")
            .defineInRange("worker_count", 0, 0, 64);

    private static final ModConfigSpec.BooleanValue LOG_TIMING = BUILDER
            .comment("Log how long each acceleration phase took.")
            .define("log_timing", true);

    // --- Tier A: parallel plugin registration ---
    // EXPERIMENTAL: many JEI plugins are not thread-safe. In testing, theurgy/ars_nouveau/etc
    // crashed when their registerRecipes() was called concurrently. Default — empty (disabled).
    // Если хочешь попробовать — добавь "Registering recipes" в список, тестируй на чистом моде.
    private static final ModConfigSpec.ConfigValue<List<? extends String>> PARALLEL_PLUGIN_PHASES = BUILDER
            .comment("EXPERIMENTAL. Phase names of PluginCaller.callOnPlugins to run in parallel.",
                    "Each plugin's handler runs concurrently. Mods NOT thread-safe in their JEI plugins",
                    "WILL crash here (we saw theurgy, ars_nouveau, compactmachines crash in our test).",
                    "Empty list = disabled (DEFAULT, recommended).",
                    "Try: [\"Registering recipes\"] only if you've verified your mod set is safe.")
            .defineList("plugins.parallel_phases",
                    List.of(),
                    () -> "Registering recipes",
                    obj -> obj instanceof String);

    // --- Tier B: parallel creative tabs ---
    // EXPERIMENTAL: many mods' CreativeModeTab.buildContents() is not thread-safe (shared mod state,
    // lazy caches, etc). In our test 22% of JEI items were lost in parallel mode. Default OFF.
    private static final ModConfigSpec.BooleanValue PARALLEL_CREATIVE_TABS = BUILDER
            .comment("EXPERIMENTAL. Build CreativeModeTab contents in parallel.",
                    "In our test pack this LOST 22% of JEI items because some mods' tab builders",
                    "race on internal state. Default OFF (safe). Enable only if your mod set is verified.")
            .define("plugins.parallel_creative_tabs", false);

    // --- Tier C: async filter build ---
    private static final ModConfigSpec.BooleanValue ASYNC_FILTER_BUILD = BUILDER
            .comment("Build IngredientFilter contents in background — player enters world immediately,",
                    "JEI search becomes available 1-2 sec later. If a player opens JEI before then,",
                    "they see an empty grid until ready. Psychologically faster, technically same.",
                    "Default: false (disabled).")
            .define("filter.async", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    // --- Public state (read by mixins) ---
    public static Mode MODE = Mode.PARALLEL_FULL;
    public static int WORKERS = 0;
    public static boolean LOG_TIMING_ENABLED = true;
    public static Set<String> PARALLEL_PHASES = ConcurrentHashMap.newKeySet();
    public static boolean PARALLEL_TABS = false;
    public static boolean ASYNC_BUILD = false;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
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

    public static boolean parallel() {
        return MODE == Mode.PARALLEL_PREFIX || MODE == Mode.PARALLEL_FULL;
    }

    public static int effectiveWorkers() {
        if (WORKERS > 0) return WORKERS;
        return Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    }

    public static boolean shouldParallelizePhase(String phase) {
        return PARALLEL_PHASES.contains(phase);
    }
}
