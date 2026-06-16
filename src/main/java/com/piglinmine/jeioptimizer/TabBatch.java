package com.piglinmine.jeioptimizer;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-tab buffer for {@code ItemStackListFactoryMixin} (Tier B).
 * <p>
 * Kept OUTSIDE the mixin package — otherwise the Mixin processor throws
 * {@code IllegalClassLoadError} because classes inside the mixin package
 * cannot be referenced from the instrumented bytecode of target classes.
 */
public final class TabBatch {
    public final List<Object> uids = new ArrayList<>(256);
    public final List<ItemStack> items = new ArrayList<>(256);
}
