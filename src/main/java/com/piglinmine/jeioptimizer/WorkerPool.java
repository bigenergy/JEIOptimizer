package com.piglinmine.jeioptimizer;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Shared worker pool. Workers inherit the caller's ContextClassLoader —
 * default AppClassLoader can't see mod-patched classes and breaks ServiceLoader
 * static init triggered by tooltip handlers.
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

            final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();

            pool = new ForkJoinPool(
                    n,
                    fjp -> {
                        ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(fjp);
                        if (contextLoader != null) {
                            t.setContextClassLoader(contextLoader);
                        }
                        t.setName("JEIOptimizer-Worker-" + t.getPoolIndex());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    },
                    null,
                    false
            );
            Jeioptimizer.LOGGER.info(
                    "[JEIOptimizer] Started ForkJoinPool with {} workers (contextClassLoader={})",
                    n, contextLoader == null ? "null" : contextLoader.getClass().getName());
            return pool;
        }
    }
}
