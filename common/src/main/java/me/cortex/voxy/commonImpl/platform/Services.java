package me.cortex.voxy.commonImpl.platform;

import java.util.ServiceLoader;

/**
 * Locates the loader-specific implementation of each platform interface.
 * Implementations are declared in {@code META-INF/services/<interface name>}
 * inside each loader project (ADR-008).
 */
public final class Services {
    public static final IPlatform PLATFORM = load(IPlatform.class);
    public static final INetworking NETWORKING = load(INetworking.class);

    private Services() {
    }

    public static <T> T load(Class<T> clazz) {
        return ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No platform implementation found for " + clazz.getName()));
    }
}
