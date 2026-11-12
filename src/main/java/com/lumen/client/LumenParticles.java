package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import org.lwjgl.opengl.GL11;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraft.entity.Entity;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.particle.ParticleDigging;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Ambient life: fireflies on warm nights, leaves drifting from trees, dust motes in caves.
 * Uses the vanilla particle system with a hard cap on live particles, and respects the
 * vanilla "Particles: All / Decreased / Minimal" setting.
 */
public class LumenParticles {

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random rand = new Random();
    private final List<Particle> live = new ArrayList<>();

    private static final int MAX_FIREFLIES = 40, MAX_LEAVES = 40, MAX_DUST = 40;
    private int fireflies, leaves, dust;
    public static String stats = "";

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        World world = mc.world;
        if (world == null || mc.player == null) { live.clear(); flies.clear(); return; }
        if (mc.isGamePaused()) return;

        // prune + count
        fireflies = leaves = dust = 0;
        boolean on = LumenConfig.enabled;
        for (int i = live.size() - 1; i >= 0; i--) {
            Particle p = live.get(i);
            if (!on) p.setExpired();
            if (!p.isAlive()) { live.remove(i); continue; }
            if (p instanceof Leaf) leaves++;
            else dust++;
        }
        updateFlies(world, on);
        fireflies = flies.size();
        stats = "fireflies=" + fireflies + " leaves=" + leaves + " dust=" + dust;
        if (!on) return;

        int setting = mc.gameSettings.particleSetting;   // 0 all, 1 decreased, 2 minimal
        if (setting >= 2) return;
        float budget = setting == 1 ? 0.5f : 1f;
        BlockPos player = new BlockPos(mc.player);

