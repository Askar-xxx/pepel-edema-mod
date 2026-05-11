package com.pepel.edema.network.packet;

import com.pepel.edema.capability.Importance;
import com.pepel.edema.capability.ReceivedEntry;
import com.pepel.edema.client.ClientBookState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server → client. Полная синхронизация состояния Книги Эрена для игрока.
 * Шлётся при login и теоретически при любых других случаях когда capability
 * могла десинхронизироваться. Содержит и unread (для мерцания/подсветки),
 * и received (для отображения списка во вкладке "Записи").
 */
public class BookSyncPacket
{
    private final Map<String, Importance> unread;
    private final List<ReceivedEntry> received;

    public BookSyncPacket(Map<String, Importance> unread, List<ReceivedEntry> received)
    {
        this.unread = unread;
        this.received = received;
    }

    public static void encode(BookSyncPacket pkt, FriendlyByteBuf buf)
    {
        buf.writeVarInt(pkt.unread.size());
        for (Map.Entry<String, Importance> e : pkt.unread.entrySet())
        {
            buf.writeUtf(e.getKey());
            buf.writeEnum(e.getValue());
        }
        buf.writeVarInt(pkt.received.size());
        for (ReceivedEntry r : pkt.received)
        {
            buf.writeUtf(r.id());
            buf.writeLong(r.timestamp());
            buf.writeEnum(r.importance());
        }
    }

    public static BookSyncPacket decode(FriendlyByteBuf buf)
    {
        int unreadN = buf.readVarInt();
        Map<String, Importance> unread = new HashMap<>();
        for (int i = 0; i < unreadN; i++)
        {
            String id = buf.readUtf();
            Importance imp = buf.readEnum(Importance.class);
            unread.put(id, imp);
        }
        int receivedN = buf.readVarInt();
        List<ReceivedEntry> received = new ArrayList<>(receivedN);
        for (int i = 0; i < receivedN; i++)
        {
            String id = buf.readUtf();
            long ts = buf.readLong();
            Importance imp = buf.readEnum(Importance.class);
            received.add(new ReceivedEntry(id, ts, imp));
        }
        return new BookSyncPacket(unread, received);
    }

    public static void handle(BookSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSup)
    {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        ClientBookState.onSync(pkt.unread, pkt.received)
                )
        );
        ctx.setPacketHandled(true);
    }
}
