package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.MenuUpdateGuard;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.GrindstoneMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips recipe recalculation while JEI fills the slots of its own fake grindstone menu. */
@Mixin(GrindstoneMenu.class)
public abstract class GrindstoneMenuMixin {

    @Inject(method = "slotsChanged", at = @At("HEAD"), cancellable = true)
    private void jeiopt$skipWhileJeiFillsSlots(Container container, CallbackInfo ci) {
        if (MenuUpdateGuard.isSuppressed(this)) {
            ci.cancel();
        }
    }
}
