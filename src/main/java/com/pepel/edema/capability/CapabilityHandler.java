package com.pepel.edema.capability;

import com.pepel.edema.PepelEdema;
import com.pepel.edema.network.PepelNetwork;
import com.pepel.edema.network.packet.BookSyncPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PepelEdema.MODID)
public class CapabilityHandler
{
    public static final ResourceLocation CAP_ID = new ResourceLocation(PepelEdema.MODID, "book_notifications");

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event)
    {
        if (event.getObject() instanceof Player)
        {
            BookNotificationsProvider provider = new BookNotificationsProvider();
            event.addCapability(CAP_ID, provider);
            event.addListener(provider::invalidate);
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event)
    {
        if (event.getEntity().level().isClientSide) return;
        event.getOriginal().reviveCaps();
        event.getOriginal().getCapability(BookNotificationsProvider.CAP).ifPresent(oldCap ->
                event.getEntity().getCapability(BookNotificationsProvider.CAP).ifPresent(newCap ->
                        newCap.copyFrom(oldCap)
                )
        );
        event.getOriginal().invalidateCaps();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        player.getCapability(BookNotificationsProvider.CAP).ifPresent(cap -> {
            if (!cap.isEmpty())
                PepelNetwork.sendTo(new BookSyncPacket(cap.snapshot(), cap.receivedSnapshot()), player);
        });
    }
}
