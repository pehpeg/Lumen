package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Automated visual test, only active when the game is started with -Dlumen.autotest=true.
 * Creates a world, runs a list of scenes and takes a Lumen ON and a vanilla screenshot of
 * each, writes a report and quits. Used by the GitHub Actions "visual-test" job.
 */
public class LumenAutoTest {

    public static boolean isEnabled() { return Boolean.getBoolean("lumen.autotest"); }

    private static final Logger LOG = LogManager.getLogger("Lumen-AutoTest");
    private final Minecraft mc = Minecraft.getMinecraft();

    private static final class Scene {
        final String name; final long time; final float yaw, pitch; final int settle;
        Runnable setup = () -> {}, beforeShot = () -> {};
        Scene(String name, long time, float yaw, float pitch, int settle) {
            this.name = name; this.time = time; this.yaw = yaw; this.pitch = pitch; this.settle = settle;
        }
    }

    private final List<Scene> scenes = new ArrayList<>();
    private BlockPos spawn;

    private boolean worldRequested;
    private int ticksInWorld, totalTicks, sceneIndex, phase, wait;
    private String pendingShot;
    private volatile boolean skipScene;
    private final StringBuilder report = new StringBuilder();

    private void buildScenes() {
        Scene s;
        scenes.add(new Scene("1_noon", 6000, 0f, 5f, 80));
        scenes.add(new Scene("2_sunset", 12400, 90f, -8f, 80));

        s = new Scene("3_night_lights", 18000, 0f, 30f, 220);
        s.setup = () -> { teleport(spawn, 0f, 30f); placeNightLights(); };
        scenes.add(s);

        s = new Scene("4_night_sky_star", 18000, 30f, -35f, 60);
        s.beforeShot = () -> LumenAtmosphere.spawnStar(mc.player.getLookVec());
        scenes.add(s);

        s = new Scene("5_cave_ores", 18000, 0f, 8f, 260);
        s.setup = this::buildOreCave;
        scenes.add(s);

        s = new Scene("6_swamp", 6000, 45f, 0f, 420);
        s.setup = () -> teleportToBiome(Biomes.SWAMPLAND);
        scenes.add(s);

        s = new Scene("7_desert", 6000, 45f, 0f, 420);
        s.setup = () -> teleportToBiome(Biomes.DESERT);
        scenes.add(s);

        s = new Scene("8_forest_leaves", 6000, 45f, 10f, 300);
        s.setup = () -> teleportToBiome(Biomes.FOREST);
        scenes.add(s);
    }

    // ---------------------------------------------------------------- main loop

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        totalTicks++;
        if (totalTicks > 20 * 60 * 25) { log("TIMEOUT - giving up"); finish(); return; }

        if (!worldRequested && mc.currentScreen instanceof GuiMainMenu) {
            worldRequested = true;
            mc.gameSettings.pauseOnLostFocus = false;
            mc.gameSettings.renderDistanceChunks = 6;
            mc.gameSettings.fancyGraphics = true;
            mc.gameSettings.hideGUI = true;
            log("creating world");
            mc.launchIntegratedServer("lumen-autotest", "Lumen AutoTest",
                    new WorldSettings(4207L, GameType.CREATIVE, true, false, WorldType.DEFAULT));
            return;
        }
        if (mc.world == null || mc.player == null || pendingShot != null) return;
        mc.gameSettings.hideGUI = true;
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        mc.player.capabilities.isFlying = true;

        ticksInWorld++;
        if (ticksInWorld == 1) {
            spawn = new BlockPos(mc.player);
            log("joined world at " + spawn);
            onServer(w -> w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false"));
            onServer(w -> w.getGameRules().setOrCreateGameRule("doMobSpawning", "false"));
            buildScenes();
            wait = 400; // let chunks load and build (software rendering is slow)
            return;
        }
        if (wait > 0) { wait--; return; }
        if (sceneIndex >= scenes.size()) { finish(); return; }

        Scene s = scenes.get(sceneIndex);
        switch (phase) {
            case 0:
                LumenConfig.enabled = true;
                skipScene = false;
                onServer(w -> w.setWorldTime(s.time));
                try { s.setup.run(); } catch (Exception ex) { log(s.name + ": setup failed: " + ex); skipScene = true; }
                look(s);
                wait = s.settle;
                phase = 1;
                break;
            case 1:
                if (skipScene) { nextScene(); break; }
                look(s);
                s.beforeShot.run();
                wait = 6;
                phase = 2;
                break;
            case 2:
                look(s);
                pendingShot = s.name + "_lumen_on";   // Lumen turns off right after this shot
                wait = 40;
                phase = 3;
                break;
            case 3:
                look(s);
                pendingShot = s.name + "_vanilla";
                wait = 5;
                nextScene();
                break;
        }
    }

