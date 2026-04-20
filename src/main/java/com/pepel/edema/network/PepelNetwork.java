package com.pepel.edema.network;

import com.pepel.edema.PepelEdema;
import com.pepel.edema.network.packet.BookNotifyPacket;
import com.pepel.edema.network.packet.BookSyncPacket;
import com.pepel.edema.network.packet.MarkReadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class PepelNetwork
{
    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(PepelEdema.MODID, "main"),
            () -> VERSION,
            VERSION::equals,
            VERSION::equals
    );

    private static int nextId = 0;

    public static void register()
    {
        CHANNEL.messageBuilder(BookNotifyPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BookNotifyPacket::encode)
                .decoder(BookNotifyPacket::decode)
                .consumerMainThread(BookNotifyPacket::handle)
                .add();

        CHANNEL.messageBuilder(MarkReadPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MarkReadPacket::encode)
                .decoder(MarkReadPacket::decode)
                .consumerMainThread(MarkReadPacket::handle)
                .add();

        CHANNEL.messageBuilder(BookSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BookSyncPacket::encode)
                .decoder(BookSyncPacket::decode)
                .consumerMainThread(BookSyncPacket::handle)
                .add();
    }

    public static void sendTo(Object packet, ServerPlayer player)
    {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendToServer(Object packet)
    {
        CHANNEL.sendToServer(packet);
    }
}
