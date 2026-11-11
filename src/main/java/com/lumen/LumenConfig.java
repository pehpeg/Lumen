package com.lumen;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = Lumen.MODID, name = "lumen")
public class LumenConfig {

    @Config.Comment("Master switch (also toggled in-game with F7)")
    public static boolean enabled = true;

    @Config.Comment("Recolor the world's lighting: warm torchlight, blue moonlight, deeper shadows (near-zero cost)")
    public static boolean lightmapGrading = true;
    @Config.Comment("How orange torches, lava and glowstone light looks")
    @Config.RangeDouble(min = 0, max = 1)
    public static double torchWarmth = 0.8;
    @Config.Comment("How blue open areas look at night")
    @Config.RangeDouble(min = 0, max = 1)
    public static double moonlightTint = 0.7;
    @Config.Comment("Darker dim areas for more contrast (0 = vanilla)")
    @Config.RangeDouble(min = 0, max = 1)
    public static double deepShadows = 0.35;

    @Config.Comment("Soft glow and light rays around the sun")
    public static boolean sunGlow = true;
    @Config.RangeDouble(min = 0, max = 2)
    public static double sunGlowStrength = 1.0;

    @Config.Comment("Faint blue halo around the moon")
    public static boolean moonGlow = true;

    @Config.Comment("Time-of-day color grading: golden hour, cool nights, underwater tint")
    public static boolean colorGrading = true;
    @Config.RangeDouble(min = 0, max = 1)
    public static double colorGradingStrength = 0.7;

    @Config.Comment("Darkened screen edges")
    public static boolean vignette = true;
    @Config.RangeDouble(min = 0, max = 1)
    public static double vignetteStrength = 0.45;

    @Config.Comment("Atmospheric fog: gradual depth haze tinted by the sky, warm at sunrise/sunset")
    public static boolean atmosphericFog = true;
    @Config.Comment("Where fog starts, as a fraction of render distance (vanilla is ~0.75)")
    @Config.RangeDouble(min = 0.05, max = 0.95)
    public static double fogStart = 0.35;

    @Mod.EventBusSubscriber(modid = Lumen.MODID)
    public static class Sync {
        @SubscribeEvent
        public static void onChanged(ConfigChangedEvent.OnConfigChangedEvent e) {
            if (Lumen.MODID.equals(e.getModID())) {
                ConfigManager.sync(Lumen.MODID, Config.Type.INSTANCE);
            }
        }
    }
}
