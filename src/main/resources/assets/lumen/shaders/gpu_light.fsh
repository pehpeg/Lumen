#version 130
// Lumen GPU lighting. Per pixel: walk from the surface to each light through a copy of the nearby
// blocks (3D texture: shape number, vanilla light, plant offset) and their pixel shapes, then apply
// Lumen's light model. Mode 0: static lights as a 2x-modulate layer. Mode 1: moving lights, added.

uniform usampler3D Vox;
uniform usampler2D Shapes;
uniform sampler2D Lights;
uniform sampler2D Atlas;

uniform ivec3 VolSize;
uniform ivec3 BaseMod;
uniform int BaseY;
uniform int Idx[48];
uniform int NStatic;
uniform int NMoving;
uniform int Mode;
uniform float Table[16];
uniform float SunB;
uniform float ColK;
uniform float ShadowK;
uniform vec2 Fog;

in vec3 vPos;
in vec3 vNrm;
in vec2 vUv;
in vec4 vCol;
in float vDist;

out vec4 fragColor;

uvec4 cellAt(ivec3 c) {
    int wy = c.y + BaseY;
    if (wy < 0) return uvec4(1u, 0u, 0u, 136u);
    if (wy > 255) return uvec4(0u, 0u, 240u, 136u);
    ivec3 t = (c + BaseMod + VolSize * 64) % VolSize;
    return texelFetch(Vox, t, 0);
}

uint shapeId(uvec4 v) { return v.r | (v.g << 8u); }
float max3(vec3 v) { return max(v.x, max(v.y, v.z)); }

// what gets through one shaped block between t0 and t1 along the ray
vec3 throughShape(uint id, uint offs, vec3 o, vec3 d, float t0, float t1, ivec3 c) {
    vec3 off = vec3(float(offs & 15u) - 8.0, 0.0, float(offs >> 4u) - 8.0) / 16.0;
    vec3 p = (o + d * t0 - vec3(c) - off) * 16.0;
    vec3 q = d * 16.0;
    vec3 s0 = floor(p);
    ivec3 s = ivec3(s0);
    ivec3 st = ivec3(sign(q));
    vec3 tM = vec3(q.x > 0.0 ? (s0.x + 1.0 - p.x) / q.x : (q.x < 0.0 ? (s0.x - p.x) / q.x : 1e30),
                   q.y > 0.0 ? (s0.y + 1.0 - p.y) / q.y : (q.y < 0.0 ? (s0.y - p.y) / q.y : 1e30),
                   q.z > 0.0 ? (s0.z + 1.0 - p.z) / q.z : (q.z < 0.0 ? (s0.z - p.z) / q.z : 1e30));
    vec3 tD = vec3(q.x != 0.0 ? abs(1.0 / q.x) : 1e30, q.y != 0.0 ? abs(1.0 / q.y) : 1e30, q.z != 0.0 ? abs(1.0 / q.z) : 1e30);
    float tEnd = t1 - t0;
    int row = int(id);
    for (int i = 0; i < 64; i++) {
        if (all(greaterThanEqual(s, ivec3(0))) && all(lessThan(s, ivec3(16)))) {
            int bit = s.x + s.y * 16 + s.z * 256;
            uint w = texelFetch(Shapes, ivec2(bit >> 5, row), 0).r;
            if (((w >> uint(bit & 31)) & 1u) != 0u) {
                uint meta = texelFetch(Shapes, ivec2(128, row), 0).r;
                if ((meta & 3u) == 0u) return vec3(0.0);   // a solid pixel
                return vec3(float((meta >> 8u) & 255u), float((meta >> 16u) & 255u), float((meta >> 24u) & 255u)) / 255.0;
            }
        }
        float nt = min(tM.x, min(tM.y, tM.z));
        if (nt > tEnd) break;
        if (tM.x <= tM.y && tM.x <= tM.z) { s.x += st.x; tM.x += tD.x; }
        else if (tM.y <= tM.z) { s.y += st.y; tM.y += tD.y; }
        else { s.z += st.z; tM.z += tD.z; }
    }
    return vec3(1.0);
}

