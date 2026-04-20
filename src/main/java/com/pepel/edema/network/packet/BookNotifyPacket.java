package com.pepel.edema.network.packet;

import com.pepel.edema.capability.Importance;
import com.pepel.edema.client.ClientBookState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class BookNotifyPacket
{
    private final String entryId;
    private final Importance importance;

    public BookNotifyPacket(String entryId, Importance importance)
    {
        this.entryId = entryId;
        this.importance = importance;
    }

    public static void encode(BookNotifyPacket pkt, FriendlyByteBuf buf)
    {
        buf.writeUtf(pkt.entryId);
        buf.writeEnum(pkt.importance);
    }

    public static BookNotifyPacket decode(FriendlyByteBuf buf)
    {
        return new BookNotifyPacket(buf.readUtf(), buf.readEnum(Importance.class));
    }

    public static void handle(BookNotifyPacket pkt, Supplier<NetworkEvent.Context> ctxSup)
    {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        ClientBookState.onNotify(pkt.entryId, pkt.importance)
                )
        );
        ctx.setPacketHandled(true);
    }
}
