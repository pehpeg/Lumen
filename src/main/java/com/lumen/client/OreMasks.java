package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.IBakedModel;
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

/**
 * Builds "glow masks" for ore blocks automatically: the ore texture is compared with the
 * stone it's embedded in (stone, netherrack or end stone, whichever matches best), and only
 * the pixels that differ - the actual ore bits - are kept. Works for modded ores too.
 */
public class OreMasks {

    private static final Logger LOG = LogManager.getLogger("Lumen");
    private static final String[] BASES = {
            "minecraft:textures/blocks/stone.png",
            "minecraft:textures/blocks/netherrack.png",
            "minecraft:textures/blocks/end_stone.png"};

    public static final class Mask {
        final DynamicTexture texture;
        final float r, g, b;
        Mask(DynamicTexture t, float r, float g, float b) { texture = t; this.r = r; this.g = g; this.b = b; }
        int textureId() { return texture.getGlTextureId(); }
    }

    private static final Mask NONE = new Mask(null, 0, 0, 0);
    private final Map<IBlockState, Mask> cache = new HashMap<>();
    private BufferedImage[] bases;
    public static int built;

    public void clear() {
        for (Mask m : cache.values()) if (m != NONE) m.texture.deleteGlTexture();
        cache.clear();
        bases = null;
    }

    /** @return the glow mask for this block, or null if it isn't a (glowing) ore. */
    public Mask get(IBlockState state) {
        Mask m = cache.get(state);
        if (m == null) {
            m = build(state);
            cache.put(state, m);
        }
        return m == NONE ? null : m;
    }

    private static boolean isOre(IBlockState state) {
        ResourceLocation id = state.getBlock().getRegistryName();
        if (id == null) return false;
        String path = id.getPath();
        if (!(path.endsWith("_ore") || path.startsWith("ore_") || path.contains("_ore_") || path.equals("ore")
                || path.startsWith("ore"))) return false;
        for (String ex : LumenConfig.emissiveOreExclude) {
            if (ex.equals(id.toString()) || ex.equals(path)) return false;
        }
        return state.isOpaqueCube();
    }

    private Mask build(IBlockState state) {
        if (!isOre(state)) return NONE;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(state);
            TextureAtlasSprite sprite = model.getParticleTexture();
            ResourceLocation icon = new ResourceLocation(sprite.getIconName());
            BufferedImage ore = read(new ResourceLocation(icon.getNamespace(), "textures/" + icon.getPath() + ".png"));
            if (ore == null) return NONE;
            int size = ore.getWidth();                      // animated textures: first frame only
            if (bases == null) {
                bases = new BufferedImage[BASES.length];
                for (int i = 0; i < BASES.length; i++) bases[i] = read(new ResourceLocation(BASES[i]));
            }

            // pick the base texture the ore texture matches best
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
            if (best == null) return NONE;

            DynamicTexture tex = new DynamicTexture(size, size);
            int[] px = tex.getTextureData();
            int count = 0;
            long sr = 0, sg = 0, sb = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int c = ore.getRGB(x, y);
                    if (differs(c, sample(best, x, y, size)) && (c >>> 24) > 128) {
                        px[y * size + x] = c | 0xFF000000;
                        count++;
                        sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
                    } else {
                        px[y * size + x] = 0;
                    }
                }
            }
            float frac = count / (float) (size * size);
            if (count == 0 || frac < 0.02f || frac > 0.65f) { // not a "stone with bits in it" texture
                tex.deleteGlTexture();
                return NONE;
            }
            tex.updateDynamicTexture();
            built++;
            float r = sr / (count * 255f), g = sg / (count * 255f), b = sb / (count * 255f);
            float max = Math.max(r, Math.max(g, b));
            if (max > 0) { r /= max; g /= max; b /= max; }   // halo color: saturated version of the ore
            return new Mask(tex, r, g, b);
        } catch (Exception ex) {
            LOG.debug("Lumen: no ore mask for {}", state, ex);
            return NONE;
        }
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

    private static BufferedImage read(ResourceLocation loc) {
        try (IResource res = Minecraft.getMinecraft().getResourceManager().getResource(loc);
             InputStream in = res.getInputStream()) {
            return ImageIO.read(in);
        } catch (Exception ex) {
            return null;
        }
    }
}
