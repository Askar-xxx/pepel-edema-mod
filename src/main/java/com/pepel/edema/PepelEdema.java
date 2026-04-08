package com.pepel.edema;

import com.mojang.logging.LogUtils;
import com.pepel.edema.item.ModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
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
        ModItems.ITEMS.register(context.getModEventBus());
        context.getModEventBus().addListener(this::onBuildCreativeTab);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("Pepel Edema mod loaded");
    }

    private void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES)
            event.accept(ModItems.BOOK_OF_EREN);
    }
}
