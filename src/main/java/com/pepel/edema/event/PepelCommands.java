package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import com.pepel.edema.worldgen.EastRuinPlacement;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
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

        event.getDispatcher().register(
                Commands.literal("pepel_f0_start")
                        .executes(ctx -> startFishermanF0(ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> startFishermanF0(EntityArgument.getPlayer(ctx, "player"))))
        );

        event.getDispatcher().register(
                Commands.literal("pepel_m0_start")
                        .executes(ctx -> startMartaM0(ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> startMartaM0(EntityArgument.getPlayer(ctx, "player"))))
        );

        event.getDispatcher().register(
                Commands.literal("pepel_m0_talk")
                        .then(Commands.literal("lia")
                                .executes(ctx -> completeMartaM0Talk(
                                        ctx.getSource().getPlayerOrException(),
                                        "lia"))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> completeMartaM0Talk(
                                                EntityArgument.getPlayer(ctx, "player"),
                                                "lia"))))
                        .then(Commands.literal("ivar")
                                .executes(ctx -> completeMartaM0Talk(
                                        ctx.getSource().getPlayerOrException(),
                                        "ivar"))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> completeMartaM0Talk(
                                                EntityArgument.getPlayer(ctx, "player"),
                                                "ivar"))))
        );

        event.getDispatcher().register(
                Commands.literal("pepel_spawn_east_ruin")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> spawnEastRuinForCommandSource(ctx))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> EastRuinPlacement.placeAfterM0(EntityArgument.getPlayer(ctx, "player")) ? 1 : 0))
        );
    }

    private static int spawnEastRuinForCommandSource(CommandContext<CommandSourceStack> ctx)
    {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
        {
            player = nearestPlayer(ctx.getSource());
        }
        if (player == null)
        {
            PepelEdema.LOGGER.warn("[east-ruin] pepel_spawn_east_ruin called without a player source and no nearby player was found");
            return 0;
        }
        return EastRuinPlacement.placeAfterM0(player) ? 1 : 0;
    }

    private static ServerPlayer nearestPlayer(CommandSourceStack source)
    {
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        ServerPlayer nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players())
        {
            double dist = player.distanceToSqr(pos);
            if (dist < nearestDist)
            {
                nearest = player;
                nearestDist = dist;
            }
        }
        return nearest;
    }

    private static int startFishermanF0(ServerPlayer player)
    {
        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(ModEvents.FISHERMAN_F0_STARTED))
        {
            data.putBoolean(ModEvents.FISHERMAN_F0_STARTED, true);
            player.displayClientMessage(Component.literal("§eКвест начат: Найти Приют"), false);
            PepelEdema.LOGGER.info("[quests] F0 started for {}", player.getGameProfile().getName());
        }
        return 1;
    }

    private static int startMartaM0(ServerPlayer player)
    {
        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(ModEvents.MARTA_M0_STARTED))
        {
            if (ModEvents.hasFishermanNote(player))
            {
                player.displayClientMessage(Component.literal("§7Марта: Рыбак, значит. Третий за этот год. Заходи."), false);
            }
            else
            {
                player.displayClientMessage(Component.literal("§7Марта: Сам пришёл? Странно, сюда Рыбак обычно направляет. Ну заходи, мир маленький."), false);
            }
            data.putBoolean(ModEvents.MARTA_M0_STARTED, true);
            player.displayClientMessage(Component.literal("§eКвест начат: Познакомься с соседями"), false);
            PepelEdema.LOGGER.info("[quests] M0 started for {}", player.getGameProfile().getName());
        }
        return 1;
    }

    private static int completeMartaM0Talk(ServerPlayer player, String npcId)
    {
        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(ModEvents.MARTA_M0_STARTED))
        {
            player.displayClientMessage(Component.literal("§7Сначала поговори с Мартой."), false);
            return 0;
        }

        String flag;
        String taskId;
        if ("lia".equals(npcId))
        {
            flag = ModEvents.MARTA_M0_LIA_COMPLETED;
            taskId = ModEvents.MARTA_M0_LIA_TASK_ID;
        }
        else
        {
            flag = ModEvents.MARTA_M0_IVAR_COMPLETED;
            taskId = ModEvents.MARTA_M0_IVAR_TASK_ID;
        }

        if (data.getBoolean(flag))
        {
            if (data.getBoolean(ModEvents.MARTA_M0_LIA_COMPLETED)
                    && data.getBoolean(ModEvents.MARTA_M0_IVAR_COMPLETED))
            {
                ModEvents.setScore(player, ModEvents.MARTA_M0_DONE_SCOREBOARD, 1);
            }
            return 1;
        }

        ServerLevel level = player.serverLevel();
        int result = ModEvents.completeFtbQuestTask(level, player, taskId);
        if (result > 0)
        {
            data.putBoolean(flag, true);
            String npcName = "lia".equals(npcId) ? "Лией" : "Иваром";
            player.displayClientMessage(Component.literal("§eЗадача обновлена: поговорить с " + npcName), false);
            PepelEdema.LOGGER.info("[quests] M0 {} task completed for {}", npcId, player.getGameProfile().getName());
            if (data.getBoolean(ModEvents.MARTA_M0_LIA_COMPLETED)
                    && data.getBoolean(ModEvents.MARTA_M0_IVAR_COMPLETED))
            {
                ModEvents.setScore(player, ModEvents.MARTA_M0_DONE_SCOREBOARD, 1);
            }
        }
        else
        {
            player.displayClientMessage(Component.literal("§cНе удалось обновить задачу FTB Quests. Проверь, что квесты перезагружены."), false);
        }
        return result;
    }
}
