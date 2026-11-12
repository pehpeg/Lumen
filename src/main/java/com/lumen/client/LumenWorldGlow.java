package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Glow halos around light sources ("fake bloom") and emissive ores.
 *
 * Every 10 ticks a small area around the player is scanned for light-emitting blocks and
 * ores; each frame those (at most a few dozen halos and a few hundred ore faces) are drawn
 * as simple quads. No framebuffers, no shaders, no chunk rebuilds.
 */
public class LumenWorldGlow {

    private static final int RADIUS_XZ = 16, RADIUS_Y = 12;

    private final Minecraft mc = Minecraft.getMinecraft();

    private static final class Glow {
        final double x, y, z;
        final float r, g, b, size, flicker;
        float alpha;
        Glow(double x, double y, double z, float r, float g, float b, float size, float flicker, float alpha) {
            this.x = x; this.y = y; this.z = z; this.r = r; this.g = g; this.b = b;
            this.size = size; this.flicker = flicker; this.alpha = alpha;
        }
    }

    private static final class OreFace {
        final BlockPos pos; final EnumFacing face; final OreMasks.Mask mask;
        OreFace(BlockPos pos, EnumFacing face, OreMasks.Mask mask) { this.pos = pos; this.face = face; this.mask = mask; }
    }

    private List<Glow> glows = new ArrayList<>();
    private List<OreFace> ores = new ArrayList<>();
    private int tickCounter;
    public static int statGlows, statOreFaces;
    private final OreMasks oreMasks = new OreMasks();

    public void clearCaches() { oreMasks.clear(); }

