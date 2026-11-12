package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds what glows around the player (scanned every 10 ticks) and draws it:
 *  - emissive ore overlays, visible in the scene
 *  - bloom sources (light blocks in their own texture, torch flames, lava, glowing ores,
 *    glowing/burning entities), drawn into LumenBloom's offscreen image each frame
 */
public class LumenWorldGlow {

    private static final int RADIUS_XZ = 20, RADIUS_Y = 14;
    private final Minecraft mc = Minecraft.getMinecraft();

    /** Shapes a bloom source can have. */
    private static final int CUBE = 0, FLAME = 1, TOP = 2;

    private static final class Light {
        final BlockPos pos; final int shape;
        final double fx, fy, fz;              // flame point (FLAME)
        final float r, g, b, intensity, flicker;
        final TextureAtlasSprite sprite;      // CUBE/TOP: the block's own texture
        Light(BlockPos pos, int shape, double fx, double fy, double fz, float r, float g, float b,
              float intensity, float flicker, TextureAtlasSprite sprite) {
            this.pos = pos; this.shape = shape; this.fx = fx; this.fy = fy; this.fz = fz;
            this.r = r; this.g = g; this.b = b; this.intensity = intensity; this.flicker = flicker; this.sprite = sprite;
        }
    }

    private static final class OreFace {
        final BlockPos pos; final EnumFacing face; final OreMasks.Mask mask;
        OreFace(BlockPos pos, EnumFacing face, OreMasks.Mask mask) { this.pos = pos; this.face = face; this.mask = mask; }
    }

    private List<Light> lights = new ArrayList<>();
    private List<OreFace> ores = new ArrayList<>();
    private int tickCounter;
    public static int statGlows, statOreFaces;
    private final OreMasks oreMasks = new OreMasks();

