package etcodehome.freeterraforged.world.worldgen.highlands;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
class TmpProbeTest {
    @Test void bench() throws Exception {
        for (var type : new ContinentType[]{ContinentType.ROLLING_HIGHLANDS, ContinentType.UPLIFT}) {
            var p = Presets.makeFTFDefault(); p.world().continent.continentType = type;
            try (var context = WorldgenFixture.context(p, 0)) {
                Cell c = new Cell();
                for (int pass = 0; pass < 2; pass++) {
                    long t0 = System.nanoTime(); int n = 0;
                    for (int z = 5000; z < 5600; z++) for (int x = 6000; x < 6600; x++) { context.heightmap.apply(c.reset(), x, z, true); n++; }
                    double ns = (System.nanoTime() - t0) / (double) n;
                    System.out.printf("RHB %s pass=%d %.0f ns/sample%n", type, pass, ns);
                }
            }
        }
    }
}