vec3 trace(vec3 o, vec3 d, float maxT, ivec3 skip, bool hasSkip) {
    vec3 tr = vec3(1.0);
    vec3 c0 = floor(o);
    ivec3 c = ivec3(c0);
    ivec3 st = ivec3(sign(d));
    vec3 tM = vec3(d.x > 0.0 ? (c0.x + 1.0 - o.x) / d.x : (d.x < 0.0 ? (c0.x - o.x) / d.x : 1e30),
                   d.y > 0.0 ? (c0.y + 1.0 - o.y) / d.y : (d.y < 0.0 ? (c0.y - o.y) / d.y : 1e30),
                   d.z > 0.0 ? (c0.z + 1.0 - o.z) / d.z : (d.z < 0.0 ? (c0.z - o.z) / d.z : 1e30));
    vec3 tD = vec3(d.x != 0.0 ? abs(1.0 / d.x) : 1e30, d.y != 0.0 ? abs(1.0 / d.y) : 1e30, d.z != 0.0 ? abs(1.0 / d.z) : 1e30);
    float tIn = 0.0;
    for (int i = 0; i < 64; i++) {
        float tOut = min(tM.x, min(tM.y, tM.z));
        if (!(hasSkip && c == skip)) {
            uvec4 v = cellAt(c);
            uint id = shapeId(v);
            if (id == 1u) {
                if (i > 0) return vec3(0.0);
            } else if (id > 1u) {
                tr *= throughShape(id, v.a, o, d, tIn + 1e-4, min(tOut, maxT), c);
                if (max3(tr) < 0.03) return vec3(0.0);
            }
        }
        if (tOut >= maxT) break;
        if (tM.x <= tM.y && tM.x <= tM.z) { c.x += st.x; tM.x += tD.x; }
        else if (tM.y <= tM.z) { c.y += st.y; tM.y += tD.y; }
        else { c.z += st.z; tM.z += tD.z; }
        tIn = tOut;
    }
    return tr;
}

float bright(float l) {
    if (l <= 0.0) return Table[0];
    if (l >= 15.0) return Table[15];
    int i = int(l);
    return mix(Table[i], Table[i + 1], l - float(i));
}

float shown(float b, float s) { return 0.03 + min(1.0, b + s); }

// lights Idx[from..to): strongest brightness, color sums (dominant light weighted b^4)
void gather(int from, int to, vec3 s, vec3 n, bool two, out float best, out vec3 colSum, out float wSum) {
    best = 0.0; colSum = vec3(0.0); wSum = 0.0;
    for (int k = from; k < to; k++) {
        int j = Idx[k];
        vec4 a = texelFetch(Lights, ivec2(j, 0), 0);
        vec4 b = texelFetch(Lights, ivec2(j, 1), 0);
        vec4 e = texelFetch(Lights, ivec2(j, 2), 0);
        bool area = b.a > 0.5;
        vec3 L = area ? clamp(s, e.xyz - 0.01, e.xyz + 1.01) : a.xyz;   // glowing blocks shine from their surface
        vec3 v = L - s;
        float dist = length(v);
        float lev = area ? a.w - 0.5 - dist : a.w - dist;
        if (lev <= 0.0 || dist < 1e-4) continue;
        v /= dist;
        float ndl = dot(n, v);
        if (area) {
            vec3 cv = a.xyz - s;
            float cl = length(cv);
            float toC = cl > 1e-4 ? dot(n, cv) / cl : 1.0;
            float nearN = ndl;
            ndl = max(nearN, toC);
            if (ndl <= 0.0 && nearN > -0.03) ndl = 0.05;
            if (dist < 0.05) ndl = 1.0;
        }
        if (two) ndl = abs(ndl);
        if (ndl <= 0.0) continue;
        vec3 tr = trace(s, v, max(0.0, dist - 0.05), ivec3(floor(e.xyz + 0.5)), e.w > 0.5);
        float tm = max3(tr);
        if (tm <= 0.0) continue;
        float br = bright(lev) * tm * min(1.0, 0.82 + ndl * 0.3);
        best = max(best, br);
        vec3 c = b.rgb * tr / tm;   // light through stained glass takes its color
        float lum = max(0.05, dot(c, vec3(0.3, 0.59, 0.11)));
        c = min(vec3(1.95), c / lum);
        float wc = br * br * br * br;
        colSum += wc * c;
        wSum += wc;
    }
}

