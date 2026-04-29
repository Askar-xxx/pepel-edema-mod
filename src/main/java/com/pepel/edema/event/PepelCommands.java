package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Отладочные команды мода. */
@Mod.EventBusSubscriber(modid = PepelEdema.MODID)
public class PepelCommands
{
    private static final String FISHERMAN_FLAG = "pepel_fisherman_spawned";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(
                Commands.literal("pepel_reset_fisherman")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            CompoundTag data = player.getPersistentData();
                            data.putBoolean(FISHERMAN_FLAG, false);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("§aФлаг рыбака сброшен. Отойди за 100 блоков от мирового спавна — появится заново."),
                                    false
                            );
                            PepelEdema.LOGGER.info("Флаг {} сброшен у {}", FISHERMAN_FLAG, player.getName().getString());
                            return 1;
                        })
        );

        event.getDispatcher().register(
                Commands.literal("pepel_spawn_npc")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("ivar")
                                .executes(ctx -> {
                                    StoryNpcSpawner.spawnIvarNear(ctx.getSource().getPlayerOrException());
                                    return 1;
                                }))
                        .then(Commands.literal("lia")
                                .executes(ctx -> {
                                    StoryNpcSpawner.spawnLiaNear(ctx.getSource().getPlayerOrException());
                                    return 1;
                                }))
                        .then(Commands.literal("marta")
                                .executes(ctx -> {
                                    StoryNpcSpawner.spawnMartaNear(ctx.getSource().getPlayerOrException());
                                    return 1;
                                }))
        );
    }
}
