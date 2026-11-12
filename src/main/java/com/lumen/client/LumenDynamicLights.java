package com.lumen.client;

import com.lumen.LumenConfig;
import com.lumen.core.DynamicLightEngine;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityMagmaCube;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic lights, LambDynamicLights-style: held torches, dropped glowing items, burning mobs,
 * blazes and fireballs light up their surroundings. Each update only marks the few chunk
 * sections around lights that actually moved or changed for re-meshing.
 */
public class LumenDynamicLights {

    private final Minecraft mc = Minecraft.getMinecraft();

    /** Last published source per entity id: x, y, z, luminance. */
    private Map<Integer, double[]> published = new HashMap<>();
    private int ticks;
    public static int statSources;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (mc.world == null || mc.player == null) {
            published.clear();
            DynamicLightEngine.setSources(new double[0]);
            return;
        }
        boolean on = LumenConfig.enabled && LumenConfig.dynamicLights;
        if (on && ++ticks % interval() != 0) return;

        Map<Integer, double[]> now = new HashMap<>();
        if (on) {
            double range2 = 48 * 48;
            for (Entity en : mc.world.loadedEntityList) {
                if (en.isDead || en.getDistanceSq(mc.player) > range2) continue;
                int lum = luminance(en);
                if (lum <= 0) continue;
                now.put(en.getEntityId(), new double[]{en.posX, en.posY + en.height * 0.6, en.posZ, lum});
            }
        }

        // re-mesh around every source that appeared, vanished, moved or changed brightness
        boolean changed = false;
        for (Map.Entry<Integer, double[]> en : now.entrySet()) {
            double[] old = published.get(en.getKey()), cur = en.getValue();
            if (old == null) { markAround(cur); changed = true; continue; }
            double dx = old[0] - cur[0], dy = old[1] - cur[1], dz = old[2] - cur[2];
            if (dx * dx + dy * dy + dz * dz > 0.01 || old[3] != cur[3]) {
                markAround(old); markAround(cur); changed = true;
            }
        }
        for (Map.Entry<Integer, double[]> en : published.entrySet()) {
            if (!now.containsKey(en.getKey())) { markAround(en.getValue()); changed = true; }
        }
        if (!changed && now.size() == published.size()) return;

        double[] flat = new double[now.size() * 4];
        int i = 0;
        for (double[] s : now.values()) { System.arraycopy(s, 0, flat, i, 4); i += 4; }
        DynamicLightEngine.setSources(flat);
        published = now;
        statSources = now.size();
    }

    private static int interval() {
        switch (LumenConfig.dynamicLightsSpeed) {
            case SMOOTH: return 1;
            case FASTEST: return 5;
            default: return 2;
        }
    }

    private void markAround(double[] s) {
        int r = (int) Math.ceil(DynamicLightEngine.RANGE);
        int x = (int) Math.floor(s[0]), y = (int) Math.floor(s[1]), z = (int) Math.floor(s[2]);
        mc.renderGlobal.markBlockRangeForRenderUpdate(x - r, y - r, z - r, x + r, y + r, z + r);
    }

    // ---------------------------------------------------------------- what glows

    static int luminance(Entity en) {
        int lum = 0;
        if (en.isBurning()) lum = 15;
        if (en instanceof EntityBlaze) lum = Math.max(lum, 10);
        else if (en instanceof EntityMagmaCube) lum = Math.max(lum, 8);
        else if (en instanceof EntityFireball) lum = Math.max(lum, 14);
        else if (en instanceof EntityTNTPrimed) lum = Math.max(lum, 10);

        boolean wet = en.isInsideOfMaterial(Material.WATER);
        if (en instanceof EntityItem) {
            lum = Math.max(lum, itemLight(((EntityItem) en).getItem(), wet));
        } else if (en instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) en;
            lum = Math.max(lum, itemLight(living.getHeldItemMainhand(), wet));
            lum = Math.max(lum, itemLight(living.getHeldItemOffhand(), wet));
        }
        return lum;
    }

    @SuppressWarnings("deprecation")
    static int itemLight(ItemStack stack, boolean underwater) {
        if (stack == null || stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (item instanceof ItemBlock) {
            Block block = ((ItemBlock) item).getBlock();
            if (underwater && (block == Blocks.TORCH || block == Blocks.FIRE)) return 0; // torches go out
            try {
                return block.getStateFromMeta(item.getMetadata(stack.getMetadata())).getLightValue();
            } catch (Exception ex) {
                return block.getDefaultState().getLightValue();
            }
        }
        if (item == Items.LAVA_BUCKET) return 15;
        if (item == Items.BLAZE_ROD) return 10;
        if (item == Items.BLAZE_POWDER || item == Items.GLOWSTONE_DUST) return 8;
        if (item == Items.NETHER_STAR) return 12;
        if (item == Items.MAGMA_CREAM) return 6;
        if (item == Items.SPECTRAL_ARROW) return 8;
        return 0;
    }
}
