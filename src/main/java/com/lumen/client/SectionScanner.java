package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans 16x16x16 chunk sections around the player a few per tick (empty sections are skipped
 * for free) and records what Lumen needs from them:
 *  - emissive quads (glowing ore bits / resource-pack emissive textures), already in world space
 *  - light-emitting blocks with a color (for colored light)
 * Block changes mark their section dirty so it's rescanned right away.
 */
public class SectionScanner {

    private final Minecraft mc = Minecraft.getMinecraft();
    private final EmissiveTextures textures;
    private final LightColors colors;

    /** One emissive quad: 4 world-space corners and their 0..1 UVs in the glow texture. */
    public static final class GlowQuad {
        public final float[] xyz; public final float[] uv; public final EmissiveTextures.Glow glow; public final long seed;
        GlowQuad(float[] xyz, float[] uv, EmissiveTextures.Glow glow, long seed) { this.xyz = xyz; this.uv = uv; this.glow = glow; this.seed = seed; }
    }

    /** A colored light block. */
    public static final class ColorLight {
        public final BlockPos pos; public final int level; public final int rgb;
        ColorLight(BlockPos pos, int level, int rgb) { this.pos = pos; this.level = level; this.rgb = rgb; }
        @Override public boolean equals(Object o) {
            return o instanceof ColorLight && ((ColorLight) o).pos.equals(pos) && ((ColorLight) o).level == level && ((ColorLight) o).rgb == rgb;
        }
        @Override public int hashCode() { return pos.hashCode() * 31 + level * 7 + rgb; }
    }

    private static final class Section {
        List<GlowQuad> quads = new ArrayList<>();
        List<ColorLight> lights = new ArrayList<>();
    }

    private final Map<Long, Section> sections = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();
    private final List<Long> order = new ArrayList<>();
    private int cursor;
    private int lastCx = Integer.MIN_VALUE, lastCy, lastCz;
    private World world;
    private boolean changed = true;
    /** Increases whenever the scanned content changes (for render caches). */
    public int version;

    /** model quads that carry an emissive texture, cached per block state (null list = none) */
    private final Map<IBlockState, List<Object[]>> stateQuads = new IdentityHashMap<>();
    private static final List<Object[]> NO_QUADS = new ArrayList<>();

    public static int statSections, statQuads, statLights;

    public SectionScanner(EmissiveTextures textures, LightColors colors) { this.textures = textures; this.colors = colors; }

    public void clearCaches() { stateQuads.clear(); sections.clear(); dirty.clear(); order.clear(); { changed = true; version++; } }

    public void markDirty(BlockPos p) {
        dirty.add(key(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4));
    }

    /** True once after the set of colored lights or glow quads changed. */
    public boolean consumeChanged() { boolean c = changed; changed = false; return c; }

