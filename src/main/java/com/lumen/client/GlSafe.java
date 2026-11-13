package com.lumen.client;

import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Isolated GL state for Lumen's layers.
 *
 * Whatever vanilla or other mods leave behind (a bound texture, texture coordinate generation,
 * a texture matrix, the lightmap unit enabled...) must not leak into Lumen's color-only layers -
 * that's what made particle textures smear across the light/shadow layer. Lumen saves the whole
 * relevant GL state, sets exactly what it needs with direct GL calls (not GlStateManager, whose
 * cache could be stale), and restores everything afterwards, so nothing leaks out either.
 */
public final class GlSafe {
    private GlSafe() {}

    private static final int ATTRIBS = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
            | GL11.GL_POLYGON_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_FOG_BIT;

    /** Saves state and sets up for untextured, depth-tested, non-writing overlay drawing. */
    public static void begin() {
        GL11.glPushAttrib(ATTRIBS);
        for (int unit : new int[]{OpenGlHelper.lightmapTexUnit, OpenGlHelper.defaultTexUnit}) {
            OpenGlHelper.setActiveTexture(unit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_R);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
        }
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-1f, -10f);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_BLEND);
        noTexCoordArrays();
    }

    /** 2x modulate blending for light/shadow layers: color 0.5 leaves the scene unchanged. */
    public static void modulate() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR);
    }

    /**
     * 2x modulate with the block's own texture as a cutout mask: color comes from the vertex only,
     * alpha from the texture (alpha-tested), so grass blades and flowers are shaded by their shape.
     */
    public static void modulateCutout(int atlasTexture) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexture);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL11.GL_REPLACE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL13.GL_PRIMARY_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_REPLACE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL11.GL_TEXTURE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR);
    }

    /** Additive light: block texture x vertex color added on top (moving lights). */
    public static void additive(int atlasTexture) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexture);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
    }

    /** Textured, alpha-blended, alpha-tested drawing (glowing textures). Binds with raw GL. */
    public static void textured() {
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glEnable(GL11.GL_FOG);
    }

    public static void end() {
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        for (int unit : new int[]{OpenGlHelper.lightmapTexUnit, OpenGlHelper.defaultTexUnit}) {
            OpenGlHelper.setActiveTexture(unit);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPopMatrix();
        }
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopAttrib();          // restores enables, blend, depth, bindings, texgen... exactly as found
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    /** Display lists capture enabled vertex arrays: make sure no stale texcoord array gets baked in. */
    public static void beforeCompile() {
        noTexCoordArrays();
    }

    private static void noTexCoordArrays() {
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
    }
}
