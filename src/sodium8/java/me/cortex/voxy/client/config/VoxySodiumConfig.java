package me.cortex.voxy.client.config;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.VoxyDiag;
import me.cortex.voxy.common.network.VoxyNetworkHandler;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers Voxy's options with Sodium 0.8.x, which dropped the
 * {@code SodiumOptionsGUI} class that {@code MixinSodiumOptionsGUI} injects into
 * and replaced it with this registration API.
 *
 * <p>
 * This class lives in the {@code sodium8} source set and is compiled against
 * Sodium 0.8.12. Sodium 0.6.x has no {@code net.caffeinemc.mods.sodium.api.config}
 * package and never discovers the entry point, so the class is simply never
 * loaded there and the mixin keeps serving that branch. The two paths are
 * mutually exclusive and describe the same options.
 */
@ConfigEntryPointForge("voxy")
public class VoxySodiumConfig implements ConfigEntryPoint {
    /** Field defaults, used as each option's reset target. */
    private static final VoxyConfig DEFAULTS = new VoxyConfig();

    private static final StorageEventHandler SAVE = () -> VoxyConfig.CONFIG.save();

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        if (!VoxyCommon.isAvailable()) {
            return;
        }

        builder.registerOwnModOptions()
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("voxy.config.title"))
                        .addOptionGroup(generalGroup(builder))
                        .addOptionGroup(threadingGroup(builder))
                        .addOptionGroup(renderingGroup(builder)));
    }

    private static OptionGroupBuilder generalGroup(ConfigBuilder builder) {
        return builder.createOptionGroup()
                .addOption(builder.createBooleanOption(id("enabled"))
                        .setName(Component.translatable("voxy.config.general.enabled"))
                        .setTooltip(Component.translatable("voxy.config.general.enabled.tooltip"))
                        .setDefaultValue(DEFAULTS.enabled)
                        .setStorageHandler(SAVE)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setBinding(v -> {
                            VoxyConfig.CONFIG.enabled = v;
                            if (v) {
                                if (VoxyClientInstance.isInGame) {
                                    VoxyCommon.createInstance();
                                    var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                    if (vrsh != null && VoxyConfig.CONFIG.enableRendering) {
                                        vrsh.createRenderer();
                                    }
                                }
                            } else {
                                var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                if (vrsh != null) {
                                    vrsh.shutdownRenderer();
                                }
                                VoxyCommon.shutdownInstance();
                            }
                        }, () -> VoxyConfig.CONFIG.enabled));
    }

    private static OptionGroupBuilder threadingGroup(ConfigBuilder builder) {
        return builder.createOptionGroup()
                .addOption(builder.createIntegerOption(id("service_threads"))
                        .setName(Component.translatable("voxy.config.general.serviceThreads"))
                        .setTooltip(Component.translatable("voxy.config.general.serviceThreads.tooltip"))
                        // Core count rather than thread count: the default is half the core
                        // count, so this is the realistic ceiling.
                        .setRange(1, CpuLayout.getCoreCount(), 1)
                        .setValueFormatter(v -> Component.literal(Integer.toString(v)))
                        .setDefaultValue(DEFAULTS.serviceThreads)
                        .setStorageHandler(SAVE)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding(v -> {
                            VoxyConfig.CONFIG.serviceThreads = v;
                            var instance = VoxyCommon.getInstance();
                            if (instance != null) {
                                instance.updateDedicatedThreads();
                            }
                        }, () -> VoxyConfig.CONFIG.serviceThreads))
                .addOption(builder.createIntegerOption(id("bake_threads"))
                        .setName(Component.translatable("voxy.config.general.bakeThreads"))
                        .setTooltip(Component.translatable("voxy.config.general.bakeThreads.tooltip"))
                        .setRange(1, CpuLayout.getCoreCount(), 1)
                        .setValueFormatter(v -> Component.literal(Integer.toString(v)))
                        .setDefaultValue(DEFAULTS.bakeThreads)
                        .setStorageHandler(SAVE)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setBinding(v -> VoxyConfig.CONFIG.bakeThreads = v, () -> VoxyConfig.CONFIG.bakeThreads))
                .addOption(builder.createBooleanOption(id("use_sodium_builder"))
                        .setName(Component.translatable("voxy.config.general.useSodiumBuilder"))
                        .setTooltip(Component.translatable("voxy.config.general.useSodiumBuilder.tooltip"))
                        // The stored field is inverted relative to what the option shows.
                        .setDefaultValue(!DEFAULTS.dontUseSodiumBuilderThreads)
                        .setStorageHandler(SAVE)
                        .setImpact(OptionImpact.VARIES)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setBinding(v -> {
                            VoxyConfig.CONFIG.dontUseSodiumBuilderThreads = !v;
                            var instance = VoxyCommon.getInstance();
                            if (instance != null) {
                                instance.updateDedicatedThreads();
                            }
                        }, () -> !VoxyConfig.CONFIG.dontUseSodiumBuilderThreads))
                .addOption(builder.createBooleanOption(id("ingest"))
                        .setName(Component.translatable("voxy.config.general.ingest"))
                        .setTooltip(Component.translatable("voxy.config.general.ingest.tooltip"))
                        .setDefaultValue(DEFAULTS.ingestEnabled)
                        .setStorageHandler(SAVE)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding(v -> VoxyConfig.CONFIG.ingestEnabled = v, () -> VoxyConfig.CONFIG.ingestEnabled))
                .addOption(builder.createEnumOption(id("multiplayer_mode"), VoxyConfig.MultiplayerMode.class)
                        .setName(Component.translatable("voxy.config.general.multiplayerMode"))
                        .setTooltip(Component.translatable("voxy.config.general.multiplayerMode.tooltip"))
                        .setElementNameProvider(mode -> switch (mode) {
                            case AUTO -> Component.translatable("voxy.config.general.multiplayerMode.auto");
                            case CLIENT_ONLY -> Component.translatable("voxy.config.general.multiplayerMode.client_only");
                            case SERVER_STREAM -> Component
                                    .translatable("voxy.config.general.multiplayerMode.server_stream");
                        })
                        .setDefaultValue(DEFAULTS.multiplayerMode)
                        .setStorageHandler(SAVE)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding(v -> VoxyConfig.CONFIG.multiplayerMode = v,
                                () -> VoxyConfig.CONFIG.multiplayerMode));
    }

    private static OptionGroupBuilder renderingGroup(ConfigBuilder builder) {
        return builder.createOptionGroup()
                .addOption(builder.createBooleanOption(id("rendering"))
                        .setName(Component.translatable("voxy.config.general.rendering"))
                        .setTooltip(Component.translatable("voxy.config.general.rendering.tooltip"))
                        .setDefaultValue(DEFAULTS.enableRendering)
                        .setStorageHandler(SAVE)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding(v -> {
                            VoxyConfig.CONFIG.enableRendering = v;
                            var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                            if (vrsh != null) {
                                if (v) {
                                    vrsh.createRenderer();
                                } else {
                                    vrsh.shutdownRenderer();
                                }
                            }
                        }, () -> VoxyConfig.CONFIG.enableRendering))
                .addOption(builder.createIntegerOption(id("sub_division_size"))
                        .setName(Component.translatable("voxy.config.general.subDivisionSize"))
                        .setTooltip(Component.translatable("voxy.config.general.subDivisionSize.tooltip"))
                        .setRange(0, SUBDIV_IN_MAX, 1)
                        .setValueFormatter(v -> Component.literal(Integer.toString(Math.round(ln2subDiv(v)))))
                        .setDefaultValue(subDiv2ln(DEFAULTS.subDivisionSize))
                        .setStorageHandler(SAVE)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding(v -> VoxyConfig.CONFIG.subDivisionSize = ln2subDiv(v),
                                () -> subDiv2ln(VoxyConfig.CONFIG.subDivisionSize)))
                .addOption(builder.createIntegerOption(id("render_distance"))
                        .setName(Component.translatable("voxy.config.general.renderDistance"))
                        .setTooltip(Component.translatable("voxy.config.general.renderDistance.tooltip"))
                        // Every unit is 32 vanilla chunks. Max 128 = 4096 chunks (~65 km).
                        .setRange(2, 128, 1)
                        .setValueFormatter(v -> Component.literal(Integer.toString(v * 32)))
                        .setDefaultValue(DEFAULTS.sectionRenderDistance)
                        .setStorageHandler(SAVE)
                        .setImpact(OptionImpact.LOW)
                        .setBinding(v -> {
                            VoxyConfig.CONFIG.sectionRenderDistance = v;
                            var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                            if (vrsh != null) {
                                var vrs = vrsh.getVoxyRenderSystem();
                                if (vrs != null) {
                                    vrs.setRenderDistance(v);
                                }
                            }
                            // Inform the server so it can clamp its streaming radius.
                            VoxyNetworkHandler.sendClientHint();
                        }, () -> VoxyConfig.CONFIG.sectionRenderDistance))
                .addOption(builder.createBooleanOption(id("vanilla_fog"))
                        .setName(Component.translatable("voxy.config.general.vanilla_fog"))
                        .setTooltip(Component.translatable("voxy.config.general.vanilla_fog.tooltip"))
                        .setDefaultValue(DEFAULTS.renderVanillaFog)
                        .setStorageHandler(SAVE)
                        .setBinding(v -> VoxyConfig.CONFIG.renderVanillaFog = v,
                                () -> VoxyConfig.CONFIG.renderVanillaFog))
                .addOption(builder.createBooleanOption(id("high_res_model_textures"))
                        .setName(Component.translatable("voxy.config.general.high_res_model_textures"))
                        .setTooltip(Component.translatable("voxy.config.general.high_res_model_textures.tooltip"))
                        .setDefaultValue(DEFAULTS.highResModelTextures)
                        .setStorageHandler(SAVE)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding(v -> VoxyConfig.CONFIG.highResModelTextures = v,
                                () -> VoxyConfig.CONFIG.highResModelTextures))
                .addOption(builder.createBooleanOption(id("render_statistics"))
                        .setName(Component.translatable("voxy.config.general.render_statistics"))
                        .setTooltip(Component.translatable("voxy.config.general.render_statistics.tooltip"))
                        // Backed by a static flag rather than by VoxyConfig, matching the
                        // behaviour of the 0.6.x page.
                        .setDefaultValue(DEFAULTS.renderStatistics)
                        .setStorageHandler(SAVE)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setBinding(v -> RenderStatistics.enabled = v, () -> RenderStatistics.enabled))
                .addOption(builder.createBooleanOption(id("debug_dump_on_screenshot"))
                        .setName(Component.translatable("voxy.config.general.debug_dump_on_screenshot"))
                        .setTooltip(Component.translatable("voxy.config.general.debug_dump_on_screenshot.tooltip"))
                        .setDefaultValue(DEFAULTS.debugDumpOnScreenshot)
                        .setStorageHandler(SAVE)
                        .setBinding(v -> VoxyConfig.CONFIG.debugDumpOnScreenshot = v,
                                () -> VoxyConfig.CONFIG.debugDumpOnScreenshot))
                .addOption(builder.createBooleanOption(id("log_gap_diag"))
                        .setName(Component.translatable("voxy.config.general.log_gap_diag"))
                        .setTooltip(Component.translatable("voxy.config.general.log_gap_diag.tooltip"))
                        .setDefaultValue(DEFAULTS.logGapDiag)
                        .setStorageHandler(SAVE)
                        .setBinding(v -> VoxyConfig.CONFIG.logGapDiag = v, () -> VoxyConfig.CONFIG.logGapDiag))
                .addOption(builder.createBooleanOption(id("diag_first_connect"))
                        .setName(Component.translatable("voxy.config.general.diag_first_connect"))
                        .setTooltip(Component.translatable("voxy.config.general.diag_first_connect.tooltip"))
                        .setDefaultValue(DEFAULTS.diagFirstConnect)
                        .setStorageHandler(SAVE)
                        .setBinding(v -> {
                            VoxyConfig.CONFIG.diagFirstConnect = v;
                            VoxyDiag.setEnabled(v);
                        }, () -> VoxyConfig.CONFIG.diagFirstConnect));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("voxy", path);
    }

    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX / SUBDIV_MIN) / Math.log(2);

    /** Maps the 0..SUBDIV_IN_MAX slider position onto the 28..256 range. */
    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN * Math.pow(2, SUBDIV_CONST * ((double) in / SUBDIV_IN_MAX)));
    }

    /** Inverse of {@link #ln2subDiv(int)}. */
    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double) in) / SUBDIV_MIN) / Math.log(2)) / SUBDIV_CONST) * SUBDIV_IN_MAX);
    }
}
