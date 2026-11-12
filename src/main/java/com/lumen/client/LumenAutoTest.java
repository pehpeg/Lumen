package com.lumen.client;

import com.lumen.LumenConfig;
import com.lumen.core.VirtualLights;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Automated visual test, only active with -Dlumen.autotest=true. Installs a small test resource
 * pack (emissive textures), creates a world, builds test scenes, takes a Lumen ON and a vanilla
 * screenshot of each, writes a report with stats and timings, and quits.
 */
public class LumenAutoTest {

    public static boolean isEnabled() { return Boolean.getBoolean("lumen.autotest"); }

    private static final Logger LOG = LogManager.getLogger("Lumen-AutoTest");
    private final Minecraft mc = Minecraft.getMinecraft();

    private static final class Scene {
        final String name; final long time; final int settle;
        Runnable setup = () -> {};
        Scene(String name, long time, int settle) { this.name = name; this.time = time; this.settle = settle; }
    }

    private final List<Scene> scenes = new ArrayList<>();
    private BlockPos spawn;
    private boolean packDone, worldRequested;
    private int ticksInWorld, totalTicks, sceneIndex, phase, wait;
    private String pendingShot;
    private volatile boolean skipScene;
    private float yaw, pitch;
    private final StringBuilder report = new StringBuilder();

    private void buildScenes() {
        Scene s;
        s = new Scene("1_dynamic_torch_night", 18000, 220);
        s.setup = () -> setupDynamicNight();
        scenes.add(s);
        s = new Scene("2_dynamic_torch_cave", 18000, 200);
        s.setup = () -> setupDarkRoom();
        scenes.add(s);
        s = new Scene("3_colored_lights", 18000, 260);
        s.setup = () -> setupColoredPlaza();
        scenes.add(s);
        s = new Scene("4_emissive_ores", 18000, 240);
        s.setup = () -> setupOreRoom();
        scenes.add(s);
        s = new Scene("5_shadows_morning", 2200, 320);
        s.setup = () -> setupShadowTower(170f);
        scenes.add(s);
        s = new Scene("6_shadows_afternoon", 9800, 320);
        s.setup = () -> setupShadowTower(170f);
        scenes.add(s);
        s = new Scene("7_forest_afternoon", 9000, 420);
        s.setup = () -> teleportToBiome(Biomes.FOREST, 120f, 25f);
        scenes.add(s);
    }

    // ---------------------------------------------------------------- main loop

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        totalTicks++;
        if (totalTicks > 20 * 60 * 25) { log("TIMEOUT - giving up"); finish(); return; }

