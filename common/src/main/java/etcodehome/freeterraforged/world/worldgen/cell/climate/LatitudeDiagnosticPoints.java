package etcodehome.freeterraforged.world.worldgen.cell.climate;

import java.util.ArrayList;
import java.util.List;
import etcodehome.freeterraforged.data.worldgen.preset.settings.LatitudeSettings;

public final class LatitudeDiagnosticPoints {
    public static final int MAX_POINTS = 4096;
    private LatitudeDiagnosticPoints() {}
    public record Point(int x, int z) {}

    /** Validate the entire request before acquiring terrain. Zero steps deliberately retain duplicates. */
    public static List<Point> transect(int x, int z, int stepX, int stepZ, int count) {
        if (count < 0 || count > MAX_POINTS) throw new IllegalArgumentException("Sample count must be 0..4096");
        var points = new ArrayList<Point>(count);
        for (int i = 0; i < count; i++) {
            long px = (long) x + (long) stepX * i;
            long pz = (long) z + (long) stepZ * i;
            if (px < -LatitudeSettings.LIMIT || px > LatitudeSettings.LIMIT || pz < -LatitudeSettings.LIMIT || pz > LatitudeSettings.LIMIT) {
                throw new IllegalArgumentException("Transect extends beyond supported world coordinates");
            }
            points.add(new Point((int) px, (int) pz));
        }
        return List.copyOf(points);
    }
}
