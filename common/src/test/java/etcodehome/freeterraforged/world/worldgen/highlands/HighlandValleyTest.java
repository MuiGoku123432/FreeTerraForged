package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HighlandValleyTest {
    @Test void widthDoesNotChangeBedAndBanksJoinContinuously() {
        for (double width : new double[]{64, 256, 512}) {
            assertEquals(56, HighlandValley.sample(0, 150, 60, 4, 12, 16, width, 400).height());
            double previous = 56;
            for (double d = 0; d <= 700; d += .25) {
                double h = HighlandValley.sample(d, 150, 60, 4, 12, 16, width, 400).height();
                assertTrue(h >= previous - 1e-9);
                previous = h;
            }
            for (double edge : new double[]{12, 28, 28 + width / 2, 428 + width / 2}) {
                double left = HighlandValley.sample(edge - 1e-5, 150, 60, 4, 12, 16, width, 400).height();
                double right = HighlandValley.sample(edge + 1e-5, 150, 60, 4, 12, 16, width, 400).height();
                assertEquals(left, right, 1e-6);
            }
        }
    }
}
