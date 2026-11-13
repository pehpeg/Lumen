package com.lumen.client;

import com.lumen.LumenConfig;
import com.lumen.core.VirtualLights;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic lights through the light engine (see VirtualLights). Each update:
 *  - find light-giving entities near the player (held/dropped light items, burning mobs, blazes...)
 *  - place a virtual emitter at each one's block position
 *  - ask the light engine to re-check only the positions that changed
 * The engine spreads the light into the real light data, so walls block it and every renderer
 * shows it. Light moves in whole-block steps, like Moving Light Sources.
 */
public class DynamicLights {

    private final Minecraft mc = Minecraft.getMinecraft();

    /** emitter position (BlockPos.toLong) -> level, as currently published */
    private Map<Long, Integer> active = new HashMap<>();
    private World activeWorld;
    private int ticks;

    private Map<Item, Integer> extraItems = new HashMap<>();
    private Map<ResourceLocation, Integer> entityLevels = new HashMap<>();
    public static int statSources, statChecks;

    private final LightColors colors;
    /** emitter position -> light color (for colored light), as currently published */
    private Map<Long, Integer> activeColors = new HashMap<>();
    private Map<Long, Integer> wantedColors = new HashMap<>();

    public DynamicLights(LightColors colors) { this.colors = colors; reloadConfig(); }

    /** Increases whenever the moving lights change. */
    public int version;

    public void reloadConfig() {
        extraItems = new HashMap<>();
        for (String s : LumenConfig.dynamicLights.extraItems) {
            String[] kv = s.split("=");
            if (kv.length != 2) continue;
            Item item = Item.getByNameOrId(kv[0].trim());
            try { if (item != null) extraItems.put(item, Integer.parseInt(kv[1].trim())); } catch (NumberFormatException ignored) {}
        }
        entityLevels = new HashMap<>();
        for (String s : LumenConfig.dynamicLights.glowingEntities) {
            String[] kv = s.split("=");
            if (kv.length != 2) continue;
            try { entityLevels.put(new ResourceLocation(kv[0].trim()), Integer.parseInt(kv[1].trim())); } catch (NumberFormatException ignored) {}
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        World world = mc.world;
        if (world != activeWorld) {          // world changed or left: forget everything
            active = new HashMap<>();
            VirtualLights.publish(new long[0], new int[0], 0);
            activeWorld = world;
        }
        if (world == null || mc.player == null) return;
        boolean on = LumenClient.active && LumenConfig.dynamicLights.enabled;
        // with Lumen's block light on, moving lights are drawn smoothly by MovingLightOverlay and
        // light entities through MovingLightLevels; the light engine path is the fallback
        boolean overlay = on && LumenConfig.colored.enabled;
        moving = overlay ? collectMoving(world) : new java.util.ArrayList<>();
        double[] flat = new double[moving.size() * 4];
        for (int k = 0; k < moving.size(); k++) System.arraycopy(moving.get(k), 1, flat, k * 4, 4);
        com.lumen.core.MovingLightLevels.publish(flat);
        if (on && !overlay && ++ticks % LumenConfig.dynamicLights.updateInterval != 0) return;

        wantedColors = new HashMap<>();
        Map<Long, Integer> wanted = (on && !overlay) ? collect(world) : new HashMap<>();
        if (wanted.equals(active) && wantedColors.equals(activeColors)) return;
        version++;

        // publish first, then re-check every position whose emission changed (both directions)
        long t0 = System.nanoTime();
        long[] pos = new long[wanted.size()];
        int[] lvl = new int[wanted.size()];
        int i = 0;
        for (Map.Entry<Long, Integer> en : wanted.entrySet()) { pos[i] = en.getKey(); lvl[i] = en.getValue(); i++; }
        VirtualLights.publish(pos, lvl, i);

        int checks = 0;
        for (Map.Entry<Long, Integer> en : active.entrySet()) {
            Integer now = wanted.get(en.getKey());
            if (now == null || !now.equals(en.getValue())) { relight(world, en.getKey()); checks++; }
        }
        for (Map.Entry<Long, Integer> en : wanted.entrySet()) {
            if (!en.getValue().equals(active.get(en.getKey()))) { relight(world, en.getKey()); checks++; }
        }
        LumenClient.addTickTime(System.nanoTime() - t0);   // light engine work included
        active = wanted;
        activeColors = wantedColors;
        statSources = wanted.size();
        statChecks += checks;
    }

    private static void relight(World world, long packed) {
        BlockPos p = BlockPos.fromLong(packed);
        if (world.isBlockLoaded(p)) world.checkLightFor(EnumSkyBlock.BLOCK, p);
    }

    /** The server replaced a chunk's light data: put our emitters in it back. */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load e) {
        if (!e.getWorld().isRemote || e.getWorld() != activeWorld || active.isEmpty()) return;
        int cx = e.getChunk().x, cz = e.getChunk().z;
        for (Long packed : active.keySet()) {
            BlockPos p = BlockPos.fromLong(packed);
            if (p.getX() >> 4 == cx && p.getZ() >> 4 == cz) relight(e.getWorld(), packed);
        }
    }

