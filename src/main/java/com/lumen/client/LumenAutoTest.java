package com.lumen.client;

import com.lumen.LumenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Automated visual test, only active when the game is started with -Dlumen.autotest=true.
 * Creates a world, takes Lumen ON/OFF screenshots at noon, sunset and night (with torches),
 * writes a short report and quits. Used by the GitHub Actions "visual-test" job.
 */
public class LumenAutoTest {

    public static boolean isEnabled() { return Boolean.getBoolean("lumen.autotest"); }

    private static final Logger LOG = LogManager.getLogger("Lumen-AutoTest");
    private final Minecraft mc = Minecraft.getMinecraft();

    private static final Object[][] SCENES = {
            // name, world time, yaw, pitch, place torches
            {"1_noon", 6000L, 0f, 5f, false},
            {"2_sunset", 12400L, 90f, -8f, false},
            {"3_night_torches", 18000L, 0f, 30f, true},
    };

    private boolean worldRequested;
    private int ticksInWorld, totalTicks, step, wait;
    private String pendingShot;
    private final StringBuilder report = new StringBuilder();

    @SubscribeEvent
    public void onGui(GuiOpenEvent e) {
        if (e.getGui() instanceof GuiMainMenu && !worldRequested) {
            log("main menu reached");
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        totalTicks++;
        if (totalTicks > 20 * 60 * 8) { log("TIMEOUT - giving up"); finish(); return; }

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

        ticksInWorld++;
        if (ticksInWorld == 1) {
            log("joined world at " + mc.player.getPosition());
            mc.player.capabilities.isFlying = true;
            runOnServer(w -> w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false"));
            wait = 400; // let chunks load and build (software rendering is slow)
            return;
        }
        if (wait > 0) { wait--; return; }

        int scene = step / 3, phase = step % 3;
        if (scene >= SCENES.length) { finish(); return; }
        Object[] s = SCENES[scene];
        String name = (String) s[0];

        switch (phase) {
            case 0: // set up the scene
                long time = (Long) s[1];
                runOnServer(w -> w.setWorldTime(time));
                look((Float) s[2], (Float) s[3]);
                if ((Boolean) s[4]) placeTorches();
                LumenConfig.enabled = true;
                wait = 80;
                break;
            case 1:
                look((Float) s[2], (Float) s[3]);
                pendingShot = name + "_lumen_on"; // Lumen turns off right after this shot
                wait = 40;
                break;
            case 2:
                look((Float) s[2], (Float) s[3]);
                pendingShot = name + "_vanilla";
                wait = 5;
                break;
        }
        step++;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || pendingShot == null) return;
        boolean onShot = pendingShot.endsWith("_lumen_on");
        ScreenShotHelper.saveScreenshot(mc.gameDir, pendingShot + ".png",
                mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        log("screenshot " + pendingShot + " (lightmap grades=" + LumenLightmap.gradeCount
                + ", lightmapFailed=" + LumenLightmap.failed + ")");
        if (onShot) LumenConfig.enabled = false;
        pendingShot = null;
    }

    private void look(float yaw, float pitch) {
        mc.player.rotationYaw = mc.player.prevRotationYaw = yaw;
        mc.player.rotationPitch = mc.player.prevRotationPitch = pitch;
        mc.player.rotationYawHead = mc.player.prevRotationYawHead = yaw;
    }

    private void placeTorches() {
        BlockPos p = mc.player.getPosition();
        runOnServer(w -> {
            int[][] offs = {{-3, 4}, {3, 4}, {0, 7}, {-5, 9}, {5, 9}};
            for (int[] o : offs) {
                BlockPos top = w.getTopSolidOrLiquidBlock(p.add(o[0], 0, o[1]));
                w.setBlockState(top, Blocks.TORCH.getDefaultState());
            }
        });
    }

    private interface WorldTask { void run(WorldServer w); }

    private void runOnServer(WorldTask task) {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) return;
        server.addScheduledTask(() -> task.run(server.getWorld(0)));
    }

    private void log(String msg) {
        LOG.info("LUMEN-AUTOTEST: " + msg);
        report.append(msg).append('\n');
    }

    private void finish() {
        log("done; lightmap grades=" + LumenLightmap.gradeCount + ", lightmapFailed=" + LumenLightmap.failed
                + ", GL renderer=" + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
        File dir = new File(mc.gameDir, "screenshots");
        dir.mkdirs();
        try (FileWriter w = new FileWriter(new File(dir, "lumen-report.txt"))) {
            w.write(report.toString());
        } catch (IOException ex) {
            LOG.warn("couldn't write report", ex);
        }
        mc.shutdown();
    }
}
