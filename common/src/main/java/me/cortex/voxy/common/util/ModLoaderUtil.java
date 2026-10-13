package me.cortex.voxy.common.util;

import me.cortex.voxy.commonImpl.platform.Services;

import java.nio.file.Path;

/**
 * Shorthand for the most common loader queries, backed by the platform service.
 */
public class ModLoaderUtil {

    public static Path getConfigDir() {
        return Services.PLATFORM.configDir();
    }

    public static Path getGameDir() {
        return Services.PLATFORM.gameDir();
    }

    public static boolean isModLoaded(String modId) {
        return Services.PLATFORM.isModLoaded(modId);
    }
}
