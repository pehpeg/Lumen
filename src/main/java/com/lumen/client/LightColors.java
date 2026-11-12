package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The color each light block gives off: from the config list, or - for anything unlisted,
 * including modded lamps - worked out from the block's own texture (the average color of its
 * brightest pixels). Near-white results count as plain white light (no tint).
 */
public class LightColors {

    public static final int WHITE = 0xFFFFFF;
    private final Map<Block, Integer> configured = new HashMap<>();
    private final Map<IBlockState, Integer> cache = new IdentityHashMap<>();

    public LightColors() { reloadConfig(); }

    public void reloadConfig() {
        configured.clear();
        cache.clear();
        for (String s : LumenConfig.colored.colors) {
            String[] kv = s.split("=");
            if (kv.length != 2) continue;
            Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(kv[0].trim()));
            try { if (b != null) configured.put(b, Integer.parseInt(kv[1].trim().replace("#", ""), 16) & 0xFFFFFF); }
            catch (NumberFormatException ignored) {}
        }
    }

    public void clearTextureCache() { cache.clear(); }

    public int colorOf(IBlockState state, IBlockAccess world, BlockPos pos) {
        Integer c = configured.get(state.getBlock());
        if (c != null) return c;
        c = cache.get(state);
        if (c == null) {
            c = fromTexture(state);
            cache.put(state, c);
        }
        return c;
    }

    private static int fromTexture(IBlockState state) {
        try {
            TextureAtlasSprite sprite = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getTexture(state);
            ResourceLocation icon = new ResourceLocation(sprite.getIconName());
            BufferedImage img = EmissiveTextures.read(new ResourceLocation(icon.getNamespace(), "textures/" + icon.getPath() + ".png"));
            if (img == null) return WHITE;
            int w = img.getWidth(), h = Math.min(img.getHeight(), w);
            // brightness histogram: average the brightest ~25% of opaque pixels
            int[] lum = new int[w * h];
            int n = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c = img.getRGB(x, y);
                if ((c >>> 24) < 128) continue;
                lum[n++] = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
            }
            if (n == 0) return WHITE;
            int[] sorted = java.util.Arrays.copyOf(lum, n);
            java.util.Arrays.sort(sorted);
            int threshold = sorted[(int) (n * 0.75)];
            long r = 0, g = 0, b = 0; int cnt = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c = img.getRGB(x, y);
                if ((c >>> 24) < 128) continue;
                int l = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
                if (l < threshold) continue;
                r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; cnt++;
            }
            if (cnt == 0) return WHITE;
            float fr = r / (float) cnt, fg = g / (float) cnt, fb = b / (float) cnt;
            float max = Math.max(fr, Math.max(fg, fb)), min = Math.min(fr, Math.min(fg, fb));
            if (max <= 0 || (max - min) / max < 0.18f) return WHITE;      // basically white light
            // normalize to full brightness, keep the hue
            int ir = Math.round(fr / max * 255), ig = Math.round(fg / max * 255), ib = Math.round(fb / max * 255);
            return (ir << 16) | (ig << 8) | ib;
        } catch (Exception e) {
            return WHITE;
        }
    }
}
