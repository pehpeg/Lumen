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
    public static double fogStart = 0.55;

    @Config.Comment("Held torches, dropped glowing items, burning mobs, blazes and fireballs light up the area (needs MixinBooter)")
    public static boolean dynamicLights = true;
    @Config.Comment("How often moving lights update: SMOOTH (every tick), FAST (every 2 ticks), FASTEST (every 5 ticks, least CPU)")
    public static DynamicSpeed dynamicLightsSpeed = DynamicSpeed.FAST;
    public enum DynamicSpeed { SMOOTH, FAST, FASTEST }

    @Config.Comment("Real cast shadows: trees, buildings and cliffs block the sun (traced through blocks when chunks are built, needs MixinBooter)")
    public static boolean sunShadows = true;
    @Config.Comment("How dark full shadow is (0 = none, 1 = deep)")
    @Config.RangeDouble(min = 0, max = 1)
    public static double shadowStrength = 0.8;
    @Config.Comment("How far (in blocks) toward the sun to look for shadow casters. Longer = longer evening shadows, slower chunk building")
    @Config.RangeInt(min = 8, max = 64)
    public static int shadowDistance = 24;
    @Config.Comment("Shadows move when the sun has moved this many degrees (smaller = smoother, more chunk rebuilding)")
    @Config.RangeDouble(min = 2, max = 30)
    public static double shadowUpdateDegrees = 6;
    @Config.Comment("Chunk columns re-meshed per tick when shadows move (higher = faster update, more CPU)")
    @Config.RangeInt(min = 1, max = 64)
    public static int shadowRebuildRate = 6;
    @Config.Comment("Shade lit only by the sky gets a cool blue tint, sunlit areas stay warm")
    @Config.RangeDouble(min = 0, max = 1)
    public static double shadeTint = 0.7;

    @Config.Comment("Lens flare when looking toward the sun (hidden when the sun is blocked)")
    public static boolean lensFlare = true;

    @Config.Comment("Soft glow halos around nearby torches, lava, glowstone, fire and other lights (fake bloom)")
    public static boolean blockGlow = true;
    @Config.RangeDouble(min = 0, max = 2)
    public static double blockGlowStrength = 1.0;
    @Config.Comment("Max number of halos drawn at once")
    @Config.RangeInt(min = 8, max = 256)
    public static int blockGlowMaxLights = 64;

    @Config.Comment("Ores glow in the dark (masks are generated automatically, works with modded ores)")
    public static boolean emissiveOres = true;
    @Config.Comment("Ore blocks (registry names, or just the path like 'coal_ore') that should not glow")
    public static String[] emissiveOreExclude = {"coal_ore"};

    @Config.Comment("Fireflies over grass on warm nights")
    public static boolean fireflies = true;
    @Config.Comment("Leaves drifting down from trees")
    public static boolean fallingLeaves = true;
    @Config.Comment("Dust motes floating in caves")
    public static boolean caveDust = true;

    @Config.Comment("Per-biome haze: green swamps, warm deserts, cold snowy biomes... blended as you walk")
    public static boolean biomeAtmosphere = true;
    @Config.RangeDouble(min = 0, max = 1)
    public static double biomeAtmosphereStrength = 0.8;

    @Config.Comment("Occasional shooting stars on clear nights")
    public static boolean shootingStars = true;

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
