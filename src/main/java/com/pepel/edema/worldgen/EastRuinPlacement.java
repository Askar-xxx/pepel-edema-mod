package com.pepel.edema.worldgen;

import com.pepel.edema.PepelEdema;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;

/** Placement восточной руины после M0. Маленькая структура, поэтому ставится синхронно. */
public final class EastRuinPlacement
{
    private static final ResourceLocation TEMPLATE_ID = new ResourceLocation(PepelEdema.MODID, "castle");
    private static final int RUIN_DISTANCE = 200;
    private static final int SEARCH_RADIUS = 64;
    private static final int SEARCH_STEP = 8;
    private static final int HEIGHTMAP_STEP = 4;
    private static final int EDGE_FEATHER = 3;
    private static final int FILL_DEPTH = 3;

    private EastRuinPlacement() {}

    public static boolean placeAfterM0(ServerPlayer player)
    {
        ServerLevel level = player.serverLevel();
        VillagePriyutState village = VillagePriyutState.get(level);
        if (!village.isSpawned() || village.getPivot() == null)
        {
            player.displayClientMessage(Component.literal("§cРуина не создана: Приют ещё не найден."), false);
            return false;
        }

        EastRuinState state = EastRuinState.get(level);
        if (state.isSpawned())
        {
            return true;
        }

        StructureTemplate template;
        try
        {
            template = level.getServer().getStructureManager().getOrCreate(TEMPLATE_ID);
        }
        catch (Exception e)
        {
            PepelEdema.LOGGER.error("[east-ruin] не удалось загрузить template {}", TEMPLATE_ID, e);
            player.displayClientMessage(Component.literal("§cРуина не создана: template castle не найден."), false);
            return false;
        }

        Vec3i size = template.getSize();
        if (size.getX() == 0 || size.getZ() == 0)
        {
            PepelEdema.LOGGER.error("[east-ruin] template {} пустой (size={})", TEMPLATE_ID, size);
            return false;
        }

        BlockPos villagePivot = village.getPivot();
        int targetX = villagePivot.getX() + RUIN_DISTANCE;
        int targetZ = villagePivot.getZ();
        Candidate candidate = findBestCandidate(level, targetX, targetZ, size);
        if (candidate == null)
        {
            PepelEdema.LOGGER.warn("[east-ruin] не найдено место около {},{}", targetX, targetZ);
            player.displayClientMessage(Component.literal("§cРуина не создана: рядом не нашлось сухого места."), false);
            return false;
        }

        int originX = candidate.x - size.getX() / 2;
        int originZ = candidate.z - size.getZ() / 2;
        prepareTerrain(level, originX, originZ, candidate.y, size);

        BlockPos templatePos = new BlockPos(originX, candidate.y, originZ);
        boolean ok = template.placeInWorld(level, templatePos, templatePos,
                new StructurePlaceSettings(), level.getRandom(), 2);
        if (!ok)
        {
            PepelEdema.LOGGER.error("[east-ruin] placeInWorld вернул false для {}", TEMPLATE_ID);
            return false;
        }

        BlockPos pivot = new BlockPos(candidate.x, candidate.y, candidate.z);
        state.markSpawned(pivot);
        PepelEdema.LOGGER.info("[east-ruin] castle placed: pivot={}, origin={}, size={}, delta={}",
                pivot, templatePos, size, candidate.delta);
        player.displayClientMessage(Component.literal("§7Где-то к востоку от Приюта просела старая каменная руина."), false);
        return true;
    }

    private static Candidate findBestCandidate(ServerLevel level, int targetX, int targetZ, Vec3i size)
    {
        int side = Math.max(size.getX(), size.getZ()) + 8;
        Candidate best = null;
        for (int ox = -SEARCH_RADIUS; ox <= SEARCH_RADIUS; ox += SEARCH_STEP)
        {
            for (int oz = -SEARCH_RADIUS; oz <= SEARCH_RADIUS; oz += SEARCH_STEP)
            {
                int x = targetX + ox;
                int z = targetZ + oz;
                AreaScan scan = scanArea(level, x, z, side);
                if (scan.waterRatio > 0.10 || scan.heights.isEmpty()) continue;
                int y = percentile(scan.heights, 0.75);
                int dist = Math.abs(ox) + Math.abs(oz);
                Candidate c = new Candidate(x, y, z, scan.delta, dist);
                if (best == null || c.score() < best.score())
                {
                    best = c;
                }
            }
        }
        return best;
    }

