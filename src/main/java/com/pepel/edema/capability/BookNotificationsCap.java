package com.pepel.edema.capability;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.util.INBTSerializable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class BookNotificationsCap implements INBTSerializable<CompoundTag>
{
    private final Map<String, Importance> unread = new HashMap<>();

    public boolean add(String entryId, Importance imp)
    {
        Importance prev = unread.put(entryId, imp);
        return prev == null || prev != imp;
    }

    public boolean remove(String entryId)
    {
        return unread.remove(entryId) != null;
    }

    public Map<String, Importance> snapshot()
    {
        return new HashMap<>(unread);
    }

    public Importance highest()
    {
        return unread.values().stream()
                .max(Comparator.comparing(Enum::ordinal))
                .orElse(null);
    }

    public boolean isEmpty()
    {
        return unread.isEmpty();
    }

    public void copyFrom(BookNotificationsCap other)
    {
        unread.clear();
        unread.putAll(other.unread);
    }

    @Override
    public CompoundTag serializeNBT()
    {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Map.Entry<String, Importance> e : unread.entrySet())
        {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", e.getKey());
            entry.putString("imp", e.getValue().name());
            list.add(entry);
        }
        tag.put("unread", list);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag)
    {
        unread.clear();
        ListTag list = tag.getList("unread", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag entry = list.getCompound(i);
            unread.put(entry.getString("id"), Importance.valueOf(entry.getString("imp")));
        }
    }
}
