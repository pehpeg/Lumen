package com.lumen.client;

import com.lumen.Lumen;
import com.lumen.LumenConfig;
import com.lumen.core.VirtualLights;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
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
    private final Minecraft mc = Minecraft.getMinecraft();

    final EmissiveTextures textures = new EmissiveTextures();
    final LightColors colors = new LightColors();
    final SectionScanner scanner = new SectionScanner(textures, colors);
    final DynamicLights dynamicLights = new DynamicLights(colors);
    final ColoredLight coloredLight = new ColoredLight();
    final SunShadows shadows = new SunShadows();

    private int lastScanVersion = -1, lastDynVersion = -1, glowVersion = -1;
    private final Map<EmissiveTextures.Glow, List<SectionScanner.GlowQuad>> glowGroups = new IdentityHashMap<>();
    private World listenedWorld;
    private boolean hintShown, hookWarned;
    private int ticksWithSources;
    private long hitsAtStart;

    // CPU time spent by Lumen (for the test report and F3)
    private static long tickNanos, tickCount, renderNanos, renderCount, worstTick;

    static void addTickTime(long nanos) { tickNanos += nanos; worstTick = Math.max(worstTick, nanos); }

    public static void resetTimers() { tickNanos = tickCount = renderNanos = renderCount = worstTick = 0; }

    public static String timings() {
        return String.format("cpu: tick avg %.3fms worst %.2fms, frame avg %.3fms",
                tickCount == 0 ? 0 : tickNanos / 1e6 / tickCount, worstTick / 1e6,
                renderCount == 0 ? 0 : renderNanos / 1e6 / renderCount);
    }

    public static void init() {
        instance = new LumenClient();
        MinecraftForge.EVENT_BUS.register(instance);
        MinecraftForge.EVENT_BUS.register(instance.dynamicLights);
        MinecraftForge.EVENT_BUS.register(new LumenLightmap());
        // resource packs changed: emissive textures and texture-derived colors must be re-read
        if (Minecraft.getMinecraft().getResourceManager() instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager()).registerReloadListener(rm -> {
                instance.textures.clear();
                instance.colors.clearTextureCache();
                instance.scanner.clearCaches();
                instance.coloredLight.clear();
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
        instance.coloredLight.clear();
        instance.shadows.clear();
        instance.lastScanVersion = -1;
    }

    // ---------------------------------------------------------------- ticking

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        World w = mc.world;
        if (w != null && w != listenedWorld) { w.addEventListener(new BlockListener()); listenedWorld = w; }
        if (w == null || mc.player == null) return;
        hints();
        if (!LumenConfig.enabled) return;
        long t0 = System.nanoTime();
        tickFeatures();
        long dt = System.nanoTime() - t0;
        tickNanos += dt; tickCount++; worstTick = Math.max(worstTick, dt);
    }

    private void tickFeatures() {
        scanner.tick(8);
        if (LumenConfig.colored.enabled) {
            if (scanner.version != lastScanVersion || dynamicLights.version != lastDynVersion) {
                lastScanVersion = scanner.version;
                lastDynVersion = dynamicLights.version;
                coloredLight.update(scanner.colorLights(), dynamicLights.coloredEmitters());
            }
            coloredLight.tick(3);
        }
        shadows.tick();
    }

    /** Block changes: rescan that section, rebuild color meshes and shadows around it. */
    private final class BlockListener implements IWorldEventListener {
        @Override public void notifyBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
            if (oldState == newState) return;
            scanner.markDirty(pos);
            coloredLight.markDirty(pos);
            shadows.markDirty(pos);
        }
        @Override public void notifyLightSet(BlockPos pos) {}
        @Override public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}
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
    public void onRenderLast(RenderWorldLastEvent e) {
        if (!LumenConfig.enabled || mc.world == null) return;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        float pt = e.getPartialTicks();
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;

        long t0 = System.nanoTime();
        LumenGl.prepare();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(false);
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1f, -10f);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        // 1) multiply layers: shadows and colored light (no fog: it would tint the multiplier)
        GlStateManager.disableFog();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(SourceFactor.DST_COLOR, DestFactor.ZERO);
        if (LumenConfig.shadows.enabled) shadows.render(cx, cy, cz);
        if (LumenConfig.colored.enabled) coloredLight.render(cx, cy, cz);
        GlStateManager.enableFog();

        // 2) glowing textures, full-bright
        if (LumenConfig.emissive.enabled) drawEmissive(cx, cy, cz, pt);

        GlStateManager.disablePolygonOffset();
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.color(1f, 1f, 1f, 1f);
        LumenGl.finish();
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
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        float time = mc.world.getTotalWorldTime() + pt;
        boolean pulse = LumenConfig.emissive.pulse;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        for (Map.Entry<EmissiveTextures.Glow, List<SectionScanner.GlowQuad>> en : glowGroups.entrySet()) {
            GlStateManager.bindTexture(en.getKey().textureId());
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
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.disableAlpha();
        GlStateManager.disableTexture2D();
        GlStateManager.bindTexture(mc.getTextureMapBlocks().getGlTextureId());
    }

    // ---------------------------------------------------------------- toggle, hints, diagnostics

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (Lumen.toggleKey == null || !Lumen.toggleKey.isPressed()) return;
        LumenConfig.enabled = !LumenConfig.enabled;
        if (mc.player != null) mc.player.sendStatusMessage(new TextComponentString(
                "Lumen: " + (LumenConfig.enabled ? "§aON" : "§cOFF (vanilla)")), true);
    }

    private void hints() {
        if (!hintShown && Lumen.toggleKey != null) {
            hintShown = true;
            mc.player.sendMessage(new TextComponentString("§6Lumen§r: press §e"
                    + Lumen.toggleKey.getDisplayName() + "§r to switch between Lumen and vanilla"));
        }
        // dynamic lights self-check: emitters exist but the light engine never asked about them
        if (DynamicLights.statSources > 0 && LumenConfig.enabled) {
            if (ticksWithSources++ == 0) hitsAtStart = VirtualLights.hits;
            if (ticksWithSources == 100 && VirtualLights.hits == hitsAtStart && !hookWarned) {
                hookWarned = true;
                mc.player.sendMessage(new TextComponentString("§6Lumen§r: §cdynamic lights aren't reaching the light engine§r"
                        + " - another mod may replace it. Please report which lighting mods you use."));
            }
        } else ticksWithSources = 0;
    }

    @SubscribeEvent
    public void onDebugText(RenderGameOverlayEvent.Text e) {
        if (!mc.gameSettings.showDebugInfo) return;
        e.getLeft().add("");
        e.getLeft().add("[Lumen] " + (LumenConfig.enabled ? "ON" : "OFF")
                + " | moving lights " + DynamicLights.statSources + (VirtualLights.hits > 0 ? " (engine OK)" : "")
                + " | colored " + ColoredLight.statMeshes + " / " + ColoredLight.statQuads + "q"
                + " | glow " + SectionScanner.statQuads + "q (" + EmissiveTextures.statPack + " pack, " + EmissiveTextures.statAuto + " auto)"
                + " | shadows " + SunShadows.statQuads + "q, " + SunShadows.statPending + " pending");
        e.getLeft().add("[Lumen] " + timings());
    }
}
