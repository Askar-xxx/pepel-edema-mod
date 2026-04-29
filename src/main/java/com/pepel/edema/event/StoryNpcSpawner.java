package com.pepel.edema.event;

import com.pepel.edema.PepelEdema;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;

public class StoryNpcSpawner
{
    private static final ResourceLocation CUSTOM_NPC_ID =
            new ResourceLocation("customnpcs", "customnpc");

    private static final int DIALOG_COLOR = 14737632;

    public static void spawnIvarNear(ServerPlayer player)
    {
        spawn(player, storyNpc(
                "Ивар",
                "pepel:textures/entity/npc/ivar.png",
                "pepel_ivar",
                new DialogOption(0, FishermanDialogs.IVAR_START_ID, "Разговор"),
                new DialogOption(1, FishermanDialogs.IVAR_RUINS_ID, "О руинах"),
                new DialogOption(2, FishermanDialogs.IVAR_WHO_ID, "Кто ты?")
        ));
    }

    public static void spawnLiaNear(ServerPlayer player)
    {
        spawn(player, storyNpc(
                "Лия",
                "pepel:textures/entity/npc/lia.png",
                "pepel_lia",
                new DialogOption(0, FishermanDialogs.LIA_START_ID, "Разговор"),
                new DialogOption(1, FishermanDialogs.LIA_ROOTS_ID, "О корнях"),
                new DialogOption(2, FishermanDialogs.LIA_WHO_ID, "Кто ты?")
        ));
    }

    public static void spawnMartaNear(ServerPlayer player)
    {
        spawn(player, storyNpc(
                "Марта",
                "pepel:textures/entity/npc/marta.png",
                "pepel_marta",
                new DialogOption(0, FishermanDialogs.MARTA_START_ID, "Разговор"),
                new DialogOption(1, FishermanDialogs.MARTA_WORK_ID, "Что делать?"),
                new DialogOption(2, FishermanDialogs.MARTA_WHO_ID, "Кто ты?")
        ));
    }

    private static void spawn(ServerPlayer player, StoryNpc npc)
    {
        ServerLevel level = player.serverLevel();
        BlockPos pos = findSpawnPos(level, player);
        float yaw = player.getYRot() + 180.0f;

        EntityType<?> npcType = ForgeRegistries.ENTITY_TYPES.getValue(CUSTOM_NPC_ID);
        if (npcType != null)
        {
            CompoundTag tag = buildNpcNbt(npc);
            Entity entity = EntityType.loadEntityRecursive(tag, level, e -> {
                e.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);
                return e;
            });
            if (entity != null)
            {
                level.addFreshEntity(entity);
                player.displayClientMessage(Component.literal("§aNPC заспавнен: " + npc.name()), false);
                PepelEdema.LOGGER.info("{} (Custom NPC) заспавнен на {}", npc.name(), pos);
                return;
            }
            PepelEdema.LOGGER.warn("Не удалось загрузить Custom NPC {}, откат на villager", npc.name());
        }

        Villager villager = new Villager(EntityType.VILLAGER, level);
        villager.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);
        villager.setCustomName(Component.literal(npc.name()));
        villager.setCustomNameVisible(true);
        villager.setPersistenceRequired();
        villager.addTag(npc.tag());
        level.addFreshEntity(villager);
        player.displayClientMessage(Component.literal("§eCustom NPCs не найден, заспавнен villager: " + npc.name()), false);
    }

    private static BlockPos findSpawnPos(ServerLevel level, ServerPlayer player)
    {
        BlockPos base = player.blockPosition().relative(player.getDirection(), 2);
        return level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(base.getX(), 0, base.getZ())
        );
    }

    private static StoryNpc storyNpc(String name, String texture, String tag, DialogOption... options)
    {
        return new StoryNpc(name, texture, tag, options);
    }

    private static CompoundTag buildNpcNbt(StoryNpc npc)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", CUSTOM_NPC_ID.toString());

        tag.putString("CustomName", "{\"text\":\"" + npc.name() + "\"}");
        tag.putBoolean("CustomNameVisible", true);
        tag.putBoolean("Invulnerable", true);
        tag.putBoolean("PersistenceRequired", true);
        tag.putBoolean("Silent", true);

        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(npc.tag()));
        tag.put("Tags", tags);

        tag.putString("Name", npc.name());
        tag.putString("Texture", npc.texture());
        tag.putInt("Size", 5);
        tag.putInt("ShowName", 0);
        tag.putBoolean("OverlayGlowing", true);
        tag.putBoolean("stopAndInteract", true);
        tag.putBoolean("npcInteracting", true);
        tag.putInt("MovingPatern", 0);
        tag.putInt("MovementType", 0);
        tag.putInt("MoveSpeed", 5);
        tag.putInt("WalkingRange", 10);
        tag.putInt("MoveState", 0);
        tag.putInt("StandingState", 0);
        tag.putInt("MovingState", 0);
        tag.putFloat("PositionOffsetX", 5.0f);
        tag.putFloat("PositionOffsetY", 5.0f);
        tag.putFloat("PositionOffsetZ", 5.0f);
        tag.putBoolean("AvoidsWater", true);
        tag.putString("ScriptLanguage", "ECMAScript");
        tag.putInt("ModRev", 18);

        ListTag opts = new ListTag();
        for (DialogOption option : npc.options())
        {
            opts.add(makeDialogOption(option.slot(), option.dialogId(), option.title()));
        }
        tag.put("NPCDialogOptions", opts);

        CompoundTag emptyLines = new CompoundTag();
        emptyLines.put("Lines", new ListTag());
        tag.put("NpcInteractLines", emptyLines);

        return tag;
    }

    private static CompoundTag makeDialogOption(int slot, int dialogId, String title)
    {
        CompoundTag dialog = new CompoundTag();
        dialog.putString("DialogCommand", "");
        dialog.putInt("Dialog", dialogId);
        dialog.putString("Title", title);
        dialog.putInt("DialogColor", DIALOG_COLOR);
        dialog.putInt("OptionType", 1);
        CompoundTag option = new CompoundTag();
        option.putInt("DialogSlot", slot);
        option.put("NPCDialog", dialog);
        return option;
    }

    private record StoryNpc(String name, String texture, String tag, DialogOption[] options) {}

    private record DialogOption(int slot, int dialogId, String title) {}
}
