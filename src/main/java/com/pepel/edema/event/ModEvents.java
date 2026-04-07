package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import com.pepel.edema.item.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PepelEdema.MODID)
public class ModEvents
{
    private static final String BOOK_GIVEN = "pepel_book_given";

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;

        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(BOOK_GIVEN))
        {
            player.addItem(new ItemStack(ModItems.BOOK_OF_EREN.get()));
            data.putBoolean(BOOK_GIVEN, true);
        }
    }
}
