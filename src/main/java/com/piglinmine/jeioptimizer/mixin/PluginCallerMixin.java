package com.piglinmine.jeioptimizer.mixin;

import com.piglinmine.jeioptimizer.Config;
import com.piglinmine.jeioptimizer.Jeioptimizer;
import com.piglinmine.jeioptimizer.WorkerPool;
import mezz.jei.api.IModPlugin;
import mezz.jei.library.load.PluginCaller;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * <b>Tier A</b> — parallel {@link PluginCaller#callOnPlugins}.
 * <p>
 * JEI walks ~47 plugins single-threaded in each phase. The heaviest phase is
 * "Registering recipes" ≈ 5 seconds (jei:minecraft 1.6s, botanypots 714ms,
 * silentgear 500ms, industrialforegoing 469ms, ...).
 * <p>
 * We only parallelize whitelisted phases (see {@code Config.PARALLEL_PHASES}).
 * Default: just "Registering recipes". Can be extended via config.
 * <p>
 * Thread-safety of shared collectors ({@code RecipeRegistration} → {@code RecipeManagerInternal})
 * is provided by {@link RecipeManagerInternalMixin}, which wraps
 * {@code addRecipes} in {@code synchronized(this)}.
 * <p>
 * Plugin errors are isolated via try/catch — a crashing plugin doesn't kill the
 * others (same as vanilla). Vanilla plugin exception is rethrown (same as vanilla).
 */
@Mixin(value = PluginCaller.class, remap = false)
public abstract class PluginCallerMixin {

    @Inject(method = "callOnPlugins", at = @At("HEAD"), cancellable = true)
    private static void jeiopt$parallelize(
            String title,
            List<IModPlugin> plugins,
            Consumer<IModPlugin> func,
            CallbackInfo ci) {

        if (!Config.shouldParallelizePhase(title)) return;
        if (plugins.size() < 4) return; // fewer than 4 — not worth it

        ci.cancel();

        long t0 = System.nanoTime();
        AtomicInteger errors = new AtomicInteger(0);

        Jeioptimizer.LOGGER.info("[JEIOptimizer] {} (parallel)... [{} plugins]", title, plugins.size());

        try {
            WorkerPool.get().submit(() ->
                    plugins.parallelStream().forEach(plugin -> {
                        try {
                            func.accept(plugin);
                        } catch (RuntimeException | LinkageError e) {
                            if (plugin instanceof VanillaPlugin) {
                                // Vanilla error — critical, same as vanilla
                                throw e;
                            }
                            errors.incrementAndGet();
                            Jeioptimizer.LOGGER.error(
                                    "[JEIOptimizer] Plugin error during parallel '{}': {} {}",
                                    title, plugin.getClass(), plugin.getPluginUid(), e
                            );
                        }
                    })
            ).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("[JEIOptimizer] interrupted during parallel '" + title + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            // VanillaPlugin error — rethrow
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException("[JEIOptimizer] failed during parallel '" + title + "'", cause);
        }

        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        if (errors.get() > 0) {
            Jeioptimizer.LOGGER.warn(
                    "[JEIOptimizer] {} took {} ms (parallel, {} plugin errors — see log above)",
                    title, elapsed, errors.get());
        } else {
            Jeioptimizer.LOGGER.info("[JEIOptimizer] {} took {} ms (parallel)", title, elapsed);
        }
    }
}
