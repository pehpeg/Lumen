package com.lumen.client;

import com.lumen.Lumen;
import com.lumen.LumenConfig;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.opengl.GL11;

/**
 * "Shader look" without shaders. Everything here is a handful of fixed-function
 * quads/fans per frame plus tweaks to vanilla fog, so the cost is close to zero:
 * no extra framebuffers, no shadow pass, no fullscreen shader programs.
 */
public class LumenRenderer {

    private final Minecraft mc = Minecraft.getMinecraft();

    // ---------------------------------------------------------------- helpers

    /** Direction from the camera to the sun, matching vanilla sky rendering. */
    private static Vec3d sunDirection(World world, float pt) {
        float angle = world.getCelestialAngle(pt) * (float) (Math.PI * 2.0);
        return new Vec3d(-MathHelper.sin(angle), MathHelper.cos(angle), 0.0);
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static float smooth(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    /** 1 when the sun is near the horizon (dawn / dusk), 0 otherwise. */
    private static float goldenHour(float sunY) {
        if (sunY < -0.2f) return 0;
        return 1f - smooth(0.0f, 0.4f, Math.abs(sunY - 0.05f));
    }

    private boolean active() {
        return LumenConfig.enabled && mc.world != null && mc.player != null;
    }

    // ---------------------------------------------------------------- toggle

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (Lumen.toggleKey != null && Lumen.toggleKey.isPressed()) {
            LumenConfig.enabled = !LumenConfig.enabled;
            if (mc.player != null) {
                mc.player.sendStatusMessage(new TextComponentString(
                        "Lumen: " + (LumenConfig.enabled ? "§aON" : "§cOFF")), true);
            }
        }
    }

    // ---------------------------------------------------------------- sun / moon glow

    @SubscribeEvent
    public void onWorldLast(RenderWorldLastEvent e) {
        if (!active()) return;
        if (mc.world.provider.isSurfaceWorld()) drawSkyGlow(e.getPartialTicks());
        drawScreenEffects(e.getPartialTicks());
    }

    private void drawSkyGlow(float pt) {
        World world = mc.world;
        Vec3d sun = sunDirection(world, pt);
        float clearSky = 1f - world.getRainStrength(pt);
        if (clearSky <= 0.01f) return;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.disableFog();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE); // additive
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        if (LumenConfig.sunGlow && sun.y > -0.15) {
            float golden = goldenHour((float) sun.y);
            float vis = clearSky * smooth(-0.15f, 0.05f, (float) sun.y) * (float) LumenConfig.sunGlowStrength;
            // warm orange near the horizon, pale yellow-white at midday
            float r = 1.0f, g = 0.85f - 0.3f * golden, b = 0.6f - 0.4f * golden;
            float t = (world.getTotalWorldTime() + pt) * 0.002f;

            drawGlow(sun, 100, 45 + 25 * golden, r, g, b, 0.35f * vis);   // wide halo
            drawGlow(sun, 100, 14, 1.0f, 0.95f, 0.85f, 0.45f * vis);      // hot core
            drawRays(sun, 100, 70 + 30 * golden, r, g, b, 0.10f * vis, t);
        }

        if (LumenConfig.moonGlow && sun.y < 0.15) {
            Vec3d moon = new Vec3d(-sun.x, -sun.y, -sun.z);
            float vis = clearSky * smooth(-0.15f, 0.1f, (float) moon.y);
            drawGlow(moon, 100, 30, 0.55f, 0.65f, 1.0f, 0.18f * vis);
        }

        GlStateManager.depthMask(true);
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        GlStateManager.enableFog();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /** Builds two vectors perpendicular to dir so we can make a camera-facing disc. */
    private static Vec3d[] basis(Vec3d dir) {
        Vec3d ref = Math.abs(dir.z) < 0.9 ? new Vec3d(0, 0, 1) : new Vec3d(1, 0, 0);
        Vec3d right = dir.crossProduct(ref).normalize();
        Vec3d up = right.crossProduct(dir).normalize();
        return new Vec3d[]{right, up};
    }

    /** Radial gradient disc: bright center fading to fully transparent edge. */
    private static void drawGlow(Vec3d dir, double dist, double radius,
                                 float r, float g, float b, float alpha) {
        if (alpha <= 0.002f) return;
        Vec3d[] bs = basis(dir);
        Vec3d c = dir.scale(dist);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        buf.pos(c.x, c.y, c.z).color(r, g, b, alpha).endVertex();
        int seg = 32;
        for (int i = 0; i <= seg; i++) {
            double a = i * Math.PI * 2 / seg;
            Vec3d p = c.add(bs[0].scale(Math.cos(a) * radius)).add(bs[1].scale(Math.sin(a) * radius));
            buf.pos(p.x, p.y, p.z).color(r, g, b, 0f).endVertex();
        }
        tess.draw();
    }

    /** Thin slowly-rotating light shafts radiating from the sun ("god rays" look). */
    private static void drawRays(Vec3d dir, double dist, double length,
                                 float r, float g, float b, float alpha, float time) {
        if (alpha <= 0.002f) return;
        Vec3d[] bs = basis(dir);
        Vec3d c = dir.scale(dist);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        int rays = 12;
        for (int i = 0; i < rays; i++) {
            // pseudo-random but stable per ray
            double jitter = MathHelper.sin(i * 12.9898f) * 0.5;
            double a = (i + jitter) * Math.PI * 2 / rays + time;
            double width = 0.035 + 0.025 * Math.abs(MathHelper.sin(i * 4.1f));
            double len = length * (0.6 + 0.4 * Math.abs(MathHelper.sin(i * 7.3f + time * 3)));
            Vec3d tipL = c.add(bs[0].scale(Math.cos(a - width) * len)).add(bs[1].scale(Math.sin(a - width) * len));
            Vec3d tipR = c.add(bs[0].scale(Math.cos(a + width) * len)).add(bs[1].scale(Math.sin(a + width) * len));
            buf.pos(c.x, c.y, c.z).color(r, g, b, alpha).endVertex();
            buf.pos(tipL.x, tipL.y, tipL.z).color(r, g, b, 0f).endVertex();
            buf.pos(tipR.x, tipR.y, tipR.z).color(r, g, b, 0f).endVertex();
        }
        tess.draw();
    }

    // ---------------------------------------------------------------- fog

    @SubscribeEvent
    public void onFogColor(EntityViewRenderEvent.FogColors e) {
        if (!active() || !LumenConfig.atmosphericFog || !mc.world.provider.isSurfaceWorld()) return;
        if (e.getState().getMaterial().isLiquid()) return;
        float pt = (float) e.getRenderPartialTicks();
        float golden = goldenHour((float) sunDirection(mc.world, pt).y)
                * (1f - mc.world.getRainStrength(pt)) * 0.55f;
        // pull the horizon haze toward a warm peach at dawn/dusk
        e.setRed(e.getRed() + (1.00f - e.getRed()) * golden);
        e.setGreen(e.getGreen() + (0.62f - e.getGreen()) * golden);
        e.setBlue(e.getBlue() + (0.42f - e.getBlue()) * golden);
    }

    @SubscribeEvent
    public void onFog(EntityViewRenderEvent.RenderFogEvent e) {
        if (!active() || !LumenConfig.atmosphericFog) return;
        if (e.getFogMode() != 0) return;                         // terrain fog only, not sky
        if (e.getState().getMaterial() != Material.AIR) return;  // leave water/lava fog alone
        if (!mc.world.provider.isSurfaceWorld()) return;
        float far = e.getFarPlaneDistance();
        // never start the haze closer than 48 blocks, so low render distances stay clear
        float start = Math.max(far * (float) LumenConfig.fogStart, Math.min(48f, far * 0.8f));
        GlStateManager.setFogStart(start);
        GlStateManager.setFogEnd(far);
    }

    // ---------------------------------------------------------------- screen grading + vignette

    // Drawn at the end of the world pass (not in the HUD overlay) so it still applies
    // with the HUD hidden (F1), and never tints the HUD or menus.
    private void drawScreenEffects(float pt) {
        if (!LumenConfig.colorGrading && !LumenConfig.vignette) return;
        double w = 1, h = 1; // normalized screen space

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0, 1, 1, 0, -1, 1);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();

        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        if (LumenConfig.colorGrading) drawGrade(w, h, pt);
        if (LumenConfig.vignette) drawVignette(w, h, (float) LumenConfig.vignetteStrength);

        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.enableFog();
        GlStateManager.disableBlend();
        GlStateManager.color(1, 1, 1, 1);

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
    }