    // ---------------------------------------------------------------- scanning

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (mc.world == null || mc.player == null) { glows.clear(); ores.clear(); oreMasks.clear(); return; }
        if (!LumenConfig.enabled || (!LumenConfig.blockGlow && !LumenConfig.emissiveOres)) { glows.clear(); ores.clear(); return; }
        if (++tickCounter % 10 != 0) return;
        scan(mc.world, new BlockPos(mc.player));
    }

    private void scan(World world, BlockPos center) {
        List<Glow> newGlows = new ArrayList<>();
        List<OreFace> newOres = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        int maxOreFaces = 600;

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
                    Block block = state.getBlock();

                    if (LumenConfig.blockGlow) {
                        int light = state.getLightValue(world, p);
                        if (light >= 7) {
                            Glow g = makeGlow(world, p, state, block, light);
                            if (g != null) newGlows.add(g);
                        }
                    }
                    if (LumenConfig.emissiveOres && newOres.size() < maxOreFaces) {
                        OreMasks.Mask mask = oreMasks.get(state);
                        if (mask != null) {
                            boolean exposed = false;
                            for (EnumFacing f : EnumFacing.VALUES) {
                                n.setPos(p).move(f);
                                if (!world.getBlockState(n).isOpaqueCube()) {
                                    newOres.add(new OreFace(p.toImmutable(), f, mask));
                                    exposed = true;
                                }
                            }
                            if (exposed && LumenConfig.blockGlow) {
                                newGlows.add(new Glow(x + 0.5, y + 0.5, z + 0.5,
                                        mask.r, mask.g, mask.b, 0.9f, 0f, 0.22f));
                            }
                        }
                    }
                }
            }
        }

        // keep the nearest halos only
        final double px = center.getX() + 0.5, py = center.getY() + 1.5, pz = center.getZ() + 0.5;
        newGlows.sort((a, b) -> Double.compare(dist2(a, px, py, pz), dist2(b, px, py, pz)));
        int max = LumenConfig.blockGlowMaxLights;
        if (newGlows.size() > max) newGlows = new ArrayList<>(newGlows.subList(0, max));
        glows = newGlows;
        ores = newOres;
        statGlows = glows.size();
        statOreFaces = ores.size();
    }

    private static double dist2(Glow g, double x, double y, double z) {
        double dx = g.x - x, dy = g.y - y, dz = g.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private Glow makeGlow(World world, BlockPos p, IBlockState state, Block block, int light) {
        double x = p.getX() + 0.5, y = p.getY() + 0.5, z = p.getZ() + 0.5;
        float r = 1.0f, g = 0.72f, b = 0.38f, size = 0.6f + light * 0.06f, flicker = 0f;
        float alpha = 0.14f + light * 0.022f;

        if (block == Blocks.TORCH || block == Blocks.FIRE || block == Blocks.LIT_PUMPKIN
                || block == Blocks.LIT_FURNACE) {
            r = 1.0f; g = 0.66f; b = 0.30f; flicker = 1f;
            if (block == Blocks.TORCH) { y = p.getY() + 0.72; size = 1.35f; }
        } else if (block == Blocks.LAVA || block == Blocks.FLOWING_LAVA) {
            // only lava with an open top: a lake of lava would otherwise eat every halo slot
            if (world.getBlockState(p.up()).getMaterial() == Material.LAVA) return null;
            r = 1.0f; g = 0.45f; b = 0.12f; y = p.getY() + 0.9; size = 1.7f; flicker = 0.5f; alpha *= 0.8f;
        } else if (block == Blocks.GLOWSTONE || block == Blocks.LIT_REDSTONE_LAMP) {
            r = 1.0f; g = 0.82f; b = 0.50f; size = 1.9f;
        } else if (block == Blocks.SEA_LANTERN || block == Blocks.BEACON || block == Blocks.END_ROD) {
            r = 0.72f; g = 0.90f; b = 1.0f; size = 1.8f;
        } else if (block == Blocks.REDSTONE_TORCH || block == Blocks.LIT_REDSTONE_ORE
                || block == Blocks.POWERED_REPEATER || block == Blocks.POWERED_COMPARATOR) {
            r = 1.0f; g = 0.20f; b = 0.10f; size = 0.9f;
            if (block == Blocks.REDSTONE_TORCH) y = p.getY() + 0.65;
        } else if (block == Blocks.PORTAL || block == Blocks.END_PORTAL || block == Blocks.ENDER_CHEST) {
            r = 0.65f; g = 0.30f; b = 1.0f; size = 1.4f; alpha *= 0.7f;
        } else if (block == Blocks.MAGMA) {
            r = 1.0f; g = 0.40f; b = 0.10f; size = 1.2f; alpha *= 0.6f;
        }

        // halos are mostly a dark-area effect: fade them where daylight already reaches
        float sky = world.getLightFor(EnumSkyBlock.SKY, p.up()) / 15f;
        float day = sky * world.getSunBrightness(1f);
        alpha *= 1f - 0.85f * MathHelper.clamp((day - 0.2f) / 0.6f, 0f, 1f);
        if (alpha <= 0.01f) return null;
        return new Glow(x, y, z, r, g, b, size, flicker, alpha);
    }

    // ---------------------------------------------------------------- rendering

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent e) {
        if (!LumenConfig.enabled || mc.world == null) return;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        float pt = e.getPartialTicks();
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        float time = (mc.world.getTotalWorldTime() + pt);

        if (LumenConfig.emissiveOres && !ores.isEmpty()) drawOres(cx, cy, cz, time);
        if (LumenConfig.blockGlow && !glows.isEmpty()) drawHalos(cx, cy, cz, time);
    }

    private void drawHalos(double cx, double cy, double cz, float time) {
        float rX = ActiveRenderInfo.getRotationX(), rZ = ActiveRenderInfo.getRotationZ();
        float rYZ = ActiveRenderInfo.getRotationYZ(), rXY = ActiveRenderInfo.getRotationXY();
        float rXZ = ActiveRenderInfo.getRotationXZ();
        // billboard axes (same as vanilla particles)
        double ax = rX, ay = 0, az = rZ;     // right
        double bx = rYZ, by = rXZ, bz = rXY; // up
        float strength = (float) LumenConfig.blockGlowStrength;

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        int seg = 16;
        for (Glow g : glows) {
            double x = g.x - cx, y = g.y - cy, z = g.z - cz;
            double d = Math.sqrt(x * x + y * y + z * z);
            float fade = MathHelper.clamp(1f - (float) d / (RADIUS_XZ + 4f), 0f, 1f);
            float flick = 1f;
            if (g.flicker > 0) {
                float seed = (float) (g.x * 13.1 + g.z * 7.7 + g.y * 3.3);
                flick = 1f - g.flicker * 0.12f * (0.5f + 0.5f * MathHelper.sin(time * 0.9f + seed)
                        * MathHelper.sin(time * 0.37f + seed * 2f));
            }
            float a = g.alpha * fade * flick * strength;
            if (a <= 0.003f) continue;
            double s = g.size * flick;
            buf.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
            buf.pos(x, y, z).color(g.r, g.g, g.b, Math.min(a, 1f)).endVertex();
            for (int i = 0; i <= seg; i++) {
                double ang = i * Math.PI * 2 / seg;
                double c = Math.cos(ang) * s, sn = Math.sin(ang) * s;
                buf.pos(x + ax * c + bx * sn, y + ay * c + by * sn, z + az * c + bz * sn)
                        .color(g.r, g.g, g.b, 0f).endVertex();
            }
            tess.draw();
        }

        GlStateManager.depthMask(true);
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.shadeModel(GL11.GL_FLAT);
    }

    private void drawOres(double cx, double cy, double cz, float time) {
        mc.entityRenderer.disableLightmap();
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

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        // group faces by mask texture to keep texture binds low
        Map<OreMasks.Mask, List<OreFace>> byMask = new java.util.IdentityHashMap<>();
        for (OreFace f : ores) byMask.computeIfAbsent(f.mask, k -> new ArrayList<>()).add(f);

        for (Map.Entry<OreMasks.Mask, List<OreFace>> en : byMask.entrySet()) {
            GlStateManager.bindTexture(en.getKey().textureId());
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            for (OreFace f : en.getValue()) {
                float pulse = 0.85f + 0.15f * MathHelper.sin(time * 0.08f
                        + (f.pos.getX() * 3 + f.pos.getY() * 5 + f.pos.getZ() * 7));
                emitFace(buf, f.pos.getX() - cx, f.pos.getY() - cy, f.pos.getZ() - cz, f.face, pulse);
            }
            tess.draw();
        }

        GlStateManager.disablePolygonOffset();
        GlStateManager.depthMask(true);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        mc.entityRenderer.enableLightmap();
        GlStateManager.bindTexture(mc.getTextureMapBlocks().getGlTextureId());
    }

    private static void emitFace(BufferBuilder buf, double x, double y, double z, EnumFacing f, float c) {
        double e = 0.001; // tiny outward offset (plus polygon offset) against z-fighting
        switch (f) {
            case UP:
                y += 1 + e;
                v(buf, x, y, z, 0, 0, c); v(buf, x, y, z + 1, 0, 1, c);
                v(buf, x + 1, y, z + 1, 1, 1, c); v(buf, x + 1, y, z, 1, 0, c); break;
            case DOWN:
                y -= e;
                v(buf, x, y, z + 1, 0, 0, c); v(buf, x, y, z, 0, 1, c);
                v(buf, x + 1, y, z, 1, 1, c); v(buf, x + 1, y, z + 1, 1, 0, c); break;
            case NORTH:
                z -= e;
                v(buf, x + 1, y + 1, z, 0, 0, c); v(buf, x + 1, y, z, 0, 1, c);
                v(buf, x, y, z, 1, 1, c); v(buf, x, y + 1, z, 1, 0, c); break;
            case SOUTH:
                z += 1 + e;
                v(buf, x, y + 1, z, 0, 0, c); v(buf, x, y, z, 0, 1, c);
                v(buf, x + 1, y, z, 1, 1, c); v(buf, x + 1, y + 1, z, 1, 0, c); break;
            case WEST:
                x -= e;
                v(buf, x, y + 1, z, 0, 0, c); v(buf, x, y, z, 0, 1, c);
                v(buf, x, y, z + 1, 1, 1, c); v(buf, x, y + 1, z + 1, 1, 0, c); break;
            case EAST:
                x += 1 + e;
                v(buf, x, y + 1, z + 1, 0, 0, c); v(buf, x, y, z + 1, 0, 1, c);
                v(buf, x, y, z, 1, 1, c); v(buf, x, y + 1, z, 1, 0, c); break;
        }
    }

    private static void v(BufferBuilder buf, double x, double y, double z, double u, double vv, float c) {
        buf.pos(x, y, z).tex(u, vv).color(c, c, c, 1f).endVertex();
    }
}