        if (LumenConfig.fireflies) spawnFireflies(world, player, budget);
        if (LumenConfig.fallingLeaves) spawnLeaves(world, player, budget);
        if (LumenConfig.caveDust) spawnDust(world, player, budget);
    }

    private void add(Particle p) {
        mc.effectRenderer.addEffect(p);
        live.add(p);
    }

    // ---------------------------------------------------------------- fireflies
    // Drawn by Lumen itself as tiny glowing points with a soft halo (additive), not through
    // the vanilla particle system: they need to glow, and this also keeps them very cheap.

    private static final class Fly {
        double x, y, z, px, py, pz, mx, my, mz;
        int age, life; float phase;
    }
    private final List<Fly> flies = new ArrayList<>();

    private void updateFlies(World world, boolean on) {
        for (int i = flies.size() - 1; i >= 0; i--) {
            Fly f = flies.get(i);
            if (!on || ++f.age >= f.life) { flies.remove(i); continue; }
            f.px = f.x; f.py = f.y; f.pz = f.z;
            f.mx = MathHelper.clamp(f.mx + (rand.nextDouble() - 0.5) * 0.004, -0.03, 0.03);
            f.my = MathHelper.clamp(f.my + (rand.nextDouble() - 0.5) * 0.003, -0.015, 0.015);
            f.mz = MathHelper.clamp(f.mz + (rand.nextDouble() - 0.5) * 0.004, -0.03, 0.03);
            double nx = f.x + f.mx, ny = f.y + f.my, nz = f.z + f.mz;
            if (world.getBlockState(new BlockPos(nx, ny, nz)).getMaterial().blocksMovement()) {
                f.mx = -f.mx; f.my = Math.abs(f.my); f.mz = -f.mz;   // bounce off blocks
            } else { f.x = nx; f.y = ny; f.z = nz; }
        }
    }

    private void spawnFireflies(World world, BlockPos player, float budget) {
        if (!world.provider.isSurfaceWorld() || world.isRaining()) return;
        if (world.getSunBrightness(1f) > 0.45f) return;           // nights only
        for (int attempt = 0; attempt < 3; attempt++) {
            if (flies.size() >= MAX_FIREFLIES * budget) return;
            int x = player.getX() + rand.nextInt(25) - 12;
            int z = player.getZ() + rand.nextInt(25) - 12;
            BlockPos top = world.getHeight(new BlockPos(x, 0, z));
            if (Math.abs(top.getY() - player.getY()) > 10) continue;
            Biome biome = world.getBiome(top);
            if (biome.getTemperature(top) < 0.3f || biome.getRainfall() < 0.2f) continue; // not in snow/desert
            IBlockState ground = world.getBlockState(top.down());
            IBlockState at = world.getBlockState(top);
            boolean plants = ground.getBlock() == Blocks.GRASS || at.getMaterial() == Material.PLANTS
                    || at.getMaterial() == Material.VINE || ground.getMaterial() == Material.LEAVES;
            if (!plants || at.getMaterial().isLiquid()) continue;
            Fly f = new Fly();
            f.x = f.px = x + rand.nextDouble();
            f.y = f.py = top.getY() + 0.5 + rand.nextDouble() * 2.0;
            f.z = f.pz = z + rand.nextDouble();
            f.mx = (rand.nextDouble() - 0.5) * 0.02; f.my = (rand.nextDouble() - 0.5) * 0.01; f.mz = (rand.nextDouble() - 0.5) * 0.02;
            f.life = 160 + rand.nextInt(160);
            f.phase = rand.nextFloat() * 6.28f;
            flies.add(f);
        }
    }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent e) {
        if (flies.isEmpty() || !LumenConfig.enabled) return;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        float pt = e.getPartialTicks();
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        double ax = ActiveRenderInfo.getRotationX(), az = ActiveRenderInfo.getRotationZ();
        double bx = ActiveRenderInfo.getRotationYZ(), by = ActiveRenderInfo.getRotationXZ(), bz = ActiveRenderInfo.getRotationXY();

        LumenGl.prepare();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        for (Fly f : flies) {
            float life = (f.age + pt) / f.life;
            float edge = Math.min(1f, Math.min(life * 6f, (1f - life) * 6f));
            float pulse = 0.5f + 0.5f * MathHelper.sin((f.age + pt) * 0.12f + f.phase);
            float a = edge * (0.15f + 0.85f * pulse * pulse);
            if (a <= 0.01f) continue;
            double x = f.px + (f.x - f.px) * pt - cx, y = f.py + (f.y - f.py) * pt - cy, z = f.pz + (f.z - f.pz) * pt - cz;
            fan(buf, tess, x, y, z, ax, az, bx, by, bz, 0.55, 0.75f, 1.0f, 0.25f, 0.45f * a); // halo
            fan(buf, tess, x, y, z, ax, az, bx, by, bz, 0.06, 0.95f, 1.0f, 0.60f, a);         // bright core
        }
        GlStateManager.depthMask(true);
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        LumenGl.finish();
    }

    private static void fan(BufferBuilder buf, Tessellator tess, double x, double y, double z,
                            double ax, double az, double bx, double by, double bz,
                            double size, float r, float g, float b, float a) {
        GlowShapes.disc(tess, x, y, z, ax, 0, az, bx, by, bz, size, r, g, b, a);
    }

    // ---------------------------------------------------------------- falling leaves

    private void spawnLeaves(World world, BlockPos player, float budget) {
        if (leaves >= MAX_LEAVES * budget) return;
        for (int attempt = 0; attempt < 4; attempt++) {
            int x = player.getX() + rand.nextInt(25) - 12, z = player.getZ() + rand.nextInt(25) - 12;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, player.getY() + 14, z);
            for (int dy = 0; dy < 24; dy++, p.move(net.minecraft.util.EnumFacing.DOWN)) {
                IBlockState state = world.getBlockState(p);
                if (!(state.getBlock() instanceof BlockLeaves)) continue;
                if (!world.isAirBlock(p.down())) continue;   // lowest leaves of the canopy
                if (rand.nextInt(4) != 0) break;              // keep it sparse
                BlockPos at = p.toImmutable();
                Leaf leaf = new Leaf(world, x + rand.nextDouble(), at.getY() - 0.05, z + rand.nextDouble(), state, rand);
                leaf.setBlockPos(at); // applies the biome foliage tint
                add(leaf);
                if (++leaves >= MAX_LEAVES * budget) return;
                break;
            }
        }
    }

    static class Leaf extends ParticleDigging {
        private final float swayPhase, swaySpeed;
        Leaf(World w, double x, double y, double z, IBlockState state, Random r) {
            super(w, x, y, z, 0, 0, 0, state);
            particleMaxAge = 200 + r.nextInt(120);
            particleScale = 2.2f + r.nextFloat() * 1.0f;   // ~1/4 block, leaf-sized
            particleGravity = 0;
            canCollide = true;
            motionX = motionY = motionZ = 0;
            swayPhase = r.nextFloat() * 6.28f;
            swaySpeed = 0.08f + r.nextFloat() * 0.06f;
        }

        @Override public void onUpdate() {
            prevPosX = posX; prevPosY = posY; prevPosZ = posZ;
            if (particleAge++ >= particleMaxAge) { setExpired(); return; }
            if (onGround) {
                // rest briefly on the ground, then fade away
                particleAlpha = Math.max(0f, particleAlpha - 0.02f);
                if (particleAlpha <= 0f) setExpired();
                return;
            }
            float t = particleAge * swaySpeed + swayPhase;
            motionX = MathHelper.sin(t) * 0.04;
            motionZ = MathHelper.cos(t * 0.7f) * 0.025;
            motionY = -0.035 - 0.015 * Math.abs(MathHelper.sin(t));
            move(motionX, motionY, motionZ);
        }
    }

    // ---------------------------------------------------------------- cave dust

    private void spawnDust(World world, BlockPos player, float budget) {
        BlockPos eye = new BlockPos(mc.player.posX, mc.player.posY + mc.player.getEyeHeight(), mc.player.posZ);
        if (world.provider.isSurfaceWorld() && world.getLightFor(EnumSkyBlock.SKY, eye) > 0) return; // underground only
        if (dust >= MAX_DUST * budget) return;
        for (int attempt = 0; attempt < 2; attempt++) {
            double x = mc.player.posX + (rand.nextDouble() - 0.5) * 12;
            double y = mc.player.posY + (rand.nextDouble() - 0.3) * 5;
            double z = mc.player.posZ + (rand.nextDouble() - 0.5) * 12;
            BlockPos p = new BlockPos(x, y, z);
            if (!world.isAirBlock(p)) continue;
            add(new Dust(world, x, y, z, rand));
            if (++dust >= MAX_DUST * budget) return;
        }
    }

    static class Dust extends Particle {
        Dust(World w, double x, double y, double z, Random r) {
            super(w, x, y, z);
            particleMaxAge = 120 + r.nextInt(120);
            particleScale = 0.9f + r.nextFloat() * 0.6f;
            setParticleTextureIndex(7);
            float c = 0.85f + r.nextFloat() * 0.15f;
            setRBGColorF(c, c * 0.95f, c * 0.85f);
            particleGravity = 0;
            canCollide = false;
            motionX = (r.nextDouble() - 0.5) * 0.006;
            motionY = (r.nextDouble() - 0.5) * 0.004;
            motionZ = (r.nextDouble() - 0.5) * 0.006;
            particleAlpha = 0;
        }

        @Override public void onUpdate() {
            prevPosX = posX; prevPosY = posY; prevPosZ = posZ;
            if (particleAge++ >= particleMaxAge) { setExpired(); return; }
            move(motionX, motionY, motionZ);
            float life = particleAge / (float) particleMaxAge;
            particleAlpha = 0.55f * Math.min(1f, Math.min(life * 5f, (1f - life) * 5f));
        }
        // brightness comes from the world, so dust only shows where there's light (near torches)
    }
}
