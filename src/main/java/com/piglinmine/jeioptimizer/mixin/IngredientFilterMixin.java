package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.Config;
import com.piglinmine.jeioptimizer.Jeioptimizer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.ingredients.IngredientFilter;
import mezz.jei.gui.search.IElementSearch;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Перенаправляет цикл из {@code IngredientFilter} конструктора
 * <pre>
 *   for (IListElementInfo&lt;?&gt; ingredient : ingredients) {
 *       addIngredient(ingredient);  // ← 50,000 итераций
 *   }
 * </pre>
 * на один batch-вызов {@link IElementSearch#addAll}, который уже
 * оптимизирован {@link ElementSearchMixin}.
 *
 * <h3>Почему ThreadLocal</h3>
 * Mixin запрещает non-static {@code @Inject} на {@code @At("HEAD")} конструктора —
 * там ещё не вызван {@code super()} и {@code this} невалиден. Поэтому HEAD-handler
 * мы делаем {@code static} + используем {@link ThreadLocal} для проброса
 * {@code ingredients} в TAIL-handler того же потока.
 *
 * <h3>Почему не сломаем рантайм</h3>
 * Глушим {@code addIngredient} только пока {@code jeiopt$initCaptured} держит
 * не-null значение в этом потоке. В TAIL мы её сбрасываем. Все последующие
 * {@code addIngredient} (из плагинов / визибилити-эвентов) работают как обычно.
 */
@Mixin(value = IngredientFilter.class, remap = false)
public abstract class IngredientFilterMixin {

    // elementSearch без @Final — в JEI оно не final (rebuildItemFilter() пересоздаёт).
    @Shadow private IElementSearch elementSearch;
    @Shadow @Final private IIngredientManager ingredientManager;
    @Shadow @Final private IIngredientVisibility ingredientVisibility;
    @Shadow public abstract void invalidateCache();
    @Shadow private native void notifyListenersOfChange();

    /**
     * Per-thread окно «мы внутри init-batch'а». Заполняется в HEAD,
     * читается в addIngredient (cancellable), очищается в TAIL.
     */
    @Unique
    private static final ThreadLocal<List<IListElementInfo<?>>> jeiopt$initCaptured = new ThreadLocal<>();

    /**
     * HEAD конструктора. ОБЯЗАН быть static — это до super().
     */
    @Inject(
            method = "<init>",
            at = @At("HEAD")
    )
    private static void jeiopt$captureForBatch(
            mezz.jei.gui.filter.IFilterTextSource filterTextSource,
            mezz.jei.common.config.IClientConfig clientConfig,
            mezz.jei.common.config.IIngredientFilterConfig config,
            IIngredientManager ingredientManager,
            java.util.Comparator<IListElement<?>> ingredientComparator,
            List<IListElementInfo<?>> ingredients,
            mezz.jei.api.helpers.IModIdHelper modIdHelper,
            IIngredientVisibility ingredientVisibility,
            mezz.jei.api.helpers.IColorHelper colorHelper,
            mezz.jei.common.config.IClientToggleState clientToggleState,
            CallbackInfo ci) {
        if (Config.enabled()) {
            jeiopt$initCaptured.set(ingredients);
        }
    }

    @Inject(
            method = "addIngredient",
            at = @At("HEAD"),
            cancellable = true
    )
    private void jeiopt$skipDuringBatchInit(IListElementInfo<?> info, CallbackInfo ci) {
        // Глушим ТОЛЬКО когда мы внутри нашего init-batch'а на этом треде.
        if (jeiopt$initCaptured.get() != null) {
            ci.cancel();
        }
    }

    @Inject(
            method = "<init>",
            at = @At("TAIL")
    )
    private void jeiopt$flushBatch(
            mezz.jei.gui.filter.IFilterTextSource filterTextSource,
            mezz.jei.common.config.IClientConfig clientConfig,
            mezz.jei.common.config.IIngredientFilterConfig config,
            IIngredientManager ingredientManager,
            java.util.Comparator<IListElement<?>> ingredientComparator,
            List<IListElementInfo<?>> ingredients,
            mezz.jei.api.helpers.IModIdHelper modIdHelper,
            IIngredientVisibility ingredientVisibility,
            mezz.jei.api.helpers.IColorHelper colorHelper,
            mezz.jei.common.config.IClientToggleState clientToggleState,
            CallbackInfo ci) {

        List<IListElementInfo<?>> batch = jeiopt$initCaptured.get();
        if (batch == null) return;
        jeiopt$initCaptured.remove();

        // Tier C: async build. Игрок попадает в мир сразу, JEI поиск становится
        // доступен через 1-2 секунды. На время сборки getElements() вернёт пусто.
        if (Config.ASYNC_BUILD) {
            jeiopt$kickAsyncBuild(batch);
            return;
        }

        jeiopt$buildSync(batch);
    }

    @Unique
    private void jeiopt$buildSync(List<IListElementInfo<?>> batch) {
        long t0 = System.nanoTime();

        // 1) updateHiddenState — sequential, дёшево.
        for (IListElementInfo<?> info : batch) {
            jeiopt$updateHidden(info);
        }
        long tAfterHidden = System.nanoTime();

        // 2) Batch add — здесь стреляет ElementSearchMixin#parallelAddAll.
        this.elementSearch.addAll(batch, this.ingredientManager);

        // 3) Один инвалидейт вместо 50k.
        this.invalidateCache();

        long now = System.nanoTime();
        Jeioptimizer.LOGGER.info(
                "[JEIOptimizer] IngredientFilter init done — {} ingredients in {} ms total " +
                        "(hidden-state: {} ms, search build: {} ms, mode={})",
                batch.size(),
                (now - t0) / 1_000_000,
                (tAfterHidden - t0) / 1_000_000,
                (now - tAfterHidden) / 1_000_000,
                Config.MODE
        );
    }

    @Unique
    private void jeiopt$kickAsyncBuild(List<IListElementInfo<?>> batch) {
        long t0 = System.nanoTime();
        Jeioptimizer.LOGGER.info(
                "[JEIOptimizer] ASYNC_BUILD ON — kicking IngredientFilter build to background " +
                        "({} ingredients, main thread released NOW)",
                batch.size());

        com.piglinmine.jeioptimizer.WorkerPool.get().execute(() -> {
            try {
                for (IListElementInfo<?> info : batch) {
                    jeiopt$updateHidden(info);
                }
                this.elementSearch.addAll(batch, this.ingredientManager);
                this.invalidateCache();
                // Дёргаем listeners на main thread — UI overlay должен пере-отрендерить
                // ingredient grid когда оно станет доступным.
                net.minecraft.client.Minecraft.getInstance().execute(this::notifyListenersOfChange);

                long ms = (System.nanoTime() - t0) / 1_000_000;
                Jeioptimizer.LOGGER.info(
                        "[JEIOptimizer] ASYNC build complete in {} ms — JEI search now active",
                        ms);
            } catch (Throwable t) {
                Jeioptimizer.LOGGER.error(
                        "[JEIOptimizer] ASYNC build failed — JEI search may be broken until reconnect",
                        t);
            }
        });
    }

    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void jeiopt$updateHidden(IListElementInfo<?> info) {
        IListElement element = info.getElement();
        ITypedIngredient typed = element.getTypedIngredient();
        boolean visible = this.ingredientVisibility.isIngredientVisible(typed);
        if (element.isVisible() != visible) {
            element.setVisible(visible);
        }
    }
}
