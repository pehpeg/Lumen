package com.lumen.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The blocks around the player as a 3D texture for GPU light tracing: per block its shape number
 * (ShapeAtlas), vanilla block and sky light, and the plant offset. Wraps around (a ring buffer in
 * every direction), so moving only uploads the 16x16x16 sections that came into range. Sections
 * are read on background threads and uploaded a few per frame.
 */
final class VoxelVolume {
    static final int SLOTS_Y = 8;

    final int rc, n;              // horizontal radius in sections; slots per horizontal side
    final int sizeX, sizeY, sizeZ;
    private final ShapeAtlas atlas;
    int tex = -1;
    int csx, csy, csz;            // center section
    private final long[] slotKey;
    private final Set<Long> dirty = new HashSet<>(), queued = new HashSet<>();
    private final ConcurrentLinkedQueue<Object[]> done = new ConcurrentLinkedQueue<>();   // {Long key, int sx, sy, sz, ByteBuffer}
    private final AtomicInteger pending = new AtomicInteger();
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Lumen voxel reader");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private int refreshCursor;
    int statUploaded;

    VoxelVolume(int rc, ShapeAtlas atlas) {
        this.rc = rc;
        this.n = rc * 2 + 1;
        this.sizeX = n * 16; this.sizeY = SLOTS_Y * 16; this.sizeZ = n * 16;
        this.atlas = atlas;
        this.slotKey = new long[n * SLOTS_Y * n];
        java.util.Arrays.fill(slotKey, Long.MIN_VALUE);
    }

    static long key(int x, int y, int z) { return ((long) x << 40) | ((long) (y & 0xFF) << 32) | (z & 0xFFFFFFFFL); }

    private static int mod(int a, int m) { return ((a % m) + m) % m; }

    private int slot(int sx, int sy, int sz) { return (mod(sx, n) * SLOTS_Y + mod(sy, SLOTS_Y)) * n + mod(sz, n); }

    void create() {
        tex = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        ByteBuffer zero = BufferUtils.createByteBuffer(sizeX * sizeY * sizeZ * 4);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGBA8UI, sizeX, sizeY, sizeZ, 0, GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_BYTE, zero);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    void delete() {
        if (tex >= 0) GL11.glDeleteTextures(tex);
        tex = -1;
        worker.shutdownNow();
    }

    /** In range for GPU lighting: every block a ray from here can reach is inside the volume. */
    boolean eligible(int sx, int sy, int sz) {
        return Math.abs(sx - csx) <= rc - 1 && Math.abs(sz - csz) <= rc - 1 && sy >= csy - 3 && sy <= csy + 2;
    }

    /** The section and its 26 neighbours are uploaded. */
    boolean ready(int sx, int sy, int sz) {
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            if (slotKey[slot(sx + x, sy + y, sz + z)] != key(sx + x, sy + y, sz + z)) return false;
        return true;
    }

    void markDirty(BlockPos p) {
        int x = p.getX(), y = p.getY(), z = p.getZ();
        // light changes spread: refresh the neighbours the change touches too
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
            dirty.add(key((x + dx * 15) >> 4, (y + dy * 15) >> 4, (z + dz * 15) >> 4));
    }

    void tick(World w, int psx, int psy, int psz) {
        csx = psx; csy = psy; csz = psz;
        List<int[]> want = new ArrayList<>();
        for (int x = -rc; x <= rc; x++) for (int y = -4; y <= 3; y++) for (int z = -rc; z <= rc; z++) {
            int sx = csx + x, sy = csy + y, sz = csz + z;
            long k = key(sx, sy, sz);
            if (queued.contains(k)) continue;
            if (slotKey[slot(sx, sy, sz)] != k || dirty.contains(k)) want.add(new int[]{sx, sy, sz, x * x + y * y * 2 + z * z});
        }
        // vanilla light changes without block changes (other light sources): slowly refresh near sections
        if (want.isEmpty()) {
            int side = 5, total = side * side * 3;
            int i = refreshCursor++ % total;
            int sx = csx + (i % side) - 2, sz = csz + ((i / side) % side) - 2, sy = csy + (i / (side * side)) - 1;
            long k = key(sx, sy, sz);
            if (!queued.contains(k)) want.add(new int[]{sx, sy, sz, 0});
        }
        want.sort((a, b) -> Integer.compare(a[3], b[3]));
        for (int[] s : want) {
            if (pending.get() >= 6) break;
            int sx = s[0], sy = s[1], sz = s[2];
            if (sy >= 0 && sy <= 15 && !w.isChunkGeneratedAt(sx, sz)) continue;
            submit(w, sx, sy, sz);
        }
    }