        if (!packDone && mc.currentScreen instanceof GuiMainMenu) {
            packDone = true;
            installTestPack();
            return;
        }
        if (packDone && !worldRequested && mc.currentScreen instanceof GuiMainMenu) {
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
            log("joined world");
            onServer(w -> w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false"));
            onServer(w -> w.getGameRules().setOrCreateGameRule("doMobSpawning", "false"));
            onServer(w -> w.getGameRules().setOrCreateGameRule("doWeatherCycle", "false"));
            buildScenes();
            wait = 400;
            return;
        }
        if (wait > 0) { wait--; return; }
        if (spawn == null) {
            spawn = new BlockPos(mc.player);
            log("spawn at " + spawn + " biome=" + mc.world.getBiome(spawn).getBiomeName());
        }
        if (sceneIndex >= scenes.size()) { finish(); return; }

        Scene s = scenes.get(sceneIndex);
        switch (phase) {
            case 0:
                LumenConfig.enabled = true;
                skipScene = false;
                clearHands();
                onServer(w -> w.setWorldTime(s.time));
                try { s.setup.run(); } catch (Exception ex) { log(s.name + ": setup failed: " + ex); skipScene = true; }
                wait = s.settle;
                phase = 1;
                break;
            case 1:
                if (skipScene) { sceneIndex++; phase = 0; break; }
                look();
                LumenClient.resetTimers();
                pendingShot = s.name + "_lumen_on";     // Lumen turns off right after this shot
                wait = 160;                              // let lights and overlays switch off
                phase = 2;
                break;
            case 2:
                look();
                pendingShot = s.name + "_vanilla";
                wait = 5;
                sceneIndex++;
                phase = 0;
                break;
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || pendingShot == null) return;
        boolean onShot = pendingShot.endsWith("_lumen_on");
        ScreenShotHelper.saveScreenshot(mc.gameDir, pendingShot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        log("screenshot " + pendingShot + " at " + new BlockPos(mc.player)
                + " | moving lights=" + DynamicLights.statSources + " relights=" + DynamicLights.statChecks
                + " engineHits=" + VirtualLights.hits
                + " | colored meshes=" + ColoredLight.statMeshes + " quads=" + ColoredLight.statQuads
                + " | glow quads=" + SectionScanner.statQuads + " packTex=" + EmissiveTextures.statPack + " autoTex=" + EmissiveTextures.statAuto
                + " | shadow quads=" + SunShadows.statQuads + " pending=" + SunShadows.statPending
                + " lastColumnBuild=" + SunShadows.statLastBuildMicros + "us"
                + " | " + LumenClient.timings());
        if (onShot) LumenConfig.enabled = false;
        pendingShot = null;
    }

    private void look() {
        mc.player.rotationYaw = mc.player.prevRotationYaw = yaw;
        mc.player.rotationPitch = mc.player.prevRotationPitch = pitch;
        mc.player.rotationYawHead = mc.player.prevRotationYawHead = yaw;
    }

    // ---------------------------------------------------------------- test resource pack

    private void installTestPack() {
        try {
            File dir = new File(mc.gameDir, "resourcepacks/LumenTestPack");
            File tex = new File(dir, "assets/minecraft/textures/blocks");
            tex.mkdirs();
            try (FileWriter w = new FileWriter(new File(dir, "pack.mcmeta"))) {
                w.write("{\"pack\":{\"pack_format\":3,\"description\":\"Lumen emissive test\"}}");
            }
            // iron block: glowing cyan cross; lapis ore: magenta frame (proves pack beats the auto mask)
            BufferedImage iron = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            BufferedImage lapis = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                if (x == 7 || x == 8 || y == 7 || y == 8) iron.setRGB(x, y, 0xFF40F0FF);
                if (x < 2 || y < 2 || x > 13 || y > 13) lapis.setRGB(x, y, 0xFFFF40E0);
            }
            ImageIO.write(iron, "png", new File(tex, "iron_block_e.png"));
            ImageIO.write(lapis, "png", new File(tex, "lapis_ore_e.png"));

            ResourcePackRepository repo = mc.getResourcePackRepository();
            repo.updateRepositoryEntriesAll();
            List<ResourcePackRepository.Entry> selected = new ArrayList<>(repo.getRepositoryEntries());
            for (ResourcePackRepository.Entry en : repo.getRepositoryEntriesAll()) {
                if (en.getResourcePackName().equals("LumenTestPack") && !selected.contains(en)) selected.add(0, en);
            }
            repo.setRepositories(selected);
            mc.gameSettings.resourcePacks.clear();
            for (ResourcePackRepository.Entry en : selected) mc.gameSettings.resourcePacks.add(en.getResourcePackName());
            mc.refreshResources();
            log("test resource pack installed: " + selected.size() + " pack(s) active");
        } catch (Exception ex) {
            log("test resource pack failed: " + ex);
        }
    }

    // ---------------------------------------------------------------- scenes

