package com.piglinmine.jeioptimizer;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-tab буфер для {@code ItemStackListFactoryMixin} (Tier B).
 * <p>
 * Лежит ВНЕ mixin-пакета — иначе Mixin processor бросает
 * {@code IllegalClassLoadError} потому что классы внутри mixin-пакета
 * не могут быть references из инструментированного байт-кода целевых классов.
 */
public final class TabBatch {
    public final List<Object> uids = new ArrayList<>(256);
    public final List<ItemStack> items = new ArrayList<>(256);
}
