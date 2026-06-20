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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinTask;
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

        // 2. Split: tooltip prefix stays on the calling thread (its mod-code may assume main thread).
        //    Everything else fans out to the worker pool, running concurrently with tooltip work.
        List<PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> tooltipPrefixes = new ArrayList<>();
        List<PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> otherPrefixes = new ArrayList<>();
        for (Map.Entry<PrefixInfo<IListElementInfo<?>, IListElement<?>>,
                       PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> e
                : this.prefixedSearchables.entrySet()) {
            PrefixedSearchable<IListElementInfo<?>, IListElement<?>> p = e.getValue();
            if (p.getMode() == SearchMode.DISABLED) continue;
            (jeiopt$isTooltipPrefix(e.getKey(), p) ? tooltipPrefixes : otherPrefixes).add(p);
        }
        int prefixCount = tooltipPrefixes.size() + otherPrefixes.size();

        ForkJoinTask<?> bg = WorkerPool.get().submit(() ->
                otherPrefixes.parallelStream().forEach(p -> jeiopt$fillPrefix(p, infos, mode))
        );

        for (PrefixedSearchable<IListElementInfo<?>, IListElement<?>> p : tooltipPrefixes) {
            jeiopt$fillPrefix(p, infos, Config.Mode.PARALLEL_PREFIX);
        }

        try {
            bg.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("JEIOptimizer interrupted during filter build", ie);
        } catch (ExecutionException ee) {
            Jeioptimizer.LOGGER.error("[JEIOptimizer] Parallel addAll failed — falling back to sequential", ee.getCause());
            jeiopt$sequentialFallback(infos, otherPrefixes);
        }

        if (Config.LOG_TIMING_ENABLED) {
            long now = System.nanoTime();
            Jeioptimizer.LOGGER.info(
                    "[JEIOptimizer] addAll done — {} infos × {} prefixes in {} ms (allElements: {} ms, parallel build: {} ms, mode={})",
                    infos.size(),
                    prefixCount,
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

        if (mode == Config.Mode.PARALLEL_FULL) {
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
                        "[JEIOptimizer] prefix.getStrings threw — skipping ingredient. error={}", t.toString());
            }
            return java.util.Collections.emptyList();
        }
    }

    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean jeiopt$prefixesLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * JEI tags each prefix with a single char ('#' tooltip, '@' modId, '$' tag, ...).
     * We use that as the source of truth and fall back to class-name only if reflection fails.
     */
    @Unique
    private static boolean jeiopt$isTooltipPrefix(PrefixInfo<?, ?> info, PrefixedSearchable<?, ?> prefix) {
        Character ch = jeiopt$prefixChar(info);
        if (Config.LOG_TIMING_ENABLED && jeiopt$prefixesLogged.compareAndSet(false, true)) {
            Jeioptimizer.LOGGER.info(
                    "[JEIOptimizer] prefix detected: char={}, infoClass={}, prefixClass={}",
                    ch, info.getClass().getName(), prefix.getClass().getName());
        }
        if (ch != null) return ch == '#';

        String cls = info.getClass().getName() + "|" + prefix.getClass().getName();
        return cls.toLowerCase(java.util.Locale.ROOT).contains("tooltip");
    }

    @Unique
    private static Character jeiopt$prefixChar(PrefixInfo<?, ?> info) {
        try {
            java.lang.reflect.Method m = info.getClass().getMethod("getPrefix");
            Object r = m.invoke(info);
            if (r instanceof Character) return (Character) r;
        } catch (Throwable ignored) {}
        try {
            for (java.lang.reflect.Field f : info.getClass().getDeclaredFields()) {
                if (f.getType() == char.class || f.getType() == Character.class) {
                    f.setAccessible(true);
                    Object v = f.get(info);
                    if (v instanceof Character) return (Character) v;
                }
            }
        } catch (Throwable ignored) {}
        return null;
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
        return helper.getUid(typed.getIngredient(), UidContext.Ingredient);
    }
}