    private static AreaScan scanArea(ServerLevel level, int cx, int cz, int side)
    {
        int half = side / 2;
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        int samples = 0;
        int water = 0;
        List<Integer> heights = new ArrayList<>();

        for (int x = cx - half; x <= cx + half; x += HEIGHTMAP_STEP)
        {
            for (int z = cz - half; z <= cz + half; z += HEIGHTMAP_STEP)
            {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                samples++;
                BlockPos surface = new BlockPos(x, Math.max(level.getMinBuildHeight(), y - 1), z);
                if (level.getFluidState(surface).is(FluidTags.WATER)
                        || level.getBlockState(surface).is(Blocks.WATER))
                {
                    water++;
                    continue;
                }
                heights.add(y);
                minH = Math.min(minH, y);
                maxH = Math.max(maxH, y);
            }
        }

        AreaScan scan = new AreaScan();
        scan.delta = heights.isEmpty() ? Integer.MAX_VALUE : maxH - minH;
        scan.waterRatio = samples == 0 ? 1.0 : (double) water / samples;
        scan.heights = heights;
        return scan;
    }

    private static int percentile(List<Integer> heights, double p)
    {
        heights.sort(Integer::compareTo);
        int idx = (int) Math.round(p * (heights.size() - 1));
        return heights.get(Math.max(0, Math.min(idx, heights.size() - 1)));
    }

    private static void prepareTerrain(ServerLevel level, int originX, int originZ, int targetY, Vec3i size)
    {
        int alignMinX = originX - EDGE_FEATHER;
        int alignMaxX = originX + size.getX() - 1 + EDGE_FEATHER;
        int alignMinZ = originZ - EDGE_FEATHER;
        int alignMaxZ = originZ + size.getZ() - 1 + EDGE_FEATHER;
        for (int x = alignMinX; x <= alignMaxX; x++)
        {
            for (int z = alignMinZ; z <= alignMaxZ; z++)
            {
                int distEdge = 0;
                if (x < originX) distEdge = Math.max(distEdge, originX - x);
                if (x > originX + size.getX() - 1) distEdge = Math.max(distEdge, x - (originX + size.getX() - 1));
                if (z < originZ) distEdge = Math.max(distEdge, originZ - z);
                if (z > originZ + size.getZ() - 1) distEdge = Math.max(distEdge, z - (originZ + size.getZ() - 1));

                int columnY = targetY;
                if (distEdge > 0)
                {
                    int natural = getGroundSurfaceY(level, x, z);
                    float weight = 1.0f - (float) distEdge / (EDGE_FEATHER + 1);
                    columnY = Math.round(targetY * weight + natural * (1.0f - weight));
                }

                int groundY = getGroundSurfaceY(level, x, z);
                if (groundY < columnY)
                {
                    for (int y = groundY; y < columnY - 1; y++)
                    {
                        level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                    }
                    level.setBlock(new BlockPos(x, columnY - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
                else if (groundY >= columnY)
                {
                    for (int y = columnY; y < groundY; y++)
                    {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!isTreePart(level.getBlockState(p)))
                        {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                    level.setBlock(new BlockPos(x, columnY - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }

                for (int y = columnY - FILL_DEPTH; y < columnY - 1; y++)
                {
                    BlockPos p = new BlockPos(x, y, z);
                    if (level.getBlockState(p).isAir())
                    {
                        level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static int getGroundSurfaceY(ServerLevel level, int x, int z)
    {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        for (int y = Math.min(top - 1, level.getMaxBuildHeight() - 1); y >= level.getMinBuildHeight(); y--)
        {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state.isAir() || isTreePart(state)) continue;
            return y + 1;
        }
        return top;
    }

    private static boolean isTreePart(BlockState state)
    {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }

    private static final class Candidate
    {
        final int x;
        final int y;
        final int z;
        final int delta;
        final int distance;

        Candidate(int x, int y, int z, int delta, int distance)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.delta = delta;
            this.distance = distance;
        }

        int score()
        {
            return delta * 100 + distance;
        }
    }

    private static final class AreaScan
    {
        int delta;
        double waterRatio;
        List<Integer> heights;
    }
}
