package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Emissive (glow) textures for block sprites.
 *
 *  1. Resource packs: if the active packs contain an OptiFine-style emissive texture
 *     ("textures/blocks/diamond_ore_e.png", suffix configurable in optifine/emissive.properties),
 *     that texture is used - for any block, not just ores.
 *  2. Otherwise, for ores, a mask is generated: the ore texture is compared with the stone it's
 *     embedded in (stone, netherrack or end stone) and only the differing "ore bits" are kept.
 *
 * Everything is cached per sprite and rebuilt when resource packs change.
 */
public class EmissiveTextures {

    private static final Logger LOG = LogManager.getLogger("Lumen");
    private static final String[] BASES = {
            "minecraft:textures/blocks/stone.png",
            "minecraft:textures/blocks/netherrack.png",
            "minecraft:textures/blocks/end_stone.png"};

    public static final class Glow {
        final DynamicTexture texture;
        final boolean fromPack;
        Glow(DynamicTexture t, boolean fromPack) { this.texture = t; this.fromPack = fromPack; }
        public int textureId() { return texture.getGlTextureId(); }
    }

    private static final Glow NONE = new Glow(null, false);
    private final Map<String, Glow> bySprite = new HashMap<>();
    private BufferedImage[] bases;
    private String suffix;
    public static int statPack, statAuto;

    /** Called on resource reload: everything is re-read from the new packs. */
    public void clear() {
        for (Glow g : bySprite.values()) if (g != NONE) g.texture.deleteGlTexture();
        bySprite.clear();
        bases = null;
        suffix = null;
        statPack = statAuto = 0;
    }

    /** @return glow texture for this sprite as used by this block, or null. */
    public Glow get(TextureAtlasSprite sprite, IBlockState state) {
        if (sprite == null) return null;
        String key = sprite.getIconName() + (isAutoOre(state) ? "#ore" : "");
        Glow g = bySprite.get(key);
        if (g == null) {
            g = build(sprite, state);
            bySprite.put(key, g);
        }
        return g == NONE ? null : g;
    }

    private Glow build(TextureAtlasSprite sprite, IBlockState state) {
        try {
            ResourceLocation icon = new ResourceLocation(sprite.getIconName());
            if (LumenConfig.emissive.resourcePackTextures) {
                BufferedImage e = read(new ResourceLocation(icon.getNamespace(), "textures/" + icon.getPath() + suffix() + ".png"));
                if (e != null) {
                    statPack++;
                    return new Glow(firstFrame(e, true), true);
                }
            }
            if (LumenConfig.emissive.autoOreMasks && isAutoOre(state)) {
                BufferedImage ore = read(new ResourceLocation(icon.getNamespace(), "textures/" + icon.getPath() + ".png"));
                if (ore != null) {
                    DynamicTexture t = oreMask(ore);
                    if (t != null) { statAuto++; return new Glow(t, false); }
                }
            }
        } catch (Exception ex) {
            LOG.debug("Lumen: no emissive texture for {}", sprite.getIconName(), ex);
        }
        return NONE;
    }

    /** OptiFine's emissive suffix ("_e" unless a pack's optifine/emissive.properties says otherwise). */
    private String suffix() {
        if (suffix != null) return suffix;
        suffix = "_e";
        try (IResource res = Minecraft.getMinecraft().getResourceManager()
                .getResource(new ResourceLocation("minecraft", "optifine/emissive.properties"));
             InputStream in = res.getInputStream()) {
            Properties p = new Properties();
            p.load(in);
            String s = p.getProperty("suffix.emissive");
            if (s != null && !s.trim().isEmpty()) suffix = s.trim();
        } catch (Exception ignored) {
            // no properties file: default suffix
        }
        return suffix;
    }

    static boolean isAutoOre(IBlockState state) {
        if (state == null) return false;
        ResourceLocation id = state.getBlock().getRegistryName();
        if (id == null) return false;
        String path = id.getPath();
        boolean ore = path.endsWith("_ore") || path.startsWith("ore") || path.contains("_ore_") || path.endsWith("ores");
        if (!ore) return false;
        for (String ex : LumenConfig.emissive.autoExclude) {
            if (ex.equals(id.toString()) || ex.equals(path)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- texture helpers

    /** Top square of a (possibly animated, vertically stacked) texture. */
    private static DynamicTexture firstFrame(BufferedImage img, boolean keepAlpha) {
        int size = img.getWidth();
        DynamicTexture tex = new DynamicTexture(size, size);
        int[] px = tex.getTextureData();
        for (int y = 0; y < size && y < img.getHeight(); y++)
            for (int x = 0; x < size; x++) {
                int c = img.getRGB(x, y);
                px[y * size + x] = keepAlpha ? c : (c | 0xFF000000);
            }
        tex.updateDynamicTexture();
        return tex;
    }

    private DynamicTexture oreMask(BufferedImage ore) {
        int size = ore.getWidth();
        if (bases == null) {
            bases = new BufferedImage[BASES.length];
            for (int i = 0; i < BASES.length; i++) bases[i] = read(new ResourceLocation(BASES[i]));
        }
        BufferedImage best = null;
        int bestSame = -1;
        for (BufferedImage base : bases) {
            if (base == null) continue;
            int same = 0;
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    if (!differs(ore.getRGB(x, y), sample(base, x, y, size))) same++;
            if (same > bestSame) { bestSame = same; best = base; }
        }
        if (best == null) return null;

        DynamicTexture tex = new DynamicTexture(size, size);
        int[] px = tex.getTextureData();
        int count = 0;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                int c = ore.getRGB(x, y);
                if (differs(c, sample(best, x, y, size)) && (c >>> 24) > 128) { px[y * size + x] = c | 0xFF000000; count++; }
                else px[y * size + x] = 0;
            }
        float frac = count / (float) (size * size);
        if (count == 0 || frac < 0.02f || frac > 0.65f) { tex.deleteGlTexture(); return null; }  // not "stone with bits"
        tex.updateDynamicTexture();
        return tex;
    }

    private static int sample(BufferedImage base, int x, int y, int size) {
        int bs = base.getWidth();
        return base.getRGB(x * bs / size, y * bs / size);
    }

    private static boolean differs(int a, int b) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return dr + dg + db > 48;
    }

    static BufferedImage read(ResourceLocation loc) {
        try (IResource res = Minecraft.getMinecraft().getResourceManager().getResource(loc);
             InputStream in = res.getInputStream()) {
            return ImageIO.read(in);
        } catch (Exception ex) {
            return null;
        }
    }
}