    private void nextScene() { sceneIndex++; phase = 0; }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || pendingShot == null) return;
        boolean onShot = pendingShot.endsWith("_lumen_on");
        ScreenShotHelper.saveScreenshot(mc.gameDir, pendingShot + ".png",
                mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        log("screenshot " + pendingShot + " at " + new BlockPos(mc.player)
                + " biome=" + mc.world.getBiome(new BlockPos(mc.player)).getBiomeName()
                + " | lightmap grades=" + LumenLightmap.gradeCount + " failed=" + LumenLightmap.failed
                + " | halos=" + LumenWorldGlow.statGlows + " oreFaces=" + LumenWorldGlow.statOreFaces
                + " oreMasks=" + OreMasks.built + " | " + LumenParticles.stats);
        if (onShot) LumenConfig.enabled = false;
        pendingShot = null;
    }

    // ---------------------------------------------------------------- scene helpers

    private void look(Scene s) {
        mc.player.rotationYaw = mc.player.prevRotationYaw = s.yaw;
        mc.player.rotationPitch = mc.player.prevRotationPitch = s.pitch;
        mc.player.rotationYawHead = mc.player.prevRotationYawHead = s.yaw;
    }

    private void teleport(BlockPos feet, float yaw, float pitch) {
        onServer(w -> {
            EntityPlayerMP p = w.getMinecraftServer().getPlayerList().getPlayers().get(0);
            p.capabilities.isFlying = true;
            p.connection.setPlayerLocation(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, yaw, pitch);
        });
    }

    private void placeNightLights() {
        BlockPos p = spawn;
        onServer(w -> {
            int[][] torches = {{-3, 4}, {3, 4}, {-5, 9}, {5, 9}};
            for (int[] o : torches) w.setBlockState(w.getTopSolidOrLiquidBlock(p.add(o[0], 0, o[1])), Blocks.TORCH.getDefaultState());
            BlockPos g = w.getTopSolidOrLiquidBlock(p.add(0, 0, 7));
            w.setBlockState(g, Blocks.GLOWSTONE.getDefaultState());
            BlockPos l = w.getTopSolidOrLiquidBlock(p.add(-2, 0, 11)).down();
            w.setBlockState(l, Blocks.LAVA.getDefaultState());
            w.setBlockState(l.east(), Blocks.LAVA.getDefaultState());
        });
    }

    private void buildOreCave() {
        BlockPos base = new BlockPos(spawn.getX(), 20, spawn.getZ());
        onServer(w -> {
            // hollow room, stone shell
            for (int x = -6; x <= 6; x++) for (int y = -1; y <= 6; y++) for (int z = -6; z <= 9; z++) {
                boolean shell = x == -6 || x == 6 || y == -1 || y == 6 || z == -6 || z == 9;
                w.setBlockState(base.add(x, y, z), shell ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
            }
            IBlockState[] ores = {Blocks.DIAMOND_ORE.getDefaultState(), Blocks.REDSTONE_ORE.getDefaultState(),
                    Blocks.GOLD_ORE.getDefaultState(), Blocks.LAPIS_ORE.getDefaultState(),
                    Blocks.EMERALD_ORE.getDefaultState(), Blocks.IRON_ORE.getDefaultState(),
                    Blocks.COAL_ORE.getDefaultState(), Blocks.QUARTZ_ORE.getDefaultState()};
            // a wall of ores in front, a few on the sides
            for (int i = 0; i < 8; i++) {
                w.setBlockState(base.add(-4 + i, 1 + (i % 2), 9), ores[i]);
                w.setBlockState(base.add(-4 + i, 3, 9), ores[(i + 3) % 8]);
            }
            w.setBlockState(base.add(-6, 2, 4), ores[0]);
            w.setBlockState(base.add(6, 2, 5), ores[1]);
            w.setBlockState(base.add(6, 1, 2), ores[4]);
            // one torch on the floor, off to the side, so the room isn't pitch black
            w.setBlockState(base.add(-5, 0, 1), Blocks.TORCH.getDefaultState());
        });
        teleport(base.add(0, 1, -3), 0f, 8f);
    }

    private void teleportToBiome(Biome biome) {
        onServer(w -> {
            List<Biome> wanted = Collections.singletonList(biome);
            BlockPos found = w.getBiomeProvider().findBiomePosition(spawn.getX(), spawn.getZ(), 3000, wanted, new Random(7));
            if (found == null) {
                log(biome.getBiomeName() + " not found nearby, skipping");
                skipScene = true;
                return;
            }
            BlockPos top = w.getHeight(found);
            EntityPlayerMP p = w.getMinecraftServer().getPlayerList().getPlayers().get(0);
            p.capabilities.isFlying = true;
            p.connection.setPlayerLocation(top.getX() + 0.5, top.getY() + 3, top.getZ() + 0.5, 45f, 0f);
        });
    }

    private void onServer(Consumer<WorldServer> task) {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) return;
        server.addScheduledTask(() -> task.accept(server.getWorld(0)));
    }

    private void log(String msg) {
        LOG.info("LUMEN-AUTOTEST: " + msg);
        synchronized (report) { report.append(msg).append('\n'); }
    }

    private void finish() {
        log("done; GL renderer=" + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
        File dir = new File(mc.gameDir, "screenshots");
        dir.mkdirs();
        try (FileWriter w = new FileWriter(new File(dir, "lumen-report.txt"))) {
            synchronized (report) { w.write(report.toString()); }
        } catch (IOException ex) {
            LOG.warn("couldn't write report", ex);
        }
        mc.shutdown();
    }
}
