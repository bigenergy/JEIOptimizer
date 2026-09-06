package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.MenuUpdateGuard;
import mezz.jei.forge.platform.RecipeHelper;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Same idea as AnvilHelperMixin. JEI already calls createResult() itself after both
 * slots are set here, so only the per-slot recalculations need suppressing.
 */
@Mixin(value = RecipeHelper.class, remap = false)
public abstract class GrindstoneResultMixin {

    @Inject(method = "getGrindstoneResult", at = @At("HEAD"), require = 0)
    private void jeiopt$suppressUpdates(
            GrindstoneMenu menu, ItemStack top, ItemStack bottom, CallbackInfoReturnable<ItemStack> cir) {
        MenuUpdateGuard.begin(menu);
    }

    @Inject(method = "getGrindstoneResult", at = @At("RETURN"), require = 0)
    private void jeiopt$resume(
            GrindstoneMenu menu, ItemStack top, ItemStack bottom, CallbackInfoReturnable<ItemStack> cir) {
        MenuUpdateGuard.end();
    }
}
