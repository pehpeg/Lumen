package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
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
        if (world == null || mc.player == null) { live.clear(); return; }
        if (mc.isGamePaused()) return;

        // prune + count
        fireflies = leaves = dust = 0;
        boolean on = LumenConfig.enabled;
        for (int i = live.size() - 1; i >= 0; i--) {
            Particle p = live.get(i);
            if (!on) p.setExpired();
            if (!p.isAlive()) { live.remove(i); continue; }
            if (p instanceof Firefly) fireflies++;
            else if (p instanceof Leaf) leaves++;
            else dust++;
        }
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

    private void spawnFireflies(World world, BlockPos player, float budget) {
        if (!world.provider.isSurfaceWorld() || world.isRaining()) return;
        if (world.getSunBrightness(1f) > 0.45f) return;           // nights only
        if (fireflies >= MAX_FIREFLIES * budget) return;
        for (int attempt = 0; attempt < 3; attempt++) {
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
            add(new Firefly(world, x + rand.nextDouble(), top.getY() + 0.4 + rand.nextDouble() * 2.0,
                    z + rand.nextDouble(), rand));
            if (fireflies++ >= MAX_FIREFLIES * budget) return;
        }
    }

    static class Firefly extends Particle {
        private final float phase;
        Firefly(World w, double x, double y, double z, Random r) {
            super(w, x, y, z);
            particleMaxAge = 160 + r.nextInt(160);
            particleScale = 0.55f + r.nextFloat() * 0.25f;
            setParticleTextureIndex(1);
            setRBGColorF(0.80f, 1.0f, 0.30f);
            particleGravity = 0;
            canCollide = true;
            motionX = (r.nextDouble() - 0.5) * 0.02;
            motionY = (r.nextDouble() - 0.5) * 0.01;
            motionZ = (r.nextDouble() - 0.5) * 0.02;
            phase = r.nextFloat() * 6.28f;
            particleAlpha = 0;
        }

        @Override public void onUpdate() {
            prevPosX = posX; prevPosY = posY; prevPosZ = posZ;
            if (particleAge++ >= particleMaxAge) { setExpired(); return; }
            // lazy wandering
            motionX = MathHelper.clamp(motionX + (rand.nextDouble() - 0.5) * 0.004, -0.03, 0.03);
            motionY = MathHelper.clamp(motionY + (rand.nextDouble() - 0.5) * 0.003, -0.015, 0.015);
            motionZ = MathHelper.clamp(motionZ + (rand.nextDouble() - 0.5) * 0.004, -0.03, 0.03);
            move(motionX, motionY, motionZ);
            // blink: slow pulse, fade in/out at birth/death
            float life = particleAge / (float) particleMaxAge;
            float edge = Math.min(1f, Math.min(life * 6f, (1f - life) * 6f));
            float pulse = 0.5f + 0.5f * MathHelper.sin(particleAge * 0.12f + phase);
            particleAlpha = edge * (0.25f + 0.75f * pulse * pulse);
        }

        @Override public int getBrightnessForRender(float pt) { return 0xF000F0; } // self-lit
        @Override public boolean shouldDisableDepth() { return false; }
    }

    // ---------------------------------------------------------------- falling leaves

    private void spawnLeaves(World world, BlockPos player, float budget) {
        if (leaves >= MAX_LEAVES * budget) return;
        for (int attempt = 0; attempt < 6; attempt++) {
            BlockPos p = player.add(rand.nextInt(25) - 12, rand.nextInt(16) - 2, rand.nextInt(25) - 12);
            IBlockState state = world.getBlockState(p);
            if (!(state.getBlock() instanceof BlockLeaves)) continue;
            if (!world.isAirBlock(p.down())) continue;
            if (rand.nextInt(3) != 0) continue; // keep it sparse
            Leaf leaf = new Leaf(world, p.getX() + rand.nextDouble(), p.getY() - 0.05, p.getZ() + rand.nextDouble(), state, rand);
            leaf.setBlockPos(p); // applies the biome foliage tint
            add(leaf);
            if (++leaves >= MAX_LEAVES * budget) return;
        }
    }

    static class Leaf extends ParticleDigging {
        private final float swayPhase, swaySpeed;
        Leaf(World w, double x, double y, double z, IBlockState state, Random r) {
            super(w, x, y, z, 0, 0, 0, state);
            particleMaxAge = 200 + r.nextInt(120);
            particleScale = 0.9f + r.nextFloat() * 0.5f;
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
            particleScale = 0.25f + r.nextFloat() * 0.2f;
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
