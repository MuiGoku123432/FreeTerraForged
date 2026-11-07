package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

class HighlandConfluenceWaterTest {
    /**
     * Connected water meets across a reach boundary beside the junction near (8835, 13089) on seed 8675309. The model-8 test
     * pinned two coordinate pairs; the RH10 coast moves the owner's drainage and with it the channel by a couple of blocks (a
     * pinned pair then straddles the bank), so the same neighbourhood is scanned instead: every 4-block pair of flowing water
     * columns (horizontal and vertical, 2-block stride) keeps its water level within 2 blocks.
     */
    @Test void loweredProfileMeetsAcrossAReachBoundaryBesideTheJunction() {
        try (var context = WorldgenFixture.context(HighlandQualityProbe.preset(),8675309)) {
            Cell a = new Cell(), b = new Cell();
            int pairs = 0;
            for (int z = 13050; z <= 13130; z += 2) for (int x = 8800; x <= 8870; x += 2) for (int[] d : new int[][]{{4, 0}, {0, 4}}) {
                context.heightmap.apply(a.reset(), x, z, false);
                context.heightmap.apply(b.reset(), x + d[0], z + d[1], false);
                if (!(a.hasRiverWater && b.hasRiverWater && a.hasFlow && b.hasFlow)) continue;
                pairs++;
                double step = Math.abs(a.riverWaterLevel-b.riverWaterLevel)*context.levels.terrainScaleFactor;
                assertTrue(step < 2,"stepped connected water at "+x+","+z+" to "+(x + d[0])+","+(z + d[1])+": "+step);
            }
            assertTrue(pairs >= 500, "too few connected water pairs near the junction: " + pairs);
        }
    }
}
