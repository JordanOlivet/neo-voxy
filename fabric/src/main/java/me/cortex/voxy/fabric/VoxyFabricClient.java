package me.cortex.voxy.fabric;

import me.cortex.voxy.client.ClientChunkIngestListener;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyCommands;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.network.chat.Component;

/**
 * Fabric client entry point. {@code VoxyClient.initVoxyClient()} itself runs
 * from {@code MixinRenderSystem} once the GL context exists, on both loaders.
 * The screenshot sidecar and the F3 lines come from mixins in
 * {@code me.cortex.voxy.fabric.mixin}, since Fabric API has no such events.
 */
public class VoxyFabricClient implements ClientModInitializer {

    private static final VoxyCommands.CommandFeedback<FabricClientCommandSource> FEEDBACK =
            new VoxyCommands.CommandFeedback<>() {
                @Override
                public void success(FabricClientCommandSource source, Component message) {
                    source.sendFeedback(message);
                }

                @Override
                public void failure(FabricClientCommandSource source, Component message) {
                    source.sendError(message);
                }
            };

    @Override
    public void onInitializeClient() {
        FabricNetworking.registerClient();

        ClientChunkEvents.CHUNK_LOAD.register(ClientChunkIngestListener::onClientChunkLoad);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            if (VoxyCommon.isAvailable()) {
                dispatcher.register(VoxyCommands.register(FEEDBACK));
            }
        });

        VoxyClient.onClientSetup();
    }
}
