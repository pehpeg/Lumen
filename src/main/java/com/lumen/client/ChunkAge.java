package com.lumen.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * How long each chunk has been loaded on the client. Freshly arrived chunks aren't drawn by the
 * chunk renderer yet; Lumen's layers wait a moment there, so no light floats over missing ground.
 */
public final class ChunkAge {
    private static final Map<Long, Long> LOADED = new HashMap<>();
    private static final int GRACE_TICKS = 30;

    private static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    private static long now() { return Minecraft.getMinecraft().world == null ? 0 : Minecraft.getMinecraft().world.getTotalWorldTime(); }

    @SubscribeEvent
    public void onLoad(ChunkEvent.Load e) {
        if (!e.getWorld().isRemote) return;
        LOADED.put(key(e.getChunk().x, e.getChunk().z), now());
    }

    @SubscribeEvent
    public void onUnload(ChunkEvent.Unload e) {
        if (!e.getWorld().isRemote) return;
        LOADED.remove(key(e.getChunk().x, e.getChunk().z));
    }

    static void clear() { LOADED.clear(); }

    /** The chunk is loaded and has had time to be drawn (unknown chunks count as ready). */
    static boolean ready(int cx, int cz) {
        Long t = LOADED.get(key(cx, cz));
        if (t == null) return true;
        long d = now() - t;
        return d < 0 || d >= GRACE_TICKS;
    }
}
