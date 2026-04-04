package com.pepel.edema;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(PepelEdema.MODID)
public class PepelEdema
{
    public static final String MODID = "pepel";
    private static final Logger LOGGER = LogUtils.getLogger();

    public PepelEdema(FMLJavaModLoadingContext context)
    {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("Пепел Эдема загружен");
    }
}
