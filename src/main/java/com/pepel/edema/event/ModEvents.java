package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import com.pepel.edema.item.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
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

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        for (Entity entity : level.getAllEntities())
        {
            if (!StoryNpcSpawner.isHomebound(entity)) continue;
            if (entity.tickCount % 20 != 0) continue;

            var home = StoryNpcSpawner.getHomePos(entity);
            double dx = entity.getX() - (home.getX() + 0.5);
            double dy = entity.getY() - home.getY();
            double dz = entity.getZ() - (home.getZ() + 0.5);
            if (dx * dx + dy * dy + dz * dz <= 9.0) continue;

            entity.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            entity.setDeltaMovement(0.0, 0.0, 0.0);
        }
    }
}
