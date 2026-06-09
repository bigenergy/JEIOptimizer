package com.piglinmine.jeioptimizer;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * JEIOptimizer — клиентский мод-ускоритель сборки ingredient filter'а JEI.
 * <p>
 * Главный пейн: при коннекте на сервер JEI пересобирает весь свой индекс
 * (рецепты + ingredients + filter). На паках с 250+ модов это даёт ~12-15 сек
 * блокировки главного потока на одной только сборке filter'а.
 * <p>
 * Мы это лечим миксином в {@code ElementSearch.addAll}, где параллелим
 * заполнение per-prefix хранилищ. См. {@link Config.Mode} для опций.
 */
@Mod(Jeioptimizer.MODID)
public class Jeioptimizer {
    public static final String MODID = "jeioptimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Jeioptimizer(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            LOGGER.info("[JEIOptimizer] Loaded on CLIENT — mixins will be applied to JEI.");
        } else {
            LOGGER.info("[JEIOptimizer] Loaded on DEDICATED SERVER — no-op (this mod is client-side only).");
        }
    }
}
