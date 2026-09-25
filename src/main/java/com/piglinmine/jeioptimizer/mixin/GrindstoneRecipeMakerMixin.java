package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.Config;
import mezz.jei.api.recipe.vanilla.IJeiGrindstoneRecipe;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.platform.IPlatformRecipeHelper;
import mezz.jei.library.plugins.vanilla.grindstone.GrindstoneRecipeMaker;
import net.minecraft.world.inventory.GrindstoneMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/** Tier D: grindstone half of the synthetic-recipe skip. See AnvilRecipeMakerMixin. */
@Mixin(value = GrindstoneRecipeMaker.class, remap = false)
public abstract class GrindstoneRecipeMakerMixin {

    @Inject(
            method = "getDisenchantRecipes",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void jeiopt$skipDisenchant(
            IPlatformRecipeHelper recipeHelper,
            GrindstoneMenu menu,
            CallbackInfoReturnable<Stream<IJeiGrindstoneRecipe>> cir) {
        if (Config.SKIP_ENCHANT_RECIPES) {
            cir.setReturnValue(Stream.empty());
        }
    }

    @Inject(
            method = "getRepairRecipes",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void jeiopt$skipRepairs(
            IPlatformRecipeHelper recipeHelper,
            IIngredientManager ingredientManager,
            GrindstoneMenu menu,
            CallbackInfoReturnable<Stream<IJeiGrindstoneRecipe>> cir) {
        if (Config.SKIP_REPAIR) {
            cir.setReturnValue(Stream.empty());
        }
    }
}
