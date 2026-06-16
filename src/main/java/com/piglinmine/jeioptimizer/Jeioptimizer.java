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
 * JEIOptimizer — client-side mod that accelerates JEI's ingredient filter build.
 * <p>
 * The pain point: when connecting to a server JEI rebuilds its entire index
 * (recipes + ingredients + filter). On packs with 250+ mods that means
 * ~12-15 sec of main-thread blocking on filter construction alone.
 * <p>
 * We fix this with a mixin on {@code ElementSearch.addAll} where we parallelize
 * the per-prefix storage fill. See {@link Config.Mode} for options.
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
