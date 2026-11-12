package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * Real bloom without shaders (the same idea as Shine/Shimmer's selective bloom).
 *
 *  1. Glowing things (light-source blocks, torch flames, lava, glowing ores, fireflies, burning
 *     mobs) are drawn into an offscreen image that shares the scene's depth buffer, so walls
 *     and terrain correctly hide them.
 *  2. That image is shrunk step by step (1/4, 1/8 ... 1/64 size) with smooth filtering and four
 *     offset samples per step - a blur that costs almost nothing because the images are tiny.
 *  3. The small images are added back up into each other and onto the screen.
 *
 * Fixed-function OpenGL only; if framebuffers aren't available it simply turns itself off.
 */
public class LumenBloom {

    private static final int LEVELS = 5;          // 1/4, 1/8, 1/16, 1/32, 1/64
    private final Minecraft mc = Minecraft.getMinecraft();

    private Framebuffer source;                    // full size, shares the main depth buffer
    private final Framebuffer[] levels = new Framebuffer[LEVELS];
    private int width = -1, height = -1, sharedDepth = -1;
    private boolean broken;

    private final FloatBuffer proj = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer model = BufferUtils.createFloatBuffer(16);

    public interface SourceDrawer { void draw(float partialTicks); }

    public boolean available() {
        return !broken && OpenGlHelper.isFramebufferEnabled() && mc.getFramebuffer() != null
                && mc.getFramebuffer().useDepth && mc.getFramebuffer().depthBuffer > 0;
    }

    /** Renders bloom for this frame. Call at the end of the world pass, with world matrices active. */
    public void render(float pt, SourceDrawer drawer) {
        if (!available()) return;
        Framebuffer main = mc.getFramebuffer();
        try {
            ensureBuffers(main);

            // 1) glowing geometry -> source (world matrices, scene depth for occlusion)
            source.bindFramebuffer(true);
            GlStateManager.clearColor(0f, 0f, 0f, 0f);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);   // color only: the depth is the scene's
            LumenGl.prepare();
            GlStateManager.disableFog();
            GlStateManager.disableLighting();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(SourceFactor.ONE, DestFactor.ONE);
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1f, -10f);     // sit exactly on block faces without z-fighting
            drawer.draw(pt);
            GlStateManager.disablePolygonOffset();
            LumenGl.prepare();

            // 2) blur chain in screen space
            proj.clear(); model.clear();
            GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, proj);
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, model);
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.loadIdentity();
            GlStateManager.ortho(0, 1, 0, 1, -1, 1);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.loadIdentity();
            GlStateManager.disableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1f, 1f, 1f, 1f);

            // downsample: source -> L0 -> L1 ... (each pass = 4 bilinear taps, a soft tent blur)
            Framebuffer prev = source;
            for (int i = 0; i < LEVELS; i++) {
                Framebuffer dst = levels[i];
                dst.bindFramebuffer(true);
                GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
                blit(prev, 1f, i == 0 ? 2.0f : 1.0f);
                prev = dst;
            }
            // upsample: add each smaller level into the next larger one (wide, smooth falloff)
            for (int i = LEVELS - 1; i > 0; i--) {
                levels[i - 1].bindFramebuffer(true);
                blit(levels[i], 0.85f, 0.5f);
            }

            // 3) add onto the screen
            main.bindFramebuffer(true);
            GlStateManager.colorMask(true, true, true, false);
            blit(levels[0], (float) (1.6 * LumenConfig.bloomStrength), 0.5f);
            GlStateManager.colorMask(true, true, true, true);

            // restore
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            proj.rewind(); GL11.glLoadMatrix(proj);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            model.rewind(); GL11.glLoadMatrix(model);
        } catch (Throwable t) {
            broken = true;
            LogManager.getLogger("Lumen").warn("Lumen: bloom failed and was turned off", t);
            main.bindFramebuffer(true);
        } finally {
            GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.enableCull();
            GlStateManager.enableAlpha();
            GlStateManager.enableFog();
            LumenGl.finish();
        }
    }

    /** Draws src over the whole current target, 4 taps offset by `spread` source texels, added. */
    private static void blit(Framebuffer src, float weight, float spread) {
        src.bindFramebufferTexture();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(SourceFactor.ONE, DestFactor.ONE);
        float u1 = (float) src.framebufferWidth / src.framebufferTextureWidth;
        float v1 = (float) src.framebufferHeight / src.framebufferTextureHeight;
        float du = spread / src.framebufferTextureWidth, dv = spread / src.framebufferTextureHeight;
        float c = 0.25f * weight;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        float[][] taps = {{-du, -dv}, {du, -dv}, {-du, dv}, {du, dv}};
        for (float[] t : taps) {
            buf.pos(0, 0, 0).tex(t[0], t[1]).color(c, c, c, 1f).endVertex();
            buf.pos(1, 0, 0).tex(u1 + t[0], t[1]).color(c, c, c, 1f).endVertex();
            buf.pos(1, 1, 0).tex(u1 + t[0], v1 + t[1]).color(c, c, c, 1f).endVertex();
            buf.pos(0, 1, 0).tex(t[0], v1 + t[1]).color(c, c, c, 1f).endVertex();
        }
        tess.draw();
    }

    private void ensureBuffers(Framebuffer main) {
        int w = main.framebufferWidth, h = main.framebufferHeight;
        if (w == width && h == height && source != null && sharedDepth == main.depthBuffer) return;
        delete();
        width = w; height = h; sharedDepth = main.depthBuffer;

        source = new Framebuffer(main.framebufferTextureWidth, main.framebufferTextureHeight, false);
        source.framebufferWidth = w; source.framebufferHeight = h;
        source.setFramebufferFilter(GL11.GL_LINEAR);
        // attach the scene's depth so glowing things are hidden behind walls
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, source.framebufferObject);
        OpenGlHelper.glFramebufferRenderbuffer(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_DEPTH_ATTACHMENT,
                OpenGlHelper.GL_RENDERBUFFER, main.depthBuffer);
        source.checkFramebufferComplete();

        int lw = w, lh = h;
        for (int i = 0; i < LEVELS; i++) {
            lw = Math.max(1, lw / (i == 0 ? 4 : 2));
            lh = Math.max(1, lh / (i == 0 ? 4 : 2));
            levels[i] = new Framebuffer(lw, lh, false);
            levels[i].setFramebufferFilter(GL11.GL_LINEAR);
        }
        main.bindFramebuffer(true);
    }

    private void delete() {
        if (source != null) {
            // detach the shared depth first so deleting our framebuffer can't touch it
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, source.framebufferObject);
            OpenGlHelper.glFramebufferRenderbuffer(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_DEPTH_ATTACHMENT,
                    OpenGlHelper.GL_RENDERBUFFER, 0);
            source.deleteFramebuffer();
            source = null;
        }
        for (int i = 0; i < LEVELS; i++) {
            if (levels[i] != null) { levels[i].deleteFramebuffer(); levels[i] = null; }
        }
    }
}
