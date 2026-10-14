package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.model.bakery.BudgetBufferRenderer;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.resources.ResourceLocation;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();

    public static void initVoxyClient() {
        Capabilities.init(); // Ensure clinit is called

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters;
        if (systemSupported) {
            SharedIndexBuffer.INSTANCE.id();
            BudgetBufferRenderer.init();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
            Logger.info("Voxy client renderer initialised");

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }
        } else {
            Logger.error("Voxy is unsupported on your system.");
        }
    }

    public static void onClientSetup() {
        // Called from each loader's client entrypoint during client setup.
        // Command registration is handled by the loader entrypoints.

        // Dev check (-Dvoxy.auditMixins=true): load every mixin target now so a
        // mixin that fails to apply on this loader shows up at startup instead of
        // whenever its target class first loads in game.
        if (VoxyCommon.isVerificationFlagOn("auditMixins")) {
            Logger.info("Auditing mixins (voxy.auditMixins)");
            org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
            Logger.info("Mixin audit finished");
        }
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return getOcclusionDebugState() != 0;
    }
}