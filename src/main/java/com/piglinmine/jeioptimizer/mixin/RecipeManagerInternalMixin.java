package com.piglinmine.jeioptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.piglinmine.jeioptimizer.Config;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.library.recipes.RecipeManagerInternal;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Поддержка для Tier A: параллельный {@link PluginCallerMixin} рискует тем,
 * что разные плагины могут одновременно вызвать {@link RecipeManagerInternal#addRecipes}
 * на одной и той же {@code RecipeType} → гонка на внутреннем ArrayList.
 * <p>
 * Лечим тупо: оборачиваем {@code addRecipes} в {@code synchronized(this)}.
 * <ul>
 *     <li>Vanilla — почти бесплатно (microseconds на add)</li>
 *     <li>Parallel — позволяет плагин-коду (~100ms каждый) идти параллельно,
 *     блокируясь только на момент финальной вставки</li>
 * </ul>
 * {@code recipeCategoriesVisibleCache = null} тоже защищён этим же монитором,
 * так что одновременный reset из разных потоков безопасен.
 */
@Mixin(value = RecipeManagerInternal.class, remap = false)
public abstract class RecipeManagerInternalMixin {

    @WrapMethod(method = "addRecipes")
    private <T> void jeiopt$synchronizeAddRecipes(
            RecipeType<T> recipeType,
            List<T> recipes,
            Operation<Void> original) {

        // Если параллель не включена — оригинал без оверхеда
        if (Config.PARALLEL_PHASES.isEmpty()) {
            original.call(recipeType, recipes);
            return;
        }

        // Синхронизируемся на самом инстансе — все addRecipes сериализуются между собой
        synchronized (this) {
            original.call(recipeType, recipes);
        }
    }
}
