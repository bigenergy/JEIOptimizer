package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.Jeioptimizer;
import com.piglinmine.jeioptimizer.MenuUpdateGuard;
import mezz.jei.common.util.ErrorUtil;
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

    // Also fires on the return inside JEI's catch handler, so the guard is always released.
    @Inject(method = "setAnvilMenu", at = @At("RETURN"), cancellable = true, require = 0)
    private static void jeiopt$resumeAndRecalculate(
            AnvilMenu menu, ItemStack left, ItemStack right, CallbackInfoReturnable<AnvilMenu> cir) {
        MenuUpdateGuard.end();
        AnvilMenu result = cir.getReturnValue();
        if (result == null) {
            return;
        }
        // JEI sets the slots inside try/catch: a broken mod item that throws during the
        // recalculation is logged and the entry skipped. Our recalculation runs after that
        // block, so the same guard has to be repeated here or the throw kills the game.
        try {
            result.createResult();
        } catch (RuntimeException | LinkageError e) {
            Jeioptimizer.LOGGER.error("Could not set anvil recipe for: ({} and {}).",
                    ErrorUtil.getItemStackInfo(left), ErrorUtil.getItemStackInfo(right), e);
            cir.setReturnValue(null);
        }
    }
}
