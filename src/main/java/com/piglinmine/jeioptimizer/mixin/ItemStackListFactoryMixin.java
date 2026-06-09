package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.Config;
import com.piglinmine.jeioptimizer.Jeioptimizer;
import com.piglinmine.jeioptimizer.TabBatch;
import com.piglinmine.jeioptimizer.WorkerPool;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.common.Internal;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.config.IJeiClientConfigs;
import mezz.jei.common.util.StackHelper;
import mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper;
import mezz.jei.library.plugins.vanilla.ingredients.ItemStackListFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <b>Tier B</b> — параллельное построение CreativeModeTab контента внутри
 * {@link ItemStackListFactory#create}.
 * <p>
 * В оригинале (см. JEI 1.21.1 sources): главный поток обходит ~50 креативных табов,
 * каждому вызывает {@code tab.buildContents(displayParameters)} (мод-код!) и собирает
 * displayItems + searchTabDisplayItems в общий List + Set для уникальности.
 * <p>
 * Сложности параллели:
 * <ul>
 *     <li>{@code tab.buildContents()} — 99% табов чисто-вычислительные, но один-два
 *     могут трогать main-thread-only state. Try/catch вокруг каждого таба, fall-back
 *     на sequential для всего метода если что-то падает.</li>
 *     <li>Уникальность по UID — каждый таб собирает свой локальный набор, финальный
 *     merge sequential.</li>
 * </ul>
 * <p>
 * При parallel ошибке — возвращаемся (return) и даём JEI пройти оригинальным путём.
 */
@Mixin(value = ItemStackListFactory.class, remap = false)
public abstract class ItemStackListFactoryMixin {

    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void jeiopt$parallelCreate(
            StackHelper stackHelper,
            ItemStackHelper itemStackHelper,
            CallbackInfoReturnable<List<ItemStack>> cir) {

        if (!Config.PARALLEL_TABS) return;

        long t0 = System.nanoTime();

        try {
            List<ItemStack> result = jeiopt$buildInParallel(stackHelper, itemStackHelper);
            cir.setReturnValue(result);

            long ms = (System.nanoTime() - t0) / 1_000_000;
            Jeioptimizer.LOGGER.info(
                    "[JEIOptimizer] ItemStackListFactory.create (parallel) — {} items in {} ms",
                    result.size(), ms);
        } catch (Throwable t) {
            // Любая ошибка → fallback на оригинал.
            // НЕ cancel'им CallbackInfo → JEI выполнит свой код.
            Jeioptimizer.LOGGER.warn(
                    "[JEIOptimizer] Parallel ItemStackListFactory failed — falling back to sequential vanilla path",
                    t);
        }
    }

    @Unique
    private static List<ItemStack> jeiopt$buildInParallel(
            StackHelper stackHelper,
            ItemStackHelper itemStackHelper) throws Exception {

        IJeiClientConfigs configs = Internal.getJeiClientConfigs();
        IClientConfig clientConfig = configs.getClientConfig();
        boolean showHidden = clientConfig.getShowHiddenIngredients();

        Minecraft minecraft = Minecraft.getInstance();
        FeatureFlagSet features = Optional.ofNullable(minecraft.player)
                .map(p -> p.connection)
                .map(ClientPacketListener::enabledFeatures)
                .orElse(FeatureFlagSet.of());

        boolean hasOperatorPerms = showHidden
                || minecraft.options.operatorItemsTab().get()
                || Optional.of(minecraft).map(m -> m.player)
                    .map(Player::canUseGameMasterBlocks).orElse(false);

        ClientLevel level = minecraft.level;
        if (level == null) {
            throw new NullPointerException("minecraft.level must be set before JEI fetches ingredients");
        }

        CreativeModeTab.ItemDisplayParameters displayParameters =
                new CreativeModeTab.ItemDisplayParameters(features, hasOperatorPerms, level.registryAccess());

        // Каждый таб собирает свои items в локальный TabBatch — никаких общих коллекций.
        // Параллелим через наш пул чтобы не мешать MC ForkJoinPool.commonPool().
        List<CreativeModeTab> tabs = new ArrayList<>(CreativeModeTabs.allTabs());

        List<TabBatch> batches = WorkerPool.get().submit(() ->
                tabs.parallelStream()
                        .map(tab -> jeiopt$processTab(tab, displayParameters, stackHelper, itemStackHelper))
                        .filter(b -> b != null)
                        .toList()
        ).get();

        // Sequential merge — глобальная уникальность по UID.
        Set<Object> globalUidSet = new HashSet<>();
        List<ItemStack> itemList = new ArrayList<>();
        for (TabBatch b : batches) {
            for (int i = 0; i < b.uids.size(); i++) {
                if (globalUidSet.add(b.uids.get(i))) {
                    itemList.add(b.items.get(i));
                }
            }
        }
        return itemList;
    }

    @Unique
    @SuppressWarnings("CallToPrintStackTrace")
    private static TabBatch jeiopt$processTab(
            CreativeModeTab tab,
            CreativeModeTab.ItemDisplayParameters displayParameters,
            StackHelper stackHelper,
            ItemStackHelper itemStackHelper) {

        // Пропускаем не-категории (поисковый таб и т.д.) — как в оригинале
        if (tab.getType() != CreativeModeTab.Type.CATEGORY) return null;

        try {
            tab.buildContents(displayParameters);
        } catch (Throwable e) {
            Jeioptimizer.LOGGER.error(
                    "[JEIOptimizer] Item Group crashed while building contents (parallel): '{}'",
                    safeName(tab), e);
            return null;
        }

        Collection<ItemStack> displayItems;
        Collection<ItemStack> searchTabDisplayItems;
        try {
            displayItems = tab.getDisplayItems();
            searchTabDisplayItems = tab.getSearchTabDisplayItems();
        } catch (Throwable e) {
            Jeioptimizer.LOGGER.error(
                    "[JEIOptimizer] Item Group crashed while getting items (parallel): '{}'",
                    safeName(tab), e);
            return null;
        }

        if (displayItems.isEmpty() && searchTabDisplayItems.isEmpty()) {
            return null;
        }

        TabBatch batch = new TabBatch();
        jeiopt$addItems(batch, displayItems, stackHelper, itemStackHelper);
        if (!displayItems.equals(searchTabDisplayItems)) {
            jeiopt$addItems(batch, searchTabDisplayItems, stackHelper, itemStackHelper);
        }
        return batch;
    }

    @Unique
    private static void jeiopt$addItems(
            TabBatch batch,
            Collection<ItemStack> items,
            StackHelper stackHelper,
            ItemStackHelper itemStackHelper) {

        Set<Object> tabUidSet = new HashSet<>();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;
            if (!itemStackHelper.isValidIngredient(stack)) continue;
            if (!itemStackHelper.isIngredientOnServer(stack)) continue;

            Object uid;
            try {
                uid = stackHelper.getUidForStack(stack, UidContext.Ingredient);
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            if (uid == null) continue;

            // tab-local uniqueness — но global уникальность обеспечивается merge'м
            if (tabUidSet.add(uid)) {
                batch.uids.add(uid);
                batch.items.add(stack);
            }
        }
    }

    @Unique
    private static String safeName(CreativeModeTab tab) {
        try {
            return tab.getDisplayName().getString();
        } catch (Throwable t) {
            return tab.toString();
        }
    }

}
