package com.pepel.edema.api;

import com.pepel.edema.PepelEdema;
import com.pepel.edema.book.BookEntries;
import com.pepel.edema.capability.BookNotificationsProvider;
import com.pepel.edema.capability.Importance;
import com.pepel.edema.network.PepelNetwork;
import com.pepel.edema.network.packet.BookNotifyPacket;
import net.minecraft.server.level.ServerPlayer;

public final class BookNotifications
{
    private BookNotifications() {}

    /**
     * Триггерит запись по id, importance берётся из реестра BookEntries.
     * Безопасно к опечаткам: если id нет в реестре — лог и выход без эффекта.
     */
    public static void notify(ServerPlayer player, String entryId)
    {
        BookEntries.Def def = BookEntries.get(entryId);
        if (def == null)
        {
            PepelEdema.LOGGER.warn("[book] notify: неизвестная запись '{}', ничего не делаю", entryId);
            return;
        }
        notify(player, entryId, def.importance());
    }

    /**
     * Триггерит запись с явной importance (используется командой
     * {@code /pepel book notify} и тестами).
     */
    public static void notify(ServerPlayer player, String entryId, Importance importance)
    {
        long timestamp = player.serverLevel().getGameTime();
        player.getCapability(BookNotificationsProvider.CAP).ifPresent(cap -> {
            boolean isNew = cap.add(entryId, importance, timestamp);
            if (isNew)
                PepelNetwork.sendTo(new BookNotifyPacket(entryId, importance, timestamp), player);
        });
    }
}
