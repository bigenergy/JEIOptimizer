package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.Config;
import com.piglinmine.jeioptimizer.Jeioptimizer;
import com.piglinmine.jeioptimizer.WorkerPool;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.search.ISearchStorageBuilder;
import mezz.jei.core.search.CombinedSearchables;
import mezz.jei.core.search.PrefixInfo;
import mezz.jei.core.search.PrefixedSearchable;
import mezz.jei.core.search.SearchMode;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearch;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinTask;

/**
 * JEI 30.x builds every prefix storage inside the {@link ElementSearch} constructor.
 * Each prefix owns an independent {@link ISearchStorageBuilder}, so we skip the vanilla
 * loop and rebuild it across the worker pool instead.
 */
@Mixin(value = ElementSearch.class, remap = false)
public abstract class ElementSearchMixin {

    @Unique
    private static final char JEIOPT$TOOLTIP_PREFIX = '#';

    @Shadow @Final
    private Map<PrefixInfo<IListElementInfo<?>, IListElement<?>>,
            PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> prefixedSearchables;

    @Shadow @Final
    private CombinedSearchables<IListElement<?>> combinedSearchables;

    @Unique
    private boolean jeiopt$hijacked;

    /**
     * Feed the vanilla per-prefix loop an empty collection so it does nothing;
     * we rebuild it in {@link #jeiopt$parallelBuild}. The cheap allElements loop above it
     * is untouched.
     */
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lmezz/jei/gui/search/ElementPrefixParser;allPrefixInfos()Ljava/util/Collection;"
            )
    )
    private Collection<PrefixInfo<IListElementInfo<?>, IListElement<?>>> jeiopt$skipVanillaLoop(
            ElementPrefixParser parser) {
        Config.Mode mode = Config.MODE;
        if (mode == Config.Mode.OFF || mode == Config.Mode.BATCH) {
            return parser.allPrefixInfos();
        }
        this.jeiopt$hijacked = true;
        return List.of();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void jeiopt$parallelBuild(
            ElementPrefixParser parser,
            Collection<IListElementInfo<?>> infos,
            IIngredientManager ingredientManager,
            CallbackInfo ci) {

        if (!this.jeiopt$hijacked) return;

        Config.Mode mode = Config.MODE;
        long t0 = System.nanoTime();

        // Tooltip prefix fires ItemTooltipEvent -> arbitrary mod code that may need the main thread.
        List<PrefixInfo<IListElementInfo<?>, IListElement<?>>> all = new ArrayList<>(parser.allPrefixInfos());
        List<PrefixInfo<IListElementInfo<?>, IListElement<?>>> tooltip = new ArrayList<>();
        List<PrefixInfo<IListElementInfo<?>, IListElement<?>>> other = new ArrayList<>();
        for (PrefixInfo<IListElementInfo<?>, IListElement<?>> info : all) {
            (info.getPrefix() == JEIOPT$TOOLTIP_PREFIX ? tooltip : other).add(info);
        }

        Map<PrefixInfo<IListElementInfo<?>, IListElement<?>>,
                PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> built = new ConcurrentHashMap<>();

        ForkJoinTask<?> bg = WorkerPool.get().submit(() ->
                other.parallelStream().forEach(info -> built.put(info, jeiopt$buildPrefix(info, infos, mode)))
        );

        // Tooltip prefix runs on the calling thread, concurrently with the pool.
        for (PrefixInfo<IListElementInfo<?>, IListElement<?>> info : tooltip) {
            built.put(info, jeiopt$buildPrefix(info, infos, Config.Mode.PARALLEL_PREFIX));
        }

        try {
            bg.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("JEIOptimizer interrupted during filter build", ie);
        } catch (ExecutionException ee) {
            Jeioptimizer.LOGGER.error(
                    "[JEIOptimizer] Parallel prefix build failed — rebuilding those prefixes sequentially",
                    ee.getCause());
        }

        // Insert in the parser's own order so search behaviour matches vanilla.
        for (PrefixInfo<IListElementInfo<?>, IListElement<?>> info : all) {
            PrefixedSearchable<IListElementInfo<?>, IListElement<?>> searchable = built.get(info);
            if (searchable == null) {
                searchable = jeiopt$buildPrefix(info, infos, Config.Mode.PARALLEL_PREFIX);
            }
            this.prefixedSearchables.put(info, searchable);
            this.combinedSearchables.addSearchable(searchable);
        }

        if (Config.LOG_TIMING_ENABLED) {
            Jeioptimizer.LOGGER.info(
                    "[JEIOptimizer] ElementSearch built — {} infos × {} prefixes in {} ms (mode={})",
                    infos.size(), all.size(), (System.nanoTime() - t0) / 1_000_000, mode);
        }
    }

    @Unique
    private static PrefixedSearchable<IListElementInfo<?>, IListElement<?>> jeiopt$buildPrefix(
            PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
            Collection<IListElementInfo<?>> infos,
            Config.Mode mode) {

        ISearchStorageBuilder<IListElement<?>> builder = prefixInfo.createStorageBuilder();

        if (prefixInfo.getMode() != SearchMode.DISABLED) {
            if (mode == Config.Mode.PARALLEL_FULL) {
                // Tokenize in parallel; builder.put stays sequential (storage is not thread-safe).
                List<Map.Entry<IListElement<?>, Collection<String>>> tokens = infos.parallelStream()
                        .<Map.Entry<IListElement<?>, Collection<String>>>map(info -> new AbstractMap.SimpleEntry<>(
                                info.getElement(), jeiopt$safeGetStrings(prefixInfo, info)))
                        .toList();
                for (Map.Entry<IListElement<?>, Collection<String>> e : tokens) {
                    for (String s : e.getValue()) jeiopt$putIfNotBlank(builder, s, e.getKey());
                }
            } else {
                for (IListElementInfo<?> info : infos) {
                    IListElement<?> element = info.getElement();
                    for (String s : jeiopt$safeGetStrings(prefixInfo, info)) {
                        jeiopt$putIfNotBlank(builder, s, element);
                    }
                }
            }
        }

        return new PrefixedSearchable<>(builder.build(), prefixInfo);
    }

    @Unique
    private static void jeiopt$putIfNotBlank(
            ISearchStorageBuilder<IListElement<?>> builder, String string, IListElement<?> element) {
        String trimmed = string.trim();
        if (!trimmed.isEmpty()) {
            builder.put(trimmed, element);
        }
    }

    @Unique
    private static Collection<String> jeiopt$safeGetStrings(
            PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
            IListElementInfo<?> info) {
        try {
            return prefixInfo.getStrings(info);
        } catch (Throwable t) {
            if (Config.LOG_TIMING_ENABLED) {
                Jeioptimizer.LOGGER.debug(
                        "[JEIOptimizer] prefixInfo.getStrings threw — skipping ingredient. error={}", t.toString());
            }
            return List.of();
        }
    }
}
