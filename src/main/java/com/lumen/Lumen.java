package com.lumen;

import com.lumen.client.LumenAtmosphere;
import com.lumen.client.LumenAutoTest;
import com.lumen.client.LumenDynamicLights;
import com.lumen.client.LumenShadows;
import com.lumen.client.LumenParticles;
import com.lumen.client.LumenWorldGlow;
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
     clientSideOnly = true,
     dependencies = "required-after:mixinbooter")
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
        MinecraftForge.EVENT_BUS.register(new LumenAtmosphere());
        MinecraftForge.EVENT_BUS.register(new LumenWorldGlow());
        MinecraftForge.EVENT_BUS.register(new LumenParticles());
        MinecraftForge.EVENT_BUS.register(new LumenDynamicLights());
        MinecraftForge.EVENT_BUS.register(new LumenShadows());
        if (LumenAutoTest.isEnabled()) MinecraftForge.EVENT_BUS.register(new LumenAutoTest());
    }
}
