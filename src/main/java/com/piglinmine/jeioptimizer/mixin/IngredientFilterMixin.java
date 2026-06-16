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
 * Redirects the loop in the {@code IngredientFilter} constructor
 * <pre>
 *   for (IListElementInfo&lt;?&gt; ingredient : ingredients) {
 *       addIngredient(ingredient);  // ← 50,000 iterations
 *   }
 * </pre>
 * into a single batch call to {@link IElementSearch#addAll}, which is already
 * optimized by {@link ElementSearchMixin}.
 *
 * <h3>Why ThreadLocal</h3>
 * Mixin forbids non-static {@code @Inject} at constructor {@code @At("HEAD")} —
 * at that point {@code super()} hasn't run and {@code this} is invalid. So the
 * HEAD handler is {@code static} and we use a {@link ThreadLocal} to pass
 * {@code ingredients} to the TAIL handler on the same thread.
 *
 * <h3>Why we won't break the runtime</h3>
 * We suppress {@code addIngredient} only while {@code jeiopt$initCaptured} holds
 * a non-null value on this thread. We clear it in TAIL. All later
 * {@code addIngredient} calls (from plugins / visibility events) work normally.
 */
@Mixin(value = IngredientFilter.class, remap = false)
public abstract class IngredientFilterMixin {

    // elementSearch without @Final — JEI's field isn't final (rebuildItemFilter() reassigns it).
    @Shadow private IElementSearch elementSearch;
    @Shadow @Final private IIngredientManager ingredientManager;
    @Shadow @Final private IIngredientVisibility ingredientVisibility;
    @Shadow public abstract void invalidateCache();
    @Shadow private native void notifyListenersOfChange();

    /**
     * Per-thread "we are inside init-batch" window. Set in HEAD,
     * read in addIngredient (cancellable), cleared in TAIL.
     */
    @Unique
    private static final ThreadLocal<List<IListElementInfo<?>>> jeiopt$initCaptured = new ThreadLocal<>();

    /**
     * Constructor HEAD. MUST be static — this runs before super().
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
        // Suppress ONLY while we are inside our init-batch on this thread.
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

        // Tier C: async build. The player enters the world immediately, JEI search
        // becomes usable 1-2 seconds later. While building, getElements() returns empty.
        if (Config.ASYNC_BUILD) {
            jeiopt$kickAsyncBuild(batch);
            return;
        }

        jeiopt$buildSync(batch);
    }

    @Unique
    private void jeiopt$buildSync(List<IListElementInfo<?>> batch) {
        long t0 = System.nanoTime();

        // 1) updateHiddenState — sequential, cheap.
        for (IListElementInfo<?> info : batch) {
            jeiopt$updateHidden(info);
        }
        long tAfterHidden = System.nanoTime();

        // 2) Batch add — this is where ElementSearchMixin#parallelAddAll fires.
        this.elementSearch.addAll(batch, this.ingredientManager);

        // 3) One invalidation instead of 50k.
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
                // Fire listeners on the main thread — UI overlay needs to re-render the
                // ingredient grid once it's available.
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