    private void setupDynamicNight() {
        BlockPos at = spawn.add(0, 0, -40);
        onServer(w -> {
            BlockPos g = dryGround(w, at);
            EntityPlayerMP p = player(w);
            p.inventory.setInventorySlotContents(p.inventory.currentItem, new ItemStack(Blocks.TORCH));
            p.inventoryContainer.detectAndSendChanges();
            p.connection.setPlayerLocation(g.getX() + 0.5, g.getY() + 0.05, g.getZ() + 0.5, 180f, 40f);
            dropItem(w, w.getTopSolidOrLiquidBlock(g.add(-3, 0, -7)), new ItemStack(Blocks.GLOWSTONE));
        });
        yaw = 180f; pitch = 40f;
    }

    private void setupDarkRoom() {
        BlockPos base = new BlockPos(spawn.getX() + 40, 30, spawn.getZ());
        onServer(w -> {
            hollowRoom(w, base, 7, 6, 10);
            EntityPlayerMP p = player(w);
            p.inventory.setInventorySlotContents(p.inventory.currentItem, new ItemStack(Blocks.TORCH));
            p.inventoryContainer.detectAndSendChanges();
            p.connection.setPlayerLocation(base.getX() + 0.5, base.getY(), base.getZ() - 4.5, 0f, 15f);
        });
        yaw = 0f; pitch = 15f;
    }

