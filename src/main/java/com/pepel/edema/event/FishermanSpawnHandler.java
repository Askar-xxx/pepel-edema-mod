package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = PepelEdema.MODID)
public class FishermanSpawnHandler
{
    private static final String FLAG           = "pepel_fisherman_spawned";
    private static final int    ISLAND_RADIUS  = 100; // блоков от мирового спавна
    private static final int    CHECK_INTERVAL = 100; // тиков (~5 сек)

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
        CompoundTag data = player.getPersistentData();
        if (data.getBoolean(FLAG)) return;

        ServerLevel level = (ServerLevel) player.level();
        BlockPos spawn = level.getSharedSpawnPos();

        double dist = Math.sqrt(player.blockPosition().distSqr(spawn));
        if (dist < ISLAND_RADIUS) return;

        BlockPos fishermanPos = findShoreNear(level, player.blockPosition());
        if (fishermanPos == null) return;

        spawnFisherman(level, fishermanPos, (ServerPlayer) player);
        data.putBoolean(FLAG, true);
    }

    /** Ищет твёрдую землю (не воду) в радиусе 10-80 блоков от игрока */
    private static BlockPos findShoreNear(ServerLevel level, BlockPos playerPos)
    {
        for (int r = 10; r <= 80; r += 10)
        {
            for (int deg = 0; deg < 360; deg += 20)
            {
                int dx = (int)(r * Math.cos(Math.toRadians(deg)));
                int dz = (int)(r * Math.sin(Math.toRadians(deg)));
                BlockPos surface = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(playerPos.getX() + dx, 0, playerPos.getZ() + dz)
                );
                BlockState below = level.getBlockState(surface.below());
                if (below.isSolid() && !below.is(Blocks.WATER))
                    return surface;
            }
        }
        return null;
    }

    private static void spawnFisherman(ServerLevel level, BlockPos pos, ServerPlayer player)
    {
        EntityType<?> npcType = ForgeRegistries.ENTITY_TYPES.getValue(CUSTOM_NPC_ID);

        if (npcType != null)
        {
            // Диалоги пишутся в FishermanDialogsLoader на ServerAboutToStartEvent — до того, как
            // Custom NPC сканирует свою папку. Здесь повторный вызов не нужен.
            CompoundTag fullTag = buildFishermanNbt(pos);
            Entity entity = EntityType.loadEntityRecursive(fullTag, level, e -> {
                e.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
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

        // Фоллбэк: ванильный виладжер
        Villager villager = new Villager(EntityType.VILLAGER, level);
        villager.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        villager.setCustomName(Component.literal("Рыбак"));
        villager.setCustomNameVisible(true);
        villager.setPersistenceRequired();
        level.addFreshEntity(villager);
        playSpawnEffect(level, pos, player);
        PepelEdema.LOGGER.info("Рыбак (villager fallback) заспавнился на {}", pos);
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

    /** Атмосферный эффект спавна: звук + сабтайтл */
    private static void playSpawnEffect(ServerLevel level, BlockPos pos, ServerPlayer player)
    {
        // Звон тотема бессмертия — мистический, торжественный: "спасение, надежда".
        level.playSound(null, pos, SoundEvents.TOTEM_USE, SoundSource.AMBIENT, 1.0f, 0.9f);

        // Сабтайтл на экране игрока
        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("...кто-то есть неподалёку")));
    }

    /** Собирает NBT-тег для Custom NPC рыбака (ключи взяты из /data get entity) */
    private static CompoundTag buildFishermanNbt(BlockPos pos)
    {
        CompoundTag tag = new CompoundTag();

        // Обязательный id для EntityType.loadEntityRecursive
        tag.putString("id", CUSTOM_NPC_ID.toString());

        // Ванильные поля
        tag.putString("CustomName", "{\"text\":\"Рыбак\"}");
        tag.putBoolean("CustomNameVisible", true);
        tag.putBoolean("Invulnerable", true);
        tag.putBoolean("PersistenceRequired", true);
        tag.putBoolean("Silent", true);

        // Custom NPCs: ключи из эталонного /data get entity дампа
        tag.putString("Name", "Рыбак");
        tag.putString("Texture", FISHERMAN_TEXTURE);
        tag.putInt("Size", 5);                        // 1 — карлик, 5 — обычный человек
        tag.putInt("ShowName", 0);                    // 0 — имя только при наведении (эталон Бришки)
        tag.putBoolean("OverlayGlowing", true);       // зелёное имя при наведении
        tag.putBoolean("stopAndInteract", true);
        tag.putBoolean("npcInteracting", true);
        tag.putInt("MovingPatern", 0);
        tag.putInt("MovementType", 0);
        tag.putInt("MoveSpeed", 5);
        tag.putInt("WalkingRange", 10);
        tag.putInt("MoveState", 1);                   // стоит на месте (эталон Бришки)
        tag.putBoolean("AvoidsWater", true);
        tag.putString("ScriptLanguage", "ECMAScript");
        tag.putInt("ModRev", 18);

        // Меню при правом клике. Dialog 4 ("Встреча") — главное меню с подпунктами Кто ты?/О деревне/Пока.
        // Слоты 1-2 — быстрый прямой заход в конкретный подпункт.
        ListTag opts = new ListTag();
        opts.add(makeDialogOption(0, FishermanDialogs.DIALOG_START_ID,   "Встреча",   14737632, 1));
        opts.add(makeDialogOption(1, FishermanDialogs.DIALOG_VILLAGE_ID, "О деревне", 14737632, 1));
        opts.add(makeDialogOption(2, FishermanDialogs.DIALOG_WHO_ID,     "Кто ты?",   14737632, 1));
        tag.put("NPCDialogOptions", opts);

        // Очищаем дефолтный "Hello @p"
        CompoundTag emptyLines = new CompoundTag();
        emptyLines.put("Lines", new ListTag());
        tag.put("NpcInteractLines", emptyLines);

        return tag;
    }
}
