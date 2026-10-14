package me.cortex.voxy.common.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxyNetworkHandlerCapabilitiesTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        VoxyNetworkHandler.removePlayer(a);
        VoxyNetworkHandler.removePlayer(b);
    }

    @Test
    void unknownPlayerIsNotCapable() {
        assertFalse(VoxyNetworkHandler.isPlayerCapable(a));
    }

    @Test
    void setCapableTrueMarksPlayerCapable() {
        VoxyNetworkHandler.setPlayerCapable(a, true);
        assertTrue(VoxyNetworkHandler.isPlayerCapable(a));
    }

    @Test
    void setCapableFalseRemovesPlayer() {
        VoxyNetworkHandler.setPlayerCapable(a, true);
        VoxyNetworkHandler.setPlayerCapable(a, false);
        assertFalse(VoxyNetworkHandler.isPlayerCapable(a));
    }

    @Test
    void removePlayerClearsCapability() {
        VoxyNetworkHandler.setPlayerCapable(a, true);
        VoxyNetworkHandler.removePlayer(a);
        assertFalse(VoxyNetworkHandler.isPlayerCapable(a));
    }

    @Test
    void distinctPlayersTrackedIndependently() {
        VoxyNetworkHandler.setPlayerCapable(a, true);
        assertTrue(VoxyNetworkHandler.isPlayerCapable(a));
        assertFalse(VoxyNetworkHandler.isPlayerCapable(b));
    }
}
