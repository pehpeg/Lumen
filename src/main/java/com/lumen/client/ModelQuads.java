package com.lumen.client;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The visible quads of a block's real model (full blocks, stairs, slabs, grass, flowers...), as
 * the chunk renderer would draw them: hidden faces culled, plant offsets applied, coplanar overlay
 * quads (grass sides) reported once. Safe on worker threads, like vanilla's chunk builders.
 */
final class ModelQuads {
    private ModelQuads() {}

    static final class Q {
        final double[][] pos = new double[4][3];
        final float[][] uv = new float[4][2];
        final double[] n = new double[3], ctr = new double[3];
        boolean twoSided;
    }

    private static final EnumFacing[] SIDES_AND_NULL = {EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH,
            EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST, null};

    static void forEach(IBlockAccess w, BlockPos p, IBlockState st, Consumer<Q> out) {
        Material mat = st.getMaterial();
        if (mat == Material.AIR || mat.isLiquid() || st.getRenderType() != EnumBlockRenderType.MODEL) return;
        try {
            BlockRendererDispatcher brd = Minecraft.getMinecraft().getBlockRendererDispatcher();
            IBlockState actual = st.getActualState(w, p);
            IBakedModel model = brd.getModelForState(actual);
            IBlockState ext = actual.getBlock().getExtendedState(actual, w, p);
            Vec3d off = actual.getOffset(w, p);
            long rand = MathHelper.getPositionRandom(p);
            boolean twoSided = !actual.isFullCube() && !mat.blocksMovement();
            Set<String> seen = new HashSet<>();
            for (EnumFacing side : SIDES_AND_NULL) {
                if (side != null && !actual.shouldSideBeRendered(w, p, side)) continue;
                for (BakedQuad bq : model.getQuads(ext, side, rand)) {
                    Q q = convert(bq, p, off, seen);
                    if (q != null) { q.twoSided = twoSided; out.accept(q); }
                }
            }
        } catch (Throwable ignored) {
            // odd modded block: leave it as vanilla draws it
        }
    }

    private static Q convert(BakedQuad bq, BlockPos p, Vec3d off, Set<String> seen) {
        int[] data = bq.getVertexData();
        VertexFormat fmt = bq.getFormat();
        int stride = fmt.getIntegerSize(), uvo = fmt.getUvOffsetById(0) / 4;
        Q q = new Q();
        for (int i = 0; i < 4; i++) {
            int b = i * stride;
            q.pos[i][0] = Float.intBitsToFloat(data[b]) + p.getX() + off.x;
            q.pos[i][1] = Float.intBitsToFloat(data[b + 1]) + p.getY() + off.y;
            q.pos[i][2] = Float.intBitsToFloat(data[b + 2]) + p.getZ() + off.z;
            q.uv[i][0] = Float.intBitsToFloat(data[b + uvo]);
            q.uv[i][1] = Float.intBitsToFloat(data[b + uvo + 1]);
        }
        double[][] sorted = q.pos.clone();
        java.util.Arrays.sort(sorted, (a, c) -> a[0] != c[0] ? Double.compare(a[0], c[0]) : a[1] != c[1] ? Double.compare(a[1], c[1]) : Double.compare(a[2], c[2]));
        StringBuilder key = new StringBuilder();
        for (double[] v : sorted) key.append(Math.round(v[0] * 64)).append(',').append(Math.round(v[1] * 64)).append(',').append(Math.round(v[2] * 64)).append(';');
        if (!seen.add(key.toString())) return null;

        double ex = q.pos[1][0] - q.pos[0][0], ey = q.pos[1][1] - q.pos[0][1], ez = q.pos[1][2] - q.pos[0][2];
        double fx = q.pos[3][0] - q.pos[0][0], fy = q.pos[3][1] - q.pos[0][1], fz = q.pos[3][2] - q.pos[0][2];
        double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        EnumFacing f = bq.getFace();
        if (nl < 1e-9) { nx = f.getXOffset(); ny = f.getYOffset(); nz = f.getZOffset(); }
        else { nx /= nl; ny /= nl; nz /= nl; }
        if (nx * f.getXOffset() + ny * f.getYOffset() + nz * f.getZOffset() < 0) { nx = -nx; ny = -ny; nz = -nz; }
        q.n[0] = nx; q.n[1] = ny; q.n[2] = nz;
        for (int a = 0; a < 3; a++) q.ctr[a] = (q.pos[0][a] + q.pos[2][a]) * 0.5;
        return q;
    }

    /** Point at (s, t) on the quad: s runs vertex 0 -> 1, t runs vertex 0 -> 3. */
    static double[] at(Q q, double s, double t) {
        double[] r = new double[3];
        for (int a = 0; a < 3; a++)
            r[a] = (1 - s) * (1 - t) * q.pos[0][a] + s * (1 - t) * q.pos[1][a] + s * t * q.pos[2][a] + (1 - s) * t * q.pos[3][a];
        return r;
    }

    static float[] uvAt(Q q, double s, double t) {
        float[] r = new float[2];
        for (int a = 0; a < 2; a++)
            r[a] = (float) ((1 - s) * (1 - t) * q.uv[0][a] + s * (1 - t) * q.uv[1][a] + s * t * q.uv[2][a] + (1 - s) * t * q.uv[3][a]);
        return r;
    }

    /**
     * Sample start point: a hair off the surface and a hair toward the quad's middle, so a point on
     * a block edge can never start inside the neighbouring block.
     */
    static double[] start(Q q, double[] pt) {
        return new double[]{
                pt[0] + q.n[0] * 0.02 + (q.ctr[0] - pt[0]) * 0.004,
                pt[1] + q.n[1] * 0.02 + (q.ctr[1] - pt[1]) * 0.004,
                pt[2] + q.n[2] * 0.02 + (q.ctr[2] - pt[2]) * 0.004};
    }

    /** Point on the lit (air) side used to read vanilla light. */
    static double[] airSide(Q q, double[] s) {
        if (q.twoSided) return s;
        return new double[]{s[0] + q.n[0] * 0.45, s[1] + q.n[1] * 0.45, s[2] + q.n[2] * 0.45};
    }
}