// vanilla's smooth block and sky light at a point (open cells only, like vanilla)
vec2 vanillaLight(vec3 q, vec3 sPt) {
    vec3 g = q - 0.5;
    vec3 g0 = floor(g);
    vec3 f = g - g0;
    ivec3 c0 = ivec3(g0);
    vec2 acc = vec2(0.0);
    float ws = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 o = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        uvec4 v = cellAt(c0 + o);
        uint id = shapeId(v);
        if (id == 1u) continue;
        if (id > 1u && (texelFetch(Shapes, ivec2(128, int(id)), 0).r & 4u) != 0u) continue;
        vec3 wv = mix(1.0 - f, f, vec3(o));
        float w = wv.x * wv.y * wv.z;
        acc += w * vec2(float(v.b & 15u), float(v.b >> 4u));
        ws += w;
    }
    if (ws > 1e-5) return acc / ws;
    ivec3 cc = ivec3(floor(sPt));
    vec2 m = vec2(0.0);
    ivec3 nb[6] = ivec3[6](ivec3(1, 0, 0), ivec3(-1, 0, 0), ivec3(0, 1, 0), ivec3(0, -1, 0), ivec3(0, 0, 1), ivec3(0, 0, -1));
    for (int i = 0; i < 6; i++) {
        uvec4 v = cellAt(cc + nb[i]);
        if (shapeId(v) == 1u) continue;
        m = max(m, vec2(float(v.b & 15u), float(v.b >> 4u)));
    }
    return m;
}

void main() {
    vec4 tex = texture(Atlas, vUv);
    if (tex.a < 0.1) discard;
    vec3 n = normalize(vNrm);
    bool two = vCol.a < 0.5;
    vec3 s = vPos + n * 0.018;
    float fog = Fog.y > Fog.x + 0.001 ? clamp((vDist - Fog.x) / (Fog.y - Fog.x), 0.0, 1.0) : 0.0;
    vec2 van = vanillaLight(two ? s : s + n * 0.45, s);
    float bv = bright(van.x), bs = bright(van.y) * SunB;
    float bt; vec3 cs; float ws;
    gather(0, NStatic, s, n, two, bt, cs, ws);
    if (Mode == 0) {
        float bb = max(bt, bv * (1.0 - ShadowK));             // light that bounces around corners
        float ratio = shown(bb, bs) / shown(bv, bs);
        vec3 c = ws > 1e-5 ? cs / ws : vec3(1.0);
        float amt = min(1.0, ColK * bt / (bb + bs + 1e-4));
        ratio *= 1.0 + 0.25 * amt;
        vec3 o = min(vec3(1.95), ratio * (1.0 + amt * (c - 1.0)));
        o = mix(o, vec3(1.0), fog);
        fragColor = vec4(o * 0.5, 1.0);
    } else {
        float mine; vec3 mc; float mw;
        gather(NStatic, NStatic + NMoving, s, n, two, mine, mc, mw);
        float already = max(bt, bv * (1.0 - ShadowK));
        float add = max(0.0, shown(max(already, mine), bs) - shown(already, bs));
        vec3 c = mw > 1e-5 ? mc / mw : vec3(1.0);
        vec3 o = tex.rgb * vCol.rgb * add * (1.0 + ColK * (c - 1.0));
        fragColor = vec4(o * (1.0 - fog), 1.0);
    }
}
