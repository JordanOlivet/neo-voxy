package me.cortex.voxy.client.debug;

import me.cortex.voxy.client.config.VoxyConfig;

import java.io.File;

// Called by each loader when a screenshot is taken (render thread, before the PNG is
// asynchronously written to disk) and writes a sidecar .txt next to the screenshot
// containing player position, Voxy state, render stats, GL info, etc. Gated on
// VoxyConfig.debugDumpOnScreenshot. Failures are logged and never propagate.
public class DebugSidecarListener {

    public static void onScreenshot(File png) {
        if (!VoxyConfig.CONFIG.debugDumpOnScreenshot) return;
        if (png == null) return;
        DebugContextDumper.dumpTo(png);
    }
}
