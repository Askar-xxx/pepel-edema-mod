package com.pepel.edema.client;

import com.pepel.edema.capability.Importance;
import com.pepel.edema.capability.ReceivedEntry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class ClientBookState
{
    public static final Map<String, Importance> unread = new HashMap<>();
    public static final List<ReceivedEntry> received = new ArrayList<>();
    private static final Set<String> receivedIds = new HashSet<>();

    public static void onNotify(String entryId, Importance imp, long timestamp)
    {
        unread.put(entryId, imp);
        if (!receivedIds.contains(entryId))
        {
            receivedIds.add(entryId);
            received.add(new ReceivedEntry(entryId, timestamp, imp));
        }
    }

    public static void onSync(Map<String, Importance> unreadSnapshot,
                              List<ReceivedEntry> receivedSnapshot)
    {
        unread.clear();
        unread.putAll(unreadSnapshot);
        received.clear();
        receivedIds.clear();
        received.addAll(receivedSnapshot);
        for (ReceivedEntry r : receivedSnapshot) receivedIds.add(r.id());
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
