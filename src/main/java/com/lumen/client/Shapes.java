package com.lumen.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a non-full block looks like to a ray of light: its real model quads and the pixels of their
 * textures. Light passes through transparent pixels (gaps in ladders, bars, fences, leaves,
 * glass), is stopped by opaque ones, and is tinted by translucent ones (stained glass).
 * Shapes and texture masks are cached; safe on worker threads.
 */
final class Shapes {
    private Shapes() {}

    private static final class Mask {
        final int w, h; final int[] argb;
        Mask(int w, int h, int[] argb) { this.w = w; this.h = h; this.argb = argb; }
    }

    private static final class Quad {
        final double ox, oy, oz, ax, ay, az, bx, by, bz, nx, ny, nz, aa, bb;
        final float[] u = new float[4], v = new float[4];   // sprite-relative 0..1 per vertex
        final Mask mask;
        Quad(double[][] p, float[][] uv, Mask mask) {
            ox = p[0][0]; oy = p[0][1]; oz = p[0][2];
            ax = p[1][0] - ox; ay = p[1][1] - oy; az = p[1][2] - oz;
            bx = p[3][0] - ox; by = p[3][1] - oy; bz = p[3][2] - oz;
            double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
            nx = cx; ny = cy; nz = cz;
            aa = ax * ax + ay * ay + az * az; bb = bx * bx + by * by + bz * bz;
            for (int i = 0; i < 4; i++) { u[i] = uv[i][0]; v[i] = uv[i][1]; }
            this.mask = mask;
        }
    }

    private static final class Shape {
        final List<Quad> quads; final boolean translucent, model;
        boolean soft;   // plants and clear glass: pixels dim the light instead of stopping it
        Shape(List<Quad> quads, boolean translucent, boolean model) { this.quads = quads; this.translucent = translucent; this.model = model; }
    }

    private static final ConcurrentHashMap<IBlockState, Shape> SHAPES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Mask> MASKS = new ConcurrentHashMap<>();
    private static final Mask SOLID = new Mask(1, 1, new int[]{0xFF000000});

    static void clear() { SHAPES.clear(); MASKS.clear(); }

    /**
     * Light transmitted through the block at (x,y,z) along the ray segment [t0, t1], per channel,
     * multiplied into t. @return false if fully blocked.
     */
    static boolean transmit(IBlockAccess w, IBlockState st, BlockPos.MutableBlockPos m, int x, int y, int z,
                            double px, double py, double pz, double dx, double dy, double dz, double t0, double t1, float[] t) {
        IBlockState actual;
        try { actual = st.getActualState(w, m.setPos(x, y, z)); } catch (Throwable e) { actual = st; }
        Shape s = SHAPES.get(actual);
        if (s == null) { s = build(actual); SHAPES.put(actual, s); }
        if (!s.model) {   // chests, signs...: their outline box
            return !hitsBox(w, actual, m, x, y, z, px, py, pz, dx, dy, dz, t0, t1);
        }
        Vec3d off;
        try { off = actual.getOffset(w, m.setPos(x, y, z)); } catch (Throwable e) { off = Vec3d.ZERO; }
        // ray in block-local space
        double lx = px - x - off.x, ly = py - y - off.y, lz = pz - z - off.z;
        boolean tinted = false;
        for (Quad q : s.quads) {
            double den = dx * q.nx + dy * q.ny + dz * q.nz;
            if (Math.abs(den) < 1e-9) continue;
            double th = ((q.ox - lx) * q.nx + (q.oy - ly) * q.ny + (q.oz - lz) * q.nz) / den;
            if (th < Math.max(t0, 1e-4) || th > t1) continue;
            double hx = lx + dx * th - q.ox, hy = ly + dy * th - q.oy, hz = lz + dz * th - q.oz;
            double sa = (hx * q.ax + hy * q.ay + hz * q.az) / q.aa, sb = (hx * q.bx + hy * q.by + hz * q.bz) / q.bb;
            if (sa < 0 || sa > 1 || sb < 0 || sb > 1) continue;
            float pu = (float) ((1 - sa) * (1 - sb) * q.u[0] + sa * (1 - sb) * q.u[1] + sa * sb * q.u[2] + (1 - sa) * sb * q.u[3]);
            float pv = (float) ((1 - sa) * (1 - sb) * q.v[0] + sa * (1 - sb) * q.v[1] + sa * sb * q.v[2] + (1 - sa) * sb * q.v[3]);
            Mask mk = q.mask;
            int ix = Math.min(mk.w - 1, Math.max(0, (int) (pu * mk.w))), iy = Math.min(mk.h - 1, Math.max(0, (int) (pv * mk.h)));
            int c = mk.argb[iy * mk.w + ix];
            float a = (c >>> 24) / 255f;
            if (a < 0.1f) continue;                           // a gap: light goes through
            if (s.soft) {                                     // thin plants / clear glass: dim per pixel hit
                float k = 1f - 0.3f * a;    // one blade: a clear shadow; dense grass: stronger
                t[0] *= k; t[1] *= k; t[2] *= k;
                if (Math.max(t[0], Math.max(t[1], t[2])) < 0.05f) return false;
                continue;
            }
            if (!s.translucent) {
                if (a >= 0.5f) return false;                  // an opaque pixel stops it
                continue;
            }
            if (tinted) continue;                             // glass filters once per block
            tinted = true;
            float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
            float mx = Math.max(0.05f, Math.max(r, Math.max(g, b)));
            float k = Math.min(1f, a * 1.6f), dim = 1f - 0.3f * a;
            t[0] *= (1 - k + k * r / mx) * dim;
            t[1] *= (1 - k + k * g / mx) * dim;
            t[2] *= (1 - k + k * b / mx) * dim;
        }
        return true;
    }

