package me.cortex.voxy.neoforge;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyCommands;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.server.VoxyServerCommands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(VoxyNeoForge.MODID)
public class VoxyNeoForge {
    public static final String MODID = "voxy";

    public VoxyNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        // Init common environment
        VoxyCommon.cleanInit(modContainer.getModInfo().getVersion().toString(),
                FMLLoader.getDist() == Dist.DEDICATED_SERVER);

        // Register network handler (both client and server)
        modEventBus.addListener(NeoForgeNetworking::register);

        // Register server event handlers (for dedicated server and integrated server)
        NeoForge.EVENT_BUS.register(NeoForgeServerEvents.class);

        // Register server commands event listener
        NeoForge.EVENT_BUS.addListener(this::onRegisterServerCommands);

        // Register client setup event
        if (FMLLoader.getDist() == Dist.CLIENT) {
            modEventBus.addListener(this::onClientSetup);
            NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
            NeoForge.EVENT_BUS.register(NeoForgeClientEvents.class);
        }
    }

    private void onRegisterServerCommands(RegisterCommandsEvent event) {
        VoxyServerCommands.register(event.getDispatcher());
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            VoxyClient.initVoxyClient();
            VoxyClient.onClientSetup();
        });
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }
}
