package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.Config;
import com.piglinmine.jeioptimizer.Jeioptimizer;
import com.piglinmine.jeioptimizer.WorkerPool;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.core.search.ISearchStorage;
import mezz.jei.core.search.PrefixInfo;
import mezz.jei.core.search.PrefixedSearchable;
import mezz.jei.core.search.SearchMode;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementSearch;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/** Parallel {@link ElementSearch#addAll}: each prefix owns its own storage, so we fan out across them. */
@Mixin(value = ElementSearch.class, remap = false)
public abstract class ElementSearchMixin {

    @Shadow @Final
    private Map<PrefixInfo<IListElementInfo<?>, IListElement<?>>,
                PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> prefixedSearchables;

    @Shadow @Final
    private Map<Object, IListElement<?>> allElements;

    // Pool lives in a shared WorkerPool — reused by all tiers.

    @Inject(method = "addAll", at = @At("HEAD"), cancellable = true)
    private void jeiopt$parallelAddAll(
            Collection<IListElementInfo<?>> infos,
            IIngredientManager ingredientManager,
            CallbackInfo ci) {

        Config.Mode mode = Config.MODE;
        if (mode == Config.Mode.OFF || mode == Config.Mode.BATCH) {
            // BATCH alone is already a win over per-item — hand off to the original
            return;
        }
        ci.cancel();

        long t0 = System.nanoTime();

        // 1. allElements — sequential, cheap (HashMap.put × N).
        for (IListElementInfo<?> info : infos) {
            Object uid = jeiopt$uid(info.getTypedIngredient(), ingredientManager);
            this.allElements.put(uid, info.getElement());
        }

        long tAfterAll = System.nanoTime();

        // 2. Per-prefix in parallel. Each prefix writes to its OWN storage.
        Collection<PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> prefixes =
                this.prefixedSearchables.values();

        try {
            WorkerPool.get().submit(() ->
                    prefixes.parallelStream()
                            .filter(p -> p.getMode() != SearchMode.DISABLED)
                            .forEach(p -> jeiopt$fillPrefix(p, infos, mode))
            ).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("JEIOptimizer interrupted during filter build", ie);
        } catch (ExecutionException ee) {
            Jeioptimizer.LOGGER.error("[JEIOptimizer] Parallel addAll failed — falling back to sequential", ee.getCause());
            jeiopt$sequentialFallback(infos, prefixes);
        }

        if (Config.LOG_TIMING_ENABLED) {
            long now = System.nanoTime();
            Jeioptimizer.LOGGER.info(
                    "[JEIOptimizer] addAll done — {} infos × {} prefixes in {} ms (allElements: {} ms, parallel build: {} ms, mode={})",
                    infos.size(),
                    prefixes.size(),
                    (now - t0) / 1_000_000,
                    (tAfterAll - t0) / 1_000_000,
                    (now - tAfterAll) / 1_000_000,
                    mode
            );
        }
    }

    @Unique
    private static void jeiopt$fillPrefix(
            PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefix,
            Collection<IListElementInfo<?>> infos,
            Config.Mode mode) {

        ISearchStorage<IListElement<?>> storage = prefix.getSearchStorage();

        // Tooltip prefix fires ItemTooltipEvent → arbitrary mod code that may need the main thread. Keep sequential.
        final boolean isTooltipPrefix = jeiopt$isTooltipPrefix(prefix);
        final Config.Mode effective = isTooltipPrefix ? Config.Mode.PARALLEL_PREFIX : mode;

        if (effective == Config.Mode.PARALLEL_FULL) {
            // Tokenize in parallel; storage.put stays sequential (suffix tree is not thread-safe).
            List<Map.Entry<IListElement<?>, Collection<String>>> tokens = infos.parallelStream()
                    .map(info -> new AbstractMap.SimpleEntry<IListElement<?>, Collection<String>>(
                            info.getElement(), jeiopt$safeGetStrings(prefix, info)))
                    .collect(Collectors.toList());
            for (Map.Entry<IListElement<?>, Collection<String>> e : tokens) {
                IListElement<?> el = e.getKey();
                for (String s : e.getValue()) storage.put(s, el);
            }
            return;
        }

        // PARALLEL_PREFIX (or tooltip downgrade): tokenize + put in this thread.
        for (IListElementInfo<?> info : infos) {
            Collection<String> strings = jeiopt$safeGetStrings(prefix, info);
            IListElement<?> el = info.getElement();
            for (String s : strings) storage.put(s, el);
        }
    }

    @Unique
    private static Collection<String> jeiopt$safeGetStrings(
            PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefix,
            IListElementInfo<?> info) {
        try {
            return prefix.getStrings(info);
        } catch (Throwable t) {
            if (Config.LOG_TIMING_ENABLED) {
                Jeioptimizer.LOGGER.debug(
                        "[JEIOptimizer] prefix.getStrings threw — skipping ingredient. prefix={}, error={}",
                        prefix.getMode(), t.toString());
            }
            return java.util.Collections.emptyList();
        }
    }

    @Unique
    private static boolean jeiopt$isTooltipPrefix(PrefixedSearchable<?, ?> prefix) {
        try {
            String cls = prefix.getClass().getName();
            if (cls.toLowerCase(java.util.Locale.ROOT).contains("tooltip")) return true;
            String modeStr = String.valueOf(prefix.getMode());
            return modeStr.toLowerCase(java.util.Locale.ROOT).contains("tooltip");
        } catch (Throwable t) {
            return false;
        }
    }

    @Unique
    private void jeiopt$sequentialFallback(
            Collection<IListElementInfo<?>> infos,
            Collection<PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> prefixes) {
        for (PrefixedSearchable<IListElementInfo<?>, IListElement<?>> p : prefixes) {
            if (p.getMode() == SearchMode.DISABLED) continue;
            ISearchStorage<IListElement<?>> storage = p.getSearchStorage();
            for (IListElementInfo<?> info : infos) {
                Collection<String> strings = jeiopt$safeGetStrings(p, info);
                IListElement<?> el = info.getElement();
                for (String s : strings) storage.put(s, el);
            }
        }
    }

    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object jeiopt$uid(ITypedIngredient<?> typed, IIngredientManager mgr) {
        IIngredientHelper helper = mgr.getIngredientHelper(typed.getType());
        return helper.getUniqueId(typed.getIngredient(), UidContext.Ingredient);
    }
}
