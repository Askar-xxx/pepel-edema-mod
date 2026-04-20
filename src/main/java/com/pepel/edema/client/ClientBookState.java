package com.pepel.edema.client;

import com.pepel.edema.capability.Importance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public class ClientBookState
{
    public static final Map<String, Importance> unread = new HashMap<>();

    public static void onNotify(String entryId, Importance imp)
    {
        unread.put(entryId, imp);
    }

    public static void onSync(Map<String, Importance> snapshot)
    {
        unread.clear();
        unread.putAll(snapshot);
    }

    public static void clear()
    {
        unread.clear();
    }

    public static boolean isEmpty()
    {
        return unread.isEmpty();
    }

    public static Importance highest()
    {
        return unread.values().stream()
                .max(Comparator.comparing(Enum::ordinal))
                .orElse(null);
    }
}
