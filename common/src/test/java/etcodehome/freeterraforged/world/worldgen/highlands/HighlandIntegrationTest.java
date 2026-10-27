package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

class HighlandIntegrationTest {
    @Test void generatedSamplerMatchesProfilesAndRendersFiveSeeds() throws Exception {
        for (long seed : new long[]{0, 1, 42, 8675309, -314159}) {
            var preset = Presets.makeFTFDefault();
            preset.world().continent.continentType = ContinentType.ROLLING_HIGHLANDS;
            try (var context = WorldgenFixture.context(preset, seed); var coast = new HighlandCoast(context)) {
                var fields = new HighlandFields(seed + preset.terrain().general.terrainSeedOffset, 4096);
                var plan = new HighlandDrainage(coast, fields, 4096, preset.world().controlPoints).build(0, 0);
                int wet = 0;
                for (var node : plan.nodes()) {
                    if (node.parent() < 0) continue;
                    Cell c = new Cell();
                    context.heightmap.applyPrecise(c, node.x(), node.z(), false);
                    if (c.hasRiverWater) {
                        assertEquals(node.water(), (c.riverWaterLevel - context.levels.water) * context.levels.terrainScaleFactor, 1e-4,
                            "seed=" + seed + " at " + node.x() + "," + node.z());
                        assertTrue(c.height <= c.riverWaterLevel, "seed=" + seed + " at " + node.x() + "," + node.z() + " h=" + c.height + " w=" + c.riverWaterLevel);
                        wet++;
                    } else assertTrue(c.continentEdge <= preset.world().controlPoints.coast);
                }
                assertTrue(wet > 100);
                BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
                // Fixed region containing the primary continent, never selected by attractiveness.
                for (int z = 0; z < 256; z++) for (int x = 0; x < 256; x++) {
                    Cell c = new Cell();
                    context.heightmap.apply(c, x * 64, z * 64, false);
                    double h = (c.height - context.levels.water) * context.levels.terrainScaleFactor;
                    assertTrue(Double.isFinite(h));
                    int v = (int) HighlandMath.clamp(55 + h * .6, 0, 255);
                    int color = c.hasRiverWater ? 0x287dcc : h < 0 ? 0x163a65 : (v << 16) | ((int) HighlandMath.clamp(v + 35, 0, 255) << 8) | (v / 2);
                    image.setRGB(x, z, color);
                }
                Path output = Path.of("build/rolling-highlands/seed-" + seed + ".png");
                Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile());
            }
        }
    }
}
