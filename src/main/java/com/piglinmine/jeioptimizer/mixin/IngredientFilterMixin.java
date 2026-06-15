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
 * Batched init для {@link IngredientFilter}. Forge 1.20.1 Mixin 0.8.5.
 *
 * <h3>Почему НЕТ {@code @At("HEAD")} на конструкторе</h3>
 * Mixin 0.8.5 annotation processor категорически запрещает любые точки кроме
 * {@code RETURN} на конструкторе ("Cannot inject into constructors at non-return
 * instructions"). На NeoForge 1.21.1 (Mixin 0.15) это смягчили — там HEAD
 * разрешён с static handler'ом. Тут — нет.
 *
 * <h3>Как обойти</h3>
 * Используем {@code @Unique}-поле {@code jeiopt$buffer} с инициализатором.
 * Mixin вставляет инициализаторы {@code @Unique} полей в КОНСТРУКТОР целевого
 * класса — то есть к моменту первого вызова {@code addIngredient(...)} буфер
 * уже создан.
 *
 * <h3>Логика</h3>
 * <ol>
 *   <li>{@code addIngredient} в цикле конструктора видит непустой buffer
 *       → добавляет {@code info} туда и cancel'ит оригинал. Цикл крутится
 *       вхолостую (~50k пустых вызовов).</li>
 *   <li>{@code <init>} {@code RETURN} сбрасывает {@code buffer = null} и
 *       вызывает batch addAll (его параллелит {@link ElementSearchMixin}).</li>
 *   <li>После {@code RETURN} buffer == null → последующие {@code addIngredient}
 *       (из рантайма JEI / event listener'ов) работают нормально.</li>
 * </ol>
 */
@Mixin(value = IngredientFilter.class, remap = false)
public abstract class IngredientFilterMixin {

    // elementSearch НЕ final в JEI — rebuildItemFilter() его пересоздаёт.
    @Shadow private IElementSearch elementSearch;
    @Shadow @Final private IIngredientManager ingredientManager;
    @Shadow @Final private IIngredientVisibility ingredientVisibility;
    @Shadow public abstract void invalidateCache();
    @Shadow private native void notifyListenersOfChange();
    @Shadow public abstract <V> void addIngredient(IListElementInfo<V> info);

    /**
     * Сигнал "конструктор отработал" — выставляется в TAIL.
     * Defaults to false (boolean default) → во время init он гарантированно false,
     * даже если Mixin не смержил field initializer.
     */
    @Unique
    private boolean jeiopt$initDone = false;

    /**
     * Init-batch буфер. Lazy-init на первое использование — на случай если
     * Mixin не смержил initializer корректно (защита от версионных багов 0.8.5).
     */
    @Unique
    private List<IListElementInfo<?>> jeiopt$buffer;

    /**
     * Перехватываем КАЖДЫЙ {@code addIngredient}. Пока {@code jeiopt$initDone == false}
     * (мы в init-фазе) — сохраняем info в buffer и отменяем оригинал.
     * После TAIL флаг становится true → последующие addIngredient проходят нормально.
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
     * Конец конструктора — flush буфера через batch path.
     * <p>
     * Сначала {@code jeiopt$buffer = null}, потом работаем — чтобы любой
     * случайный {@code addIngredient} в нашей TAIL-логике (его там нет, но
     * для безопасности) шёл нормальным путём, а не зацикливался.
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

        this.jeiopt$initDone = true; // ← с этого момента addIngredient идёт мимо буфера
        List<IListElementInfo<?>> batch = this.jeiopt$buffer;
        this.jeiopt$buffer = null;

        if (batch == null || batch.isEmpty()) return;

        // Config мог измениться пока цикл крутился — fail-safe: переиграть руками
        if (!Config.enabled()) {
            for (IListElementInfo<?> info : batch) {
                this.addIngredient(info); // теперь buffer == null → реальное добавление
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
