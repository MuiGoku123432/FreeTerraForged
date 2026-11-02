package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import org.junit.jupiter.api.Test;

/**
 * RH5 C: Rolling Highlands climate reads a smoothed landscape height, so altitude cooling and rain shadow do not follow
 * riverbeds or single hilltops. Measured on 96-block transects across rivers of the default preset (seed 0): the
 * climate height changes by well under a block per block and does not dip into the valley the way the ground does.
 * (Temperature itself is dithered at biome-region edges by design, so it is not compared block to block.)
 */
class HighlandClimateTest {
    @Test void climateIgnoresRiverbedsAndHillDetail() {
        try (var context = WorldgenFixture.context(HighlandQualityProbe.preset(), 0)) {
            var continent = (RollingHighlandsContinent) context.heightmap.continent();
            var plan = continent.planOf(0, 0).plan();
            var nodes = plan.nodes();
            Cell c = new Cell();
            double scale = context.levels.terrainScaleFactor, worstStep = 0, worstClimateDip = 0, climateDips = 0, groundDips = 0;
            int transects = 0;
            for (int i = 0; i < nodes.size() && transects < 40; i += 97) {
                var node = nodes.get(i);
                if (node.parent() < 0 || node.water() < 15) continue;
                var p = nodes.get(node.parent());
                double fx = node.x() - p.x(), fz = node.z() - p.z(), l = Math.hypot(fx, fz);
                if (l < 1e-6) continue;
                double nx = -fz / l, nz = fx / l;
                double[] climate = new double[97], ground = new double[97];
                boolean wet = false;
                for (int d = -48; d <= 48; d++) {
                    context.heightmap.applyPrecise(c.reset(), node.x() + nx * d, node.z() + nz * d, true);
                    assertTrue(c.rollingHighlands);
                    if (d == 0) wet = c.hasRiverWater;
                    climate[d + 48] = c.climateHeight * scale; ground[d + 48] = c.height * scale;
                    if (d > -48) worstStep = Math.max(worstStep, Math.abs(climate[d + 48] - climate[d + 47]));
                }
                if (!wet) continue;
                // Dip at the channel relative to the mean of the transect ends.
                double climateDip = .5 * (climate[0] + climate[96]) - climate[48];
                worstClimateDip = Math.max(worstClimateDip, climateDip);
                climateDips += climateDip; groundDips += .5 * (ground[0] + ground[96]) - ground[48];
                transects++;
            }
            String summary = String.format(java.util.Locale.ROOT, "RHC transects=%d worstClimateStep=%.3f worstClimateDip=%.2f meanClimateDip=%.2f meanGroundDip=%.2f",
                transects, worstStep, worstClimateDip, climateDips / transects, groundDips / transects);
            System.out.println(summary);
            assertTrue(transects >= 10, summary);
            assertTrue(worstStep < .5, summary);
            assertTrue(worstClimateDip < 4 && climateDips < .4 * groundDips, summary);
        }
    }
}
