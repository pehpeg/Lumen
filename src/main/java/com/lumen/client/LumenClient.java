package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lumen's client side: owns the features, ticks them, and draws the overlay layers once per
 * frame in a fixed order (shadows, colored light, then glowing textures on top).
 */
public class LumenClient {

    private static LumenClient instance;
    /** Lumen on/off: the toggle key (default K), and the automated test's comparison screenshots. */
    static boolean active = true;
    private static net.minecraft.client.settings.KeyBinding toggleKey;
    private final Minecraft mc = Minecraft.getMinecraft();

    final EmissiveTextures textures = new EmissiveTextures();
    final LightColors colors = new LightColors();
    final SectionScanner scanner = new SectionScanner(textures, colors);
    final DynamicLights dynamicLights = new DynamicLights(colors);
    final BlockLightSections blockLight = new BlockLightSections();
    final MovingLightOverlay movingLights = new MovingLightOverlay();
    final SunShadows shadows = new SunShadows();
    final GpuLighting gpu = new GpuLighting();

    private int lastScanVersion = -1, glowVersion = -1;
    private final Map<EmissiveTextures.Glow, List<SectionScanner.GlowQuad>> glowGroups = new IdentityHashMap<>();
    private World listenedWorld;

    // CPU time spent by Lumen (for the test report)
    private static long tickNanos, tickCount, renderNanos, renderCount, worstTick;

    static void addTickTime(long nanos) { tickNanos += nanos; worstTick = Math.max(worstTick, nanos); }

    public static void resetTimers() { tickNanos = tickCount = renderNanos = renderCount = worstTick = 0; }

    public static String gpuStats() { return instance == null ? "" : instance.gpu.stats(); }

    public static String timings() {
        return String.format("cpu: tick avg %.3fms worst %.2fms, frame avg %.3fms",
                tickCount == 0 ? 0 : tickNanos / 1e6 / tickCount, worstTick / 1e6,
                renderCount == 0 ? 0 : renderNanos / 1e6 / renderCount);
    }

