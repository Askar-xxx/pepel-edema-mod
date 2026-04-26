package com.pepel.edema.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Флаг "рыбак уже заспавнен в этом мире". Хранится в overworld DimensionDataStorage,
 * поэтому общий для всех игроков и переживает релог/смерть.
 */
public class FishermanSpawnState extends SavedData
{
    private static final String DATA_NAME = "pepel_fisherman_spawn";
    private static final String KEY       = "spawned";

    private boolean spawned = false;

    public static FishermanSpawnState get(ServerLevel anyLevel)
    {
        // Один глобальный стейт на сервер — храним в overworld.
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                FishermanSpawnState::load,
                FishermanSpawnState::new,
                DATA_NAME
        );
    }

    public boolean isSpawned()
    {
        return spawned;
    }

    public void markSpawned()
    {
        if (!this.spawned)
        {
            this.spawned = true;
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putBoolean(KEY, spawned);
        return tag;
    }

    public static FishermanSpawnState load(CompoundTag tag)
    {
        FishermanSpawnState state = new FishermanSpawnState();
        state.spawned = tag.getBoolean(KEY);
        return state;
    }
}
