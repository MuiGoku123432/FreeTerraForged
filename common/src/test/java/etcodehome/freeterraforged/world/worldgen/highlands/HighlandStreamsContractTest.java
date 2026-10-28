package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.world.worldgen.ChunkFlowField;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.RiverWater;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

/**
 * Streams Reflowing 2.15.2 reads FTF cells reflectively (RESEARCH-RH3 §5): WorldLookup.applyCell(cell, x, z, false),
 * river = (terrain == TerrainType.RIVER), surface = max(riverWaterLevel, levels.water) * terrainScaleFactor when
 * riverWaterLevel > 0, and the packed flowAngle byte. This test reads RH cells the same way and checks that what SR
 * sees agrees with the water RH places.
 */
class HighlandStreamsContractTest {
    @Test void streamsReflowingSeesRollingHighlandsWater() throws Exception {
        for (long seed : new long[]{0, 42}) {
            try (var context = WorldgenFixture.context(HighlandQualityProbe.preset(), seed)) {
                var continent = (RollingHighlandsContinent) context.heightmap.continent();
                // A fixed window around the origin continent's first confluence of two larger channels.
                var plan = continent.planOf(0, 0).plan();
                int[] kids = new int[plan.nodes().size()];
                for (var node : plan.nodes()) if (node.parent() >= 0) kids[node.parent()]++;
                int cx = 0, cz = 0;
                for (int j = 0; j < kids.length; j++) if (kids[j] >= 2) { cx = (int) plan.nodes().get(j).x(); cz = (int) plan.nodes().get(j).z(); break; }
                var levels = context.levels;
                Cell cell = new Cell();
                int river = 0, mismatched = 0, hidden = 0, flowless = 0, phantom = 0;
                StringBuilder examples = new StringBuilder();
                for (int z = cz - 256; z < cz + 256; z += 2) for (int x = cx - 256; x < cx + 256; x += 2) {
                    context.lookup.applyCell(cell.reset(), x, z, false);
                    boolean srRiver = cell.terrain == TerrainType.RIVER;
                    boolean water = RiverWater.isWater(cell);
                    if (water && !srRiver && !cell.terrain.isWetland()) { hidden++; continue; }
                    if (srRiver && !water) { phantom++; continue; }
                    if (!srRiver) continue;
                    river++;
                    double srSurface = cell.riverWaterLevel > 0 ? Math.max(cell.riverWaterLevel, levels.water)
                        : cell.height <= levels.water ? levels.water : Double.NaN;
                    int srY = (int) Math.floor(srSurface * levels.terrainScaleFactor);
                    int placedY = Math.max(levels.scale(RiverWater.level(cell, levels.water)), levels.scale(levels.water));
                    if (Math.abs(srY - placedY) > 1) {
                        if (mismatched++ < 5) examples.append(" [").append(x).append(',').append(z).append(" sr=").append(srY).append(" placed=").append(placedY).append(']');
                    }
                    if (cell.hasFlow && ((cell.flowAngle & 0xFF) >>> ChunkFlowField.MAG_SHIFT) == 0) flowless++;
                }
                System.out.printf("RHSR seed=%d window=(%d,%d) riverCells=%d hiddenWater=%d phantomRiver=%d surfaceMismatch=%d zeroMagnitudeFlow=%d%s%n",
                    seed, cx, cz, river, hidden, phantom, mismatched, flowless, examples);
                assertTrue(river > 0, "window contains river cells");
                assertEquals(0, hidden, "non-wetland water must be RIVER so Streams Reflowing sees it");
                assertEquals(0, phantom, "RIVER terrain must carry water");
                assertEquals(0, mismatched, "Streams Reflowing surface must match placed water within 1 block");
                assertEquals(0, flowless, "flowing cells must have a non-zero packed magnitude");
            }
        }
    }
}
