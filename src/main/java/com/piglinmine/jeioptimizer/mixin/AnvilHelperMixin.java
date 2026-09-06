package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.MenuUpdateGuard;
import mezz.jei.library.plugins.vanilla.anvil.AnvilHelper;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Setting the two anvil slots fires a recipe recalculation per slot, and the first
 * one only ever sees half the inputs. Suppress both and recalculate once at the end.
 * The value JEI reads afterwards is identical.
 */
@Mixin(value = AnvilHelper.class, remap = false)
public abstract class AnvilHelperMixin {

    @Inject(method = "setAnvilMenu", at = @At("HEAD"), require = 0)
    private static void jeiopt$suppressUpdates(
            AnvilMenu menu, ItemStack left, ItemStack right, CallbackInfoReturnable<AnvilMenu> cir) {
        MenuUpdateGuard.begin(menu);
    }

    @Inject(method = "setAnvilMenu", at = @At("RETURN"), require = 0)
    private static void jeiopt$resumeAndRecalculate(
            AnvilMenu menu, ItemStack left, ItemStack right, CallbackInfoReturnable<AnvilMenu> cir) {
        MenuUpdateGuard.end();
        AnvilMenu result = cir.getReturnValue();
        if (result != null) {
            result.createResult();
        }
    }
}
