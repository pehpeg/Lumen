package com.lumen.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Registers Lumen's mixins (used for dynamic lights) through MixinBooter.
 * If MixinBooter isn't installed this class can't load; Forge skips it and the @Mod's
 * "required-after:mixinbooter" dependency shows a clear missing-mod screen instead.
 */
@IFMLLoadingPlugin.Name("Lumen")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
public class LumenLoadingPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override public List<String> getMixinConfigs() { return Collections.singletonList("mixins.lumen.json"); }

    @Override public String[] getASMTransformerClass() { return new String[0]; }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) { }
    @Override public String getAccessTransformerClass() { return null; }
}
