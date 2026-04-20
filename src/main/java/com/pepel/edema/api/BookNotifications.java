package com.pepel.edema.api;

import com.pepel.edema.capability.BookNotificationsProvider;
import com.pepel.edema.capability.Importance;
import com.pepel.edema.network.PepelNetwork;
import com.pepel.edema.network.packet.BookNotifyPacket;
import net.minecraft.server.level.ServerPlayer;

public final class BookNotifications
{
    private BookNotifications() {}

    public static void notify(ServerPlayer player, String entryId, Importance importance)
    {
        player.getCapability(BookNotificationsProvider.CAP).ifPresent(cap -> {
            boolean isNew = cap.add(entryId, importance);
            if (isNew)
                PepelNetwork.sendTo(new BookNotifyPacket(entryId, importance), player);
        });
    }
}
