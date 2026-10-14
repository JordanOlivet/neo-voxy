package me.cortex.voxy.fabric.mixin;

import me.cortex.voxy.client.VoxyDebugScreenEntry;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Fabric stand-in for NeoForge's CustomizeGuiOverlayEvent.DebugText: appends the Voxy
// lines to the right-hand column of the F3 screen.
@Mixin(DebugScreenOverlay.class)
public class MixinDebugScreenOverlay {
    @Inject(method = "getSystemInformation", at = @At("RETURN"))
    private void voxy$appendDebugText(CallbackInfoReturnable<List<String>> cir) {
        VoxyDebugScreenEntry.appendDebugText(cir.getReturnValue());
    }
}
