package com.lumen;

import com.lumen.client.LumenAutoTest;
import com.lumen.client.LumenClient;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

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

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LumenClient.init();
        if (LumenAutoTest.isEnabled()) MinecraftForge.EVENT_BUS.register(new LumenAutoTest());
    }
}
