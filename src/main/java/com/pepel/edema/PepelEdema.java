package com.pepel.edema;

import com.mojang.logging.LogUtils;
import com.pepel.edema.capability.BookNotificationsProvider;
import com.pepel.edema.config.PepelConfig;
import com.pepel.edema.item.ModItems;
import com.pepel.edema.network.PepelNetwork;
import com.pepel.edema.worldgen.ModStructurePieceTypes;
import com.pepel.edema.worldgen.ModStructureTypes;
import com.pepel.edema.worldgen.SpawnHandler;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(PepelEdema.MODID)
public class PepelEdema
{
    public static final String MODID = "pepel";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PepelEdema(FMLJavaModLoadingContext context)
    {
        IEventBus bus = context.getModEventBus();

        ModItems.ITEMS.register(bus);
        ModStructureTypes.STRUCTURE_TYPES.register(bus);
        ModStructurePieceTypes.PIECE_TYPES.register(bus);

        bus.addListener(this::onBuildCreativeTab);
        bus.addListener(BookNotificationsProvider::register);

        PepelNetwork.register();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, PepelConfig.SPEC, "pepel-worldgen.toml");

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(SpawnHandler::onCreateSpawn);
        LOGGER.info("Pepel Edema mod loaded");
    }

    private void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES)
            event.accept(ModItems.BOOK_OF_EREN);
    }
}
