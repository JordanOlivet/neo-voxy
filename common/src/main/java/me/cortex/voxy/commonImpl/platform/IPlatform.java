package me.cortex.voxy.commonImpl.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Mod loader queries needed by the common code. One implementation per loader,
 * discovered through {@link Services} (ADR-008).
 */
public interface IPlatform {

    /** Loader name as shown to users, e.g. {@code "neoforge"} or {@code "fabric"}. */
    String loaderName();

    /** Version of the loader itself, or {@code "unknown"}. */
    String loaderVersion();

    boolean isDedicatedServer();

    Path configDir();

    Path gameDir();

    boolean isModLoaded(String modId);

    /** Version string of a loaded mod, empty if the mod is absent. */
    Optional<String> modVersion(String modId);

    /** Root paths of a mod's files (jar roots or dev output folders), empty if absent. */
    List<Path> modRootPaths(String modId);
}
