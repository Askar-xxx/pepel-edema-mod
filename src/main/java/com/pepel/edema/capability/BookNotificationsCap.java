package com.pepel.edema.capability;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.util.INBTSerializable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Capability на игрока: что у него в Книге Эрена.
 *
 * - {@code received} — упорядоченный список всех когда-либо полученных записей.
 *   Никогда не уменьшается. Источник для вкладки "Записи" в GUI.
 * - {@code unread} — подмножество received, ещё не открытых игроком. Источник
 *   для мерцания книги, тоста и сортировки в GUI (подсветка).
 *
 * Прочитанные записи остаются в received навсегда — игрок может перечитать.
 */
public class BookNotificationsCap implements INBTSerializable<CompoundTag>
{
    private final Map<String, Importance> unread = new HashMap<>();
    private final List<ReceivedEntry> received = new ArrayList<>();
    private final Set<String> receivedIds = new HashSet<>();

    /**
     * Добавляет запись. Если запись уже была получена ранее — только обновляет
     * importance в unread (на случай если игрок ещё не открыл). Возвращает true
     * только когда это новая для игрока запись и стоит слать BookNotifyPacket.
     */
    public boolean add(String entryId, Importance imp, long timestamp)
    {
        if (receivedIds.contains(entryId))
        {
            // Уже получали — apdate только unread, на случай повышения importance
            unread.put(entryId, imp);
            return false;
        }
        receivedIds.add(entryId);
        received.add(new ReceivedEntry(entryId, timestamp, imp));
        unread.put(entryId, imp);
        return true;
    }

    /** Удаляет из unread (игрок открыл). В received остаётся — для перечитывания. */
    public boolean remove(String entryId)
    {
        return unread.remove(entryId) != null;
    }

    public Map<String, Importance> snapshot()
    {
        return new HashMap<>(unread);
    }

    public List<ReceivedEntry> receivedSnapshot()
    {
        return new ArrayList<>(received);
    }

    public Importance highest()
    {
        return unread.values().stream()
                .max(Comparator.comparing(Enum::ordinal))
                .orElse(null);
    }

    public boolean isEmpty()
    {
        return unread.isEmpty() && received.isEmpty();
    }

    public boolean hasUnread()
    {
        return !unread.isEmpty();
    }

    public void copyFrom(BookNotificationsCap other)
    {
        unread.clear();
        unread.putAll(other.unread);
        received.clear();
        received.addAll(other.received);
        receivedIds.clear();
        receivedIds.addAll(other.receivedIds);
    }

    @Override
    public CompoundTag serializeNBT()
    {
        CompoundTag tag = new CompoundTag();

        ListTag unreadList = new ListTag();
        for (Map.Entry<String, Importance> e : unread.entrySet())
        {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", e.getKey());
            entry.putString("imp", e.getValue().name());
            unreadList.add(entry);
        }
        tag.put("unread", unreadList);

        ListTag receivedList = new ListTag();
        for (ReceivedEntry r : received)
        {
            receivedList.add(r.toNbt());
        }
        tag.put("received", receivedList);

        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag)
    {
        unread.clear();
        received.clear();
        receivedIds.clear();

        ListTag unreadList = tag.getList("unread", Tag.TAG_COMPOUND);
        for (int i = 0; i < unreadList.size(); i++)
        {
            CompoundTag entry = unreadList.getCompound(i);
            unread.put(entry.getString("id"), Importance.valueOf(entry.getString("imp")));
        }

        ListTag receivedList = tag.getList("received", Tag.TAG_COMPOUND);
        for (int i = 0; i < receivedList.size(); i++)
        {
            ReceivedEntry r = ReceivedEntry.fromNbt(receivedList.getCompound(i));
            received.add(r);
            receivedIds.add(r.id());
        }
    }
}
