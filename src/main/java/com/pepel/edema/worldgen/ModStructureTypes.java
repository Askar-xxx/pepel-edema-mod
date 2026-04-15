package com.pepel.edema.worldgen;

import com.pepel.edema.PepelEdema;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModStructureTypes
{
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, PepelEdema.MODID);

    public static final RegistryObject<StructureType<SpawnIslandStructure>> SPAWN_ISLAND =
            STRUCTURE_TYPES.register("spawn_island", () -> () -> SpawnIslandStructure.CODEC);
}