    // ---------------------------------------------------------------- scanning

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (mc.world == null || mc.player == null) { lights.clear(); ores.clear(); oreMasks.clear(); return; }
        if (!LumenConfig.enabled || (!LumenConfig.bloom && !LumenConfig.emissiveOres)) { lights.clear(); ores.clear(); return; }
        if (++tickCounter % 10 != 0) return;
        scan(mc.world, new BlockPos(mc.player));
    }

    private void scan(World world, BlockPos center) {
        List<Light> newLights = new ArrayList<>();
        List<OreFace> newOres = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();

        for (int dx = -RADIUS_XZ; dx <= RADIUS_XZ; dx++) {
            for (int dz = -RADIUS_XZ; dz <= RADIUS_XZ; dz++) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!world.isBlockLoaded(p.setPos(x, center.getY(), z))) continue;
                for (int dy = -RADIUS_Y; dy <= RADIUS_Y; dy++) {
                    int y = center.getY() + dy;
                    if (y < 0 || y > 255) continue;
                    p.setPos(x, y, z);
                    IBlockState state = world.getBlockState(p);
                    if (state.getMaterial() == Material.AIR) continue;

                    if (LumenConfig.bloom) {
                        int light = state.getLightValue(world, p);
                        if (light >= 7) {
                            Light l = makeLight(world, p.toImmutable(), state, light);
                            if (l != null) newLights.add(l);
                        }
                    }
                    if (LumenConfig.emissiveOres && newOres.size() < 800) {
                        OreMasks.Mask mask = oreMasks.get(state);
                        if (mask != null) {
                            for (EnumFacing f : EnumFacing.VALUES) {
                                n.setPos(p).move(f);
                                if (!world.getBlockState(n).isOpaqueCube()) newOres.add(new OreFace(p.toImmutable(), f, mask));
                            }
                        }
                    }
                }
            }
        }
        // nearest first, capped
        final int px = center.getX(), py = center.getY(), pz = center.getZ();
        newLights.sort((a, b) -> Integer.compare(d2(a.pos, px, py, pz), d2(b.pos, px, py, pz)));
        int max = LumenConfig.bloomMaxLights;
        if (newLights.size() > max) newLights = new ArrayList<>(newLights.subList(0, max));
        lights = newLights;
        ores = newOres;
        statGlows = lights.size();
        statOreFaces = ores.size();
    }

    private static int d2(BlockPos a, int x, int y, int z) {
        int dx = a.getX() - x, dy = a.getY() - y, dz = a.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private Light makeLight(World world, BlockPos p, IBlockState state, int light) {
        Block block = state.getBlock();
        float k = light / 15f;
        double cx = p.getX() + 0.5, cy = p.getY() + 0.5, cz = p.getZ() + 0.5;

        if (block instanceof BlockTorch) {
            boolean redstone = block == Blocks.REDSTONE_TORCH;
            double fx = cx, fy = p.getY() + 0.7, fz = cz;
            EnumFacing facing = state.getValue(BlockTorch.FACING);
            if (facing.getAxis().isHorizontal()) {   // wall torch: flame leans away from the wall
                EnumFacing opp = facing.getOpposite();
                fx += 0.27 * opp.getXOffset(); fy += 0.22; fz += 0.27 * opp.getZOffset();
            }
            return redstone
                    ? new Light(p, FLAME, fx, fy - 0.05, fz, 1.0f, 0.15f, 0.08f, 0.7f, 0f, null)
                    : new Light(p, FLAME, fx, fy, fz, 1.0f, 0.62f, 0.25f, 1.0f, 1f, null);
        }
        if (block == Blocks.FIRE) {
            return new Light(p, FLAME, cx, p.getY() + 0.35, cz, 1.0f, 0.5f, 0.15f, 1.0f, 1f, null);
        }
        if (block == Blocks.LAVA || block == Blocks.FLOWING_LAVA) {
            if (world.getBlockState(p.up()).getMaterial() == Material.LAVA) return null; // only the surface
            return new Light(p, TOP, 0, 0, 0, 1.0f, 0.55f, 0.25f, 0.45f, 0.4f, sprite(state));
        }
        if (!state.isFullCube()) {   // lanterns from mods, end rods, brewing stands...: glow at the center
            return new Light(p, FLAME, cx, cy, cz, 1.0f, 0.8f, 0.55f, 0.5f * k, 0f, null);
        }
        // full light blocks (glowstone, sea lantern, lamps, jack o'lanterns, magma...): their own texture
        float r = 1f, g = 0.9f, b = 0.75f, intensity = 0.4f * k;
        if (block == Blocks.SEA_LANTERN || block == Blocks.BEACON) { r = 0.85f; g = 0.95f; b = 1f; }
        else if (block == Blocks.MAGMA) { intensity = 0.35f; }
        return new Light(p, CUBE, 0, 0, 0, r, g, b, intensity, 0f, sprite(state));
    }

    private TextureAtlasSprite sprite(IBlockState state) {
        try {
            return mc.getBlockRendererDispatcher().getBlockModelShapes().getTexture(state);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- visible ore overlay

    public void renderOres(float pt) {
        if (!LumenConfig.emissiveOres || ores.isEmpty() || mc.world == null) return;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        float time = mc.world.getTotalWorldTime() + pt;

        LumenGl.prepare();
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.depthMask(false);
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1f, -10f);
        drawOreFaces(cx, cy, cz, time, 1f);
        GlStateManager.disablePolygonOffset();
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        LumenGl.finish();
    }

    private void drawOreFaces(double cx, double cy, double cz, float time, float brightness) {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        Map<OreMasks.Mask, List<OreFace>> byMask = new IdentityHashMap<>();
        for (OreFace f : ores) byMask.computeIfAbsent(f.mask, k -> new ArrayList<>()).add(f);
        for (Map.Entry<OreMasks.Mask, List<OreFace>> en : byMask.entrySet()) {
            GlStateManager.bindTexture(en.getKey().textureId());
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            for (OreFace f : en.getValue()) {
                float pulse = brightness * (0.85f + 0.15f * MathHelper.sin(time * 0.08f
                        + (f.pos.getX() * 3 + f.pos.getY() * 5 + f.pos.getZ() * 7)));
                emitFace(buf, f.pos.getX() - cx, f.pos.getY() - cy, f.pos.getZ() - cz, f.face, pulse, 0, 0, 1, 1);
            }
            tess.draw();
        }
    }

    // ---------------------------------------------------------------- bloom sources

    /** Draws everything that should bloom. Called by LumenBloom with additive blending set up. */
    public void drawBloomSources(float pt) {
        Entity view = mc.getRenderViewEntity();
        if (view == null || mc.world == null) return;
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        float time = mc.world.getTotalWorldTime() + pt;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        // 1) textured light blocks and lava surfaces, in their own texture
        GlStateManager.enableTexture2D();
        mc.getTextureManager().bindTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (Light l : lights) {
            if (l.shape == FLAME || l.sprite == null) continue;
            float c = l.intensity * flicker(l, time);
            double x = l.pos.getX() - cx, y = l.pos.getY() - cy, z = l.pos.getZ() - cz;
            float u0 = l.sprite.getMinU(), v0 = l.sprite.getMinV(), u1 = l.sprite.getMaxU(), v1 = l.sprite.getMaxV();
            if (l.shape == TOP) {
                emitFaceTinted(buf, x, y - 0.1, z, EnumFacing.UP, l.r * c, l.g * c, l.b * c, u0, v0, u1, v1);
            } else {
                for (EnumFacing f : EnumFacing.VALUES) emitFaceTinted(buf, x, y, z, f, l.r * c, l.g * c, l.b * c, u0, v0, u1, v1);
            }
        }
        tess.draw();

        // 2) emissive ores bloom softly in their own colors
        if (LumenConfig.emissiveOres && !ores.isEmpty()) drawOreFaces(cx, cy, cz, time, 0.55f);

        // 3) flames and other small lights: small bright cubes (the blur turns them into round glows)
        GlStateManager.disableTexture2D();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Light l : lights) {
            if (l.shape != FLAME) continue;
            float c = l.intensity * flicker(l, time);
            cube(buf, l.fx - cx, l.fy - cy, l.fz - cz, 0.09, l.r * c, l.g * c, l.b * c);
        }
        // glowing / burning entities (dropped glowstone, blazes, mobs on fire, held torches in 3rd person)
        boolean firstPerson = mc.gameSettings.thirdPersonView == 0;
        int count = 0;
        for (Entity en : mc.world.loadedEntityList) {
            if (count >= 48) break;
            if (en == view && firstPerson) continue;
            if (en.getDistanceSq(view) > 32 * 32) continue;
            int lum = LumenDynamicLights.luminance(en);
            if (lum < 6) continue;
            count++;
            double x = en.lastTickPosX + (en.posX - en.lastTickPosX) * pt - cx;
            double y = en.lastTickPosY + (en.posY - en.lastTickPosY) * pt + en.height * 0.55 - cy;
            double z = en.lastTickPosZ + (en.posZ - en.lastTickPosZ) * pt - cz;
            boolean fire = en.isBurning();
            float c = lum / 15f * (fire ? 0.8f : 0.7f);
            double size = fire ? Math.max(0.15, en.width * 0.35) : 0.12;
            cube(buf, x, y, z, size, c, c * (fire ? 0.5f : 0.75f), c * (fire ? 0.15f : 0.4f));
        }
        // fireflies
        LumenParticles.emitFireflyBloom(buf, cx, cy, cz, pt);
        tess.draw();
        GlStateManager.enableTexture2D();
    }

    private static float flicker(Light l, float time) {
        if (l.flicker <= 0) return 1f;
        float seed = l.pos.getX() * 13.1f + l.pos.getZ() * 7.7f + l.pos.getY() * 3.3f;
        return 1f - l.flicker * 0.15f * (0.5f + 0.5f * MathHelper.sin(time * 0.9f + seed) * MathHelper.sin(time * 0.37f + seed * 2f));
    }

    static void cube(BufferBuilder buf, double x, double y, double z, double h, float r, float g, float b) {
        double x0 = x - h, x1 = x + h, y0 = y - h, y1 = y + h, z0 = z - h, z1 = z + h;
        // 6 faces
        q(buf, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b);
        q(buf, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, r, g, b);
        q(buf, x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0, r, g, b);
        q(buf, x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1, r, g, b);
        q(buf, x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, r, g, b);
        q(buf, x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0, r, g, b);
    }

    private static void q(BufferBuilder buf, double ax, double ay, double az, double bx, double by, double bz,
                          double cx, double cy, double cz, double dx, double dy, double dz, float r, float g, float b) {
        buf.pos(ax, ay, az).color(r, g, b, 1f).endVertex();
        buf.pos(bx, by, bz).color(r, g, b, 1f).endVertex();
        buf.pos(cx, cy, cz).color(r, g, b, 1f).endVertex();
        buf.pos(dx, dy, dz).color(r, g, b, 1f).endVertex();
    }

    // ---------------------------------------------------------------- block faces

    private static void emitFace(BufferBuilder buf, double x, double y, double z, EnumFacing f, float c,
                                 float u0, float v0, float u1, float v1) {
        emitFaceTinted(buf, x, y, z, f, c, c, c, u0, v0, u1, v1);
    }

    private static void emitFaceTinted(BufferBuilder buf, double x, double y, double z, EnumFacing f,
                                       float r, float g, float b, float u0, float v0, float u1, float v1) {
        double e = 0.002;
        switch (f) {
            case UP:
                y += 1 + e;
                v(buf, x, y, z, u0, v0, r, g, b); v(buf, x, y, z + 1, u0, v1, r, g, b);
                v(buf, x + 1, y, z + 1, u1, v1, r, g, b); v(buf, x + 1, y, z, u1, v0, r, g, b); break;
            case DOWN:
                y -= e;
                v(buf, x, y, z + 1, u0, v0, r, g, b); v(buf, x, y, z, u0, v1, r, g, b);
                v(buf, x + 1, y, z, u1, v1, r, g, b); v(buf, x + 1, y, z + 1, u1, v0, r, g, b); break;
            case NORTH:
                z -= e;
                v(buf, x + 1, y + 1, z, u0, v0, r, g, b); v(buf, x + 1, y, z, u0, v1, r, g, b);
                v(buf, x, y, z, u1, v1, r, g, b); v(buf, x, y + 1, z, u1, v0, r, g, b); break;
            case SOUTH:
                z += 1 + e;
                v(buf, x, y + 1, z, u0, v0, r, g, b); v(buf, x, y, z, u0, v1, r, g, b);
                v(buf, x + 1, y, z, u1, v1, r, g, b); v(buf, x + 1, y + 1, z, u1, v0, r, g, b); break;
            case WEST:
                x -= e;
                v(buf, x, y + 1, z, u0, v0, r, g, b); v(buf, x, y, z, u0, v1, r, g, b);
                v(buf, x, y, z + 1, u1, v1, r, g, b); v(buf, x, y + 1, z + 1, u1, v0, r, g, b); break;
            case EAST:
                x += 1 + e;
                v(buf, x, y + 1, z + 1, u0, v0, r, g, b); v(buf, x, y, z + 1, u0, v1, r, g, b);
                v(buf, x, y, z, u1, v1, r, g, b); v(buf, x, y + 1, z, u1, v0, r, g, b); break;
        }
    }

    private static void v(BufferBuilder buf, double x, double y, double z, double u, double vv, float r, float g, float b) {
        buf.pos(x, y, z).tex(u, vv).color(r, g, b, 1f).endVertex();
    }
}
