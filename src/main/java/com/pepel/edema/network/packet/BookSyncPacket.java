package com.pepel.edema.network.packet;

import com.pepel.edema.capability.Importance;
import com.pepel.edema.client.ClientBookState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class BookSyncPacket
{
    private final Map<String, Importance> snapshot;

    public BookSyncPacket(Map<String, Importance> snapshot)
    {
        this.snapshot = snapshot;
    }

    public static void encode(BookSyncPacket pkt, FriendlyByteBuf buf)
    {
        buf.writeVarInt(pkt.snapshot.size());
        for (Map.Entry<String, Importance> e : pkt.snapshot.entrySet())
        {
            buf.writeUtf(e.getKey());
            buf.writeEnum(e.getValue());
        }
    }

    public static BookSyncPacket decode(FriendlyByteBuf buf)
    {
        int n = buf.readVarInt();
        Map<String, Importance> map = new HashMap<>();
        for (int i = 0; i < n; i++)
        {
            String id = buf.readUtf();
            Importance imp = buf.readEnum(Importance.class);
            map.put(id, imp);
        }
        return new BookSyncPacket(map);
    }

    public static void handle(BookSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSup)
    {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        ClientBookState.onSync(pkt.snapshot)
                )
        );
        ctx.setPacketHandled(true);
    }
}