    // ---------------------------------------------------------------- what gives light

    private Map<Long, Integer> collect(World world) {
        Entity viewer = mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : mc.player;
        double range2 = (double) LumenConfig.dynamicLights.range * LumenConfig.dynamicLights.range;
        int max = LumenConfig.dynamicLights.maxSources;

        // gather (distance, pos, level), keep the nearest `max`
        java.util.List<double[]> found = new java.util.ArrayList<>();
        for (Entity en : world.loadedEntityList) {
            if (en.isDead) continue;
            double d2 = en.getDistanceSq(viewer);
            if (d2 > range2) continue;
            int lum = luminance(en);
            if (lum <= 0) continue;
            BlockPos p = emitterPos(world, en);
            if (p == null) continue;
            found.add(new double[]{d2, p.toLong(), lum, colorOf(en)});
        }
        if (found.size() > max) {
            found.sort((a, b) -> Double.compare(a[0], b[0]));
            found = found.subList(0, max);
        }
        Map<Long, Integer> out = new HashMap<>();
        for (double[] f : found) {
            long key = (long) f[1];
            Integer prev = out.get(key);
            if (prev == null || prev < (int) f[2]) { out.put(key, (int) f[2]); wantedColors.put(key, (int) f[3]); }
        }
        return out;
    }

    private java.util.List<double[]> moving = new java.util.ArrayList<>();

    /** {entityId, x, y, z, level, rgb} per moving light, nearest first (overlay mode). */
    public java.util.List<double[]> moving() { return moving; }

    private java.util.List<double[]> collectMoving(World world) {
        Entity viewer = mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : mc.player;
        double range2 = (double) LumenConfig.dynamicLights.range * LumenConfig.dynamicLights.range;
        java.util.List<double[]> found = new java.util.ArrayList<>();
        for (Entity en : world.loadedEntityList) {
            if (en.isDead) continue;
            double d2 = en.getDistanceSq(viewer);
            if (d2 > range2) continue;
            int lum = luminance(en);
            if (lum <= 0) continue;
            double y = en.posY + Math.min(en.getEyeHeight() * 0.85, en.height * 0.75);
            found.add(new double[]{en.getEntityId(), en.posX, y, en.posZ, lum, colorOf(en), d2});
        }
        found.sort((a, b) -> Double.compare(a[6], b[6]));
        int max = LumenConfig.dynamicLights.maxSources;
        return found.size() > max ? new java.util.ArrayList<>(found.subList(0, max)) : found;
    }

    /** Where the light sits: the entity's head (or body) block, as long as light can exist there. */
    private static BlockPos emitterPos(World world, Entity en) {
        BlockPos p = new BlockPos(en.posX, en.posY + Math.min(en.getEyeHeight(), en.height * 0.85), en.posZ);
        if (world.getBlockState(p).getLightOpacity(world, p) < 15) return p;
        p = new BlockPos(en.posX, en.posY + 0.1, en.posZ);
        if (world.getBlockState(p).getLightOpacity(world, p) < 15) return p;
        p = p.up();
        return world.getBlockState(p).getLightOpacity(world, p) < 15 ? p : null;
    }

