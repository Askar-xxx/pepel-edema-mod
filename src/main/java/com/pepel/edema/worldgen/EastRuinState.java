package com.pepel.edema.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

/** Состояние восточной руины этапа 2. Хранится глобально на overworld. */
public class EastRuinState extends SavedData
{
    private static final String DATA_NAME = "pepel_east_ruin";

    private boolean spawned = false;
    private boolean guardianSpawned = false;
    private BlockPos pivot = null;
    private UUID guardianUuid = null;

    public static EastRuinState get(ServerLevel anyLevel)
    {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                EastRuinState::load,
                EastRuinState::new,
                DATA_NAME
        );
    }

    public boolean isSpawned() { return spawned; }
    public boolean isGuardianSpawned() { return guardianSpawned; }
    public BlockPos getPivot() { return pivot; }
    public UUID getGuardianUuid() { return guardianUuid; }

    public void markSpawned(BlockPos pivot)
    {
        this.spawned = true;
        this.pivot = pivot;
        setDirty();
    }

    public void markGuardianSpawned(UUID uuid)
    {
        this.guardianSpawned = true;
        this.guardianUuid = uuid;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putBoolean("spawned", spawned);
        tag.putBoolean("guardianSpawned", guardianSpawned);
        if (pivot != null)
        {
            tag.putInt("pivotX", pivot.getX());
            tag.putInt("pivotY", pivot.getY());
            tag.putInt("pivotZ", pivot.getZ());
        }
        if (guardianUuid != null)
        {
            tag.putUUID("guardianUuid", guardianUuid);
        }
        return tag;
    }

    public static EastRuinState load(CompoundTag tag)
    {
        EastRuinState s = new EastRuinState();
        s.spawned = tag.getBoolean("spawned");
        s.guardianSpawned = tag.getBoolean("guardianSpawned");
        if (tag.contains("pivotX"))
        {
            s.pivot = new BlockPos(tag.getInt("pivotX"), tag.getInt("pivotY"), tag.getInt("pivotZ"));
        }
        if (tag.hasUUID("guardianUuid"))
        {
            s.guardianUuid = tag.getUUID("guardianUuid");
        }
        return s;
    }
}
