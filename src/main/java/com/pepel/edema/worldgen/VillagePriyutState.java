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

    private boolean spawned = false;
    private BlockPos pivot  = null;

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

    public void markSpawned(BlockPos pivot)
    {
        this.spawned = true;
        this.pivot   = pivot;
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
        return s;
    }
}
