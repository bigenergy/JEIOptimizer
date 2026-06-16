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

import java.util.ArrayList;
import java.util.List;

/**
 * Batched init for {@link IngredientFilter}. Forge 1.20.1 Mixin 0.8.5.
 *
 * <h3>Why we do NOT use {@code @At("HEAD")} on the constructor</h3>
 * The Mixin 0.8.5 annotation processor categorically forbids any point other
 * than {@code RETURN} on a constructor ("Cannot inject into constructors at
 * non-return instructions"). NeoForge 1.21.1 (Mixin 0.15) relaxed this — HEAD
 * works there with a static handler. Not here.
 *
 * <h3>Workaround</h3>
 * We use a {@code @Unique} field {@code jeiopt$buffer} with an initializer.
 * Mixin injects initializers of {@code @Unique} fields into the CONSTRUCTOR of
 * the target class — so by the first call to {@code addIngredient(...)} the
 * buffer already exists.
 *
 * <h3>Logic</h3>
 * <ol>
 *   <li>{@code addIngredient} inside the constructor loop sees a non-empty
 *       buffer → appends {@code info} and cancels the original. The loop spins
 *       no-op (~50k empty calls).</li>
 *   <li>{@code <init>} {@code RETURN} resets {@code buffer = null} and calls
 *       batch addAll (parallelized by {@link ElementSearchMixin}).</li>
 *   <li>After {@code RETURN} buffer == null → later {@code addIngredient}
 *       calls (from JEI runtime / event listeners) work normally.</li>
 * </ol>
 */
@Mixin(value = IngredientFilter.class, remap = false)
public abstract class IngredientFilterMixin {

    // elementSearch is NOT final in JEI — rebuildItemFilter() reassigns it.
    @Shadow private IElementSearch elementSearch;
    @Shadow @Final private IIngredientManager ingredientManager;
    @Shadow @Final private IIngredientVisibility ingredientVisibility;
    @Shadow public abstract void invalidateCache();
    @Shadow private native void notifyListenersOfChange();
    @Shadow public abstract <V> void addIngredient(IListElementInfo<V> info);

    /**
     * "Constructor finished" signal — set in TAIL.
     * Defaults to false (boolean default) → during init it's guaranteed false
     * even if Mixin didn't merge the field initializer.
     */
    @Unique
    private boolean jeiopt$initDone = false;

    /**
     * Init-batch buffer. Lazy-init on first use in case Mixin didn't merge the
     * initializer correctly (guard against Mixin 0.8.5 version bugs).
     */
    @Unique
    private List<IListElementInfo<?>> jeiopt$buffer;

    /**
     * Intercept EVERY {@code addIngredient}. While {@code jeiopt$initDone == false}
     * (we're in the init phase) — save info into the buffer and cancel the original.
     * After TAIL the flag is true → later addIngredient calls pass through normally.
     */
    @Inject(
            method = "addIngredient",
            at = @At("HEAD"),
            cancellable = true
    )
    private void jeiopt$bufferDuringInit(IListElementInfo<?> info, CallbackInfo ci) {
        if (this.jeiopt$initDone) return;
        if (!Config.enabled()) return;
        if (this.jeiopt$buffer == null) {
            this.jeiopt$buffer = new ArrayList<>();
        }
        this.jeiopt$buffer.add(info);
        ci.cancel();
    }

    /**
     * Constructor end — flush the buffer through the batch path.
     * <p>
     * Set {@code jeiopt$buffer = null} first, then do work — so any stray
     * {@code addIngredient} from our TAIL logic (none today, but defensive)
     * takes the normal path instead of looping back.
     */
    @Inject(
            method = "<init>",
            at = @At("RETURN")
    )
    private void jeiopt$flush(
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

        this.jeiopt$initDone = true; // ← from this point addIngredient bypasses the buffer
        List<IListElementInfo<?>> batch = this.jeiopt$buffer;
        this.jeiopt$buffer = null;

        if (batch == null || batch.isEmpty()) return;

        // Config may have changed while the loop ran — fail-safe: replay manually
        if (!Config.enabled()) {
            for (IListElementInfo<?> info : batch) {
                this.addIngredient(info); // buffer == null now → real add path
            }
            return;
        }

        if (Config.ASYNC_BUILD) {
            jeiopt$kickAsyncBuild(batch);
            return;
        }
        jeiopt$buildSync(batch);
    }

    @Unique
    private void jeiopt$buildSync(List<IListElementInfo<?>> batch) {
        long t0 = System.nanoTime();
        for (IListElementInfo<?> info : batch) {
            jeiopt$updateHidden(info);
        }
        long tAfterHidden = System.nanoTime();
        this.elementSearch.addAll(batch, this.ingredientManager);
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
