package com.lumen;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = Lumen.MODID, name = "lumen")
public class LumenConfig {

    @Config.Comment("Master switch (toggle in-game with K)")
    public static boolean enabled = true;

    @Config.Name("dynamic_lights")
    public static DynamicLights dynamicLights = new DynamicLights();
    @Config.Name("emissive")
    public static Emissive emissive = new Emissive();
    @Config.Name("colored_light")
    public static Colored colored = new Colored();
    @Config.Name("shadows")
    public static Shadows shadows = new Shadows();
    @Config.Name("lighting")
    public static Lighting lighting = new Lighting();

    public static class DynamicLights {
        @Config.Comment("Held torches, dropped glowing items, burning mobs, blazes, fireballs... light up the world.\n"
                + "Uses the game's own light engine, so it works with OptiFine, Nothirium, Vintagium, Celeritas and other renderers.")
        public boolean enabled = true;
        @Config.Comment("Update every N ticks (1 = smoothest, higher = less CPU)")
        @Config.RangeInt(min = 1, max = 10)
        public int updateInterval = 1;
        @Config.Comment("Max distance (blocks) for entities to emit dynamic light")
        @Config.RangeInt(min = 8, max = 128)
        public int range = 48;
        @Config.Comment("Max number of moving lights at once (nearest first)")
        @Config.RangeInt(min = 1, max = 256)
        public int maxSources = 48;
        @Config.Comment("Extra items that give light when held or dropped, as 'modid:item=level' (e.g. 'minecraft:blaze_rod=10').\n"
                + "Any item that places a light-emitting block already works automatically.")
        public String[] extraItems = {
                "minecraft:lava_bucket=15", "minecraft:blaze_rod=10", "minecraft:blaze_powder=8",
                "minecraft:glowstone_dust=8", "minecraft:nether_star=12", "minecraft:magma_cream=6",
                "minecraft:spectral_arrow=8"};
        @Config.Comment("Entities that always glow, as 'modid:entity=level'")
        public String[] glowingEntities = {
                "minecraft:blaze=10", "minecraft:magma_cube=8", "minecraft:fireball=14", "minecraft:small_fireball=12",
                "minecraft:dragon_fireball=12", "minecraft:spectral_arrow=8", "minecraft:tnt=10", "minecraft:xp_orb=4"};
        @Config.Comment("Torches and fire stop giving light underwater")
        public boolean waterSensitive = true;
    }

    public static class Emissive {
        @Config.Comment("Glowing ores and other emissive textures, drawn full-bright")
        public boolean enabled = true;
        @Config.Comment("Use emissive textures from resource packs (OptiFine format: 'diamond_ore_e.png' next to 'diamond_ore.png').\n"
                + "Any block with such a texture glows, not only ores.")
        public boolean resourcePackTextures = true;
        @Config.Comment("For ores without an emissive texture, generate one automatically (the ore bits, compared with stone)")
        public boolean autoOreMasks = true;
        @Config.Comment("Ores that should not get an automatic glow (registry name or just the path)")
        public String[] autoExclude = {"coal_ore"};
        @Config.Comment("Scan radius (blocks) for emissive blocks")
        @Config.RangeInt(min = 8, max = 64)
        public int radius = 32;
        @Config.Comment("Soft brightness pulse")
        public boolean pulse = true;
    }

    public static class Colored {
        @Config.Comment("Colored light: torches warm orange, redstone red, sea lanterns cool, modded lamps in their own color...\n"
                + "Unlisted light blocks get their color from their texture automatically.")
        public boolean enabled = true;
        @Config.Comment("How strongly light colors tint the world (0..1)")
        @Config.RangeDouble(min = 0, max = 1)
        public double strength = 0.65;
        @Config.Comment("Colors as 'modid:block=RRGGBB' (overrides the automatic color)")
        public String[] colors = {
                "minecraft:torch=FF9A40", "minecraft:fire=FF8030", "minecraft:lava=FF6A20", "minecraft:flowing_lava=FF6A20",
                "minecraft:lit_pumpkin=FFA040", "minecraft:lit_furnace=FF9040", "minecraft:magma=FF5020",
                "minecraft:redstone_torch=FF2A15", "minecraft:lit_redstone_ore=FF3020", "minecraft:lit_redstone_lamp=FFC070",
                "minecraft:glowstone=FFD080", "minecraft:sea_lantern=B0E8FF", "minecraft:beacon=C0F0FF",
                "minecraft:end_rod=F0E8FF", "minecraft:portal=A040FF", "minecraft:end_portal=6050FF",
                "minecraft:end_gateway=6050FF", "minecraft:ender_chest=8060FF", "minecraft:brewing_stand=FFE080"};
        @Config.Comment("Scan radius (blocks) for colored light sources")
        @Config.RangeInt(min = 8, max = 64)
        public int radius = 32;
    }

    public static class Shadows {
        @Config.Comment("Sun shadows: trees, buildings and cliffs cast shadows, sides facing away from the sun are shaded.\n"
                + "Traced through the blocks off the main thread; works with any renderer.")
        public boolean enabled = true;
        @Config.Comment("How dark full shadow is (0..1)")
        @Config.RangeDouble(min = 0, max = 1)
        public double strength = 0.55;
        @Config.Comment("Radius in chunks around you that gets shadows")
        @Config.RangeInt(min = 2, max = 16)
        public int radiusChunks = 6;
        @Config.Comment("How far (blocks) toward the sun to look for shadow casters")
        @Config.RangeInt(min = 8, max = 64)
        public int castDistance = 32;
        @Config.Comment("Shadows move when the sun has moved this many degrees")
        @Config.RangeDouble(min = 2, max = 30)
        public double updateDegrees = 5;
    }

    public static class Lighting {
        @Config.Comment("Darker dim areas for more contrast (0 = vanilla)")
        @Config.RangeDouble(min = 0, max = 1)
        public double deepShadows = 0.3;
        @Config.Comment("Blue moonlight at night (0 = vanilla)")
        @Config.RangeDouble(min = 0, max = 1)
        public double moonlight = 0.6;
    }

    @Mod.EventBusSubscriber(modid = Lumen.MODID)
    public static class Sync {
        @SubscribeEvent
        public static void onChanged(ConfigChangedEvent.OnConfigChangedEvent e) {
            if (Lumen.MODID.equals(e.getModID())) {
                ConfigManager.sync(Lumen.MODID, Config.Type.INSTANCE);
                com.lumen.client.LumenClient.onConfigChanged();
            }
        }
    }
}
