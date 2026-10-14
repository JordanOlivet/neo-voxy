package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlDebug;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.io.PrintWriter;
import java.io.StringWriter;

// GlDebug.LogEntry is package-private in 1.21.1, so we treat msgObj as Object
// and rely on toString() for the underlying message text.
@Mixin(GlDebug.class)
public class MixinGlDebug {
    @WrapOperation(
        method = "printDebugLog",
        at = @At(
            value = "INVOKE",
            target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V",
            remap = false
        )
    )
    private static void voxy$wrapDebug(Logger logger, String base, Object msgObj, Operation<Void> original) {
        if (msgObj != null) {
            var throwable = new Throwable(msgObj.toString());
            if (voxy$isCausedByVoxy(throwable.getStackTrace())) {
                original.call(logger, base + "\n" + voxy$getStackTraceAsString(throwable), throwable);
                return;
            }
        }
        original.call(logger, base, msgObj);
    }

    @Unique
    private static String voxy$getStackTraceAsString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    @Unique
    private static boolean voxy$isCausedByVoxy(StackTraceElement[] elements) {
        for (StackTraceElement element : elements) {
            if (element.getClassName().startsWith("me.cortex.voxy")) {
                return true;
            }
        }
        return false;
    }
}
