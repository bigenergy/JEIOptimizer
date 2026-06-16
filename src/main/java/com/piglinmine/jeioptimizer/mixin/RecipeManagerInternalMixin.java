package com.piglinmine.jeioptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.piglinmine.jeioptimizer.Config;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.library.recipes.RecipeManagerInternal;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Support for Tier A: parallel {@link PluginCallerMixin} risks letting different
 * plugins call {@link RecipeManagerInternal#addRecipes} on the same
 * {@code RecipeType} concurrently → race on the internal ArrayList.
 * <p>
 * Fix the dumb way: wrap {@code addRecipes} in {@code synchronized(this)}.
 * <ul>
 *     <li>Vanilla — basically free (microseconds per add)</li>
 *     <li>Parallel — lets plugin code (~100ms each) run in parallel and only
 *     blocks during the final insert</li>
 * </ul>
 * {@code recipeCategoriesVisibleCache = null} is guarded by the same monitor,
 * so simultaneous reset from different threads is safe.
 */
@Mixin(value = RecipeManagerInternal.class, remap = false)
public abstract class RecipeManagerInternalMixin {

    @WrapMethod(method = "addRecipes")
    private <T> void jeiopt$synchronizeAddRecipes(
            RecipeType<T> recipeType,
            List<T> recipes,
            Operation<Void> original) {

        // If parallelism isn't enabled — call original, zero overhead
        if (Config.PARALLEL_PHASES.isEmpty()) {
            original.call(recipeType, recipes);
            return;
        }

        // Synchronize on the instance itself — all addRecipes serialize against each other
        synchronized (this) {
            original.call(recipeType, recipes);
        }
    }
}
