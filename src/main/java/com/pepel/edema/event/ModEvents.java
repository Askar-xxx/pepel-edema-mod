package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import com.pepel.edema.api.BookNotifications;
import com.pepel.edema.capability.BookNotificationsProvider;
import com.pepel.edema.item.ModItems;
import com.pepel.edema.worldgen.VillagePriyutState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PepelEdema.MODID)
public class ModEvents
{
    private static final String BOOK_GIVEN = "pepel_book_given";
    private static final String PRIYUT_TITLE_SHOWN = "pepel_priyut_title_shown";
    public static final String FISHERMAN_F0_STARTED = "pepel_fisherman_f0_started";
    public static final String FISHERMAN_F0_COMPLETED = "pepel_fisherman_f0_completed";
    public static final String FISHERMAN_F0_TASK_ID = "7100000000000001";
    public static final String MARTA_NOTE_SCOREBOARD = "pepel_marta_note";
    public static final String MARTA_M0_STARTED = "pepel_marta_m0_started";
    public static final String MARTA_M0_LIA_COMPLETED = "pepel_marta_m0_lia_completed";
    public static final String MARTA_M0_IVAR_COMPLETED = "pepel_marta_m0_ivar_completed";
    public static final String MARTA_M0_LIA_TASK_ID = "7100000000000002";
    public static final String MARTA_M0_IVAR_TASK_ID = "7100000000000003";
    private static final double QUEST_BOARD_USE_RADIUS_SQ = 4.0 * 4.0;

    /**
     * Tick игрока (player.tickCount), на котором появляется первая запись Эрена.
     * Считается с момента когда игрок реально начал тикать в мире (не с login event,
     * который fires во время loading screen клиента). 100 тиков = 5 секунд после
     * того как игрок увидит мир.
     */
    private static final int AWAKENING_TRIGGER_TICK = 100;

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
        // Триггер p0_awakening теперь в onPlayerTick по player.tickCount —
        // login event fires до завершения loading screen, поэтому ставить
        // delay относительно login = игрок видит уведомление мгновенно после
        // загрузки чанков. По tickCount задержка отсчитывается от реального
        // момента когда игрок начал двигаться в мире.
    }

    private static void sendBookUpdatedSubtitle(ServerPlayer player)
    {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 60, 25));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("[ Книга Эрена обновилась... ]")));
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

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 != 0) return;
        if (player.level().dimension() != Level.OVERWORLD) return;

        // p0_awakening — на 100-м тике игрока (5 сек после реального появления
        // в мире). Если запись уже была получена в прошлой сессии — пропускаем.
        // Не используем persistent-флаг: при выходе в первые 5 сек игрок просто
        // получит запись при следующем входе на 100-м тике.
        if (player.tickCount == AWAKENING_TRIGGER_TICK)
        {
            boolean alreadyHas = player.getCapability(BookNotificationsProvider.CAP)
                    .map(cap -> cap.receivedSnapshot().stream()
                            .anyMatch(r -> "p0_awakening".equals(r.id())))
                    .orElse(false);
            if (!alreadyHas)
            {
                BookNotifications.notify(player, "p0_awakening");
                sendBookUpdatedSubtitle(player);
            }
        }

        syncMartaNoteScore(player);

        CompoundTag data = player.getPersistentData();
        ServerLevel level = (ServerLevel) player.level();
        VillagePriyutState state = VillagePriyutState.get(level);
        BlockPos pivot = state.getPivot();
        if (!state.isSpawned() || pivot == null) return;

        int radius = state.getEnterRadius();
        double dx = player.getX() - (pivot.getX() + 0.5);
        double dz = player.getZ() - (pivot.getZ() + 0.5);
        if (dx * dx + dz * dz > (double) radius * radius) return;

        if (!data.getBoolean(PRIYUT_TITLE_SHOWN))
        {
            data.putBoolean(PRIYUT_TITLE_SHOWN, true);
            playPriyutTitle(level, pivot, player);
            // Один триггер — два эффекта: RPG-надпись + запись в Книге Эрена.
            // Свой subtitle не шлём — он бы перебил "Здесь ещё держатся люди"
            // от playPriyutTitle. Тост от BookNotifyPacket появляется в углу,
            // его достаточно для индикации "обновилась книга".
            BookNotifications.notify(player, "p1_first_people");
        }

        if (data.getBoolean(FISHERMAN_F0_STARTED) && !data.getBoolean(FISHERMAN_F0_COMPLETED))
        {
            completeFishermanF0(level, player, data);
        }
    }

    private static void playPriyutTitle(ServerLevel level, BlockPos pivot, ServerPlayer player)
    {
        level.playSound(null, pivot, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.AMBIENT, 0.8f, 0.9f);
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 25));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("ДЕРЕВНЯ ПРИЮТ")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("Здесь ещё держатся люди")));
    }

    private static void completeFishermanF0(ServerLevel level, ServerPlayer player, CompoundTag data)
    {
        int result = completeFtbQuestTask(level, player, FISHERMAN_F0_TASK_ID);
        if (result > 0)
        {
            data.putBoolean(FISHERMAN_F0_COMPLETED, true);
        }
    }

    public static int completeFtbQuestTask(ServerLevel level, ServerPlayer player, String taskId)
    {
        return level.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(2),
                "ftbquests change_progress " + player.getGameProfile().getName()
                        + " complete " + taskId);
    }

    public static boolean hasFishermanNote(Player player)
    {
        for (ItemStack stack : player.getInventory().items)
        {
            if (isFishermanNote(stack))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isFishermanNote(ItemStack stack)
    {
        return stack.is(Items.PAPER)
                && stack.hasCustomHoverName()
                && "Рыбак прислал".equals(stack.getHoverName().getString());
    }

    private static void syncMartaNoteScore(ServerPlayer player)
    {
        Scoreboard scoreboard = player.serverLevel().getScoreboard();
        Objective objective = scoreboard.getObjective(MARTA_NOTE_SCOREBOARD);
        if (objective == null)
        {
            objective = scoreboard.addObjective(
                    MARTA_NOTE_SCOREBOARD,
                    ObjectiveCriteria.DUMMY,
                    Component.literal(MARTA_NOTE_SCOREBOARD),
                    ObjectiveCriteria.RenderType.INTEGER);
        }
        scoreboard.getOrCreatePlayerScore(player.getScoreboardName(), objective)
                .setScore(hasFishermanNote(player) ? 1 : 0);
    }

    @SubscribeEvent
    public static void onQuestBoardRightClick(PlayerInteractEvent.RightClickBlock event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().dimension() != Level.OVERWORLD) return;

        ServerLevel level = (ServerLevel) player.level();
        BlockPos clickedPos = event.getPos();
        if (!level.getBlockState(clickedPos).is(Blocks.JUNGLE_WALL_SIGN)) return;

        VillagePriyutState state = VillagePriyutState.get(level);
        BlockPos boardPivot = state.getQuestBoardPivot();
        if (!state.isSpawned() || boardPivot == null) return;
        if (clickedPos.distSqr(boardPivot) > QUEST_BOARD_USE_RADIUS_SQ) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        level.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(2),
                "ftbquests open_book");
    }
}
