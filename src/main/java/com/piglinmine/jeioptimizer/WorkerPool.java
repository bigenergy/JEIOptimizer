package com.piglinmine.jeioptimizer;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Глобальный пул воркеров для всех Tier'ов JEIOptimizer.
 * Lazy-init на первое обращение — не платим если мод не включался.
 * <p>
 * Daemon-треды + пониженный priority — не мешают main thread'у и не держат JVM.
 */
public final class WorkerPool {
    private WorkerPool() {}

    private static volatile ForkJoinPool pool;

    public static ForkJoinPool get() {
        ForkJoinPool p = pool;
        if (p != null) return p;
        synchronized (WorkerPool.class) {
            if (pool != null) return pool;
            int n = Config.effectiveWorkers();
            pool = new ForkJoinPool(
                    n,
                    fjp -> {
                        ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(fjp);
                        t.setName("JEIOptimizer-Worker-" + t.getPoolIndex());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    },
                    null,
                    false
            );
            Jeioptimizer.LOGGER.info("[JEIOptimizer] Started ForkJoinPool with {} workers", n);
            return pool;
        }
    }
}
