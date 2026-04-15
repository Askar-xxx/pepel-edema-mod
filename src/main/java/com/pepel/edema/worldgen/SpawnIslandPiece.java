package com.pepel.edema.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

public class SpawnIslandPiece extends TemplateStructurePiece
{
    public SpawnIslandPiece(StructureTemplateManager mgr, ResourceLocation template, BlockPos pos)
    {
        super(ModStructurePieceTypes.SPAWN_ISLAND_PIECE.get(), 0, mgr, template, template.toString(), makeSettings(), pos);
    }

    public SpawnIslandPiece(StructurePieceSerializationContext ctx, CompoundTag tag)
    {
        super(ModStructurePieceTypes.SPAWN_ISLAND_PIECE.get(), tag, ctx.structureTemplateManager(), id -> makeSettings());
    }

    private static StructurePlaceSettings makeSettings()
    {
        return new StructurePlaceSettings().setIgnoreEntities(true);
    }

    @Override
    protected void handleDataMarker(String data, BlockPos pos, ServerLevelAccessor level, RandomSource rnd, BoundingBox box)
    {
        // шаблон без data markers; метод обязательный абстракт, оставляем пустым
    }
}
