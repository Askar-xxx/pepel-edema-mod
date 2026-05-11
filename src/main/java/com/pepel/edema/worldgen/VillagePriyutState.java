package com.pepel.edema.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Состояние деревни Приют. Один глобальный pivot на мир — координаты колодца.
 * Ставится один раз когда срабатывает FishermanSpawnHandler (вычисляется по вектору
 * от центра острова к рыбаку, затем DISTANCE_FROM_FISHERMAN блоков "за рыбаком").
 *
 * Хранится в overworld DimensionDataStorage — переживает релог/смерть/перезапуск.
 */
public class VillagePriyutState extends SavedData
{
    private static final String DATA_NAME = "pepel_village_priyut";
    private static final int DEFAULT_ENTER_RADIUS = 96;

    private boolean spawned = false;
    private BlockPos pivot  = null;
    private BlockPos questBoardPivot = null;
    private int enterRadius = DEFAULT_ENTER_RADIUS;

    public static VillagePriyutState get(ServerLevel anyLevel)
    {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                VillagePriyutState::load,
                VillagePriyutState::new,
                DATA_NAME
        );
    }

    public boolean isSpawned() { return spawned; }
    public BlockPos getPivot() { return pivot; }
    public BlockPos getQuestBoardPivot() { return questBoardPivot != null ? questBoardPivot : fallbackQuestBoardPivot(); }
    public int getEnterRadius() { return enterRadius; }

    public void markSpawned(BlockPos pivot)
    {
        markSpawned(pivot, DEFAULT_ENTER_RADIUS);
    }

    public void markSpawned(BlockPos pivot, int enterRadius)
    {
        markSpawned(pivot, enterRadius, null);
    }

    public void markSpawned(BlockPos pivot, int enterRadius, BlockPos questBoardPivot)
    {
        this.spawned = true;
        this.pivot   = pivot;
        this.enterRadius = enterRadius;
        this.questBoardPivot = questBoardPivot;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putBoolean("spawned", spawned);
        if (pivot != null)
        {
            tag.putInt("pivotX", pivot.getX());
            tag.putInt("pivotY", pivot.getY());
            tag.putInt("pivotZ", pivot.getZ());
        }
        if (questBoardPivot != null)
        {
            tag.putInt("questBoardX", questBoardPivot.getX());
            tag.putInt("questBoardY", questBoardPivot.getY());
            tag.putInt("questBoardZ", questBoardPivot.getZ());
        }
        tag.putInt("enterRadius", enterRadius);
        return tag;
    }

    public static VillagePriyutState load(CompoundTag tag)
    {
        VillagePriyutState s = new VillagePriyutState();
        s.spawned = tag.getBoolean("spawned");
        if (tag.contains("pivotX"))
        {
            s.pivot = new BlockPos(tag.getInt("pivotX"), tag.getInt("pivotY"), tag.getInt("pivotZ"));
        }
        if (tag.contains("enterRadius"))
        {
            s.enterRadius = tag.getInt("enterRadius");
        }
        if (tag.contains("questBoardX"))
        {
            s.questBoardPivot = new BlockPos(
                    tag.getInt("questBoardX"),
                    tag.getInt("questBoardY"),
                    tag.getInt("questBoardZ"));
        }
        return s;
    }

    private BlockPos fallbackQuestBoardPivot()
    {
        return pivot == null ? null : pivot.offset(-3, 0, 14);
    }
}
