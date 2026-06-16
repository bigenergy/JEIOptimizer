package com.piglinmine.jeioptimizer;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * JEIOptimizer — client-side mod that accelerates JEI's ingredient filter build.
 * <p>
 * Forge 1.20.1 backport. See the NeoForge 1.21.1 branch for full details.
 */
@Mod(Jeioptimizer.MODID)
public class Jeioptimizer {
    public static final String MODID = "jeioptimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Jeioptimizer() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        modBus.addListener(Config::onLoad);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            LOGGER.info("[JEIOptimizer] Loaded on CLIENT — mixins will be applied to JEI.");
        } else {
            LOGGER.info("[JEIOptimizer] Loaded on DEDICATED SERVER — no-op (this mod is client-side only).");
        }
    }
}
