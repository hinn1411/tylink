package com.tylink.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Warn up network connection after SnapStart restore for AWS SDK
 */
public final class SnapStartWarmup {

    private static final Logger log = LogManager.getLogger(SnapStartWarmup.class);

    private static final List<Resource> REGISTERED = new CopyOnWriteArrayList<>();

    private SnapStartWarmup() {
    }
    // Default Context only keeps week reference which won't survive
    // garbage collection. We need to create strong reference by calling real SDK requests
    public static void registerAfterRestore(Runnable warmup) {
        Resource resource = new Resource() {
            @Override
            public void beforeCheckpoint(Context<? extends Resource> context) {
            }

            @Override
            public void afterRestore(Context<? extends Resource> context) {
                try {
                    warmup.run();
                } catch (RuntimeException e) {
                    log.warn("SnapStart afterRestore warmup failed, continuing anyway", e);
                }
            }
        };
        REGISTERED.add(resource);
        Core.getGlobalContext().register(resource);
    }
}
