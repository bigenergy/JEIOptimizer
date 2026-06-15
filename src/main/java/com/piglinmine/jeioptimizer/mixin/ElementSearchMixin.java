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

/**
 * Сердце оптимизации: параллельный {@link ElementSearch#addAll}.
 * <p>
 * Vanilla JEI делает это так: один поток в цикле проходит по 50k айтемов
 * × 10 префиксов = 500k операций {@code storage.put}. Это и даёт 12 секунд.
 * <p>
 * Ключевой инсайт: каждый префикс ({@code name}, {@code modId}, {@code tag},
 * {@code tooltip}, ...) имеет СВОЁ ОТДЕЛЬНОЕ хранилище ({@code GeneralizedSuffixTree}).
 * Между префиксами нет общего состояния → можно строить их параллельно
 * без синхронизации.
 * <p>
 * Что мы делаем:
 * <ul>
 *   <li>{@code allElements} (общий) — заполняем sequential, дёшево</li>
 *   <li>Per-prefix storages — {@code parallelStream} по префиксам, каждый
 *   поток наполняет своё хранилище полностью изолированно</li>
 * </ul>
 * При 6 рабочих потоках профит ≈ {@code min(workers, prefixCount)}.
 */
@Mixin(value = ElementSearch.class, remap = false)
public abstract class ElementSearchMixin {

    @Shadow @Final
    private Map<PrefixInfo<IListElementInfo<?>, IListElement<?>>,
                PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> prefixedSearchables;

    @Shadow @Final
    private Map<Object, IListElement<?>> allElements;

    // Пул вынесен в общий WorkerPool — переиспользуется всеми Tier'ами.

    @Inject(method = "addAll", at = @At("HEAD"), cancellable = true)
    private void jeiopt$parallelAddAll(
            Collection<IListElementInfo<?>> infos,
            IIngredientManager ingredientManager,
            CallbackInfo ci) {

        Config.Mode mode = Config.MODE;
        if (mode == Config.Mode.OFF || mode == Config.Mode.BATCH) {
            // BATCH сам по себе уже выгоден vs per-item — отдаём оригиналу
            return;
        }
        ci.cancel();

        long t0 = System.nanoTime();

        // 1. allElements — sequential, дёшево (HashMap.put × N).
        for (IListElementInfo<?> info : infos) {
            Object uid = jeiopt$uid(info.getTypedIngredient(), ingredientManager);
            this.allElements.put(uid, info.getElement());
        }

        long tAfterAll = System.nanoTime();

        // 2. Per-prefix параллельно. Каждый префикс пишет в СВОЙ storage.
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

        if (mode == Config.Mode.PARALLEL_FULL) {
            // Внутри одного префикса: tokenize в параллель, но storage.put серийно
            // (suffix tree не thread-safe).
            List<Map.Entry<IListElement<?>, Collection<String>>> tokens = infos.parallelStream()
                    .map(info -> new AbstractMap.SimpleEntry<IListElement<?>, Collection<String>>(
                            info.getElement(), prefix.getStrings(info)))
                    .collect(Collectors.toList());
            for (Map.Entry<IListElement<?>, Collection<String>> e : tokens) {
                IListElement<?> el = e.getKey();
                for (String s : e.getValue()) storage.put(s, el);
            }
            return;
        }

        // PARALLEL_PREFIX: tokenize + put — оба в этом потоке. Гонок нет (storage уникален).
        for (IListElementInfo<?> info : infos) {
            Collection<String> strings = prefix.getStrings(info);
            IListElement<?> el = info.getElement();
            for (String s : strings) storage.put(s, el);
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
                Collection<String> strings = p.getStrings(info);
                IListElement<?> el = info.getElement();
                for (String s : strings) storage.put(s, el);
            }
        }
    }

    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object jeiopt$uid(ITypedIngredient<?> typed, IIngredientManager mgr) {
        // JEI 15.x: getUniqueId(V, UidContext) → String (на 19.x был Object getUid)
        IIngredientHelper helper = mgr.getIngredientHelper(typed.getType());
        return helper.getUniqueId(typed.getIngredient(), UidContext.Ingredient);
    }
}
