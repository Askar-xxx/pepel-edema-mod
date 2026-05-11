package com.pepel.edema.capability;

import net.minecraft.nbt.CompoundTag;

/**
 * Полученная игроком запись Книги Эрена: id записи, момент получения и importance.
 * Упорядочиваются в BookNotificationsCap по timestamp ASC — старые сверху.
 */
public record ReceivedEntry(String id, long timestamp, Importance importance)
{
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putLong("ts", timestamp);
        t.putString("imp", importance.name());
        return t;
    }

    public static ReceivedEntry fromNbt(CompoundTag t)
    {
        return new ReceivedEntry(
                t.getString("id"),
                t.getLong("ts"),
                Importance.valueOf(t.getString("imp"))
        );
    }
}
