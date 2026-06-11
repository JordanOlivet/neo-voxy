package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

import java.util.ArrayList;
import java.util.List;

//F3 debug-screen contributor. Hooks NeoForge's CustomizeGuiOverlayEvent.DebugText (the 1.21.1
// equivalent of Fabric's DebugScreenEntry) and appends Voxy instance + render-system lines,
// including the RenderStatistics counters when the toggle is on.
public class VoxyDebugScreenEntry {

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!Minecraft.getInstance().getDebugOverlay().showDebugScreen()) return;

        List<String> lines = event.getRight();

        if (!VoxyCommon.isAvailable()) {
            lines.add(ChatFormatting.RED + "voxy-" + VoxyCommon.MOD_VERSION);
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            lines.add(ChatFormatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);
            return;
        }

        VoxyRenderSystem vrs = null;
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr instanceof IGetVoxyRenderSystem holder) {
            vrs = holder.getVoxyRenderSystem();
        }

        lines.add((vrs == null ? ChatFormatting.DARK_GREEN : ChatFormatting.GREEN) + "voxy-" + VoxyCommon.MOD_VERSION);

        List<String> instanceLines = new ArrayList<>();
        instance.addDebug(instanceLines);
        lines.addAll(instanceLines);

        if (vrs != null) {
            List<String> renderLines = new ArrayList<>();
            vrs.addDebugInfo(renderLines);
            lines.addAll(renderLines);
        }
    }
}
