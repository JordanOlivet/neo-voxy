package me.cortex.voxy.fabric.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.voxy.client.debug.DebugSidecarListener;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

// Fabric stand-in for NeoForge's ScreenshotEvent: fires on the render thread once the
// target file is known, right before the PNG is handed to the IO pool.
@Mixin(Screenshot.class)
public class MixinScreenshot {
    // File locals in _grab: the gameDirectory parameter, the screenshots folder,
    // then the target file (ordinal 2).
    @Inject(method = "_grab", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;ioPool()Ljava/util/concurrent/ExecutorService;"))
    private static void voxy$onScreenshot(CallbackInfo ci, @Local(ordinal = 2) File screenshotFile) {
        DebugSidecarListener.onScreenshot(screenshotFile);
    }
}