    private void submit(World w, int sx, int sy, int sz) {
        long k = key(sx, sy, sz);
        dirty.remove(k);
        queued.add(k);
        ChunkCache cache = sy < 0 || sy > 15 ? null
                : new ChunkCache(w, new BlockPos((sx << 4) - 1, Math.max(0, (sy << 4) - 1), (sz << 4) - 1),
                new BlockPos((sx << 4) + 16, Math.min(255, (sy << 4) + 16), (sz << 4) + 16), 0);
        pending.incrementAndGet();
        worker.submit(() -> {
            try {
                done.add(new Object[]{k, sx, sy, sz, read(cache, sx, sy, sz)});
            } catch (Throwable t) {
                done.add(new Object[]{k, sx, sy, sz, null});
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private ByteBuffer read(ChunkCache c, int sx, int sy, int sz) {
        ByteBuffer b = BufferUtils.createByteBuffer(16 * 16 * 16 * 4);
        if (c == null) {   // above / below the world
            for (int i = 0; i < 4096; i++) { b.put((byte) (sy < 0 ? 1 : 0)); b.put((byte) 0); b.put((byte) (sy < 0 ? 0 : 0xF0)); b.put((byte) 0); }
            b.flip();
            return b;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            p.setPos((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
            IBlockState st = c.getBlockState(p);
            int id = atlas.idFor(st, c, p);
            int bl = c.getLightFor(EnumSkyBlock.BLOCK, p), sk = c.getLightFor(EnumSkyBlock.SKY, p);
            int off = 0x88;
            if (id >= 2) {
                try {
                    Vec3d o = st.getOffset(c, p);
                    int ox = Math.max(-8, Math.min(7, (int) Math.round(o.x * 16))), oz = Math.max(-8, Math.min(7, (int) Math.round(o.z * 16)));
                    off = (ox + 8) | ((oz + 8) << 4);
                } catch (Throwable ignored) { }
            }
            b.put((byte) (id & 0xFF)); b.put((byte) (id >> 8)); b.put((byte) ((bl & 15) | ((sk & 15) << 4))); b.put((byte) off);
        }
        b.flip();
        return b;
    }

    /** Main thread: upload finished sections (a few per frame). */
    void upload(int max) {
        Object[] r;
        int count = 0;
        boolean bound = false;
        while (count < max && (r = done.poll()) != null) {
            long k = (Long) r[0];
            queued.remove(k);
            ByteBuffer data = (ByteBuffer) r[4];
            if (data == null) continue;
            int sx = (Integer) r[1], sy = (Integer) r[2], sz = (Integer) r[3];
            if (Math.abs(sx - csx) > rc || Math.abs(sz - csz) > rc || sy < csy - 4 || sy > csy + 3) continue;   // left the volume meanwhile
            if (!bound) {
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
                GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
                bound = true;
            }
            GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, mod(sx, n) * 16, mod(sy, SLOTS_Y) * 16, mod(sz, n) * 16, 16, 16, 16,
                    GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_BYTE, data);
            slotKey[slot(sx, sy, sz)] = k;
            count++;
            statUploaded++;
        }
        if (bound) GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    int readyCount() {
        int c = 0;
        for (int x = -rc; x <= rc; x++) for (int y = -4; y <= 3; y++) for (int z = -rc; z <= rc; z++)
            if (slotKey[slot(csx + x, csy + y, csz + z)] == key(csx + x, csy + y, csz + z)) c++;
        return c;
    }
}
