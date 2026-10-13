package me.cortex.voxy.client.core;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30.glGetIntegeri;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {
    private final WorldEngine worldIn;

    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    private final RenderDistanceTracker renderDistanceTracker;
    public final ChunkBoundRenderer chunkBoundRenderer;

    private final ViewportSelector<?> viewportSelector;

    private final AbstractRenderPipeline pipeline;

    // LOD streaming reception service
    private LodReceptionService lodReceptionService;

    private Viewport<?> deferredViewport;
    private int deferredSourceFB;

    private static AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRenderBackendFactory() {
        // TODO: need todo a thing where selects optimal section render based on if
        // supports the pipeline and geometry data type
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        // Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even
        // if the rest of loading takes more
        // than timeout, we keep the world acquired
        world.acquireRef();
        System.gc();

        // Fking HATE EVERYTHING AAAAAAAAAAAAAAAA
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        try {
            // wait for opengl to be finished, this should hopefully ensure all memory
            // allocations are free
            glFinish();
            glFinish();

            this.worldIn = world;

            var backendFactory = getRenderBackendFactory();

            {
                this.modelService = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm,
                        IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));

                this.geometryData = new BasicSectionGeometryData(1 << 20, RenderResourceReuse.getOrCreateGeometryBuffer());

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                // Pre-bake common block models to reduce likelihood of black faces on initial
                // join
                prebakeCommonModels(world, this.modelService);

                this.nodeManager.start();
            }

            this.pipeline = RenderPipelineFactory.createPipeline(this.nodeManager, this.nodeCleaner, this.traversal,
                    this::frexStillHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);// Configure the model service
            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                int minSec = Minecraft.getInstance().level.getMinSection() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSection() - 1) >> 5;

                // Do some very cheeky stuff for MiB
                if (VoxyCommon.IS_MINE_IN_ABYSS) {// TODO: make this somehow configurable
                    minSec = -8;
                    maxSec = 7;
                }

                this.renderDistanceTracker = new RenderDistanceTracker(20,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.chunkBoundRenderer = new ChunkBoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + this.geometryData.getMaxCapacity() + " geometry capacity, using pipeline '"
                    + this.pipeline.getClass().getSimpleName() + "' with renderer '"
                    + sectionRenderer.getClass().getSimpleName() + "'");

            // Initialize LOD reception service for network streaming
            this.lodReceptionService = new LodReceptionService(world, world.getMapper(), this.modelService);
        } catch (RuntimeException e) {
            world.releaseRef();// If something goes wrong, we must release the world first
            throw e;
        }

        for (int i = 0; i < oldBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
        }

        for (int i = 0; i < 12; i++) {
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0 + i);
            GlStateManager._bindTexture(0);
            glBindSampler(i, 0);
        }
    }

    public Viewport<?> setupViewport(ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }

        // Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int) Math.floor(cameraX) >> 4) + 512) >> 10;
            cameraX -= sector << 14;// 10+4
            cameraY += (16 + (256 - 32 - sector * 30)) * 16;
        }

        // cameraY += 100;
        var projection = computeProjectionMat(matrices.projection());// RenderSystem.getProjectionMatrix();
        // var projection = ShadowMatrices.createOrthoMatrix(160, -16*300, 16*300);
        // var projection = new Matrix4f(matrices.projection());

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        int width = dims[2];
        int height = dims[3];

        {// Apply render scaling factor
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width * factor[0]);
                height = (int) (height * factor[1]);
            }
        }

        viewport
                .setVanillaProjection(matrices.projection())
                .setProjection(projection)
                .setModelView(new Matrix4f(matrices.modelView()))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .update();

        if (VoxyClient.getOcclusionDebugState() == 0) {
            viewport.frameId++;
        }

        return viewport;
    }

    public void renderOpaque(Viewport<?> viewport) {
        if (viewport == null) {
            return;
        }

        TimingStatistics.resetSamplers();

        long startTime = System.nanoTime();
        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();// Start marker
        TimingStatistics.main.start();

        // TODO: optimize
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int boundFB = oldFB;

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        glViewport(0, 0, viewport.width, viewport.height);

        // var target = DefaultTerrainRenderPasses.CUTOUT.getTarget();
        // boundFB = ((net.minecraft.client.texture.GlTexture)
        // target.getColorAttachment()).getOrCreateFramebuffer(((GlBackend)
        // RenderSystem.getDevice()).getFramebufferManager(),
        // target.getDepthAttachment());
        if (boundFB == 0) {
            throw new IllegalStateException("Cannot use the default framebuffer as cannot source from it");
        }

        // this.autoBalanceSubDivSize();

        this.pipeline.preSetup(viewport);

        TimingStatistics.E.start();
        if ((!VoxyClient.disableSodiumChunkRender()) && !IrisUtil.irisShadowActive()) {
            this.chunkBoundRenderer.render(viewport);
        } else {
            viewport.depthBoundingBuffer.clear(0);
        }
        TimingStatistics.E.stop();

        // The entire rendering pipeline (excluding the chunkbound thing)
        this.pipeline.runPipeline(viewport, boundFB, dims[2], dims[3]);

        this.deferredViewport = viewport;
        this.deferredSourceFB = boundFB;

        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        PrintfDebugUtil.tick();

        // As much dynamic runtime stuff here
        {
            // Tick upload stream (this is ok to do here as upload ticking is just memory
            // management)
            UploadStream.INSTANCE.tick();

            // Tick LOD reception service to process pull-based requests
            if (this.lodReceptionService != null) {
                this.lodReceptionService.tick();
            }

            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ)
                    && VoxyClient.isFrexActive())
                ;// While FF is active, run until everything is processed
            TimingStatistics.H.start();
            // Done here as is allows less gl state resetup
            do {
                this.modelService.tick(900_000);
            } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
            TimingStatistics.H.stop();
        }
        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.tick();

        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
        glViewport(dims[0], dims[1], dims[2], dims[3]);

        {// Reset state manager stuffs
            glUseProgram(0);
            glEnable(GL_DEPTH_TEST);

            GlStateManager._glBindVertexArray(0);// Clear binding

            GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0 + i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }

            IrisUtil.clearIrisSamplers();// Thanks iris (sigh)

            // TODO: should/needto actually restore all of these, not just clear them
            // Clear all the bindings
            for (int i = 0; i < oldBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
            }

            // ((SodiumShader)
            // Iris.getPipelineManager().getPipelineNullable().getSodiumPrograms().getProgram(DefaultTerrainRenderPasses.CUTOUT).getInterface()).setupState(DefaultTerrainRenderPasses.CUTOUT,
            // fogParameters);
        }

        TimingStatistics.all.stop();

        TimingStatistics.I.start();
        glFlush();
        TimingStatistics.I.stop();

        /*
         * TimingStatistics.F.start();
         * this.postProcessing.setup(viewport.width, viewport.height, boundFB);
         * TimingStatistics.F.stop();
         * 
         * this.renderer.renderFarAwayOpaque(viewport,
         * this.chunkBoundRenderer.getDepthBoundTexture());
         * 
         * 
         * TimingStatistics.F.start();
         * //Compute the SSAO of the rendered terrain, TODO: fix it breaking depth or
         * breaking _something_ am not sure what
         * this.postProcessing.computeSSAO(viewport.MVP);
         * TimingStatistics.F.stop();
         * 
         * TimingStatistics.G.start();
         * //We can render the translucent directly after as it is the furthest
         * translucent objects
         * this.renderer.renderFarAwayTranslucent(viewport,
         * this.chunkBoundRenderer.getDepthBoundTexture());
         * TimingStatistics.G.stop();
         * 
         * 
         * TimingStatistics.F.start();
         * this.postProcessing.renderPost(viewport, matrices.projection(), boundFB);
         * TimingStatistics.F.stop();
         */
    }

    public void blitOverTranslucent() {
        if (this.deferredViewport == null) return;
        var viewport = this.deferredViewport;
        int sourceFB = this.deferredSourceFB;
        this.deferredViewport = null;

        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        this.pipeline.blitOverTranslucent(viewport, sourceFB);
        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
    }

    private void autoBalanceSubDivSize() {
        // only increase quality while there are very few mesh queues, this stops,
        // e.g. while flying and is rendering alot of low quality chunks
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        // Auto fps targeting
        if (Minecraft.getInstance().getFps() < MIN_FPS) {
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize
                    + INCREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 256);
        }

        if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize
                    - DECREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 28);
        }
    }

    private static Matrix4f makeProjectionMatrix(float near, float far) {
        // TODO: use the existing projection matrix use mulLocal by the inverse of the
        // projection and then mulLocal our projection

        var projection = new Matrix4f();
        var client = Minecraft.getInstance();
        var gameRenderer = client.gameRenderer;// tickCounter.getTickDelta(true);

        float fov = (float) gameRenderer.getFov(gameRenderer.getMainCamera(),
                client.getTimer().getGameTimeDeltaPartialTick(true), true);

        projection.setPerspective(fov * 0.01745329238474369f,
                (float) client.getWindow().getWidth() / (float) client.getWindow().getHeight(),
                near, far);
        return projection;
    }

    // TODO: Make a reverse z buffer
    private static Matrix4f computeProjectionMat(Matrix4fc base) {
        return base.mulLocal(
                makeProjectionMatrix(0.05f, Minecraft.getInstance().gameRenderer.getDepthFar()).invert(),
                new Matrix4f())
                .mulLocal(makeProjectionMatrix(VoxyClient.getOcclusionDebugState() <= 1 ? 1f : 0.1f, 16 * 3000));
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) {
            return false;
        }
        // If frex is running we must tick everything to ensure correctness
        UploadStream.INSTANCE.tick();
        // Done here as is allows less gl state resetup
        this.modelService.tick(100_000_000);
        GL11.glFinish();
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount() != 0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(int renderDistance) {
        this.renderDistanceTracker.setRenderDistance(renderDistance+1);//the +1 is to cover the outer ring of chunks when rendering a circle
    }

    public Viewport<?> getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewportSelector.getViewport();
    }

    public boolean dumpModelAtlas(java.io.File out) {
        return this.modelService.getStore().dumpAtlasToPng(out);
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("Buf/Tex [#/Mb]: [" + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize() / 1_000_000) + "],["
                + GlTexture.getCount() + "/" + (GlTexture.getEstimatedTotalSize() / 1_000_000) + "]");
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            var player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null) {
                debug.add(this.nodeManager.dumpNodeHierarchy(
                        player.getBlockX(), player.getBlockY(), player.getBlockZ()));
                debug.add(this.nodeManager.dumpL0Grid(
                        player.getBlockX(), player.getBlockY(), player.getBlockZ()));
                debug.add(this.nodeManager.dumpGapTree(
                        player.getBlockX(), player.getBlockY(), player.getBlockZ()));
            }
        }
        {
            TimingStatistics.update();
            debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", "
                    + TimingStatistics.main.pVal() + ", " + TimingStatistics.postDynamic.pVal() + ", "
                    + TimingStatistics.all.pVal());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", "
                    + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: " + TimingStatistics.E.pVal() + ", " + TimingStatistics.F.pVal() + ", "
                    + TimingStatistics.G.pVal() + ", " + TimingStatistics.H.pVal() + ", " + TimingStatistics.I.pVal());
        }
        PrintfDebugUtil.addToOut(debug);
    }

    public void shutdown() {
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down rendering");
        try {
            // Cleanup callbacks
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();

            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.traversal.free();
            this.nodeCleaner.free();

            var geometryDataToReuse = (BasicSectionGeometryData) this.geometryData;
            this.geometryData.free();
            if (geometryDataToReuse.isExternalGeometryBuffer) {
                RenderResourceReuse.giveBackGeometryBuffer(geometryDataToReuse.getGeometryBuffer());
            }
            this.chunkBoundRenderer.free();

            this.viewportSelector.free();

            // Shutdown LOD streaming reception
            if (this.lodReceptionService != null) {
                this.lodReceptionService.close();
                this.lodReceptionService = null;
            }
        } catch (Exception e) {
            Logger.error("Error shutting down renderer components", e);
        }
        Logger.info("Shutting down render pipeline");
        try {
            this.pipeline.free();
        } catch (Exception e) {
            Logger.error("Error releasing render pipeline", e);
        }

        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();

        // Release hold on the world
        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }

    // Block path-names whose every {@link BlockState} should be pre-baked at
    // world load. Curated to cover world-gen and the broad set of decorative
    // blocks players touch most. Pairs with {@link #PREBAKE_BLOCK_SUFFIXES} —
    // anything matching either is enumerated. Excluding glazed_terracotta /
    // stairs / slabs / walls keeps the bake count bounded (Mojang stairs+slabs
    // alone would be 100+ block types × dozens of blockstates each).
    private static final java.util.Set<String> PREBAKE_BLOCK_EXACT = java.util.Set.of(
            "stone", "cobblestone", "mossy_cobblestone", "smooth_stone",
            "granite", "andesite", "diorite",
            "polished_granite", "polished_andesite", "polished_diorite",
            "calcite", "tuff", "dripstone_block", "pointed_dripstone",
            "deepslate", "cobbled_deepslate", "polished_deepslate",
            "basalt", "smooth_basalt", "blackstone", "gilded_blackstone",
            "netherrack", "soul_sand", "soul_soil",
            "end_stone", "obsidian", "crying_obsidian", "magma_block",
            "glowstone", "shroomlight",
            "dirt", "grass_block", "podzol", "mycelium",
            "coarse_dirt", "rooted_dirt", "mud", "packed_mud",
            "sand", "red_sand", "gravel", "suspicious_sand", "suspicious_gravel",
            "snow", "snow_block", "ice", "packed_ice", "blue_ice", "powder_snow",
            "water", "lava",
            "terracotta",
            "sandstone", "red_sandstone", "smooth_sandstone", "smooth_red_sandstone",
            "cut_sandstone", "chiseled_sandstone", "cut_red_sandstone", "chiseled_red_sandstone",
            "bamboo", "bamboo_block", "bamboo_mosaic",
            "amethyst_block", "budding_amethyst",
            "moss_block", "moss_carpet", "pale_moss_block", "pale_moss_carpet",
            "mangrove_roots", "muddy_mangrove_roots",
            "azalea", "flowering_azalea",
            "pumpkin", "carved_pumpkin", "jack_o_lantern", "melon", "hay_block",
            "clay", "honey_block", "slime_block", "honeycomb_block",
            "nether_quartz_ore", "ancient_debris", "nether_gold_ore",
            "bone_block", "spawner", "barrel",
            "prismarine", "prismarine_bricks", "dark_prismarine", "sea_lantern",
            "iron_block", "gold_block", "diamond_block", "emerald_block",
            "redstone_block", "lapis_block", "coal_block", "copper_block",
            "raw_iron_block", "raw_gold_block", "raw_copper_block",
            "netherite_block",
            "quartz_block", "smooth_quartz", "chiseled_quartz_block",
            "purpur_block", "purpur_pillar", "end_stone_bricks",
            "crimson_nylium", "warped_nylium", "crimson_planks", "warped_planks",
            "warped_wart_block", "nether_wart_block",
            "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks",
            "deepslate_bricks", "cracked_deepslate_bricks", "chiseled_deepslate",
            "deepslate_tiles", "cracked_deepslate_tiles",
            "bricks", "mud_bricks", "nether_bricks", "red_nether_bricks",
            "polished_blackstone", "polished_blackstone_bricks"
    );

    // Block path-name suffixes that pick up the long tail of LOD-visible
    // material types (every wood, every leaf type, every ore variant, all
    // wools/terracottas/concretes). Slabs/stairs/walls are deliberately
    // excluded.
    private static final String[] PREBAKE_BLOCK_SUFFIXES = {
            "_log", "_wood", "_leaves", "_planks",
            "_ore",
            "_terracotta", "_concrete", "_concrete_powder",
            "_wool",
            "_stem", "_hyphae",
            "_sapling"
    };

    // Hard cap on the number of bake requests pre-baking will enqueue. Each
    // request triggers a GL render-to-FBO + readback path on the main thread,
    // so an unbounded burst can stall the title-screen / loading transitions.
    // Empirically the curated list above generates ~1.5k blockstates — the cap
    // is mostly a backstop against future Mojang additions.
    private static final int PREBAKE_MAX_REQUESTS = 2500;

    /**
     * Pre-bakes models for common blocks to avoid black faces on initial join.
     * This gives the model baking system a head start before LOD data arrives.
     * <p>
     * Iterates the block registry, filters by {@link #PREBAKE_BLOCK_EXACT} and
     * {@link #PREBAKE_BLOCK_SUFFIXES}, and enumerates every {@link
     * net.minecraft.world.level.block.state.BlockState} of each matching block
     * so axis / distance / persistent / waterlogged / age variants all get a
     * bake request. Previously the helper only requested 16 hard-coded names
     * via {@code mapper.getOrRegisterBlockStateFromString(name + "[]")}, which
     * meant a single default state per block — the rest hit the mesh-gen
     * retry path on first connect and produced the "Mesh generation delayed"
     * storm.
     */
    private static void prebakeCommonModels(WorldEngine world, ModelBakerySubsystem modelService) {
        var mapper = world.getMapper();
        int matchedBlocks = 0;
        int requested = 0;
        int statesSeen = 0;

        for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            var rl = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            if (rl == null || !"minecraft".equals(rl.getNamespace())) continue;
            String path = rl.getPath();
            // Skip families whose blockstate fan-out is huge and rarely visible in LOD.
            if (path.endsWith("_stairs") || path.endsWith("_slab") || path.endsWith("_wall")
                    || path.endsWith("_fence") || path.endsWith("_fence_gate")
                    || path.endsWith("_door") || path.endsWith("_trapdoor")
                    || path.endsWith("_glazed_terracotta")) {
                continue;
            }
            boolean matches = PREBAKE_BLOCK_EXACT.contains(path);
            if (!matches) {
                for (String suffix : PREBAKE_BLOCK_SUFFIXES) {
                    if (path.endsWith(suffix)) { matches = true; break; }
                }
            }
            if (!matches) continue;
            matchedBlocks++;

            for (var state : block.getStateDefinition().getPossibleStates()) {
                statesSeen++;
                if (requested >= PREBAKE_MAX_REQUESTS) break;
                try {
                    int blockId = mapper.getIdForBlockState(state);
                    if (blockId > 0 && modelService.requestBlockBake(blockId)) {
                        requested++;
                    }
                } catch (Exception ignore) {
                    // Some blocks may not survive the mapper round-trip on exotic mods.
                }
            }
            if (requested >= PREBAKE_MAX_REQUESTS) break;
        }

        Logger.info("Requested pre-baking of " + requested + " block states across "
                + matchedBlocks + " block types (out of " + statesSeen + " enumerated)");
    }

    public LodReceptionService getLodReceptionService() {
        return this.lodReceptionService;
    }
}
