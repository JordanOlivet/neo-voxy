package me.cortex.voxy.neoforge;

import me.cortex.voxy.commonImpl.platform.IPlatform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class NeoForgePlatform implements IPlatform {

    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public String loaderVersion() {
        return FMLLoader.versionInfo().neoForgeVersion();
    }

    @Override
    public boolean isDedicatedServer() {
        return FMLLoader.getDist() == Dist.DEDICATED_SERVER;
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        // ModList is not built yet while early mixin configs load; fall back to
        // the loading-time mod list then.
        var modList = ModList.get();
        if (modList != null) {
            return modList.isLoaded(modId);
        }
        return FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }

    @Override
    public Optional<String> modVersion(String modId) {
        var modList = ModList.get();
        if (modList == null) {
            return Optional.empty();
        }
        return modList.getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString());
    }

    @Override
    public List<Path> modRootPaths(String modId) {
        var modList = ModList.get();
        if (modList == null) {
            return List.of();
        }
        var modFile = modList.getModFileById(modId);
        if (modFile == null) {
            return List.of();
        }
        return List.of(modFile.getFile().getSecureJar().getRootPath());
    }
}