    /**
     * The block's shape as 16x16x16 pixel voxels for GPU tracing: 128 ints of bits (bit index
     * x + y*16 + z*256) and a meta int: bit0 translucent, bit1 soft, bit2 borrows its neighbours'
     * light (stairs/slabs), bits 8..31 = how much r/g/b gets through a hit (tint or dimming).
     * null if nothing in it stops light.
     */
    static int[] voxelize(IBlockState actual, IBlockAccess w, BlockPos pos) {
        Shape s = SHAPES.get(actual);
        if (s == null) { s = build(actual); SHAPES.put(actual, s); }
        int[] out = new int[129];
        boolean any = false;
        float sr = 0, sg = 0, sb = 0, sa = 0; int sn = 0;
        if (!s.model) {
            AxisAlignedBB box;
            try { box = actual.getBoundingBox(w, pos); } catch (Throwable e) { box = null; }
            if (box == null) return null;
            int x0 = vox(box.minX), y0 = vox(box.minY), z0 = vox(box.minZ);
            int x1 = Math.max(x0, (int) Math.ceil(box.maxX * 16 - 1e-4) - 1), y1 = Math.max(y0, (int) Math.ceil(box.maxY * 16 - 1e-4) - 1),
                    z1 = Math.max(z0, (int) Math.ceil(box.maxZ * 16 - 1e-4) - 1);
            for (int x = x0; x <= Math.min(15, x1); x++) for (int y = y0; y <= Math.min(15, y1); y++) for (int z = z0; z <= Math.min(15, z1); z++) {
                int bit = x + y * 16 + z * 256;
                out[bit >> 5] |= 1 << (bit & 31);
                any = true;
            }
        } else {
            float thr = s.soft || s.translucent ? 0.1f : 0.5f;
            final int N = 48;
            for (Quad q : s.quads) {
                double nl = Math.sqrt(q.nx * q.nx + q.ny * q.ny + q.nz * q.nz);
                if (nl < 1e-9) continue;
                // half a pixel inward, so a face on a pixel boundary marks the solid side
                double px = -q.nx / nl / 32, py = -q.ny / nl / 32, pz = -q.nz / nl / 32;
                for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
                    double a = (i + 0.5) / N, b = (j + 0.5) / N;
                    float pu = (float) ((1 - a) * (1 - b) * q.u[0] + a * (1 - b) * q.u[1] + a * b * q.u[2] + (1 - a) * b * q.u[3]);
                    float pv = (float) ((1 - a) * (1 - b) * q.v[0] + a * (1 - b) * q.v[1] + a * b * q.v[2] + (1 - a) * b * q.v[3]);
                    Mask mk = q.mask;
                    int ix = Math.min(mk.w - 1, Math.max(0, (int) (pu * mk.w))), iy = Math.min(mk.h - 1, Math.max(0, (int) (pv * mk.h)));
                    int c = mk.argb[iy * mk.w + ix];
                    float al = (c >>> 24) / 255f;
                    if (al < thr) continue;
                    int x = vox(q.ox + q.ax * a + q.bx * b + px), y = vox(q.oy + q.ay * a + q.by * b + py), z = vox(q.oz + q.az * a + q.bz * b + pz);
                    int bit = x + y * 16 + z * 256;
                    out[bit >> 5] |= 1 << (bit & 31);
                    any = true;
                    if (s.translucent) { sr += ((c >> 16) & 0xFF) / 255f; sg += ((c >> 8) & 0xFF) / 255f; sb += (c & 0xFF) / 255f; sa += al; sn++; }
                }
            }
        }
        if (!any) return null;
        int meta = 0;
        float tr = 0, tg = 0, tb = 0;
        if (s.translucent && sn > 0) {
            float r = sr / sn, g = sg / sn, b = sb / sn, a = sa / sn;
            float mx = Math.max(0.05f, Math.max(r, Math.max(g, b)));
            float k = Math.min(1f, a * 1.6f), dim = 1f - 0.3f * a;
            tr = (1 - k + k * r / mx) * dim; tg = (1 - k + k * g / mx) * dim; tb = (1 - k + k * b / mx) * dim;
            meta |= 1;
        } else if (s.soft) {
            boolean plant = actual.getMaterial() != net.minecraft.block.material.Material.GLASS;
            tr = tg = tb = plant ? 0.55f : 0.7f;   // a plant: a clear shadow, light still gets through
            meta |= 2;
        }
        try { if (actual.useNeighborBrightness()) meta |= 4; } catch (Throwable ignored) { }
        meta |= (Math.round(Math.max(0, Math.min(1, tr)) * 255) << 8) | (Math.round(Math.max(0, Math.min(1, tg)) * 255) << 16)
                | (Math.round(Math.max(0, Math.min(1, tb)) * 255) << 24);
        out[128] = meta;
        return out;
    }

    private static int vox(double v) { return Math.max(0, Math.min(15, (int) Math.floor(v * 16))); }

    private static Shape build(IBlockState st) {
        boolean translucent;
        try { translucent = st.getBlock().getRenderLayer() == BlockRenderLayer.TRANSLUCENT; } catch (Throwable e) { translucent = false; }
        if (st.getRenderType() != EnumBlockRenderType.MODEL) return new Shape(new ArrayList<>(), translucent, false);
        List<Quad> out = new ArrayList<>();
        try {
            IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(st);
            List<BakedQuad> all = new ArrayList<>();
            for (EnumFacing f : EnumFacing.VALUES) all.addAll(model.getQuads(st, f, 0L));
            all.addAll(model.getQuads(st, null, 0L));
            for (BakedQuad bq : all) {
                int[] d = bq.getVertexData();
                VertexFormat fmt = bq.getFormat();
                int stride = fmt.getIntegerSize(), uvo = fmt.getUvOffsetById(0) / 4;
                TextureAtlasSprite sp = bq.getSprite();
                float u0 = sp.getMinU(), du = Math.max(1e-6f, sp.getMaxU() - u0), v0 = sp.getMinV(), dv = Math.max(1e-6f, sp.getMaxV() - v0);
                double[][] p = new double[4][3];
                float[][] uv = new float[4][2];
                for (int i = 0; i < 4; i++) {
                    int b = i * stride;
                    p[i][0] = Float.intBitsToFloat(d[b]); p[i][1] = Float.intBitsToFloat(d[b + 1]); p[i][2] = Float.intBitsToFloat(d[b + 2]);
                    uv[i][0] = (Float.intBitsToFloat(d[b + uvo]) - u0) / du;
                    uv[i][1] = (Float.intBitsToFloat(d[b + uvo + 1]) - v0) / dv;
                }
                out.add(new Quad(p, uv, mask(sp)));
            }
        } catch (Throwable e) {
            return new Shape(new ArrayList<>(), translucent, false);
        }
        Shape shape = new Shape(out, translucent, true);
        net.minecraft.block.material.Material mat = st.getMaterial();
        shape.soft = !translucent && (mat == net.minecraft.block.material.Material.GLASS
                || (!mat.blocksMovement() && mat != net.minecraft.block.material.Material.LEAVES));
        return shape;
    }

    private static Mask mask(TextureAtlasSprite sp) {
        String name = sp.getIconName();
        Mask m = MASKS.get(name);
        if (m != null) return m;
        try {
            ResourceLocation icon = new ResourceLocation(name);
            BufferedImage img = EmissiveTextures.read(new ResourceLocation(icon.getNamespace(), "textures/" + icon.getPath() + ".png"));
            if (img == null) { m = SOLID; }
            else {
                int w = img.getWidth(), h = Math.min(img.getHeight(), w);   // animated: first frame
                int[] px = new int[w * h];
                img.getRGB(0, 0, w, h, px, 0, w);
                m = new Mask(w, h, px);
            }
        } catch (Throwable e) {
            m = SOLID;
        }
        MASKS.put(name, m);
        return m;
    }

    private static boolean hitsBox(IBlockAccess w, IBlockState st, BlockPos.MutableBlockPos mp, int bx, int by, int bz,
                                   double x, double y, double z, double dx, double dy, double dz, double t0, double t1) {
        AxisAlignedBB box;
        try { box = st.getBoundingBox(w, mp.setPos(bx, by, bz)); } catch (Throwable e) { return true; }
        if (box == null) return false;
        double lo = t0, hi = t1;
        double[] o = {x, y, z}, d = {dx, dy, dz};
        double[] mn = {bx + box.minX, by + box.minY, bz + box.minZ}, mx = {bx + box.maxX, by + box.maxY, bz + box.maxZ};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-9) {
                if (o[a] < mn[a] || o[a] > mx[a]) return false;
            } else {
                double ta = (mn[a] - o[a]) / d[a], tb = (mx[a] - o[a]) / d[a];
                if (ta > tb) { double s = ta; ta = tb; tb = s; }
                lo = Math.max(lo, ta); hi = Math.min(hi, tb);
                if (lo > hi) return false;
            }
        }
        return true;
    }
}
