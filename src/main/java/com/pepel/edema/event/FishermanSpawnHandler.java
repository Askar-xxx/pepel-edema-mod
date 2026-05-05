package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = PepelEdema.MODID)
public class FishermanSpawnHandler
{
    /** Тег у entity рыбака. Страховка против race-condition / повреждённого SavedData. */
    public static final  String ENTITY_TAG     = "pepel_fisherman";

    /** Радиус "зоны острова Приют" — внутри неё запрещено и спавнить рыбака, и триггерить поиск. */
    private static final int    ISLAND_RADIUS  = 100;
    private static final int    CHECK_INTERVAL = 100;

    /**
     * Минимальное смещение игрока за CHECK_INTERVAL (квадрат), чтобы считать "идёт куда-то".
     * 1 блок за 5 сек = 0.2 блока/сек — медленно, но всё ещё движение.
     * На серверной стороне Player.getDeltaMovement() для ходьбы часто = 0 (сервер трекает
     * позицию через клиентские пакеты, физика не обновляет вектор скорости), поэтому
     * считаем дельту сами через сравнение позиций между чеками.
     */
    private static final double MIN_DELTA_SQ   = 1.0;
    /** Огромная дельта = телепорт / лоадинг чанков. Игнорируем чтобы не плодить спавны. */
    private static final double MAX_DELTA_SQ   = 500.0 * 500.0;

    /** Позиция игрока с прошлого CHECK_INTERVAL — для расчёта направления движения. */
    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();

    /** Конус поиска по курсу: радиусы и углы относительно направления движения. */
    private static final int[]  SEARCH_RADII   = {25, 30, 35, 40, 45, 50, 55, 60};
    private static final int[]  SEARCH_ANGLES  = {0, -15, 15, -30, 30, -45, 45};
    private static final int    LOCAL_SEARCH_RADIUS = 6;

    /** Минимальное расстояние от воды до точки спавна (защита от обрывов и узких кос). */
    private static final int    SHORE_BUFFER   = 10;
    private static final int[][] DIRS_8        = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** Структура костра 5×5×7. Костёр в схеме на локальной (2, 2, 3) — анкер при размещении. */
    private static final ResourceLocation CAMP_STRUCTURE = new ResourceLocation(PepelEdema.MODID, "fisherman_camp");
    private static final int    CAMPFIRE_LOCAL_X = 2;
    private static final int    CAMPFIRE_LOCAL_Y = 2;
    private static final int    CAMPFIRE_LOCAL_Z = 3;
    /** Размер схемы для проверки свободного места. Y=2..4 (3 слоя над фундаментом) должны быть air. */
    private static final int    CAMP_W           = 5;
    private static final int    CAMP_L           = 7;
    private static final int    CAMP_AIR_Y_FROM  = 2;
    private static final int    CAMP_AIR_Y_TO    = 5;

    /** Рыбак стоит в 2 блоках к востоку от костра (на краю схемы), смотрит на запад. */
    private static final int    NPC_OFFSET_X = 2;
    private static final int    NPC_OFFSET_Z = 0;
    private static final float  NPC_YAW      = 90.0f;

