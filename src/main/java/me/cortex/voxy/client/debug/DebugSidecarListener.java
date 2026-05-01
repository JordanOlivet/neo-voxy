package me.cortex.voxy.client.debug;

import me.cortex.voxy.client.config.VoxyConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenshotEvent;

import java.io.File;

// Listens to NeoForge's ScreenshotEvent (fired on the render thread before the PNG is
// asynchronously written to disk) and writes a sidecar .txt next to the screenshot
// containing player position, Voxy state, render stats, GL info, etc. Gated on
// VoxyConfig.debugDumpOnScreenshot. Failures are logged and never propagate.
public class DebugSidecarListener {

    @SubscribeEvent
    public static void onScreenshot(ScreenshotEvent event) {
        if (!VoxyConfig.CONFIG.debugDumpOnScreenshot) return;
        File png = event.getScreenshotFile();
        if (png == null) return;
        DebugContextDumper.dumpTo(png);
    }
}
