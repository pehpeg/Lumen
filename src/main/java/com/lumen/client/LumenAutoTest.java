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
        boolean cpu;   // also take a shot with the CPU light path, for comparison with the GPU one
        Runnable setup = () -> {};
        java.util.function.IntConsumer during;   // runs every tick while the scene settles (e.g. walking)
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
        s.cpu = true;
        scenes.add(s);
        s = new Scene("2_dynamic_torch_cave", 18000, 200);
        s.setup = () -> setupDarkRoom();
        s.cpu = true;
        scenes.add(s);
        s = new Scene("3_colored_lights", 18000, 260);
        s.setup = () -> setupColoredPlaza();
        scenes.add(s);
        s = new Scene("3b_torch_shadows", 18000, 300);
        s.setup = () -> setupTorchShadows();
        s.cpu = true;
        scenes.add(s);
        s = new Scene("3c_stairs_grass", 18000, 300);
        s.setup = () -> setupStairsGrass();
        scenes.add(s);
        s = new Scene("3d_gaps_bars_ladder_fence", 18000, 300);
        s.setup = () -> setupGaps();
        s.cpu = true;
        scenes.add(s);
        s = new Scene("3e_stained_glass", 18000, 300);
        s.setup = () -> setupGlass();
        scenes.add(s);
        s = new Scene("3g_glowstone_wall", 18000, 300);
        s.setup = () -> setupGlowWall();
        scenes.add(s);
        s = new Scene("3h_tallgrass_torch", 18000, 300);
        s.setup = () -> setupTallGrass();
        scenes.add(s);
        s = new Scene("3i_far_lights_walking", 18000, 700);
        s.setup = () -> setupFarLights();
        s.during = left -> walkFar(left);
        s.cpu = true;
        scenes.add(s);
        s = new Scene("3f_entity_torch_night", 18000, 260);
        s.setup = () -> setupEntities(true);
        scenes.add(s);
        s = new Scene("5b_entity_shadows_day", 8000, 260);
        s.setup = () -> setupEntities(false);
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
        s = new Scene("6b_sunset_sky_color", 12200, 320);
        s.setup = () -> setupShadowTower(170f);
        scenes.add(s);
        s = new Scene("6c_moon_shadows", 18000, 320);
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
        if (wait > 0) {
            wait--;
            if ((phase == 1 || phase == 3) && sceneIndex < scenes.size() && scenes.get(sceneIndex).during != null) scenes.get(sceneIndex).during.accept(wait);
            return;
        }
        if (spawn == null) {
            spawn = new BlockPos(mc.player);
            log("spawn at " + spawn + " biome=" + mc.world.getBiome(spawn).getBiomeName());
        }
        if (sceneIndex >= scenes.size()) { finish(); return; }

        Scene s = scenes.get(sceneIndex);
        switch (phase) {
            case 0:
                LumenClient.active = true;
                skipScene = false;
                mc.gameSettings.renderDistanceChunks = 6;
                clearHands();
                onServer(w -> w.setWorldTime(s.time));
                try { s.setup.run(); } catch (Exception ex) { log(s.name + ": setup failed: " + ex); skipScene = true; }
                LumenClient.resetTimers();
                wait = s.settle;
                phase = 1;
                if (s.cpu) { LumenConfig.colored.gpu = false; phase = 3; }
                break;
            case 3:
                look();
                pendingShot = s.name + "_cpu";
                wait = 5;
                phase = 4;
                break;
            case 4:
                LumenConfig.colored.gpu = true;   // back to the GPU path for the main shot
                LumenClient.resetTimers();
                wait = 240;
                phase = 1;
                break;
            case 1:
                if (skipScene) { sceneIndex++; phase = 0; break; }
                look();
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
                + " | block lights=" + BlockLightSections.statLights + " sections=" + BlockLightSections.statSections
                + " quads=" + BlockLightSections.statQuads + " pending=" + BlockLightSections.statPending
                + " lastSection=" + BlockLightSections.statLastBuildMicros + "us"
                + " | movingLayers=" + MovingLightOverlay.statSources + " quads=" + MovingLightOverlay.statQuads
                + " lastMoving=" + MovingLightOverlay.statLastBuildMicros + "us"
                + " | " + LumenClient.gpuStats()
                + " | entityShadows=" + EntityShadows.statEntities + "/" + EntityShadows.statQuads + "q"
                + " | glow quads=" + SectionScanner.statQuads + " packTex=" + EmissiveTextures.statPack + " autoTex=" + EmissiveTextures.statAuto
                + " | shadow quads=" + SunShadows.statQuads + " pending=" + SunShadows.statPending
                + " lastColumnBuild=" + SunShadows.statLastBuildMicros + "us"
                + " | " + LumenClient.timings());
        if (pendingShot.startsWith("3i_far") && !pendingShot.endsWith("_vanilla"))
            for (BlockPos t : farTowers) log("  tower " + (int) Math.sqrt(t.distanceSq(new BlockPos(mc.player))) + "m: " + LumenClient.debugLight(t));
        if (onShot) LumenClient.active = false;
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

    /** One torch on a stone floor, surrounded by things that should cast shadows from it. */
    private void setupTorchShadows() {
        BlockPos at = spawn.add(-40, 0, -40);
        onServer(w -> {
            BlockPos g = dryGround(w, at);
            int y = g.getY();
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                w.setBlockState(new BlockPos(g.getX() + x, y - 1, g.getZ() + z), Blocks.STONE.getDefaultState());
                for (int h = 0; h < 8; h++) w.setBlockToAir(new BlockPos(g.getX() + x, y + h, g.getZ() + z));
            }
            w.setBlockState(g, Blocks.TORCH.getDefaultState());
            for (int h = 0; h < 3; h++) w.setBlockState(g.add(2, h, -2), Blocks.STONEBRICK.getDefaultState());   // pillar
            w.setBlockState(g.add(-2, 0, -1), Blocks.OAK_STAIRS.getDefaultState());                             // stairs
            w.setBlockState(g.add(-3, 0, -1), Blocks.OAK_STAIRS.getDefaultState());
            w.setBlockState(g.add(0, 0, 3), Blocks.STONE_SLAB.getDefaultState());                               // slab
            for (int x = -2; x <= 1; x++) w.setBlockState(g.add(x, 0, -4), Blocks.COBBLESTONE.getDefaultState()); // low wall
            w.setBlockState(g.add(3, 0, 2), Blocks.GRASS.getDefaultState());                                    // raised blocks
            w.setBlockState(g.add(4, 0, 2), Blocks.GRASS.getDefaultState());
            w.setBlockState(g.add(3, 0, 3), Blocks.GRASS.getDefaultState());
            player(w).connection.setPlayerLocation(g.getX() + 0.5, y + 9, g.getZ() + 7.5, 180f, 52f);
        });
        yaw = 180f; pitch = 52f;
    }

    /** Like the player's screenshot: torch in tall grass, a 2x2 group of stairs in front, a path behind. */
    /** Flat stone floor with an open area above, returns the ground position. */
    private static BlockPos flatFloor(WorldServer w, BlockPos at, int half) {
        BlockPos g = dryGround(w, at);
        int y = g.getY();
        for (int x = -half; x <= half; x++) for (int z = -half; z <= half; z++) {
            w.setBlockState(new BlockPos(g.getX() + x, y - 1, g.getZ() + z), Blocks.STONE.getDefaultState());
            for (int h = 0; h < 10; h++) w.setBlockToAir(new BlockPos(g.getX() + x, y + h, g.getZ() + z));
        }
        return g;
    }

    /** A torch with iron bars, a ladder and a fence around it: light should pass the gaps. */
    private void setupGaps() {
        onServer(w -> {
            BlockPos g = flatFloor(w, spawn.add(80, 0, 0), 10);
            w.setBlockState(g, Blocks.TORCH.getDefaultState());
            for (int x = -1; x <= 1; x++) for (int h = 0; h < 3; h++) w.setBlockState(g.add(x, h, 2), Blocks.IRON_BARS.getDefaultState());
            for (int h = 0; h < 3; h++) w.setBlockState(g.add(-2, h, -1), Blocks.LADDER.getDefaultState()
                    .withProperty(net.minecraft.block.BlockLadder.FACING, net.minecraft.util.EnumFacing.EAST));
            w.setBlockState(g.add(-3, h0(), -1), Blocks.STONE.getDefaultState());
            for (int z = -3; z <= -1; z++) w.setBlockState(g.add(2, 0, z), Blocks.OAK_FENCE.getDefaultState());
            player(w).connection.setPlayerLocation(g.getX() + 0.5, g.getY() + 9, g.getZ() + 9.5, 180f, 50f);
        });
        yaw = 180f; pitch = 50f;
    }

    private static int h0() { return 0; }

    /** A torch with stained glass on three sides and clear glass on the fourth. */
    private void setupGlass() {
        onServer(w -> {
            BlockPos g = flatFloor(w, spawn.add(80, 0, 40), 10);
            w.setBlockState(g, Blocks.TORCH.getDefaultState());
            net.minecraft.block.state.IBlockState sg = Blocks.STAINED_GLASS.getDefaultState();
            for (int i = -1; i <= 1; i++) for (int h = 0; h < 2; h++) {
                w.setBlockState(g.add(i, h, 2), sg.withProperty(net.minecraft.block.BlockStainedGlass.COLOR, net.minecraft.item.EnumDyeColor.PURPLE));
                w.setBlockState(g.add(-2, h, i), sg.withProperty(net.minecraft.block.BlockStainedGlass.COLOR, net.minecraft.item.EnumDyeColor.BLUE));
                w.setBlockState(g.add(2, h, i), sg.withProperty(net.minecraft.block.BlockStainedGlass.COLOR, net.minecraft.item.EnumDyeColor.YELLOW));
                w.setBlockState(g.add(i, h, -2), Blocks.GLASS.getDefaultState());
            }
            player(w).connection.setPlayerLocation(g.getX() + 0.5, g.getY() + 10, g.getZ() + 8.5, 180f, 55f);
        });
        yaw = 180f; pitch = 55f;
    }

    /** A few mobs standing still; at night with a torch next to them. */
    private void setupEntities(boolean night) {
        onServer(w -> {
            BlockPos g = flatFloor(w, spawn.add(40, 0, 80), 10);
            if (night) {
                w.setBlockState(g.add(0, 0, -2), Blocks.TORCH.getDefaultState());
                w.setBlockState(g.add(-5, 0, 1), Blocks.TORCH.getDefaultState());   // a second light: shadows must not cross lit spots
            }
            net.minecraft.entity.EntityLiving[] mobs = {new net.minecraft.entity.passive.EntityVillager(w),
                    new net.minecraft.entity.passive.EntityPig(w), new net.minecraft.entity.monster.EntityZombie(w)};
            double[][] at = {{-2, 0}, {1.5, 0.5}, {0, 2}};
            net.minecraft.entity.passive.EntityChicken flier = new net.minecraft.entity.passive.EntityChicken(w);
            flier.setNoAI(true); flier.setNoGravity(true); flier.enablePersistence();
            flier.setLocationAndAngles(g.getX() + 3.5, g.getY() + 2.5, g.getZ() - 1.5, 0f, 0f);   // in the air
            w.spawnEntity(flier);
            for (int i = 0; i < mobs.length; i++) {
                mobs[i].setNoAI(true);
                mobs[i].enablePersistence();
                mobs[i].setLocationAndAngles(g.getX() + 0.5 + at[i][0], g.getY(), g.getZ() + 0.5 + at[i][1], 0f, 0f);
                w.spawnEntity(mobs[i]);
            }
            player(w).connection.setPlayerLocation(g.getX() + 0.5, g.getY() + 7, g.getZ() + 8.5, 180f, 40f);
        });
        yaw = 180f; pitch = 40f;
    }

    private void setupStairsGrass() {
        BlockPos at = spawn.add(-40, 0, 40);
        onServer(w -> {
            BlockPos g = dryGround(w, at);
            int y = g.getY();
            for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
                BlockPos f = new BlockPos(g.getX() + x, y - 1, g.getZ() + z);
                w.setBlockState(f, (z >= 4 && Math.abs(x) <= 2) ? Blocks.GRASS_PATH.getDefaultState() : Blocks.GRASS.getDefaultState());
                for (int h = 0; h < 8; h++) w.setBlockToAir(f.up(h + 1));
                if ((x * 7 + z * 13) % 3 != 0 && !(z >= 1 && z <= 3 && x >= -1 && x <= 1) && !(z >= 4 && Math.abs(x) <= 2) && !(x == 0 && z == 0))
                    w.setBlockState(f.up(), Blocks.TALLGRASS.getStateFromMeta(1));
            }
            w.setBlockState(g, Blocks.TORCH.getDefaultState());
            net.minecraft.block.state.IBlockState stairs = Blocks.OAK_STAIRS.getDefaultState();
            w.setBlockState(g.add(-1, 0, 1), stairs.withProperty(net.minecraft.block.BlockStairs.FACING, net.minecraft.util.EnumFacing.EAST));
            w.setBlockState(g.add(0, 0, 1), stairs.withProperty(net.minecraft.block.BlockStairs.FACING, net.minecraft.util.EnumFacing.WEST));
            w.setBlockState(g.add(-1, 0, 2), stairs.withProperty(net.minecraft.block.BlockStairs.FACING, net.minecraft.util.EnumFacing.NORTH));
            w.setBlockState(g.add(0, 0, 2), stairs.withProperty(net.minecraft.block.BlockStairs.FACING, net.minecraft.util.EnumFacing.NORTH));
            player(w).connection.setPlayerLocation(g.getX() + 0.0, y + 5, g.getZ() + 7.5, 180f, 40f);
        });
        yaw = 180f; pitch = 40f;
    }

    /** A big stone wall with glowstone set into it (flush), like a lit facade. */
    private void setupGlowWall() {
        BlockPos at = spawn.add(40, 0, -40);
        onServer(w -> {
            BlockPos g = dryGround(w, at);
            int y = g.getY();
            for (int x = -12; x <= 12; x++) for (int z = -10; z <= 4; z++) {
                w.setBlockState(new BlockPos(g.getX() + x, y - 1, g.getZ() + z), Blocks.GRASS.getDefaultState());
                for (int h = 0; h < 14; h++) w.setBlockToAir(new BlockPos(g.getX() + x, y + h, g.getZ() + z));
            }
            for (int x = -12; x <= 12; x++) for (int h = 0; h < 12; h++) {
                BlockPos p = new BlockPos(g.getX() + x, y + h, g.getZ() + 4);
                boolean glow = (h == 1 || h == 3) && Math.floorMod(x, 2) == 0 && Math.abs(x) <= 10;
                w.setBlockState(p, glow ? Blocks.GLOWSTONE.getDefaultState() : Blocks.STONEBRICK.getDefaultState());
            }
            player(w).connection.setPlayerLocation(g.getX() + 0.5, y + 3, g.getZ() - 8.5, 0f, 12f);
        });
        yaw = 0f; pitch = 12f;
    }

    /** A torch beside tall grass and a double plant: grass blades should cast crisp shadows. */
    private void setupTallGrass() {
        BlockPos at = spawn.add(-40, 0, -40);
        onServer(w -> {
            BlockPos g = dryGround(w, at);
            int y = g.getY();
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                BlockPos f = new BlockPos(g.getX() + x, y - 1, g.getZ() + z);
                w.setBlockState(f, Blocks.GRASS.getDefaultState());
                for (int h = 0; h < 8; h++) w.setBlockToAir(f.up(h + 1));
            }
            w.setBlockState(g, Blocks.TORCH.getDefaultState());
            BlockPos d = g.add(1, 0, -1);
            w.setBlockState(d, Blocks.DOUBLE_PLANT.getStateFromMeta(2));       // double tall grass, lower half
            w.setBlockState(d.up(), Blocks.DOUBLE_PLANT.getStateFromMeta(10)); // upper half
            w.setBlockState(g.add(-1, 0, -1), Blocks.TALLGRASS.getStateFromMeta(1));
            w.setBlockState(g.add(-2, 0, 1), Blocks.TALLGRASS.getStateFromMeta(1));
            w.setBlockState(g.add(1, 0, 2), Blocks.RED_FLOWER.getDefaultState());
            player(w).connection.setPlayerLocation(g.getX() + 0.5, y + 5, g.getZ() + 6.5, 180f, 45f);
        });
        yaw = 180f; pitch = 45f;
    }

    private BlockPos farBase;
    private final java.util.List<BlockPos> farTowers = new java.util.ArrayList<>();

    /**
     * Glowstone towers far down a line (up to ~150 blocks), seen while walking toward them at
     * walking speed with a 12-chunk render distance: far lights must show up and stay lit.
     */
    private void setupFarLights() {
        mc.gameSettings.renderDistanceChunks = 12;
        onServer(w -> {
            farBase = w.getTopSolidOrLiquidBlock(spawn.add(-200, 0, -200));
            for (int i = 0; i < 6; i++) {
                BlockPos t = w.getTopSolidOrLiquidBlock(farBase.add(40 + i * 24, 0, (i % 2 == 0 ? -6 : 6)));
                for (int h = 0; h < 3; h++) w.setBlockState(t.up(h), Blocks.COBBLESTONE.getDefaultState());
                w.setBlockState(t.up(3), Blocks.GLOWSTONE.getDefaultState());
                w.setBlockState(t.up(4), Blocks.TORCH.getDefaultState());
                farTowers.add(t.up(3));
            }
            player(w).connection.setPlayerLocation(farBase.getX() - 40.5, farBase.getY() + 20, farBase.getZ() + 0.5, -90f, 12f);
        });
        yaw = -90f; pitch = 12f;
    }

    /** Walk toward the towers (4.3 blocks/s, like walking), crossing many sections before the shot. */
    private void walkFar(int left) {
        if (farBase == null) return;
        int done = 700 - left;   // walk for 14 s, then let the far terrain finish drawing
        double x = farBase.getX() - 40.5 + Math.min(done, 280) * 0.215;
        final double fx = x;
        onServer(w -> player(w).connection.setPlayerLocation(fx, farBase.getY() + 20, farBase.getZ() + 0.5, -90f, 12f));
        look();
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
            for (net.minecraft.entity.EntityLiving it : w.getEntities(net.minecraft.entity.EntityLiving.class, e -> true)) it.setDead();
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
