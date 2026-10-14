package me.cortex.voxy.neoforge;

import me.cortex.voxy.client.ClientChunkIngestListener;
import me.cortex.voxy.client.VoxyDebugScreenEntry;
import me.cortex.voxy.client.debug.DebugSidecarListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.ScreenshotEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Forwards NeoForge client-only events to the common client hooks. Registered
 * on the physical client only.
 */
public class NeoForgeClientEvents {

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        ClientChunkIngestListener.onClientChunkLoad(event.getLevel(), event.getChunk());
    }

    @SubscribeEvent
    public static void onScreenshot(ScreenshotEvent event) {
        DebugSidecarListener.onScreenshot(event.getScreenshotFile());
    }

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        VoxyDebugScreenEntry.appendDebugText(event.getRight());
    }
}
