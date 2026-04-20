package com.pepel.edema.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pepel.edema.config.PepelConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;

public class SpawnIslandStructure extends Structure
{
    public static final Codec<SpawnIslandStructure> CODEC = RecordCodecBuilder.create(i -> i.group(
            settingsCodec(i),
            ResourceLocation.CODEC.fieldOf("template").forGetter(s -> s.template),
            Codec.INT.optionalFieldOf("y_offset", 0).forGetter(s -> s.yOffset),
            TagKey.codec(Registries.BIOME).fieldOf("land_biome_tag").forGetter(s -> s.landTag),
            TagKey.codec(Registries.BIOME).fieldOf("ocean_biome_tag").forGetter(s -> s.oceanTag),
            TagKey.codec(Registries.BIOME).fieldOf("forbidden_biome_tag").forGetter(s -> s.forbiddenTag)
    ).apply(i, SpawnIslandStructure::new));

    private final ResourceLocation template;
    private final int yOffset;
    private final TagKey<Biome> landTag;
    private final TagKey<Biome> oceanTag;
    private final TagKey<Biome> forbiddenTag;

    public int yOffset() { return yOffset; }

    public ResourceLocation template() { return template; }

    public SpawnIslandStructure(StructureSettings settings, ResourceLocation template, int yOffset,
                                TagKey<Biome> landTag, TagKey<Biome> oceanTag, TagKey<Biome> forbiddenTag)
    {
        super(settings);
        this.template = template;
        this.yOffset = yOffset;
        this.landTag = landTag;
        this.oceanTag = oceanTag;
        this.forbiddenTag = forbiddenTag;
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext ctx)
    {
        if (!ringCheck(ctx)) return Optional.empty();

        // templatePosition в StructureTemplate — это НИЖНИЙ ЛЕВЫЙ УГОЛ пасты.
        // Чтобы центр схемы (а значит и остров) оказался в центре чанка,
        // смещаем origin на половину размеров template по X и Z.
        StructureTemplate tmpl = ctx.structureTemplateManager().getOrCreate(this.template);
        Vec3i size = tmpl.getSize();

        ChunkPos cp = ctx.chunkPos();
        int cx = cp.getMiddleBlockX();
        int cz = cp.getMiddleBlockZ();
        int y = ctx.chunkGenerator().getSeaLevel() - 1 + this.yOffset;
        BlockPos pos = new BlockPos(cx - size.getX() / 2, y, cz - size.getZ() / 2);

        return Optional.of(new GenerationStub(pos, builder ->
                builder.addPiece(new SpawnIslandPiece(ctx.structureTemplateManager(), this.template, pos))
        ));
    }

    private boolean ringCheck(GenerationContext ctx)
    {
        PepelConfig.Resolved p = PepelConfig.resolved();
        BiomeSource source = ctx.biomeSource();
        Climate.Sampler sampler = ctx.randomState().sampler();

        ChunkPos cp = ctx.chunkPos();
        int bx = cp.getMiddleBlockX() >> 2;
        int by = 60 >> 2;
        int bz = cp.getMiddleBlockZ() >> 2;

        Holder<Biome> center = source.getNoiseBiome(bx, by, bz, sampler);
        if (!center.is(this.oceanTag)) return false;

        // Абсолютные пороги для early-exit: считаем общее кол-во сэмплов заранее,
        // чтобы отбраковывать чанк как только гарантирован провал — не дожидаясь всех 16 лучей.
        int innerLimit = Math.min(p.innerRadius(), p.distMin());
        int innerPerRay = 0;
        for (int r = 32; r < innerLimit; r += p.step()) innerPerRay++;
        int outerPerRay = 0;
        for (int r = p.distMin(); r <= p.distMax(); r += p.step()) outerPerRay++;
        int innerTotalMax = innerPerRay * p.numRays();
        int outerTotalMax = outerPerRay * p.numRays();
        double innerFailThreshold = p.innerCoverageMax() * innerTotalMax;
        double outerSuccessThreshold = p.coverageMin() * outerTotalMax;

        int innerLand = 0;
        int innerTotal = 0;
        int outerLand = 0;
        int outerTotal = 0;
        // Порядок лучей через bit-reversal (если numRays — степень двойки):
        // первые 4 итерации покрывают все 4 стороны света (0°, 90°, 180°, 270°),
        // следующие 4 — диагонали. Early-exit срабатывает намного раньше на плохих кандидатах.
        int n = p.numRays();
        int bits = Integer.numberOfTrailingZeros(n);
        boolean isPow2 = n > 0 && (1 << bits) == n;
        for (int i = 0; i < n; i++)
        {
            int k = isPow2 ? (Integer.reverse(i) >>> (32 - bits)) : i;
            double theta = 2.0 * Math.PI * k / n;
            double cos = Math.cos(theta);
            double sin = Math.sin(theta);

            for (int r = 32; r < innerLimit; r += p.step())
            {
                int sx = bx + (int) Math.round(r * cos) / 4;
                int sz = bz + (int) Math.round(r * sin) / 4;
                Holder<Biome> b = source.getNoiseBiome(sx, by, sz, sampler);
                if (b.is(this.forbiddenTag)) return false;
                innerTotal++;
                if (b.is(this.landTag)) innerLand++;
                if (innerLand > innerFailThreshold) return false;
            }

            for (int r = p.distMin(); r <= p.distMax(); r += p.step())
            {
                int sx = bx + (int) Math.round(r * cos) / 4;
                int sz = bz + (int) Math.round(r * sin) / 4;
                Holder<Biome> b = source.getNoiseBiome(sx, by, sz, sampler);
                if (b.is(this.forbiddenTag)) return false;
                outerTotal++;
                if (b.is(this.landTag)) outerLand++;
                if (outerLand + (outerTotalMax - outerTotal) < outerSuccessThreshold) return false;
            }
        }

        if (innerTotal > 0 && (double) innerLand / innerTotal > p.innerCoverageMax()) return false;
        return outerTotal > 0 && (double) outerLand / outerTotal >= p.coverageMin();
    }

    @Override
    public StructureType<?> type()
    {
        return ModStructureTypes.SPAWN_ISLAND.get();
    }
}
