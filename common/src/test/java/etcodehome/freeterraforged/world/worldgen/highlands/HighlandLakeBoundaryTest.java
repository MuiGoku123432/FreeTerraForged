package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

/** One-block cell check of every ordinary-lake edge, independent of the resolver's sampling grid. */
class HighlandLakeBoundaryTest {
    @Test void terrainFiltersPreserveTheBanksOfTheIcedLake() {
        try (var context = WorldgenFixture.context(HighlandQualityProbe.preset(),42)) {
            Cell wet = new Cell(), dry = new Cell(), raw = new Cell();
            for (int[] point : new int[][]{{-1545,-1746},{-1546,-1745}}) {
                context.lookup.applyCell(wet.reset(),point[0],point[1],true,false);
                assertTrue(wet.hasRiverWater);
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    int x=point[0]+d[0],z=point[1]+d[1];
                    context.lookup.applyCell(dry.reset(),x,z,true,false);
                    if(dry.hasRiverWater) continue;
                    context.heightmap.apply(raw.reset(),x,z,false);
                    assertTrue(context.levels.scale(raw.height)>=context.levels.scale(wet.riverWaterLevel),"raw bank contains water");
                    assertTrue(raw.erosionMask,"the immediate bank participates in existing filter protection");
                    assertTrue(context.levels.scale(dry.height)>=context.levels.scale(wet.riverWaterLevel),
                        "filtered lake bank below water at "+x+","+z);
                }
            }
        }
    }
    @Test void dryNeighboursContainWaterAtEveryLakeEdge() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var rh = (RollingHighlandsContinent) context.heightmap.continent();
            var index = rh.planOf(0,0); var basins = index.plan().basins();
            var field = HighlandBasins.class.getDeclaredField("bodies"); field.setAccessible(true);
            @SuppressWarnings("unchecked") var bodies = (java.util.List<HighlandBasins.Body>) field.get(basins);
            for (var oxbow : index.plan().oxbows()) for (int j = oxbow.nodeFrom(); j <= oxbow.nodeTo(); j++)
                assertTrue(oxbow.water() <= index.plan().nodes().get(j).water()+1e-9,"oxbow above its revised channel");
            Cell wet = new Cell(), dry = new Cell();
            int checked = 0, spills = 0; double worst = 0;
            StringBuilder examples = new StringBuilder();
            int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
            for (int id = 0; id < basins.count(); id++) {
                double[] bounds = basins.lakeBounds(id);
                for (int z = (int) bounds[1]; z <= bounds[3]; z++) for (int x = (int) bounds[0]; x <= bounds[2]; x++) {
                    var lake = basins.sample(x,z);
                    if (bodies.get(id).depth(x,z) > 0)
                        assertEquals(id,lake.lake(),"overlapping body ownership at "+x+","+z);
                    if (lake == null || lake.lake() != id) continue;
                    for (var d : directions) {
                        if (basins.sample(x+d[0],z+d[1]) != null) continue;
                        context.heightmap.apply(wet.reset(),x,z,false);
                        context.heightmap.apply(dry.reset(),x+d[0],z+d[1],false);
                        if (!wet.hasRiverWater || dry.hasRiverWater) continue; // river outlets are intentional openings
                        checked++;
                        int surface = context.levels.scale(wet.riverWaterLevel), ground = context.levels.scale(dry.height);
                        if (ground < surface) {
                            worst = Math.max(worst,surface-ground);
                            if (spills++ < 10) examples.append(" [lake=").append(id).append(" wet=").append(x).append(',').append(z)
                                .append(" dry=").append(x+d[0]).append(',').append(z+d[1]).append(" water=").append(surface).append(" ground=").append(ground).append(']');
                        }
                    }
                }
            }
            String result = "RHL boundary checked="+checked+" spills="+spills+" worst="+worst+examples;
            System.out.println(result);
            assertTrue(checked > 0);
            assertEquals(0,spills,result);
        }
    }
}
