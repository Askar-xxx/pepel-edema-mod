package com.pepel.edema.client;

import com.pepel.edema.PepelEdema;
import com.pepel.edema.client.render.BookDecorator;
import com.pepel.edema.item.ModItems;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterItemDecorationsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PepelEdema.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetup
{
    @SubscribeEvent
    public static void onRegisterItemDecorations(RegisterItemDecorationsEvent event)
    {
        event.register(ModItems.BOOK_OF_EREN.get(), new BookDecorator());
    }
}