    int luminance(Entity en) {
        int lum = 0;
        if (en.isBurning() && !en.isInWater()) lum = 15;
        ResourceLocation id = EntityList.getKey(en);
        if (id != null) {
            Integer fixed = entityLevels.get(id);
            if (fixed != null) lum = Math.max(lum, fixed);
        }
        boolean wet = LumenConfig.dynamicLights.waterSensitive && en.isInsideOfMaterial(Material.WATER);
        if (en instanceof EntityItem) {
            lum = Math.max(lum, itemLight(((EntityItem) en).getItem(), wet));
        } else if (en instanceof EntityItemFrame) {
            lum = Math.max(lum, itemLight(((EntityItemFrame) en).getDisplayedItem(), wet));
        } else if (en instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) en;
            lum = Math.max(lum, itemLight(living.getHeldItemMainhand(), wet));
            lum = Math.max(lum, itemLight(living.getHeldItemOffhand(), wet));
            for (ItemStack armor : living.getArmorInventoryList()) lum = Math.max(lum, itemLight(armor, wet) - 2);
        }
        return lum;
    }

    @SuppressWarnings("deprecation")
    int itemLight(ItemStack stack, boolean underwater) {
        if (stack == null || stack.isEmpty()) return 0;
        Item item = stack.getItem();
        int lum = 0;
        if (item instanceof ItemBlock) {
            Block block = ((ItemBlock) item).getBlock();
            if (underwater && (block == Blocks.TORCH || block == Blocks.FIRE)) return 0;
            try {
                lum = block.getStateFromMeta(item.getMetadata(stack.getMetadata())).getLightValue();
            } catch (Exception ex) {
                lum = block.getDefaultState().getLightValue();
            }
        }
        Integer extra = extraItems.get(item);
        if (extra != null) lum = Math.max(lum, extra);
        // flaming weapons glow a little
        if (lum < 8 && EnchantmentHelper.getEnchantmentLevel(Enchantments.FIRE_ASPECT, stack) > 0) lum = Math.max(lum, 8);
        if (lum < 6 && EnchantmentHelper.getEnchantmentLevel(Enchantments.FLAME, stack) > 0) lum = Math.max(lum, 6);
        return lum;
    }

    /** Light color of a glowing entity: the block its light item would place, or fire. */
    @SuppressWarnings("deprecation")
    private int colorOf(Entity en) {
        ItemStack stack = ItemStack.EMPTY;
        if (en instanceof EntityItem) stack = ((EntityItem) en).getItem();
        else if (en instanceof EntityItemFrame) stack = ((EntityItemFrame) en).getDisplayedItem();
        else if (en instanceof EntityLivingBase) {
            EntityLivingBase l = (EntityLivingBase) en;
            stack = itemLight(l.getHeldItemMainhand(), false) >= itemLight(l.getHeldItemOffhand(), false)
                    ? l.getHeldItemMainhand() : l.getHeldItemOffhand();
        }
        if (!stack.isEmpty() && itemLight(stack, false) > 0) {
            Item item = stack.getItem();
            if (item instanceof ItemBlock) {
                Block b = ((ItemBlock) item).getBlock();
                try { return colors.colorOf(b.getStateFromMeta(item.getMetadata(stack.getMetadata())), null, null); }
                catch (Exception ex) { return colors.colorOf(b.getDefaultState(), null, null); }
            }
            if (item == net.minecraft.init.Items.LAVA_BUCKET || item == net.minecraft.init.Items.MAGMA_CREAM)
                return colors.colorOf(Blocks.LAVA.getDefaultState(), null, null);
            if (item == net.minecraft.init.Items.GLOWSTONE_DUST)
                return colors.colorOf(Blocks.GLOWSTONE.getDefaultState(), null, null);
            return colors.colorOf(Blocks.FIRE.getDefaultState(), null, null);
        }
        if (en.isBurning()) return colors.colorOf(Blocks.FIRE.getDefaultState(), null, null);
        return colors.colorOf(Blocks.FIRE.getDefaultState(), null, null);   // blazes, fireballs...
    }
}
