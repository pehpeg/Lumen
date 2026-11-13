package com.lumen.client;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Numbers every block shape for the GPU: 0 = nothing stops light, 1 = full solid block, 2+ = a
 * pixel-voxel shape (stairs, slabs, fences, ladders, plants, glass...). Identical shapes share one
 * number. New shapes are queued for upload to the shape texture (one row each). Worker-thread safe.
 */
final class ShapeAtlas {
    static final int CAPACITY = 4096, ROW = 129;

    private static final int[] NONE = new int[0];
    private final ConcurrentHashMap<IBlockState, Integer> byState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Integer> byContent = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<Object[]> uploads = new ConcurrentLinkedQueue<>();   // {Integer id, int[] row}
    private int next = 2;

    void clear() {
        byState.clear();
        byContent.clear();
        uploads.clear();
        synchronized (this) { next = 2; }
    }

    /** Shape number of the block at p (state is the stored state; its actual shape is used). */
    int idFor(IBlockState st, IBlockAccess w, BlockPos p) {
        Material mat = st.getMaterial();
        if (mat == Material.AIR || mat.isLiquid()) return 0;
        if (st.isOpaqueCube()) return 1;
        IBlockState actual;
        try { actual = st.getActualState(w, p); } catch (Throwable e) { actual = st; }
        Integer id = byState.get(actual);
        if (id != null) return id;
        int[] bits;
        try { bits = Shapes.voxelize(actual, w, p); } catch (Throwable e) { bits = null; }
        int v;
        if (bits == null) v = 0;
        else {
            Key k = new Key(bits);
            Integer known = byContent.get(k);
            if (known != null) v = known;
            else synchronized (this) {
                known = byContent.get(k);
                if (known != null) v = known;
                else if (next >= CAPACITY) v = mat.blocksMovement() ? 1 : 0;   // too many shapes: approximate
                else {
                    v = next++;
                    byContent.put(k, v);
                    uploads.add(new Object[]{v, bits});
                }
            }
        }
        byState.put(actual, v);
        return v;
    }

    private static final class Key {
        final int[] a; final int h;
        Key(int[] a) { this.a = a; this.h = Arrays.hashCode(a); }
        @Override public int hashCode() { return h; }
        @Override public boolean equals(Object o) { return o instanceof Key && Arrays.equals(a, ((Key) o).a); }
    }
}
