package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

class LegacyTerrainTest {
    @Test void frozenUnstableCustomTerrain() throws Exception {
        StringBuilder actual = new StringBuilder();
        for (var mode : new ContinentType[]{ContinentType.UPLIFT, ContinentType.MULTI_IMPROVED}) {
            for (long seed : new long[]{0, 42, -314159}) {
                Preset preset = Presets.makeFTFDefault();
                preset.world().continent.continentType = mode;
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                try (var context = WorldgenFixture.context(preset, seed)) {
                    for (int i = 0; i < 256; i++) {
                        Cell c = new Cell();
                        context.heightmap.apply(c, i * 193 - 24000, i * 397 - 45000, true);
                        String row = Float.floatToIntBits(c.height) + "," + c.terrain.getName() + ","
                            + Float.floatToIntBits(c.riverMask) + "," + Float.floatToIntBits(c.riverWaterLevel)
                            + "," + Float.floatToIntBits(c.temperature) + "," + Float.floatToIntBits(c.moisture);
                        hash.update(row.getBytes(StandardCharsets.UTF_8));
                    }
                }
                actual.append(mode).append(' ').append(seed).append(' ').append(HexFormat.of().formatHex(hash.digest())).append('\n');
            }
        }
        Path fixture = Path.of("src/test/resources/rolling-highlands/legacy-terrain.sha256");
        if (Boolean.getBoolean("ftf.captureBaseline")) Files.writeString(fixture, actual);
        assertEquals(Files.readString(fixture), actual.toString());
    }
}
