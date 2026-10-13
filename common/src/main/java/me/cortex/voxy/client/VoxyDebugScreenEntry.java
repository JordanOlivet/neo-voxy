package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

//F3 debug-screen contributor. Each loader hands it the right-hand column of the F3 screen
// (NeoForge: CustomizeGuiOverlayEvent.DebugText) and it appends Voxy instance + render-system
// lines, including the RenderStatistics counters when the toggle is on.
public class VoxyDebugScreenEntry {

    public static void appendDebugText(List<String> lines) {
        if (!Minecraft.getInstance().getDebugOverlay().showDebugScreen()) return;

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