    private void setupColoredPlaza() {
        BlockPos at = spawn.add(40, 0, -40);
        onServer(w -> {
            BlockPos g = dryGround(w, at);
            int y = g.getY();
            for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
                w.setBlockState(new BlockPos(g.getX() + x, y - 1, g.getZ() + z), Blocks.STONE.getDefaultState());
                for (int h = 0; h < 6; h++) w.setBlockToAir(new BlockPos(g.getX() + x, y + h, g.getZ() + z));
            }
            // a back wall so light has something to fall on
            for (int x = -9; x <= 9; x++) for (int h = 0; h < 4; h++)
                w.setBlockState(new BlockPos(g.getX() + x, y + h, g.getZ() - 9), Blocks.STONE.getDefaultState());
            w.setBlockState(g.add(-6, 0, -6), Blocks.TORCH.getDefaultState());
            w.setBlockState(g.add(-1, 0, -6), Blocks.REDSTONE_TORCH.getDefaultState());
            w.setBlockState(g.add(4, 0, -6), Blocks.SEA_LANTERN.getDefaultState());
            w.setBlockState(g.add(-4, 0, 0), Blocks.GLOWSTONE.getDefaultState());
            w.setBlockState(g.add(5, -1, 0), Blocks.LAVA.getDefaultState());
            w.setBlockState(g.add(6, -1, 0), Blocks.LAVA.getDefaultState());
            EntityPlayerMP p = player(w);
            p.connection.setPlayerLocation(g.getX() + 0.5, y + 7, g.getZ() + 9.5, 180f, 38f);
        });
        yaw = 180f; pitch = 38f;
    }

    private void setupOreRoom() {
        BlockPos base = new BlockPos(spawn.getX(), 20, spawn.getZ());
        onServer(w -> {
            hollowRoom(w, base, 6, 6, 9);
            IBlockState[] ores = {Blocks.DIAMOND_ORE.getDefaultState(), Blocks.REDSTONE_ORE.getDefaultState(),
                    Blocks.GOLD_ORE.getDefaultState(), Blocks.LAPIS_ORE.getDefaultState(),
                    Blocks.EMERALD_ORE.getDefaultState(), Blocks.IRON_ORE.getDefaultState(),
                    Blocks.COAL_ORE.getDefaultState(), Blocks.QUARTZ_ORE.getDefaultState()};
            for (int i = 0; i < 8; i++) {
                w.setBlockState(base.add(-4 + i, 1, 9), ores[i]);
                w.setBlockState(base.add(-4 + i, 3, 9), ores[(i + 3) % 8]);
            }
            w.setBlockState(base.add(-1, 5, 9), Blocks.IRON_BLOCK.getDefaultState());   // pack emissive
            w.setBlockState(base.add(1, 5, 9), Blocks.IRON_BLOCK.getDefaultState());
            w.setBlockState(base.add(-6, 2, 4), ores[0]);
            w.setBlockState(base.add(6, 2, 5), ores[3]);
            w.setBlockState(base.add(-5, 0, 1), Blocks.TORCH.getDefaultState());
            player(w).connection.setPlayerLocation(base.getX() + 0.5, base.getY() + 1, base.getZ() - 3.5, 0f, 8f);
        });
        yaw = 0f; pitch = 8f;
    }

    private void setupShadowTower(float lookYaw) {
        onServer(w -> {
            BlockPos base = w.getTopSolidOrLiquidBlock(spawn.add(0, 0, 8));
            for (int y = 0; y < 9; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                w.setBlockState(base.add(x, y, z), Blocks.STONEBRICK.getDefaultState());
            for (int x = 2; x <= 6; x++) for (int z = -1; z <= 1; z++)
                w.setBlockState(base.add(x, 5, z), Blocks.STONEBRICK.getDefaultState());
            BlockPos g = w.getTopSolidOrLiquidBlock(spawn.add(1, 0, 22));
            player(w).connection.setPlayerLocation(g.getX() + 0.5, g.getY() + 3, g.getZ() + 0.5, lookYaw, 22f);
        });
        yaw = lookYaw; pitch = 22f;
    }

    private void teleportToBiome(Biome biome, float lookYaw, float lookPitch) {
        onServer(w -> {
            BlockPos found = w.getBiomeProvider().findBiomePosition(spawn.getX(), spawn.getZ(), 3000,
                    Collections.singletonList(biome), new Random(7));
            if (found == null) { log(biome.getBiomeName() + " not found, skipping"); skipScene = true; return; }
            w.getChunkProvider().provideChunk(found.getX() >> 4, found.getZ() >> 4);
            BlockPos top = w.getHeight(found);
            player(w).connection.setPlayerLocation(top.getX() + 0.5, top.getY() + 6, top.getZ() + 0.5, lookYaw, lookPitch);
            log(biome.getBiomeName() + " at " + top);
        });
        yaw = lookYaw; pitch = lookPitch;
    }

    // ---------------------------------------------------------------- helpers

    private static void hollowRoom(WorldServer w, BlockPos base, int halfX, int height, int depth) {
        for (int x = -halfX; x <= halfX; x++) for (int y = -1; y <= height; y++) for (int z = -halfX; z <= depth; z++) {
            boolean shell = x == -halfX || x == halfX || y == -1 || y == height || z == -halfX || z == depth;
            w.setBlockState(base.add(x, y, z), shell ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
        }
    }

    private static BlockPos dryGround(WorldServer w, BlockPos at) {
        BlockPos g = w.getTopSolidOrLiquidBlock(at);
        for (int i = 0; i < 40 && w.getBlockState(g).getMaterial().isLiquid(); i++)
            g = w.getTopSolidOrLiquidBlock(at.add((i % 7 - 3) * 6, 0, -(i / 7) * 6));
        return g;
    }

    private static void dropItem(WorldServer w, BlockPos at, ItemStack stack) {
        EntityItem item = new EntityItem(w, at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5, stack);
        item.setPickupDelay(32767);
        item.setNoDespawn();
        item.motionX = item.motionY = item.motionZ = 0;
        w.spawnEntity(item);
    }

    private void clearHands() {
        onServer(w -> {
            EntityPlayerMP p = player(w);
            p.inventory.setInventorySlotContents(p.inventory.currentItem, ItemStack.EMPTY);
            p.inventoryContainer.detectAndSendChanges();
            for (EntityItem it : w.getEntities(EntityItem.class, e -> true)) it.setDead();
        });
    }

    private static EntityPlayerMP player(WorldServer w) {
        EntityPlayerMP p = w.getMinecraftServer().getPlayerList().getPlayers().get(0);
        p.capabilities.isFlying = true;
        return p;
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
        } catch (Exception ex) {
            LOG.warn("couldn't write report", ex);
        }
        mc.shutdown();
    }
}
