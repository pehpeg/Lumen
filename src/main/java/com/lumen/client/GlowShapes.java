package com.lumen.client;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Shared "soft glow" disc. Instead of a single linear center-to-edge fade (which looks like a
 * flat cone), it uses a bright core, a mid ring and a long faint tail - a cheap approximation
 * of the gaussian-ish falloff real bloom has. Still only ~3 rings of vertices per disc.
 */
public final class GlowShapes {
    private GlowShapes() {}

    // radius fractions and alpha fractions of the rings
    private static final double[] RING_R = {0.0, 0.18, 0.45, 1.0};
    private static final float[] RING_A = {1.0f, 0.55f, 0.16f, 0.0f};

    private static final int SEG = 20;
    private static final double[] COS = new double[SEG + 1], SIN = new double[SEG + 1];
    static {
        for (int i = 0; i <= SEG; i++) {
            double a = i * Math.PI * 2 / SEG;
            COS[i] = Math.cos(a); SIN[i] = Math.sin(a);
        }
    }

    /**
     * Draws a soft disc centered at (x, y, z), spanned by the unit axes a and b.
     * Caller sets up blending (usually additive) and disables textures.
     */
    public static void disc(Tessellator tess, double x, double y, double z,
                            double ax, double ay, double az, double bx, double by, double bz,
                            double radius, float r, float g, float b, float alpha) {
        if (alpha <= 0.002f || radius <= 0) return;
        BufferBuilder buf = tess.getBuffer();
        // core fan
        buf.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        buf.pos(x, y, z).color(r, g, b, Math.min(1f, alpha)).endVertex();
        ring(buf, x, y, z, ax, ay, az, bx, by, bz, radius * RING_R[1], r, g, b, alpha * RING_A[1]);
        tess.draw();
        // outer rings as strips
        for (int k = 1; k < RING_R.length - 1; k++) {
            double r0 = radius * RING_R[k], r1 = radius * RING_R[k + 1];
            float a0 = alpha * RING_A[k], a1 = alpha * RING_A[k + 1];
            buf.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= SEG; i++) {
                double c = COS[i], s = SIN[i];
                buf.pos(x + (ax * c + bx * s) * r0, y + (ay * c + by * s) * r0, z + (az * c + bz * s) * r0)
                        .color(r, g, b, Math.min(1f, a0)).endVertex();
                buf.pos(x + (ax * c + bx * s) * r1, y + (ay * c + by * s) * r1, z + (az * c + bz * s) * r1)
                        .color(r, g, b, Math.min(1f, a1)).endVertex();
            }
            tess.draw();
        }
    }

    private static void ring(BufferBuilder buf, double x, double y, double z,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double rad, float r, float g, float b, float a) {
        for (int i = 0; i <= SEG; i++) {
            double c = COS[i] * rad, s = SIN[i] * rad;
            buf.pos(x + ax * c + bx * s, y + ay * c + by * s, z + az * c + bz * s)
                    .color(r, g, b, Math.min(1f, a)).endVertex();
        }
    }
}
