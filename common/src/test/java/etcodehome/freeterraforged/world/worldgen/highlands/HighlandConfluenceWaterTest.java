package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

class HighlandConfluenceWaterTest {
    @Test void loweredProfileMeetsAcrossAReachBoundaryBesideTheJunction() {
        try (var context = WorldgenFixture.context(HighlandQualityProbe.preset(),8675309)) {
            Cell a = new Cell(), b = new Cell();
            for (int[] pair : new int[][]{{8835,13085,8835,13089},{8827,13093,8831,13093}}) {
                context.heightmap.apply(a.reset(),pair[0],pair[1],false);
                context.heightmap.apply(b.reset(),pair[2],pair[3],false);
                assertTrue(a.hasRiverWater && b.hasRiverWater && a.hasFlow && b.hasFlow);
                double step = Math.abs(a.riverWaterLevel-b.riverWaterLevel)*context.levels.terrainScaleFactor;
                assertTrue(step < 2,"stepped connected water: "+step);
            }
        }
    }
}
