package com.lumen.client;

/**
 * Adaptive (quadtree) refinement of a quad for sharp shadow edges.
 *
 * The quad is split into quarters only where its samples disagree (a shadow edge crosses it),
 * down to cells of 1/maxCells of the quad - with maxCells = 16 that's one texture pixel per block.
 * Evenly lit or evenly shaded parts stay large, so the cost follows the length of the shadow
 * edges, not the area. Samples are cached on the finest grid and shared between neighbours.
 */
public final class AdaptiveQuad {
    private AdaptiveQuad() {}

    public interface Sampler { float[] at(double s, double t); }
    public interface Emitter { void quad(double s0, double t0, double s1, double t1, float[] a, float[] b, float[] c, float[] d); }

    /**
     * @param maxCells  finest subdivision per side (power of two, 1..64)
     * @param key       index of the value in a sample that decides splitting
     * @param threshold split when that value varies by more than this inside a cell
     */
    public static void subdivide(int maxCells, int key, float threshold, Sampler sampler, Emitter emitter) {
        subdivide(maxCells, 1, key, threshold, sampler, emitter);
    }

    /** minCells: always split down to at least this grid first (catches features thinner than a block). */
    public static void subdivide(int maxCells, int minCells, int key, float threshold, Sampler sampler, Emitter emitter) {
        int n = Integer.highestOneBit(Math.max(1, Math.min(64, maxCells)));
        int start = n / Math.max(1, Math.min(n, Integer.highestOneBit(minCells)));
        float[][] cache = new float[(n + 1) * (n + 1)][];
        for (int i = 0; i < n; i += start) for (int j = 0; j < n; j += start)
            node(i, j, start, n, key, threshold, sampler, emitter, cache);
    }

    private static float[] get(int i, int j, int n, Sampler s, float[][] cache) {
        int idx = i * (n + 1) + j;
        float[] v = cache[idx];
        if (v == null) { v = s.at(i / (double) n, j / (double) n); cache[idx] = v; }
        return v;
    }

    private static void node(int i0, int j0, int size, int n, int key, float thr, Sampler s, Emitter e, float[][] cache) {
        float[] a = get(i0, j0, n, s, cache), b = get(i0 + size, j0, n, s, cache);
        float[] c = get(i0 + size, j0 + size, n, s, cache), d = get(i0, j0 + size, n, s, cache);
        if (size > 1) {
            int h = size / 2;
            float lo = Math.min(Math.min(a[key], b[key]), Math.min(c[key], d[key]));
            float hi = Math.max(Math.max(a[key], b[key]), Math.max(c[key], d[key]));
            float m = get(i0 + h, j0 + h, n, s, cache)[key];
            lo = Math.min(lo, m); hi = Math.max(hi, m);
            if (hi - lo <= thr && size < n) {
                // below the top level also check edge midpoints: catches thin shadows (fence posts, bars)
                for (float[] v : new float[][]{get(i0 + h, j0, n, s, cache), get(i0 + size, j0 + h, n, s, cache),
                        get(i0 + h, j0 + size, n, s, cache), get(i0, j0 + h, n, s, cache)}) {
                    lo = Math.min(lo, v[key]); hi = Math.max(hi, v[key]);
                }
            }
            if (hi - lo > thr) {
                node(i0, j0, h, n, key, thr, s, e, cache);
                node(i0 + h, j0, h, n, key, thr, s, e, cache);
                node(i0 + h, j0 + h, h, n, key, thr, s, e, cache);
                node(i0, j0 + h, h, n, key, thr, s, e, cache);
                return;
            }
        } else {
            // finest cell on an edge: one flat value (its center), so the edge is a hard pixel step
            // instead of a one-cell blend
            float lo = Math.min(Math.min(a[key], b[key]), Math.min(c[key], d[key]));
            float hi = Math.max(Math.max(a[key], b[key]), Math.max(c[key], d[key]));
            if (hi - lo > thr) {
                float[] m = s.at((i0 + 0.5) / n, (j0 + 0.5) / n);
                e.quad(i0 / (double) n, j0 / (double) n, (i0 + 1) / (double) n, (j0 + 1) / (double) n, m, m, m, m);
                return;
            }
        }
        e.quad(i0 / (double) n, j0 / (double) n, (i0 + size) / (double) n, (j0 + size) / (double) n, a, b, c, d);
    }
}
