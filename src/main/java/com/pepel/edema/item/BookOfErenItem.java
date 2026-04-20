package com.pepel.edema.item;

import com.pepel.edema.client.ClientBookState;
import com.pepel.edema.gui.BookOfErenScreen;
import com.pepel.edema.network.PepelNetwork;
import com.pepel.edema.network.packet.MarkReadPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashSet;

public class BookOfErenItem extends Item
{
    public BookOfErenItem()
    {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        if (level.isClientSide)
            openScreen();
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @OnlyIn(Dist.CLIENT)
    private void openScreen()
    {
        for (String entryId : new HashSet<>(ClientBookState.unread.keySet()))
            PepelNetwork.sendToServer(new MarkReadPacket(entryId));
        ClientBookState.clear();
        Minecraft.getInstance().setScreen(new BookOfErenScreen());
    }
}
