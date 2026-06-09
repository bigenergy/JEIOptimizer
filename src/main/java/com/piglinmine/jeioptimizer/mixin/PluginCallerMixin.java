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
 * <b>Tier A</b> — параллельный {@link PluginCaller#callOnPlugins}.
 * <p>
 * JEI обходит ~47 плагинов одним потоком в каждой фазе. Самая жирная фаза —
 * "Registering recipes" ≈ 5 секунд (jei:minecraft 1.6s, botanypots 714ms,
 * silentgear 500ms, industrialforegoing 469ms, ...).
 * <p>
 * Параллелим только whitelisted фазы (см. {@code Config.PARALLEL_PHASES}).
 * Дефолт: только "Registering recipes". Можно расширить через конфиг.
 * <p>
 * Thread-safety общих collector'ов ({@code RecipeRegistration} → {@code RecipeManagerInternal})
 * обеспечивается {@link RecipeManagerInternalMixin} который оборачивает
 * {@code addRecipes} в {@code synchronized(this)}.
 * <p>
 * Ошибки плагинов изолированы try/catch — упавший плагин не валит остальных
 * (как и в оригинале). Vanilla plugin исключение — бросаем дальше (как в оригинале).
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
        if (plugins.size() < 4) return; // меньше 4 — не имеет смысла

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
                                // Vanilla error — критично, как в оригинале
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
            // VanillaPlugin error — пробросим
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
