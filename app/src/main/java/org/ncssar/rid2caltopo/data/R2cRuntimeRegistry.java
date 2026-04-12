package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for instance-scoped runtimes.
 *
 * Production currently uses a single default runtime. Tests can register and
 * swap runtimes as the remaining singleton seams are extracted.
 */
public final class R2cRuntimeRegistry {
    private static final Map<String, R2cRuntime> REGISTERED = new ConcurrentHashMap<>();
    private static volatile R2cRuntime defaultRuntime =
            new R2cRuntime("default",
                    DefaultPeerCoordinator.getInstance(),
                    DefaultCalTopoSessionGateway.getInstance(),
                    DefaultTrackerPublisher.getInstance());

    private R2cRuntimeRegistry() { }

    @NonNull
    public static R2cRuntime getDefaultRuntime() {
        return defaultRuntime;
    }

    public static void registerRuntime(@NonNull R2cRuntime runtime) {
        REGISTERED.put(runtime.getRuntimeId(), runtime);
    }

    @Nullable
    public static R2cRuntime getRuntime(@NonNull String runtimeId) {
        return REGISTERED.get(runtimeId);
    }

    public static void unregisterRuntime(@NonNull String runtimeId) {
        REGISTERED.remove(runtimeId);
    }

    public static void setDefaultRuntimeForTesting(@NonNull R2cRuntime runtime) {
        defaultRuntime = runtime;
        registerRuntime(runtime);
    }

    public static void resetDefaultRuntimeForTesting() {
        defaultRuntime = new R2cRuntime("default",
                DefaultPeerCoordinator.getInstance(),
                DefaultCalTopoSessionGateway.getInstance(),
                DefaultTrackerPublisher.getInstance());
        REGISTERED.clear();
    }
}