    private void drawGrade(double w, double h, float pt) {
        float s = (float) LumenConfig.colorGradingStrength;
        Entity view = mc.getRenderViewEntity();
        float r = 1, g = 1, b = 1;       // multiplicative tint
        float lr = 0, lg = 0, lb = 0;    // additive lift

        if (view != null && view.isInsideOfMaterial(Material.WATER)) {
            r = 0.72f; g = 0.93f; b = 1.0f;
        } else if (mc.world.provider.isSurfaceWorld()) {
            float sunY = (float) sunDirection(mc.world, pt).y;
            float clear = 1f - mc.world.getRainStrength(pt);
            float golden = goldenHour(sunY) * clear;
            float night = smooth(0.05f, -0.25f, sunY);
            float day = 1f - Math.max(golden, night);

            // day: very slight warmth + richness; golden hour: amber; night: moonlit blue
            // daytime stays almost neutral so the blue sky isn't washed out
            r = day * 1.00f + golden * 1.00f + night * 0.80f;
            g = day * 0.99f + golden * 0.86f + night * 0.88f;
            b = day * 0.97f + golden * 0.70f + night * 1.00f;
            lr = golden * 0.05f;
            lg = golden * 0.02f;
            // rain: desaturated grey-blue
            float rain = mc.world.getRainStrength(pt);
            r += (0.88f - r) * rain * 0.5f;
            g += (0.90f - g) * rain * 0.5f;
            b += (0.95f - b) * rain * 0.5f;
        } else if (mc.world.provider.isNether()) {
            r = 1.0f; g = 0.88f; b = 0.78f;
        }

        r = 1 + (r - 1) * s; g = 1 + (g - 1) * s; b = 1 + (b - 1) * s;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        // multiply: dst * color
        GlStateManager.blendFunc(SourceFactor.DST_COLOR, DestFactor.ZERO);
        quad(buf, tess, w, h, r, g, b, 1);

        // add: soft warm lift for golden hour
        if (lr + lg + lb > 0.001f) {
            GlStateManager.blendFunc(SourceFactor.ONE, DestFactor.ONE);
            quad(buf, tess, w, h, lr * s, lg * s, lb * s, 1);
        }
    }

    private static void quad(BufferBuilder buf, Tessellator tess, double w, double h,
                             float r, float g, float b, float a) {
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        buf.pos(0, h, 0).color(r, g, b, a).endVertex();
        buf.pos(w, h, 0).color(r, g, b, a).endVertex();
        buf.pos(w, 0, 0).color(r, g, b, a).endVertex();
        buf.pos(0, 0, 0).color(r, g, b, a).endVertex();
        tess.draw();
    }

    private static void drawVignette(double w, double h, float strength) {
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        double cx = w / 2, cy = h / 2;
        double inX = cx * 0.55, inY = cy * 0.55;   // clear center
        double outX = cx * 1.45, outY = cy * 1.45; // past the corners
        int seg = 48;
        buf.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= seg; i++) {
            double a = i * Math.PI * 2 / seg;
            double c = Math.cos(a), s = Math.sin(a);
            buf.pos(cx + c * inX, cy + s * inY, 0).color(0f, 0f, 0f, 0f).endVertex();
            buf.pos(cx + c * outX, cy + s * outY, 0).color(0f, 0f, 0f, strength).endVertex();
        }
        tess.draw();
    }
}
