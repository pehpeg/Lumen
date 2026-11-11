package com.lumen;

import com.lumen.client.LumenLightmap;
import com.lumen.client.LumenRenderer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = Lumen.MODID,
     name = Lumen.NAME,
     version = Lumen.VERSION,
     acceptedMinecraftVersions = "[1.12.2]",
     clientSideOnly = true)
public class Lumen {
    public static final String MODID = "lumen";
    public static final String NAME = "Lumen";
    public static final String VERSION = "1.0.0";

    public static KeyBinding toggleKey;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        toggleKey = new KeyBinding("key.lumen.toggle", Keyboard.KEY_F7, "key.categories.lumen");
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(new LumenRenderer());
        MinecraftForge.EVENT_BUS.register(new LumenLightmap());
    }
}
