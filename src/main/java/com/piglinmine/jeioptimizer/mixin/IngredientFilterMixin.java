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

/** Batched init for IngredientFilter (JEI 11.8 / Forge 1.19.2). Signature-agnostic constructor hook. */
@Mixin(value = IngredientFilter.class, remap = false)
public abstract class IngredientFilterMixin {

    @Shadow private IElementSearch elementSearch;
    @Shadow @Final private IIngredientManager ingredientManager;
    @Shadow @Final private IIngredientVisibility ingredientVisibility;
    @Shadow public abstract void invalidateCache();
    @Shadow private native void notifyListenersOfChange();
    @Shadow public abstract <V> void addIngredient(IListElementInfo<V> info);

    @Unique
    private boolean jeiopt$initDone = false;

    @Unique
    private List<IListElementInfo<?>> jeiopt$buffer;

    @Inject(method = "addIngredient", at = @At("HEAD"), cancellable = true)
    private void jeiopt$bufferDuringInit(IListElementInfo<?> info, CallbackInfo ci) {
        if (this.jeiopt$initDone) return;
        if (!Config.enabled()) return;
        if (this.jeiopt$buffer == null) {
            this.jeiopt$buffer = new ArrayList<>();
        }
        this.jeiopt$buffer.add(info);
        ci.cancel();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void jeiopt$flush(CallbackInfo ci) {
        this.jeiopt$initDone = true;
        List<IListElementInfo<?>> batch = this.jeiopt$buffer;
        this.jeiopt$buffer = null;

        if (batch == null || batch.isEmpty()) return;

        if (!Config.enabled()) {
            for (IListElementInfo<?> info : batch) this.addIngredient(info);
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
        for (IListElementInfo<?> info : batch) jeiopt$updateHidden(info);
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
                for (IListElementInfo<?> info : batch) jeiopt$updateHidden(info);
                this.elementSearch.addAll(batch, this.ingredientManager);
                this.invalidateCache();
                net.minecraft.client.Minecraft.getInstance().execute(this::notifyListenersOfChange);

                long ms = (System.nanoTime() - t0) / 1_000_000;
                Jeioptimizer.LOGGER.info("[JEIOptimizer] ASYNC build complete in {} ms", ms);
            } catch (Throwable t) {
                Jeioptimizer.LOGGER.error("[JEIOptimizer] ASYNC build failed", t);
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
