package me.cortex.voxy.client.config;

import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;

// Adapts VoxyConfig to Sodium's option GUI storage. Kept separate from VoxyConfig
// so the core config class does not link against the Sodium GUI API: this adapter
// is only loaded when the Sodium options screen is built (VoxyConfigScreenPages),
// by which point Sodium's classes are resolvable. See VoxyConfig for context.
public class VoxyOptionStorage implements OptionStorage<VoxyConfig> {
    public static final VoxyOptionStorage INSTANCE = new VoxyOptionStorage();

    @Override
    public VoxyConfig getData() {
        return VoxyConfig.CONFIG;
    }

    @Override
    public void save() {
        VoxyConfig.CONFIG.save();
    }
}
