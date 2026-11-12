package com.lumen.core;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Virtual light emitters for dynamic lights.
 *
 * The block light engine asks every position "how much light do you emit?" through
 * Block.getLightValue(state, world, pos). Lumen's mixin adds these virtual emitters to the answer,
 * on the client world only. The game's own light engine (or Phosphor/Alfheim if installed) then
 * spreads the light into the real light data - with walls blocking it correctly - and every chunk
 * renderer (vanilla, OptiFine, Nothirium, Vintagium, Celeritas...) just sees ordinary light.
 * Same principle as Moving Light Sources' invisible light blocks, without touching the world.
 *
 * Lookups happen on the client thread inside lighting; the table is an immutable snapshot
 * (open addressing, power-of-two capacity) so a lookup is a couple of array reads.
 */
public final class VirtualLights {

    private static final class Table {
        final long[] keys; final byte[] vals; final int mask; final int size;
        Table(long[] keys, byte[] vals, int size) { this.keys = keys; this.vals = vals; this.mask = keys.length - 1; this.size = size; }
    }

    private static final long EMPTY = Long.MIN_VALUE;
    private static volatile Table table = new Table(new long[]{EMPTY}, new byte[1], 0);

    /** Diagnostics: lookups that hit an emitter (proves the hook is live). */
    public static long hits;

    private VirtualLights() {}

    /** Replaces all emitters. positions are BlockPos.toLong() values. */
    public static void publish(long[] positions, int[] levels, int count) {
        int cap = 8;
        while (cap < count * 3) cap <<= 1;
        long[] keys = new long[cap];
        byte[] vals = new byte[cap];
        java.util.Arrays.fill(keys, EMPTY);
        for (int i = 0; i < count; i++) {
            int slot = slot(positions[i], cap - 1);
            while (keys[slot] != EMPTY && keys[slot] != positions[i]) slot = (slot + 1) & (cap - 1);
            keys[slot] = positions[i];
            vals[slot] = (byte) Math.max(vals[slot], Math.min(15, levels[i]));
        }
        table = new Table(keys, vals, count);
    }

    public static boolean isEmpty() { return table.size == 0; }

    /** Light the virtual emitter at pos gives off (0 if none). Called from the lighting hook. */
    public static int emission(IBlockAccess access, BlockPos pos) {
        Table t = table;
        if (t.size == 0 || pos == null) return 0;
        if (!(access instanceof World) || !((World) access).isRemote) return 0;   // client world only
        long key = pos.toLong();
        int slot = slot(key, t.mask);
        while (true) {
            long k = t.keys[slot];
            if (k == key) { hits++; return t.vals[slot]; }
            if (k == EMPTY) return 0;
            slot = (slot + 1) & t.mask;
        }
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }
}
