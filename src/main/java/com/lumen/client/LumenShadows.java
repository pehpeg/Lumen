package com.lumen.client;

import com.lumen.LumenConfig;
import com.lumen.core.SunShadowEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the block-traced sun shadows: snaps the sun direction to steps, and when the step
 * changes (or shadows switch on/off at dawn, dusk or in rain) re-meshes the loaded chunks a few
 * columns per tick, nearest first, so the shadows move without a frame-time spike.
 */
public class LumenShadows {

    private final Minecraft mc = Minecraft.getMinecraft();
    private int currentKey = Integer.MIN_VALUE;
    private int stamp;
    private final List<int[]> sweep = new ArrayList<>();
    private int sweepIndex;
    public static int statStep, statPending;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        World world = mc.world;
        if (world == null || mc.player == null) {
            currentKey = Integer.MIN_VALUE; sweep.clear();
            SunShadowEngine.publish(new SunShadowEngine.Settings(false, 0, 1, 0, 0, 0, ++stamp));
            return;
        }

        // sun direction, snapped to steps (same convention as the vanilla sky renderer)
        float step = (float) LumenConfig.shadowUpdateDegrees;
        float deg = world.getCelestialAngle(1f) * 360f;
        int index = Math.round(deg / step);
        double rad = Math.toRadians(index * step);
        double dx = -Math.sin(rad), dy = Math.cos(rad);
        boolean active = LumenConfig.enabled && LumenConfig.sunShadows && world.provider.isSurfaceWorld()
                && dy > 0.12 && world.getRainStrength(1f) < 0.3f;
        int key = active ? index : -1;

        if (key != currentKey) {
            currentKey = key;
            stamp++;
            // a slight tilt toward +Z so shadows of north-south walls aren't perfectly edge-on
            double tz = 0.12, len = Math.sqrt(dx * dx + dy * dy + tz * tz);
            SunShadowEngine.publish(new SunShadowEngine.Settings(active, dx / len, dy / len, tz / len,
                    6.0 * LumenConfig.shadowStrength, LumenConfig.shadowDistance, stamp));
            statStep = key;
            startSweep();
        }

        // re-mesh a few chunk columns per tick, nearest first
        int budget = LumenConfig.shadowRebuildRate;
        while (budget-- > 0 && sweepIndex < sweep.size()) {
            int[] c = sweep.get(sweepIndex++);
            mc.renderGlobal.markBlockRangeForRenderUpdate(c[0] << 4, 0, c[1] << 4, (c[0] << 4) + 15, 255, (c[1] << 4) + 15);
        }
        statPending = sweep.size() - sweepIndex;
    }

    private void startSweep() {
        sweep.clear();
        sweepIndex = 0;
        int r = mc.gameSettings.renderDistanceChunks + 1;
        int pcx = mc.player.chunkCoordX, pcz = mc.player.chunkCoordZ;
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++)
                if (x * x + z * z <= r * r) sweep.add(new int[]{pcx + x, pcz + z});
        sweep.sort((a, b) -> Integer.compare(
                (a[0] - pcx) * (a[0] - pcx) + (a[1] - pcz) * (a[1] - pcz),
                (b[0] - pcx) * (b[0] - pcx) + (b[1] - pcz) * (b[1] - pcz)));
    }
}
