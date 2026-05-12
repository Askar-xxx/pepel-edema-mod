package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import com.pepel.edema.api.BookNotifications;
import com.pepel.edema.capability.BookNotificationsProvider;
import com.pepel.edema.item.ModItems;
import com.pepel.edema.worldgen.EastRuinState;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
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
    public static final String MARTA_M0_DONE_SCOREBOARD = "pepel_m0_done";
    public static final String EAST_RUIN_REACHED_TASK_ID = "7100000000000004";
    public static final String EAST_RUIN_GUARDIAN_TASK_ID = "7100000000000005";
    public static final String STONE_GUARDIAN_TAG = "pepel_stone_guardian";
    public static final String RUIN_DONE_SCOREBOARD = "pepel_ruin_done";
    private static final double QUEST_BOARD_USE_RADIUS_SQ = 4.0 * 4.0;
    private static final double RUIN_GUARDIAN_TRIGGER_RADIUS_SQ = 10.0 * 10.0;
    private static final double STONE_GUARDIAN_AGGRO_RADIUS_SQ = 32.0 * 32.0;

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
            if (entity.tickCount % 20 == 0)
            {
                StoryNpcSpawner.refreshStoryNpcOptions(entity);
            }

            if (entity.getTags().contains(STONE_GUARDIAN_TAG) && entity instanceof IronGolem guardian)
            {
                updateStoneGuardianTarget(level, guardian);
            }

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

        CompoundTag data = player.getPersistentData();
        syncMartaNoteScore(player);
        syncStoryProgressScores(player, data);

        ServerLevel level = (ServerLevel) player.level();
        StoryNpcSpawner.refreshIvarDialogPhase(level, getIvarStartDialogFor(player, data));
        checkEastRuinGuardian(level, player);

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

    private static void syncStoryProgressScores(ServerPlayer player, CompoundTag data)
    {
        boolean m0Done = data.getBoolean(MARTA_M0_LIA_COMPLETED)
                && data.getBoolean(MARTA_M0_IVAR_COMPLETED);
        if (m0Done)
        {
            setScore(player, MARTA_M0_DONE_SCOREBOARD, 1);
        }
        if (data.getBoolean(RUIN_DONE_SCOREBOARD))
        {
            setScore(player, RUIN_DONE_SCOREBOARD, 1);
        }
    }

    private static int getIvarStartDialogFor(ServerPlayer player, CompoundTag data)
    {
        if (data.getBoolean(RUIN_DONE_SCOREBOARD) || getScore(player, RUIN_DONE_SCOREBOARD) > 0)
        {
            return FishermanDialogs.IVAR_AFTER_RUIN_START_ID;
        }
        if (data.getBoolean(MARTA_M0_LIA_COMPLETED) && data.getBoolean(MARTA_M0_IVAR_COMPLETED))
        {
            return FishermanDialogs.IVAR_AFTER_M0_START_ID;
        }
        return FishermanDialogs.IVAR_START_ID;
    }

    public static void setScore(ServerPlayer player, String objectiveName, int value)
    {
        Scoreboard scoreboard = player.serverLevel().getScoreboard();
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null)
        {
            objective = scoreboard.addObjective(
                    objectiveName,
                    ObjectiveCriteria.DUMMY,
                    Component.literal(objectiveName),
                    ObjectiveCriteria.RenderType.INTEGER);
        }
        scoreboard.getOrCreatePlayerScore(player.getScoreboardName(), objective).setScore(value);
        scoreboard.getOrCreatePlayerScore(player.getName().getString(), objective).setScore(value);
    }

    private static int getScore(ServerPlayer player, String objectiveName)
    {
        Scoreboard scoreboard = player.serverLevel().getScoreboard();
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) return 0;
        if (!scoreboard.hasPlayerScore(player.getName().getString(), objective)) return 0;
        return scoreboard.getOrCreatePlayerScore(player.getName().getString(), objective).getScore();
    }

    private static void checkEastRuinGuardian(ServerLevel level, ServerPlayer player)
    {
        EastRuinState state = EastRuinState.get(level);
        BlockPos pivot = state.getPivot();
        if (!state.isSpawned() || state.isGuardianSpawned() || pivot == null) return;

        double dx = player.getX() - (pivot.getX() + 0.5);
        double dz = player.getZ() - (pivot.getZ() + 0.5);
        if (dx * dx + dz * dz > RUIN_GUARDIAN_TRIGGER_RADIUS_SQ) return;

        IronGolem guardian = EntityType.IRON_GOLEM.create(level);
        if (guardian == null) return;

        completeFtbQuestTask(level, player, EAST_RUIN_REACHED_TASK_ID);

        BlockPos spawnPos = findGuardianSpawnPos(level, pivot);
        clearGuardianSpawnSpace(level, spawnPos);
        guardian.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                player.getYRot() + 180.0f, 0.0f);
        guardian.setPlayerCreated(false);
        guardian.setCustomName(Component.literal("Каменный страж"));
        guardian.setPersistenceRequired();
        guardian.addTag(STONE_GUARDIAN_TAG);
        guardian.setTarget(player);
        level.addFreshEntity(guardian);
        state.markGuardianSpawned(guardian.getUUID());

        level.playSound(null, spawnPos, SoundEvents.IRON_GOLEM_REPAIR, SoundSource.HOSTILE, 1.0f, 0.6f);
        BookNotifications.notify(player, "p2a_first_guardian");
        sendBookUpdatedSubtitle(player);
        PepelEdema.LOGGER.info("[east-ruin] guardian spawned at {} for {}", spawnPos, player.getGameProfile().getName());
    }

    private static void updateStoneGuardianTarget(ServerLevel level, IronGolem guardian)
    {
        if (guardian.tickCount % 10 != 0) return;
        if (guardian.getTarget() instanceof ServerPlayer target
                && target.isAlive()
                && !target.isCreative()
                && !target.isSpectator()
                && guardian.distanceToSqr(target) <= STONE_GUARDIAN_AGGRO_RADIUS_SQ)
        {
            return;
        }

        ServerPlayer nearest = null;
        double nearestDist = STONE_GUARDIAN_AGGRO_RADIUS_SQ;
        for (ServerPlayer player : level.players())
        {
            if (!player.isAlive() || player.isCreative() || player.isSpectator()) continue;
            double dist = guardian.distanceToSqr(player);
            if (dist <= nearestDist)
            {
                nearest = player;
                nearestDist = dist;
            }
        }
        if (nearest != null)
        {
            guardian.setTarget(nearest);
            guardian.setLastHurtByMob(nearest);
        }
    }

    private static BlockPos findGuardianSpawnPos(ServerLevel level, BlockPos ruinPivot)
    {
        int[][] offsets = {
                {0, -5}, {1, -5}, {-1, -5}, {0, -4}, {1, -4}, {-1, -4},
                {2, -5}, {-2, -5}, {0, -6}, {1, -6}, {-1, -6},
                {0, -3}, {1, -3}, {-1, -3}, {0, -2}
        };
        for (int[] offset : offsets)
        {
            int x = ruinPivot.getX() + offset[0];
            int z = ruinPivot.getZ() + offset[1];
            for (int y = ruinPivot.getY(); y <= ruinPivot.getY() + 6; y++)
            {
                if (isGolemSpawnable(level, x, y, z))
                {
                    return new BlockPos(x, y, z);
                }
            }
        }
        return new BlockPos(ruinPivot.getX(), ruinPivot.getY() + 2, ruinPivot.getZ() + 15);
    }

    private static boolean isGolemSpawnable(ServerLevel level, int x, int y, int z)
    {
        if (level.getBlockState(new BlockPos(x, y - 1, z)).isAir()) return false;
        return isSoftSpawnBlock(level.getBlockState(new BlockPos(x, y, z)))
                && isSoftSpawnBlock(level.getBlockState(new BlockPos(x, y + 1, z)))
                && isSoftSpawnBlock(level.getBlockState(new BlockPos(x, y + 2, z)))
                && isSoftSpawnBlock(level.getBlockState(new BlockPos(x + 1, y, z)))
                && isSoftSpawnBlock(level.getBlockState(new BlockPos(x, y, z + 1)))
                && isSoftSpawnBlock(level.getBlockState(new BlockPos(x + 1, y + 1, z)))
                && isSoftSpawnBlock(level.getBlockState(new BlockPos(x, y + 1, z + 1)));
    }

    private static boolean isSoftSpawnBlock(BlockState state)
    {
        return state.isAir() || state.canBeReplaced();
    }

    private static void clearGuardianSpawnSpace(ServerLevel level, BlockPos spawnPos)
    {
        for (int dx = 0; dx <= 1; dx++)
        {
            for (int dz = 0; dz <= 1; dz++)
            {
                for (int dy = 0; dy <= 2; dy++)
                {
                    BlockPos pos = spawnPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir() && isSoftSpawnBlock(state))
                    {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event)
    {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (!entity.getTags().contains(STONE_GUARDIAN_TAG)) return;
        if (!(entity instanceof IronGolem guardian)) return;
        if (event.getSource().getEntity() instanceof ServerPlayer player)
        {
            guardian.setTarget(player);
            guardian.setLastHurtByMob(player);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event)
    {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (!entity.getTags().contains(STONE_GUARDIAN_TAG)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;

        BookNotifications.notify(player, "p2b_eren_intro");
        sendBookUpdatedSubtitle(player);
        completeFtbQuestTask(player.serverLevel(), player, EAST_RUIN_GUARDIAN_TASK_ID);
        player.getPersistentData().putBoolean(RUIN_DONE_SCOREBOARD, true);
        setScore(player, RUIN_DONE_SCOREBOARD, 1);
        StoryNpcSpawner.refreshIvarDialogPhase(player.serverLevel(), FishermanDialogs.IVAR_AFTER_RUIN_START_ID);
        for (Entity candidate : player.serverLevel().getAllEntities())
        {
            StoryNpcSpawner.refreshStoryNpcOptions(candidate);
        }
        PepelEdema.LOGGER.info("[east-ruin] guardian killed by {}", player.getGameProfile().getName());
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
