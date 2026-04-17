package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Пишет эталонные JSON-диалоги рыбака в папку мира ДО старта Custom NPC.
 *
 * Причина существования: Custom NPC сканирует customnpcs/dialogs/ один раз при старте сервера
 * (в ServerStartingEvent/позже). Если положить файлы позже (например при первом спавне рыбака),
 * они подхватятся только после перезахода в мир.
 */
@Mod.EventBusSubscriber(modid = PepelEdema.MODID)
public class FishermanDialogsLoader
{
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event)
    {
        FishermanDialogs.write(event.getServer());
    }
}
