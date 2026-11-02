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
        int wet = 0, failed = 0, flowing = 0, flowMissing = 0, spills = 0;
        Cell neighbour = new Cell();
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
                    // Water or ice at the planned surface. Features may legitimately place solid blocks there (e.g.
                    // boulders in shallow wetland pools); what must never happen is an air gap.
                    boolean present = top.getFluidState().is(FluidTags.WATER) || top.is(Blocks.ICE) || top.is(Blocks.FROSTED_ICE)
                        || !top.isAir() && top.getFluidState().isEmpty() && top.isSolid();
                    pos.setY(bed);
                    var floor = level.getBlockState(pos);
                    // Below sea level (blocks under the sea surface, y < sea level) a deep pool may open into aquifer water: still water, not a leak.
                    boolean sealed = !floor.isAir() && (floor.getFluidState().isEmpty()
                        || bed < level.getSeaLevel() && floor.getFluidState().isSource());
                    // Above the planned surface only a one-block flowing riffle from a higher neighbouring step is
                    // acceptable; source water or anything higher means the column overflowed.
                    pos.setY(water + 1);
                    var above = level.getBlockState(pos).getFluidState();
                    pos.setY(water + 2);
                    boolean extra = above.is(FluidTags.WATER) && above.isSource()
                        || level.getBlockState(pos).getFluidState().is(FluidTags.WATER);
                    // Flow-field parity: every channel column with a current must carry it in the chunk's flow field.
                    boolean flowOk = true;
                    if (cell.hasFlow) {
                        flowing++;
                        var flowField = level.getChunk(x >> 4, z >> 4) instanceof etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder holder
                            ? holder.freeterraforged$getFlowField() : null;
                        flowOk = flowField != null && flowField.hasFlow(x & 15, z & 15);
                        if (!flowOk) flowMissing++;
                    }
                    // Containment: a dry neighbour must be solid at the water surface, or water would spill.
                    boolean contained = true;
                    String spill = "";
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        context.lookup.applyCell(neighbour.reset(), x + d[0], z + d[1], true, false);
                        if (neighbour.hasRiverWater) continue;
                        pos.set(x + d[0], water, z + d[1]);
                        var side = level.getBlockState(pos);
                        if (side.isAir()) {
                            contained = false; spills++;
                            int solid = water;
                            while (solid > water - 8 && level.getBlockState(pos.set(x + d[0], solid, z + d[1])).isAir()) solid--;
                            spill = " spillNeighbour=" + neighbour.terrain.getName() + "/" + neighbour.riverZone + "@" + context.levels.scale(neighbour.height)
                                + " raw=" + neighbour.height + " water=" + cell.riverWaterLevel + " solidTop=" + solid + ":" + level.getBlockState(pos);
                            break;
                        }
                    }
                    if (!present || !sealed || extra || !flowOk || !contained) {
                        if (failed++ < 12) examples.append(" [").append(x).append(',').append(z).append(" water=").append(water)
                            .append(" bed=").append(bed).append(" present=").append(present).append(" sealed=").append(sealed).append(sealed ? "" : ":" + floor).append(" extra=").append(extra)
                            .append(" flow=").append(flowOk).append(" contained=").append(contained)
                            .append(" top=").append(top.getBlock().builtInRegistryHolder().key().location().getPath())
                            .append(" terrain=").append(cell.terrain.getName()).append(spill).append(']');
                    }
                }
            }
        }
        String report = "Rolling Highlands FULL-chunk water checks: chunks=" + positions.size() + " wetColumns=" + wet
            + " flowColumns=" + flowing + " flowMissing=" + flowMissing + " spills=" + spills + " failures=" + failed + examples;
        if (wet == 0 || failed > 0) throw new IllegalStateException(report);
        return report;
    }
}
