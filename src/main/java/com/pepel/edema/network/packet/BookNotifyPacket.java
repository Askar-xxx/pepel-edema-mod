package com.pepel.edema.network.packet;

import com.pepel.edema.capability.Importance;
import com.pepel.edema.client.ClientBookState;
import com.pepel.edema.client.toast.BookToast;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → client. Уведомление о новой записи в Книге Эрена.
 * Тост, звук и добавление в локальный received-список с timestamp.
 */
public class BookNotifyPacket
{
    private final String entryId;
    private final Importance importance;
    private final long timestamp;

    public BookNotifyPacket(String entryId, Importance importance, long timestamp)
    {
        this.entryId = entryId;
        this.importance = importance;
        this.timestamp = timestamp;
    }

    public static void encode(BookNotifyPacket pkt, FriendlyByteBuf buf)
    {
        buf.writeUtf(pkt.entryId);
        buf.writeEnum(pkt.importance);
        buf.writeLong(pkt.timestamp);
    }

    public static BookNotifyPacket decode(FriendlyByteBuf buf)
    {
        return new BookNotifyPacket(
                buf.readUtf(),
                buf.readEnum(Importance.class),
                buf.readLong());
    }

    public static void handle(BookNotifyPacket pkt, Supplier<NetworkEvent.Context> ctxSup)
    {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                    ClientBookState.onNotify(pkt.entryId, pkt.importance, pkt.timestamp);
                    Minecraft.getInstance().getToasts().addToast(new BookToast(pkt.entryId, pkt.importance));
                    if (Minecraft.getInstance().player != null)
                    {
                        Minecraft.getInstance().player.playSound(
                                pkt.importance == Importance.KEY
                                        ? SoundEvents.END_PORTAL_FRAME_FILL
                                        : SoundEvents.BOOK_PAGE_TURN,
                                1.0f, 1.0f
                        );
                    }
                })
        );
        ctx.setPacketHandled(true);
    }
}
