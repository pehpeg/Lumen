package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.BiomeDictionary.Type;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Per-biome haze (Atmospherics-style) and shooting stars.
 *
 * Biomes are classified with Forge's BiomeDictionary, so modded biomes get sensible haze too.
 * The tint and density are blended over a few seconds so walking between biomes is smooth.
 */
public class LumenAtmosphere {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random rand = new Random();

    // smoothed current values: fog color multiplier and fog-start multiplier
    private static float tr = 1, tg = 1, tb = 1, density = 1;
    private static boolean initialized;

    public static float tintR() { return tr; }
    public static float tintG() { return tg; }
    public static float tintB() { return tb; }
    /** < 1 means the haze starts closer (denser). */
    public static float fogStartFactor() { return density; }

    /** {r, g, b, fogStart} for a biome. */
    private static float[] target(Biome biome) {
        if (BiomeDictionary.hasType(biome, Type.SWAMP))    return new float[]{0.80f, 0.92f, 0.66f, 0.55f};
        if (BiomeDictionary.hasType(biome, Type.SPOOKY))   return new float[]{0.80f, 0.86f, 0.80f, 0.60f};
        if (BiomeDictionary.hasType(biome, Type.MUSHROOM)) return new float[]{0.95f, 0.80f, 0.98f, 0.75f};
        if (BiomeDictionary.hasType(biome, Type.JUNGLE))   return new float[]{0.88f, 1.00f, 0.84f, 0.70f};
        if (BiomeDictionary.hasType(biome, Type.SNOWY))    return new float[]{0.90f, 0.95f, 1.00f, 0.80f};
        if (BiomeDictionary.hasType(biome, Type.MESA))     return new float[]{1.00f, 0.84f, 0.68f, 0.80f};
        if (BiomeDictionary.hasType(biome, Type.SANDY)
                || (BiomeDictionary.hasType(biome, Type.HOT) && BiomeDictionary.hasType(biome, Type.DRY)))
                                                           return new float[]{1.00f, 0.90f, 0.72f, 0.80f};
        if (BiomeDictionary.hasType(biome, Type.OCEAN))    return new float[]{0.92f, 0.97f, 1.00f, 1.00f};
        return new float[]{1f, 1f, 1f, 1f};
    }

    // ---------------------------------------------------------------- ticking

    private static final class Star {
        Vec3d pos, vel; int age, life;
    }
    private static final List<Star> stars = new ArrayList<>();

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        World world = mc.world;
        if (world == null || mc.player == null) { initialized = false; stars.clear(); return; }

        // biome haze
        float[] t = {1, 1, 1, 1};
        if (LumenConfig.enabled && LumenConfig.biomeAtmosphere && world.provider.isSurfaceWorld()) {
            float[] raw = target(world.getBiome(new BlockPos(mc.player)));
            float s = (float) LumenConfig.biomeAtmosphereStrength;
            for (int i = 0; i < 4; i++) t[i] = 1f + (raw[i] - 1f) * s;
        }
        if (!initialized) { tr = t[0]; tg = t[1]; tb = t[2]; density = t[3]; initialized = true; }
        float k = 0.03f; // ~3 seconds to fully blend
        tr += (t[0] - tr) * k; tg += (t[1] - tg) * k; tb += (t[2] - tb) * k; density += (t[3] - density) * k;

        // shooting stars
        for (Iterator<Star> it = stars.iterator(); it.hasNext(); ) {
            Star s = it.next();
            s.pos = s.pos.add(s.vel);
            if (++s.age >= s.life) it.remove();
        }
        if (LumenConfig.enabled && LumenConfig.shootingStars && isClearNight(world) && rand.nextInt(500) == 0) {
            spawnStar();
        }
    }

    private static boolean isClearNight(World world) {
        return world.provider.isSurfaceWorld() && world.getRainStrength(1f) < 0.2f
                && world.getSunBrightness(1f) < 0.3f;
    }

    /** Spawns a shooting star somewhere in the sky (also used by the auto-test). */
    public static void spawnStar() {
        double yaw = rand.nextDouble() * Math.PI * 2;
        double elev = Math.toRadians(30 + rand.nextDouble() * 45);
        Vec3d dir = new Vec3d(Math.cos(yaw) * Math.cos(elev), Math.sin(elev), Math.sin(yaw) * Math.cos(elev));
        spawnStar(dir);
    }

    public static void spawnStar(Vec3d dir) {
        Star s = new Star();
        s.pos = dir.normalize().scale(100);
        // travel roughly sideways and slightly downward across the sky
        Vec3d side = s.pos.crossProduct(new Vec3d(0, 1, 0)).normalize();
        if (rand.nextBoolean()) side = side.scale(-1);
        s.vel = side.scale(2.2).add(0, -0.9, 0);
        s.life = 18 + rand.nextInt(10);
        stars.add(s);
    }

    // ---------------------------------------------------------------- rendering

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent e) {
        if (stars.isEmpty() || !LumenConfig.enabled || !LumenConfig.shootingStars) return;
        float pt = e.getPartialTicks();

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.disableFog();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (Star s : stars) {
            float life = (s.age + pt) / s.life;
            float a = Math.min(1f, Math.min(life * 4f, (1f - life) * 3f));
            if (a <= 0) continue;
            Vec3d head = s.pos.add(s.vel.scale(pt));
            Vec3d tail = head.subtract(s.vel.scale(7));
            // width perpendicular to both the trail and the view direction
            Vec3d w = s.vel.crossProduct(head).normalize().scale(0.35);
            Vec3d h1 = head.add(w), h2 = head.subtract(w);
            buf.pos(h1.x, h1.y, h1.z).color(1f, 1f, 0.95f, a).endVertex();
            buf.pos(h2.x, h2.y, h2.z).color(1f, 1f, 0.95f, a).endVertex();
            buf.pos(tail.x, tail.y, tail.z).color(0.7f, 0.8f, 1f, 0f).endVertex();
        }
        tess.draw();

        GlStateManager.depthMask(true);
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        GlStateManager.enableFog();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.shadeModel(GL11.GL_FLAT);
    }
}