    public void tick(int budget) {
        World w = mc.world;
        if (w != world) { world = w; clearCaches(); lastCx = Integer.MIN_VALUE; }
        if (w == null || mc.player == null) return;
        int radius = Math.max(LumenConfig.emissive.radius, LumenConfig.colored.radius);
        int r = (radius + 15) >> 4, ry = Math.max(1, (radius / 2 + 15) >> 4);
        int pcx = mc.player.chunkCoordX, pcy = (int) mc.player.posY >> 4, pcz = mc.player.chunkCoordZ;

        if (pcx != lastCx || pcy != lastCy || pcz != lastCz) {   // moved to another section: new scan order
            lastCx = pcx; lastCy = pcy; lastCz = pcz;
            order.clear();
            Set<Long> keep = new HashSet<>();
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) for (int y = -ry; y <= ry; y++) {
                int sy = pcy + y;
                if (sy < 0 || sy > 15) continue;
                long k = key(pcx + x, sy, pcz + z);
                order.add(k);
                keep.add(k);
            }
            order.sort((a, b) -> Long.compare(dist(a, pcx, pcy, pcz), dist(b, pcx, pcy, pcz)));
            if (sections.keySet().retainAll(keep)) { changed = true; version++; }
            cursor = 0;
        }
        // dirty sections first, then the round-robin refresh
        Iterator<Long> it = dirty.iterator();
        while (it.hasNext() && budget > 0) {
            long k = it.next(); it.remove();
            if (order.contains(k)) { scan(w, k); budget--; }
        }
        for (int i = 0; i < budget && !order.isEmpty(); i++) {
            if (cursor >= order.size()) cursor = 0;
            scan(w, order.get(cursor++));
        }
        statSections = sections.size();
    }

    private static long dist(long k, int cx, int cy, int cz) {
        int x = (int) (k >> 40), y = (int) ((k >> 32) & 0xFF), z = (int) k;
        long dx = x - cx, dy = y - cy, dz = z - cz;
        return dx * dx + dy * dy * 4 + dz * dz;
    }

    static long key(int cx, int cy, int cz) { return ((long) cx << 40) | ((long) (cy & 0xFF) << 32) | (cz & 0xFFFFFFFFL); }

    private void scan(World w, long k) {
        int cx = (int) (k >> 40), cy = (int) ((k >> 32) & 0xFF), cz = (int) k;
        if (!w.isChunkGeneratedAt(cx, cz)) { if (sections.remove(k) != null) { changed = true; version++; } return; }
        Chunk chunk = w.getChunk(cx, cz);
        ExtendedBlockStorage storage = chunk.getBlockStorageArray()[cy];
        Section old = sections.get(k);
        if (storage == Chunk.NULL_BLOCK_STORAGE || storage.isEmpty()) {
            if (old != null) { sections.remove(k); { changed = true; version++; } }
            return;
        }
        Section s = new Section();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        boolean wantGlow = LumenConfig.emissive.enabled, wantColor = LumenConfig.colored.enabled;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            IBlockState state = storage.get(x, y, z);
            if (state.getMaterial() == Material.AIR) continue;
            p.setPos((cx << 4) + x, (cy << 4) + y, (cz << 4) + z);
            if (wantColor) {
                int light = state.getLightValue(w, p);
                if (light >= 5 && com.lumen.core.VirtualLights.emission(w, p) < light) {  // not one of our moving lights
                    int rgb = colors.colorOf(state, w, p);
                    if (rgb != LightColors.WHITE) s.lights.add(new ColorLight(p.toImmutable(), light, rgb));
                }
            }
            if (wantGlow) {
                List<Object[]> quads = glowQuads(state);
                if (quads != NO_QUADS) {
                    for (Object[] q : quads) {
                        EnumFacing cull = (EnumFacing) q[0];
                        if (cull != null) {
                            n.setPos(p).move(cull);
                            if (w.getBlockState(n).doesSideBlockRendering(w, n, cull.getOpposite())) continue;
                        }
                        float[] local = (float[]) q[1];
                        float[] xyz = new float[12];
                        for (int i = 0; i < 4; i++) {
                            xyz[i * 3] = local[i * 3] + p.getX();
                            xyz[i * 3 + 1] = local[i * 3 + 1] + p.getY();
                            xyz[i * 3 + 2] = local[i * 3 + 2] + p.getZ();
                        }
                        s.quads.add(new GlowQuad(xyz, (float[]) q[2], (EmissiveTextures.Glow) q[3], p.toLong()));
                    }
                }
            }
        }
        if (s.quads.isEmpty() && s.lights.isEmpty()) {
            if (old != null) { sections.remove(k); { changed = true; version++; } }
            return;
        }
        if (old == null || !old.lights.equals(s.lights) || old.quads.size() != s.quads.size()) { changed = true; version++; }
        sections.put(k, s);
    }

    /** [cullFace, local xyz (12 floats), glow uv (8 floats), glow] for each quad of the state with a glow texture. */
    private List<Object[]> glowQuads(IBlockState state) {
        List<Object[]> cached = stateQuads.get(state);
        if (cached != null) return cached;
        List<Object[]> out = new ArrayList<>();
        try {
            IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(state);
            List<BakedQuad> all = new ArrayList<>();
            for (EnumFacing f : EnumFacing.VALUES) all.addAll(model.getQuads(state, f, 0L));
            all.addAll(model.getQuads(state, null, 0L));
            for (BakedQuad q : all) {
                EmissiveTextures.Glow glow = textures.get(q.getSprite(), state);
                if (glow == null) continue;
                VertexFormat fmt = q.getFormat();
                int stride = fmt.getIntegerSize();
                int uvOff = fmt.getUvOffsetById(0) / 4;
                int[] data = q.getVertexData();
                float[] xyz = new float[12], uv = new float[8];
                float u0 = q.getSprite().getMinU(), du = q.getSprite().getMaxU() - u0;
                float v0 = q.getSprite().getMinV(), dv = q.getSprite().getMaxV() - v0;
                for (int i = 0; i < 4; i++) {
                    int b = i * stride;
                    xyz[i * 3] = Float.intBitsToFloat(data[b]);
                    xyz[i * 3 + 1] = Float.intBitsToFloat(data[b + 1]);
                    xyz[i * 3 + 2] = Float.intBitsToFloat(data[b + 2]);
                    uv[i * 2] = (Float.intBitsToFloat(data[b + uvOff]) - u0) / du;
                    uv[i * 2 + 1] = (Float.intBitsToFloat(data[b + uvOff + 1]) - v0) / dv;
                }
                out.add(new Object[]{cullOf(q), xyz, uv, glow});
            }
        } catch (Exception ignored) {
            // odd modded model: no glow for it
        }
        List<Object[]> result = out.isEmpty() ? NO_QUADS : out;
        stateQuads.put(state, result);
        return result;
    }

    /** A quad lying on the block's outer face can be culled by the neighbor on that side. */
    private static EnumFacing cullOf(BakedQuad q) {
        int[] d = q.getVertexData();
        int stride = q.getFormat().getIntegerSize();
        EnumFacing f = q.getFace();
        int axis = f.getAxis().ordinal();
        float edge = f.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? 1f : 0f;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(Float.intBitsToFloat(d[i * stride + axis]) - edge) > 1e-3f) return null;
        }
        return f;
    }

    public void forEachQuad(java.util.function.Consumer<GlowQuad> c) {
        for (Section s : sections.values()) for (GlowQuad q : s.quads) c.accept(q);
    }

    public List<ColorLight> colorLights() {
        List<ColorLight> out = new ArrayList<>();
        for (Section s : sections.values()) out.addAll(s.lights);
        statLights = out.size();
        return out;
    }

    public int quadCount() {
        int n = 0;
        for (Section s : sections.values()) n += s.quads.size();
        statQuads = n;
        return n;
    }
}
