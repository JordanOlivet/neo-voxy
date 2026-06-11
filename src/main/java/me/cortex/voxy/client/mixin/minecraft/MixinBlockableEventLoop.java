package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.LoadException;
import net.minecraft.util.thread.BlockableEventLoop;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// 1.21.1 BlockableEventLoop.doRunTask catches Exception and logs via
// LOGGER.error(FATAL_MARKER, "Error executing task on {}", name(), exception)
// without checking isNonRecoverable (that method doesn't exist on this version).
// We redirect that error call so a LoadException escapes the catch handler.
@Mixin(BlockableEventLoop.class)
public abstract class MixinBlockableEventLoop {
    @Redirect(
        method = "doRunTask",
        at = @At(
            value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Lorg/slf4j/Marker;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
            remap = false
        )
    )
    private void voxy$forceCrashOnError(Logger logger, Marker marker, String message, Object nameArg, Object exception) {
        if (exception instanceof LoadException le) {
            if (le.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw le;
        }
        logger.error(marker, message, nameArg, exception);
    }
}