    private static final ResourceLocation CUSTOM_NPC_ID =
            new ResourceLocation("customnpcs", "customnpc");
    private static final String FISHERMAN_TEXTURE =
            "customnpcs:textures/entity/humanmale/villageroldsteve.png";

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player.level().isClientSide) return;
        if (event.player.tickCount % CHECK_INTERVAL != 0) return;

        Player player = event.player;
        ServerLevel level = (ServerLevel) player.level();

        FishermanSpawnState state = FishermanSpawnState.get(level);
        if (state.isSpawned()) return;

        BlockPos islandSpawn = level.getSharedSpawnPos();
        double distFromIsland = Math.sqrt(player.blockPosition().distSqr(islandSpawn));
        if (distFromIsland < ISLAND_RADIUS) return;

        // Считаем направление через дельту позиции между чеками. На серверной стороне
        // Player.getDeltaMovement() для обычной ходьбы/плавания не обновляется (сервер
        // получает позицию пакетами от клиента, без пересчёта скорости).
        UUID pid = player.getUUID();
        Vec3 currentPos = player.position();
        Vec3 lastPos = LAST_POS.put(pid, currentPos);
        if (lastPos == null) return; // первый сэмпл — направления ещё нет, ждём следующий тик

        double dx = currentPos.x - lastPos.x;
        double dz = currentPos.z - lastPos.z;
        double deltaSq = dx * dx + dz * dz;
        if (deltaSq < MIN_DELTA_SQ) return;       // стоит / еле двигается
        if (deltaSq > MAX_DELTA_SQ) return;       // телепорт — не доверяем направлению

        double delta = Math.sqrt(deltaSq);
        double dirX = dx / delta;
        double dirZ = dz / delta;

        BlockPos campfirePos = findShoreInDirection(level, player.blockPosition(), dirX, dirZ, islandSpawn);
        if (campfirePos == null) return;

        // Страховка: если SavedData затёрто, но рыбак уже в мире — синхронизируем флаг и выходим.
        for (Entity e : level.getServer().overworld().getEntities().getAll())
        {
            if (e.getTags().contains(ENTITY_TAG))
            {
                state.markSpawned();
                return;
            }
        }

        spawnCamp(level, campfirePos, (ServerPlayer) player);
        state.markSpawned();

        // PoC деревни Приют: ставим её "за рыбаком" по вектору от острова.
        // Pivot = campfirePos + dir * 300 блоков (см. DISTANCE_FROM_FISHERMAN в VillagePlacement).
        com.pepel.edema.worldgen.VillagePlacement.tryPlaceVillage(level, islandSpawn, campfirePos);
    }

    /**
     * Ищет берег в конусе ±60° от направления движения игрока. Каждый кандидат должен:
     * - быть на твёрдой земле (не вода),
     * - быть за пределами зоны острова Приют,
     * - иметь land-buffer ≥ SHORE_BUFFER блоков по 8 направлениям (не у обрыва).
     */
    private static BlockPos findShoreInDirection(ServerLevel level, BlockPos playerPos,
                                                 double dirX, double dirZ, BlockPos islandSpawn)
    {
        BlockPos fallback = null;
        for (int r : SEARCH_RADII)
        {
            for (int angleDeg : SEARCH_ANGLES)
            {
                double rad = Math.toRadians(angleDeg);
                double cos = Math.cos(rad);
                double sin = Math.sin(rad);
                double rotX = dirX * cos - dirZ * sin;
                double rotZ = dirX * sin + dirZ * cos;
                int dx = (int) Math.round(rotX * r);
                int dz = (int) Math.round(rotZ * r);

                BlockPos projected = level.getHeightmapPos(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        new BlockPos(playerPos.getX() + dx, 0, playerPos.getZ() + dz)
                );

                BlockPos surface = findNearbyCampSpot(level, projected, islandSpawn);
                if (surface == null) continue;

                if (isPreferredCampGround(level, surface))
                {
                    return surface;
                }
                if (fallback == null)
                {
                    fallback = surface;
                }
            }
        }
        return fallback;
    }

    private static BlockPos findNearbyCampSpot(ServerLevel level, BlockPos projected, BlockPos islandSpawn)
    {
        BlockPos bestFallback = null;
        int bestFallbackDistSq = Integer.MAX_VALUE;

        for (int radius = 0; radius <= LOCAL_SEARCH_RADIUS; radius++)
        {
            BlockPos preferred = null;
            int bestPreferredDistSq = Integer.MAX_VALUE;

            for (int dx = -radius; dx <= radius; dx++)
            {
                for (int dz = -radius; dz <= radius; dz++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;

                    BlockPos surface = level.getHeightmapPos(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            new BlockPos(projected.getX() + dx, 0, projected.getZ() + dz)
                    );
                    if (!isValidCampSpot(level, surface, islandSpawn)) continue;

                    int distSq = dx * dx + dz * dz;
                    if (isPreferredCampGround(level, surface))
                    {
                        if (distSq < bestPreferredDistSq)
                        {
                            preferred = surface;
                            bestPreferredDistSq = distSq;
                        }
                    }
                    else if (distSq < bestFallbackDistSq)
                    {
                        bestFallback = surface;
                        bestFallbackDistSq = distSq;
                    }
                }
            }

            if (preferred != null)
            {
                return preferred;
            }
        }

        return bestFallback;
    }

    private static boolean isValidCampSpot(ServerLevel level, BlockPos surface, BlockPos islandSpawn)
    {
        return isLandPoint(level, surface)
                && !insideIslandZone(surface, islandSpawn)
                && hasLandBuffer(level, surface)
                && hasFlatCampFootprint(level, surface)
                && hasClearCampArea(level, surface);
    }

    private static boolean isLandPoint(ServerLevel level, BlockPos surface)
    {
        BlockState below = level.getBlockState(surface.below());
        return below.isSolid() && !below.is(Blocks.WATER);
    }

    /** Трава/земля предпочтительнее пляжа: песок/гравий оставляем запасным вариантом. */
    private static boolean isPreferredCampGround(ServerLevel level, BlockPos surface)
    {
        BlockState below = level.getBlockState(surface.below());
        return below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.DIRT)
                || below.is(Blocks.PODZOL) || below.is(Blocks.MYCELIUM);
    }

    private static boolean insideIslandZone(BlockPos pos, BlockPos islandSpawn)
    {
        return pos.distSqr(islandSpawn) <= (double) ISLAND_RADIUS * ISLAND_RADIUS;
    }

    /**
     * Схема костра ставится только на ровную естественную площадку 5×7.
     * Это важнее, чем чинить блоки после пасты: так мы не срезаем склон, не
     * вскрываем бок песчаником и не ломаем деревья/горы вокруг точки спавна.
     */
    private static boolean hasFlatCampFootprint(ServerLevel level, BlockPos campfirePos)
    {
        BlockPos origin = campfirePos.offset(-CAMPFIRE_LOCAL_X, -CAMPFIRE_LOCAL_Y, -CAMPFIRE_LOCAL_Z);
        int expectedY = campfirePos.getY();
        for (int dx = 0; dx < CAMP_W; dx++)
        {
            for (int dz = 0; dz < CAMP_L; dz++)
            {
                BlockPos column = origin.offset(dx, 0, dz);
                BlockPos surface = level.getHeightmapPos(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        new BlockPos(column.getX(), 0, column.getZ())
                );
                if (surface.getY() != expectedY) return false;

                BlockState ground = level.getBlockState(surface.below());
                if (!ground.isSolid() || ground.is(Blocks.WATER)) return false;
            }
        }
        return true;
    }

    /**
     * Строгая проверка свободного места над фундаментом — на 3 слоях (y=2..4 schema-local)
     * во всём 5×7 footprint должен быть воздух или заменяемая мелкая растительность.
     * Деревья, листва, чужие постройки и камни всё ещё отбрасывают кандидата.
     */
    private static boolean hasClearCampArea(ServerLevel level, BlockPos campfirePos)
    {
        BlockPos origin = campfirePos.offset(-CAMPFIRE_LOCAL_X, -CAMPFIRE_LOCAL_Y, -CAMPFIRE_LOCAL_Z);
        for (int dx = 0; dx < CAMP_W; dx++)
        {
            for (int dz = 0; dz < CAMP_L; dz++)
            {
                for (int dy = CAMP_AIR_Y_FROM; dy < CAMP_AIR_Y_TO; dy++)
                {
                    BlockState state = level.getBlockState(origin.offset(dx, dy, dz));
                    if (!state.isAir() && !state.canBeReplaced())
                    {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Проверяем что в радиусе SHORE_BUFFER по 8 направлениям нет воды на поверхности. */
    private static boolean hasLandBuffer(ServerLevel level, BlockPos pos)
    {
        for (int[] d : DIRS_8)
        {
            int tx = pos.getX() + d[0] * SHORE_BUFFER;
            int tz = pos.getZ() + d[1] * SHORE_BUFFER;
            BlockPos s = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(tx, 0, tz)
            );
            // Heightmap для воды возвращает позицию ВЫШЕ водной поверхности —
            // блок под s будет водой. Этого достаточно чтобы поймать берег.
            if (level.getBlockState(s.below()).is(Blocks.WATER)) return false;
        }
        return true;
    }

    /** Размещает схему костра + спавнит рыбака рядом с ним. */
    private static void spawnCamp(ServerLevel level, BlockPos campfirePos, ServerPlayer player)
    {
        boolean placed = placeStructure(level, campfirePos);
        if (!placed)
        {
            PepelEdema.LOGGER.warn("Структура {} не загрузилась — спавним рыбака без костра", CAMP_STRUCTURE);
        }

        BlockPos npcPos = campfirePos.offset(NPC_OFFSET_X, 0, NPC_OFFSET_Z);
        spawnFisherman(level, npcPos, NPC_YAW, player);
    }

    private static boolean placeStructure(ServerLevel level, BlockPos campfirePos)
    {
        StructureTemplateManager mgr = level.getServer().getStructureManager();
        Optional<StructureTemplate> tmplOpt = mgr.get(CAMP_STRUCTURE);
        if (tmplOpt.isEmpty()) return false;

        BlockPos origin = campfirePos.offset(-CAMPFIRE_LOCAL_X, -CAMPFIRE_LOCAL_Y, -CAMPFIRE_LOCAL_Z);
        StructureTemplate tmpl = tmplOpt.get();

        // ВАЖНО: запрашиваем биом-блок ПЕРЕД placeInWorld, иначе схема уже положит свою
        // траву поверх и pickGroundRemap прочитает grass_block → вернёт null → не сработает.
        GroundRemap gr = pickGroundRemap(level.getBlockState(campfirePos.below()));

        tmpl.placeInWorld(level, origin, origin, new StructurePlaceSettings(),
                level.getRandom(), Block.UPDATE_ALL);

        // Биом-адаптация ТОЛЬКО фундамента (y=0 base + y=1 surface). Костёр/брёвна/цепи
        // на y=2..4 не трогаются. Сделано post-обработкой а не StructureProcessor'ом потому
        // что vanilla AxisAlignedLinearPosTest не умеет ограничивать ровно один Y
        // (clampedLerp возвращает maxChance вне диапазона, и схема выше фундамента тоже
        // переписывалась бы), а кастомный StructureProcessor требует регистрации Type.
        // На y=0 в схеме есть air-дырки (под костром + край) — заполняем их base-блоком,
        // иначе sand на y=1 остался бы без опоры.
        if (gr != null)
        {
            Vec3i size = tmpl.getSize();
            BlockState surfaceState = gr.surface().defaultBlockState();
            BlockState baseState    = gr.base().defaultBlockState();
            boolean surfaceFalls = gr.surface() instanceof FallingBlock;
            for (int dx = 0; dx < size.getX(); dx++)
            {
                for (int dz = 0; dz < size.getZ(); dz++)
                {
                    BlockPos basePos = origin.offset(dx, 0, dz);
                    BlockState bs = level.getBlockState(basePos);
                    if (bs.is(Blocks.DIRT) || bs.isAir())
                    {
                        level.setBlock(basePos, baseState, Block.UPDATE_CLIENTS);
                    }

                    BlockPos surfPos = origin.offset(dx, 1, dz);
                    BlockState ss = level.getBlockState(surfPos);
                    if (ss.is(Blocks.GRASS_BLOCK) || ss.is(Blocks.DIRT))
                    {
                        level.setBlock(surfPos, surfaceState, Block.UPDATE_CLIENTS);
                    }
                }
            }
            if (surfaceFalls)
            {
                fillStableCampfirePad(level, origin, baseState);
            }
        }
        return true;
    }

    /** Для sand/gravel-пляжей делаем устойчивую 3×3 площадку под самим костром. */
    private static void fillStableCampfirePad(ServerLevel level, BlockPos origin, BlockState baseState)
    {
        for (int dx = CAMPFIRE_LOCAL_X - 1; dx <= CAMPFIRE_LOCAL_X + 1; dx++)
        {
            for (int dz = CAMPFIRE_LOCAL_Z - 1; dz <= CAMPFIRE_LOCAL_Z + 1; dz++)
            {
                BlockPos pos = origin.offset(dx, 1, dz);
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                        || state.getBlock() instanceof FallingBlock)
                {
                    level.setBlock(pos, baseState, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** Пара (поверхность, фундамент) для биом-ремапа. Фундамент — устойчивый блок без гравитации. */
    private record GroundRemap(Block surface, Block base) {}

    /** Возвращает remap-пару под биом, или null для grass/dirt (стандарт схемы оставляем). */
    private static GroundRemap pickGroundRemap(BlockState ground)
    {
        if (ground.is(Blocks.SAND))        return new GroundRemap(Blocks.SAND,        Blocks.SANDSTONE);
        if (ground.is(Blocks.RED_SAND))    return new GroundRemap(Blocks.RED_SAND,    Blocks.RED_SANDSTONE);
        if (ground.is(Blocks.GRAVEL))      return new GroundRemap(Blocks.GRAVEL,      Blocks.STONE);
        if (ground.is(Blocks.STONE))       return new GroundRemap(Blocks.STONE,       Blocks.STONE);
        if (ground.is(Blocks.SNOW_BLOCK))  return new GroundRemap(Blocks.SNOW_BLOCK,  Blocks.SNOW_BLOCK);
        if (ground.is(Blocks.PACKED_ICE))  return new GroundRemap(Blocks.SNOW_BLOCK,  Blocks.SNOW_BLOCK);
        if (ground.is(Blocks.MYCELIUM))    return new GroundRemap(Blocks.MYCELIUM,    Blocks.DIRT);
        if (ground.is(Blocks.PODZOL))      return new GroundRemap(Blocks.PODZOL,      Blocks.DIRT);
        return null;
    }

    private static void spawnFisherman(ServerLevel level, BlockPos pos, float yaw, ServerPlayer player)
    {
        EntityType<?> npcType = ForgeRegistries.ENTITY_TYPES.getValue(CUSTOM_NPC_ID);

        if (npcType != null)
        {
            CompoundTag fullTag = buildFishermanNbt(pos);
            Entity entity = EntityType.loadEntityRecursive(fullTag, level, e -> {
                e.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);
                return e;
            });
            if (entity != null)
            {
                level.addFreshEntity(entity);
                playSpawnEffect(level, pos, player);
                PepelEdema.LOGGER.info("Рыбак (Custom NPC) заспавнился на {}", pos);
                return;
            }
            PepelEdema.LOGGER.warn("EntityType.loadEntityRecursive вернул null — откат на виладжера");
        }
        else
        {
            PepelEdema.LOGGER.info("customnpcs не загружен — спавним виладжера-заглушку");
        }

        Villager villager = new Villager(EntityType.VILLAGER, level);
        villager.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);
        villager.setCustomName(Component.literal("Рыбак"));
        villager.setCustomNameVisible(true);
        villager.setPersistenceRequired();
        villager.addTag(ENTITY_TAG);
        level.addFreshEntity(villager);
        playSpawnEffect(level, pos, player);
        PepelEdema.LOGGER.info("Рыбак (villager fallback) заспавнился на {}", pos);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event)
    {
        LAST_POS.remove(event.getEntity().getUUID());
    }

    private static CompoundTag makeDialogOption(int slot, int dialogId, String title, int color, int type)
    {
        CompoundTag dialog = new CompoundTag();
        dialog.putString("DialogCommand", "");
        dialog.putInt("Dialog", dialogId);
        dialog.putString("Title", title);
        dialog.putInt("DialogColor", color);
        dialog.putInt("OptionType", type);
        CompoundTag option = new CompoundTag();
        option.putInt("DialogSlot", slot);
        option.put("NPCDialog", dialog);
        return option;
    }

    private static void playSpawnEffect(ServerLevel level, BlockPos pos, ServerPlayer player)
    {
        level.playSound(null, pos, SoundEvents.TOTEM_USE, SoundSource.AMBIENT, 1.0f, 0.9f);
        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("...кто-то есть неподалёку")));
    }

    private static CompoundTag buildFishermanNbt(BlockPos pos)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", CUSTOM_NPC_ID.toString());

        tag.putString("CustomName", "{\"text\":\"Рыбак\"}");
        tag.putBoolean("CustomNameVisible", true);
        tag.putBoolean("Invulnerable", true);
        tag.putBoolean("PersistenceRequired", true);
        tag.putBoolean("Silent", true);

        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(ENTITY_TAG));
        tag.put("Tags", tags);

        tag.putString("Name", "Рыбак");
        tag.putString("Texture", FISHERMAN_TEXTURE);
        tag.putInt("Size", 5);
        tag.putInt("ShowName", 0);
        tag.putBoolean("OverlayGlowing", true);
        tag.putBoolean("stopAndInteract", true);
        tag.putBoolean("npcInteracting", true);
        tag.putInt("MovingPatern", 0);
        tag.putInt("MovementType", 0);
        tag.putInt("MoveSpeed", 5);
        tag.putInt("WalkingRange", 10);
        tag.putInt("MoveState", 1);
        tag.putBoolean("AvoidsWater", true);
        tag.putString("ScriptLanguage", "ECMAScript");
        tag.putInt("ModRev", 18);

        ListTag opts = new ListTag();
        opts.add(makeDialogOption(0, FishermanDialogs.DIALOG_START_ID,   "Встреча",   14737632, 1));
        opts.add(makeDialogOption(1, FishermanDialogs.DIALOG_VILLAGE_ID, "О деревне", 14737632, 1));
        opts.add(makeDialogOption(2, FishermanDialogs.DIALOG_WHO_ID,     "Кто ты?",   14737632, 1));
        tag.put("NPCDialogOptions", opts);

        CompoundTag emptyLines = new CompoundTag();
        emptyLines.put("Lines", new ListTag());
        tag.put("NpcInteractLines", emptyLines);

        return tag;
    }
}
