package com.pepel.edema.worldgen;

import com.mojang.datafixers.util.Pair;
import com.pepel.edema.PepelEdema;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.event.level.LevelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Optional;

public class SpawnHandler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SpawnHandler.class);
    private static final ResourceLocation TEMPLATE_ID = new ResourceLocation(PepelEdema.MODID, "spawn_island");
    private static final ResourceLocation TEMPLATE_NBT = new ResourceLocation(PepelEdema.MODID, "structures/spawn_island.nbt");
    private static final TagKey<Structure> SPAWN_ISLAND_TAG = TagKey.create(Registries.STRUCTURE, TEMPLATE_ID);
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
            LOGGER.warn("Spawn island not found within {} chunks of origin after {} ms",
                    SEARCH_RADIUS_CHUNKS, System.currentTimeMillis() - start);
            return;
        }

        // Origin template = (центр чанка - size/2) по X/Z, см. SpawnIslandStructure.findGenerationPoint.
        ChunkPos cp = new ChunkPos(found.getFirst());
        StructureTemplate tmpl = level.getServer().getStructureManager().getOrCreate(TEMPLATE_ID);
        Vec3i size = tmpl.getSize();
        int originX = cp.getMiddleBlockX() - size.getX() / 2;
        int originZ = cp.getMiddleBlockZ() - size.getZ() / 2;

        int[] centroid = readIslandCentroid(level);
        if (centroid == null)
        {
            LOGGER.warn("Failed to compute island centroid from NBT, falling back to vanilla spawn");
            return;
        }

        int worldX = originX + centroid[0];
        int worldZ = originZ + centroid[1];
        level.getChunk(worldX >> 4, worldZ >> 4, ChunkStatus.FULL, true);
        int worldY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);

        BlockPos spawnPos = new BlockPos(worldX, worldY, worldZ);
        level.setDefaultSpawnPos(spawnPos, 0.0F);
        event.setCanceled(true);
        LOGGER.info("Spawn island done in {} ms. Template={}x{}, island centroid local=({},{}), spawn={}",
                System.currentTimeMillis() - start, size.getX(), size.getZ(), centroid[0], centroid[1], spawnPos);
    }

    // Центр массы острова: среднее X/Z по всем присутствующим блокам в NBT.
    // Air в Structure NBT не хранится, а конвертер стрипает воду/служебный песок —
    // значит в списке blocks ровно блоки самого острова.
    private static int[] readIslandCentroid(ServerLevel level)
    {
        try
        {
            Optional<Resource> resOpt = level.getServer().getResourceManager().getResource(TEMPLATE_NBT);
            if (resOpt.isEmpty())
            {
                LOGGER.error("NBT resource not found: {}", TEMPLATE_NBT);
                return null;
            }
            CompoundTag tag;
            try (InputStream is = resOpt.get().open())
            {
                tag = NbtIo.readCompressed(is);
            }

            ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
            long sumX = 0, sumZ = 0, count = 0;
            for (int i = 0; i < blocks.size(); i++)
            {
                CompoundTag b = blocks.getCompound(i);
                ListTag pos = b.getList("pos", Tag.TAG_INT);
                sumX += pos.getInt(0);
                sumZ += pos.getInt(2);
                count++;
            }
            if (count == 0)
            {
                LOGGER.error("NBT has no blocks");
                return null;
            }
            return new int[]{(int) (sumX / count), (int) (sumZ / count)};
        }
        catch (Exception e)
        {
            LOGGER.error("Failed to parse spawn island NBT", e);
            return null;
        }
    }
}
