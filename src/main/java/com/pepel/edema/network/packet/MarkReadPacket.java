package com.pepel.edema.network.packet;

import com.pepel.edema.capability.BookNotificationsProvider;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class MarkReadPacket
{
    private final String entryId;

    public MarkReadPacket(String entryId)
    {
        this.entryId = entryId;
    }

    public static void encode(MarkReadPacket pkt, FriendlyByteBuf buf)
    {
        buf.writeUtf(pkt.entryId);
    }

    public static MarkReadPacket decode(FriendlyByteBuf buf)
    {
        return new MarkReadPacket(buf.readUtf());
    }

    public static void handle(MarkReadPacket pkt, Supplier<NetworkEvent.Context> ctxSup)
    {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            player.getCapability(BookNotificationsProvider.CAP).ifPresent(cap ->
                    cap.remove(pkt.entryId)
            );
        });
        ctx.setPacketHandled(true);
    }
}
