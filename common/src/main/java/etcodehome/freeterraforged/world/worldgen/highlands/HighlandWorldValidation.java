package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.List;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

/** Opt-in benchmark assertion over FULL chunks, including surface/features and scheduled fluid ticks. */
public final class HighlandWorldValidation {
    private HighlandWorldValidation() {}
    public static String validate(ServerLevel level, List<ChunkPos> positions) {
        var state = (FTFRandomState) (Object) level.getChunkSource().randomState();
        var context = state.generatorContext();
        if (context == null || !(context.heightmap.continent() instanceof RollingHighlandsContinent)) {
            throw new IllegalStateException("RH validation requested for a different terrain mode");
        }
        int wet = 0, failed = 0;
        StringBuilder examples = new StringBuilder();
        var pos = new BlockPos.MutableBlockPos();
        Cell cell = new Cell();
        for (var chunk : positions) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
                    context.lookup.applyCell(cell.reset(), x, z, true, false);
                    if (!cell.hasRiverWater) continue;
                    int water = context.levels.scale(cell.riverWaterLevel), bed = context.levels.scale(cell.height);
                    if (water <= bed) continue;
                    wet++;
                    pos.set(x, water, z);
                    var top = level.getBlockState(pos);
                    boolean present = top.getFluidState().is(FluidTags.WATER) || top.is(Blocks.ICE) || top.is(Blocks.FROSTED_ICE);
                    pos.setY(bed);
                    boolean sealed = !level.getBlockState(pos).isAir() && level.getBlockState(pos).getFluidState().isEmpty();
                    pos.setY(water + 1);
                    boolean extra = level.getBlockState(pos).getFluidState().is(FluidTags.WATER);
                    if (!present || !sealed || extra) {
                        if (failed++ < 12) examples.append(" [").append(x).append(',').append(z).append(" water=").append(water)
                            .append(" bed=").append(bed).append(" present=").append(present).append(" sealed=").append(sealed).append(" extra=").append(extra).append(']');
                    }
                }
            }
        }
        String report = "Rolling Highlands FULL-chunk water checks: chunks=" + positions.size() + " wetColumns=" + wet + " failures=" + failed + examples;
        if (wet == 0 || failed > 0) throw new IllegalStateException(report);
        return report;
    }
}
