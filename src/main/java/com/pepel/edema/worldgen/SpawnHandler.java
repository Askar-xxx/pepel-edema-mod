package com.pepel.edema.worldgen;

import com.mojang.datafixers.util.Pair;
import com.pepel.edema.PepelEdema;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
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

    /**
     * Внимание: для RandomSpreadStructurePlacement Mojang интерпретирует этот параметр НЕ в чанках,
     * а в spacing-units. При spacing=64 значение 16 = поиск в радиусе 16×64 = 1024 чанка ≈ 16 384 блока
     * от мирового спавна, что даёт (2*16+1)² = 1089 кандидатов к проверке. Ожидаемое время ~1 сек.
     *
     * Радиус 4 (256 чанков ≈ 4096 блоков) пробовался — поиск отрабатывал за 200 мс, но не находил
     * ни одного подходящего слота: 81 кандидата мало для случайного попадания в "открытый океан с
     * островом". Радиус 16 даёт хороший запас, оставаясь мгновенным по человеческим меркам.
     *
     * Старое значение 200 раздуло перебор до 401² = 160 801 кандидатов на радиусе ~205 км —
     * findNearestMapStructure висел >2 минут и тоже не находил ничего.
     */
    private static final int SEARCH_RADIUS_SPACING_UNITS = 16;

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
                .findNearestMapStructure(level, tagSet.get(), BlockPos.ZERO, SEARCH_RADIUS_SPACING_UNITS, false);

        if (found == null)
        {
            LOGGER.warn("Spawn island not found within {} spacing-units of origin after {} ms",
                    SEARCH_RADIUS_SPACING_UNITS, System.currentTimeMillis() - start);
            return;
        }

        Structure structure = found.getSecond().value();
        if (!(structure instanceof SpawnIslandStructure island))
        {
            LOGGER.warn("Found structure is not SpawnIslandStructure, got {}", structure.getClass().getName());
            return;
        }

        Vec3i size = level.getServer().getStructureManager().getOrCreate(TEMPLATE_ID).getSize();
        int[] pit = readPit(level);
        if (pit == null)
        {
            LOGGER.warn("pepel_pit tag missing in {}, falling back to vanilla spawn", TEMPLATE_NBT);
            return;
        }

        // placement: origin = (центр чанка - size/2) по X/Z, y = seaLevel - 1 + yOffset,
        // см. SpawnIslandStructure.findGenerationPoint. Повторяем тот же расчёт.
        ChunkPos cp = new ChunkPos(found.getFirst());
        int originX = cp.getMiddleBlockX() - size.getX() / 2;
        int originZ = cp.getMiddleBlockZ() - size.getZ() / 2;
        int baseY = level.getSeaLevel() - 1 + island.yOffset();

        int worldX = originX + pit[0];
        int worldY = baseY + pit[1];
        int worldZ = originZ + pit[2];

        // Координаты ямы взяты из pepel_pit-тега NBT (см. readPit), без обращения к мировым
        // блокам — синхронный прогрев чанка не нужен. setDefaultSpawnPos сам триггерит
        // loadPlayerSpawn (генерит спавн-квадрат 11x11 чанков). Раньше тут стоял
        // level.getChunk(..., ChunkStatus.FULL, true) — он дублировал работу loadPlayerSpawn
        // и тормозил создание мира на секунды.
        BlockPos spawnPos = new BlockPos(worldX, worldY, worldZ);
        level.setDefaultSpawnPos(spawnPos, 0.0F);
        event.setCanceled(true);
        LOGGER.info("Spawn island done in {} ms. Pit local=({},{},{}), spawn={}",
                System.currentTimeMillis() - start, pit[0], pit[1], pit[2], spawnPos);
    }

    // Читаем кастомный тег pepel_pit, записанный schem_to_nbt.py. Структура tag:
    //   pepel_pit: { x: Int, y: Int, z: Int }  — локальные координаты template.
    private static int[] readPit(ServerLevel level)
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
            if (!tag.contains("pepel_pit", 10)) return null;
            CompoundTag pit = tag.getCompound("pepel_pit");
            return new int[]{pit.getInt("x"), pit.getInt("y"), pit.getInt("z")};
        }
        catch (Exception e)
        {
            LOGGER.error("Failed to read pepel_pit from spawn island NBT", e);
            return null;
        }
    }
}