    public static void init() {
        toggleKey = new net.minecraft.client.settings.KeyBinding("key.lumen.toggle", org.lwjgl.input.Keyboard.KEY_K, "key.categories.lumen");
        net.minecraftforge.fml.client.registry.ClientRegistry.registerKeyBinding(toggleKey);
        instance = new LumenClient();
        MinecraftForge.EVENT_BUS.register(instance);
        MinecraftForge.EVENT_BUS.register(instance.dynamicLights);
        // resource packs changed: emissive textures and texture-derived colors must be re-read
        if (Minecraft.getMinecraft().getResourceManager() instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager()).registerReloadListener(rm -> {
                instance.textures.clear();
                Shapes.clear();
                instance.colors.clearTextureCache();
                instance.scanner.clearCaches();
                instance.blockLight.clear();
                instance.movingLights.clear();
                instance.gpu.clear();
                instance.lastScanVersion = -1;
            });
        }
    }

    public static void onConfigChanged() {
        if (instance == null) return;
        instance.dynamicLights.reloadConfig();
        instance.colors.reloadConfig();
        instance.textures.clear();
        instance.scanner.clearCaches();
        instance.blockLight.clear();
                instance.movingLights.clear();
        instance.shadows.clear();
        instance.gpu.clear();
        instance.lastScanVersion = -1;
    }

    // ---------------------------------------------------------------- ticking

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        World w = mc.world;
        if (w != null && w != listenedWorld) { w.addEventListener(new BlockListener()); listenedWorld = w; }
        if (w == null || mc.player == null) return;
        if (!active) return;
        long t0 = System.nanoTime();
        tickFeatures();
        long dt = System.nanoTime() - t0;
        tickNanos += dt; tickCount++; worstTick = Math.max(worstTick, dt);
    }

    private void tickFeatures() {
        scanner.tick(24);
        if (LumenConfig.colored.enabled) {
            if (scanner.version != lastScanVersion) {
                lastScanVersion = scanner.version;
                blockLight.update(scanner.colorLights());
            }
            boolean onGpu = gpu.active();
            BlockLightSections.gpu = onGpu ? gpu : null;
            blockLight.tick();
            if (onGpu) {
                gpu.tick(blockLight.lights(), dynamicLights.moving());
                if (movingLights.hasAny()) movingLights.clear();   // the GPU draws moving lights
            } else {
                movingLights.update(dynamicLights.moving());
                movingLights.tick(blockLight);
            }
        }
        shadows.tick();
    }

    /** Block changes: rescan that section, rebuild color meshes and shadows around it. */
    private final class BlockListener implements IWorldEventListener {
        @Override public void notifyBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
            if (oldState == newState) return;
            scanner.markDirty(pos);
            blockLight.markDirty(pos);
            gpu.markDirty(pos);
            shadows.markDirty(pos);
        }
        @Override public void notifyLightSet(BlockPos pos) { gpu.markLight(pos); }
        /** Whole chunks arriving at once (many blocks changed, chunk loaded): refresh the GPU's copy. */
        @Override public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
            gpu.markRange(x1, y1, z1, x2, y2, z2);
        }
        @Override public void playSoundToAllNearExcept(@Nullable EntityPlayer player, SoundEvent sound, SoundCategory category,
                                                       double x, double y, double z, float volume, float pitch) {}
        @Override public void playRecord(SoundEvent sound, BlockPos pos) {}
        @Override public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z,
                                            double xs, double ys, double zs, int... params) {}
        @Override public void spawnParticle(int id, boolean ignoreRange, boolean minimise, double x, double y, double z,
                                            double xs, double ys, double zs, int... params) {}
        @Override public void onEntityAdded(Entity entity) {}
        @Override public void onEntityRemoved(Entity entity) {}
        @Override public void broadcastSound(int id, BlockPos pos, int data) {}
        @Override public void playEvent(EntityPlayer player, int type, BlockPos pos, int data) {}
        @Override public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {}
    }

    // ---------------------------------------------------------------- rendering

    @SubscribeEvent
    public void onKey(net.minecraftforge.fml.common.gameevent.InputEvent.KeyInputEvent e) {
        if (toggleKey == null || !toggleKey.isPressed()) return;
        active = !active;
        if (mc.player != null) mc.player.sendStatusMessage(new net.minecraft.util.text.TextComponentString(
                "Lumen: " + (active ? "\u00a7aON" : "\u00a7cOFF")), true);
    }

    private final EntityShadows entityShadows = new EntityShadows();
    private boolean drawnThisFrame;

    /** Called right before the translucent pass (see MixinEntityRenderer). */
    public static void renderBeforeTranslucent(float pt) {
        if (instance == null) return;
        instance.renderLayers(pt);
        instance.drawnThisFrame = true;
    }

    /** Fallback when another mod reworked the world pass: draw at the end of the frame. */
    @SubscribeEvent
    public void onRenderLast(RenderWorldLastEvent e) {
        if (!drawnThisFrame) renderLayers(e.getPartialTicks());
        drawnThisFrame = false;
    }

    /** True when Lumen draws this entity's shadow itself (the vanilla blob is skipped). */
    public static boolean replacesShadow(Entity e) {
        return active && LumenConfig.shadows.entityShadows && e instanceof net.minecraft.entity.EntityLivingBase;
    }

    private void renderLayers(float pt) {
        if (!active || mc.world == null) return;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;

        long t0 = System.nanoTime();
        GlSafe.begin();
        try {
            // 1) light and shadow layers (2x modulate)
            GlSafe.modulate();
            if (LumenConfig.shadows.enabled) shadows.render(cx, cy, cz);
            if (LumenConfig.shadows.entityShadows) entityShadows.render(pt, cx, cy, cz, blockLight.lights(), dynamicLights.moving());
            if (LumenConfig.colored.enabled) {
                int atlas = mc.getTextureMapBlocks().getGlTextureId();
                GlSafe.modulateCutout(atlas);
                blockLight.render(cx, cy, cz);
                GlSafe.additive(atlas);          // moving lights add their light on top
                movingLights.render(cx, cy, cz);
                if (BlockLightSections.gpu != null) {
                    try {
                        gpu.render(cx, cy, cz, pt, blockLight.lights(), dynamicLights.moving());
                    } catch (Throwable t) {
                        gpu.fail(t);
                    }
                }
            }
            // 2) glowing textures, full-bright
            if (LumenConfig.emissive.enabled) drawEmissive(cx, cy, cz, pt);
        } finally {
            GlSafe.end();
        }
        renderNanos += System.nanoTime() - t0; renderCount++;
    }

    private void drawEmissive(double cx, double cy, double cz, float pt) {
        if (glowVersion != scanner.version) {
            glowVersion = scanner.version;
            glowGroups.clear();
            scanner.forEachQuad(q -> glowGroups.computeIfAbsent(q.glow, k -> new ArrayList<>()).add(q));
            scanner.quadCount();
        }
        if (glowGroups.isEmpty()) return;
        GlSafe.textured();
        float time = mc.world.getTotalWorldTime() + pt;
        boolean pulse = LumenConfig.emissive.pulse;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        for (Map.Entry<EmissiveTextures.Glow, List<SectionScanner.GlowQuad>> en : glowGroups.entrySet()) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, en.getKey().textureId());
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            for (SectionScanner.GlowQuad q : en.getValue()) {
                float c = pulse ? 0.88f + 0.12f * MathHelper.sin(time * 0.07f + (q.seed % 1000) * 0.37f) : 1f;
                for (int i = 0; i < 4; i++) {
                    buf.pos(q.xyz[i * 3] - cx, q.xyz[i * 3 + 1] - cy, q.xyz[i * 3 + 2] - cz)
                            .tex(q.uv[i * 2], q.uv[i * 2 + 1]).color(c, c, c, 1f).endVertex();
                }
            }
            tess.draw();
        }
    }
}
