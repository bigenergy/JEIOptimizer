package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.Config;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import mezz.jei.library.plugins.vanilla.anvil.AnvilRecipeMaker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/**
 * Tier D: skip JEI's synthetic anvil entries.
 * <p>
 * JEI generates one entry per enchantable item x enchantment x level, which grows
 * multiplicatively with the pack's enchantment count. Both toggles default to false
 * because dropping these removes them from JEI's UI — real recipes are untouched.
 */
@Mixin(value = AnvilRecipeMaker.class, remap = false)
public abstract class AnvilRecipeMakerMixin {

    @Inject(
            method = "getBookEnchantmentRecipes()Ljava/util/stream/Stream;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void jeiopt$skipBookEnchantments(CallbackInfoReturnable<Stream<IJeiAnvilRecipe>> cir) {
        if (Config.SKIP_ENCHANT_RECIPES) {
            cir.setReturnValue(Stream.empty());
        }
    }

    @Inject(
            method = "getRepairRecipes()Ljava/util/stream/Stream;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void jeiopt$skipRepairs(CallbackInfoReturnable<Stream<IJeiAnvilRecipe>> cir) {
        if (Config.SKIP_REPAIR) {
            cir.setReturnValue(Stream.empty());
        }
    }
}
