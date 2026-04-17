package com.pepel.edema.worldgen;

import com.mojang.datafixers.util.Pair;
import com.pepel.edema.PepelEdema;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.event.level.LevelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SpawnHandler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SpawnHandler.class);
    private static final TagKey<Structure> SPAWN_ISLAND_TAG = TagKey.create(
            Registries.STRUCTURE,
            new ResourceLocation(PepelEdema.MODID, "spawn_island"));
    private static final ResourceLocation TEMPLATE_ID = new ResourceLocation(PepelEdema.MODID, "spawn_island");
    private static final int SEARCH_RADIUS_CHUNKS = 200;

    public static void onCreateSpawn(LevelEvent.CreateSpawnPosition event)
    {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        Optional<HolderSet.Named<Structure>> tagSet = level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getTag(SPAWN_ISLAND_TAG);

        if (tagSet.isEmpty() || tagSet.get().size() == 0)
        {
            LOGGER.warn("Spawn island tag is empty, falling back to vanilla spawn");
            return;
        }

        long start = System.currentTimeMillis();
        Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, tagSet.get(), BlockPos.ZERO, SEARCH_RADIUS_CHUNKS, false);

        if (found == null)
        {
            LOGGER.warn("Spawn island not found within {} chunks of origin after {} ms, falling back to vanilla spawn",
                    SEARCH_RADIUS_CHUNKS, System.currentTimeMillis() - start);
            return;
        }

        BlockPos foundPos = found.getFirst();
        Structure structure = found.getSecond().value();

        ChunkPos cp = new ChunkPos(foundPos);
        level.getChunk(cp.x, cp.z, ChunkStatus.STRUCTURE_STARTS, true);
        StructureStart ss = level.structureManager().getStructureAt(foundPos, structure);

        if (ss == StructureStart.INVALID_START)
        {
            LOGGER.warn("StructureStart invalid for pos={}, using found pos directly", foundPos);
            level.setDefaultSpawnPos(foundPos.above(30), 0.0F);
            event.setCanceled(true);
            return;
        }

        BoundingBox bb = ss.getBoundingBox();
        BlockPos origin = new BlockPos(bb.minX(), bb.minY(), bb.minZ());
        LOGGER.info("Island bbox: ({}..{}, {}..{}, {}..{}), origin={}",
                bb.minX(), bb.maxX(), bb.minY(), bb.maxY(), bb.minZ(), bb.maxZ(), origin);

        // Читаем сам .nbt template: там только настоящий остров.
        // Песчаный столб — часть ланд-шейфта мира/террагена, в template его НЕТ,
        // так что filterBlocks по grass_block даст координаты только острова.
        StructureTemplate tmpl = level.getStructureManager().getOrCreate(TEMPLATE_ID);
        StructurePlaceSettings settings = new StructurePlaceSettings();

        List<StructureTemplate.StructureBlockInfo> surface = new ArrayList<>();
        for (Block b : new Block[] { Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MOSS_BLOCK })
        {
            surface.addAll(tmpl.filterBlocks(origin, settings, b));
        }

        BlockPos spawnPos;
        if (surface.isEmpty())
        {
            LOGGER.warn("Template has no grass/podzol/mycelium/moss — fallback to foundPos+30");
            spawnPos = foundPos.above(30);
        }
        else
        {
            double cx = 0, cz = 0;
            for (var info : surface) { cx += info.pos().getX(); cz += info.pos().getZ(); }
            cx /= surface.size();
            cz /= surface.size();

            // Ближайший к центроиду блок — но с максимальным Y среди равноудалённых,
            // чтобы не угодить под крону/нависающую землю.
            BlockPos best = surface.get(0).pos();
            double bestDist = Double.MAX_VALUE;
            for (var info : surface)
            {
                BlockPos p = info.pos();
                double dx = p.getX() - cx, dz = p.getZ() - cz;
                double d = dx * dx + dz * dz;
                if (d < bestDist || (d == bestDist && p.getY() > best.getY()))
                {
                    bestDist = d;
                    best = p;
                }
            }
            spawnPos = best.above(1);
            LOGGER.info("Template grass/podzol: {} blocks, centroid=({},{}), spawn={}",
                    surface.size(), (int) cx, (int) cz, spawnPos);
        }

        level.setDefaultSpawnPos(spawnPos, 0.0F);
        event.setCanceled(true);
        LOGGER.info("Spawn island done in {} ms. World spawn set to {}",
                System.currentTimeMillis() - start, spawnPos);
    }
}
