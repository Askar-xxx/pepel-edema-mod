package com.pepel.edema.item;

import com.pepel.edema.gui.BookOfErenScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

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
        Minecraft.getInstance().setScreen(new BookOfErenScreen());
    }
}
