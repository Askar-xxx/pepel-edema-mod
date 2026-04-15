package com.pepel.edema.worldgen;

import com.pepel.edema.PepelEdema;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModStructurePieceTypes
{
    public static final DeferredRegister<StructurePieceType> PIECE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, PepelEdema.MODID);

    public static final RegistryObject<StructurePieceType> SPAWN_ISLAND_PIECE =
            PIECE_TYPES.register("spawn_island_piece", () -> SpawnIslandPiece::new);
}
