package etcodehome.freeterraforged.world.worldgen.highlands;

/**
 * RH7 S: surface cover of Rolling Highlands range columns, by slope and by height against the snowline and treeline
 * the range carries on the cell. Steep ground is bare rock, moderately steep ground above the treeline scree, gentle
 * ground above the snowline snow and between the treeline and snowline alpine meadow; below the treeline (and off
 * the ranges) the biome's own surface stays.
 */
public final class HighlandSurface {
    public enum Cover { CLIFF, SCREE, SNOW, MEADOW, BIOME }

    /** Slopes (degrees) at and above which ground is cliff rock (above / below the treeline), and scree above the treeline. */
    public static final double CLIFF_SLOPE = 45, FOREST_CLIFF_SLOPE = 50, SCREE_SLOPE = 33;
    /** Range weight below which the biome surface stays. */
    public static final double MIN_RANGE = .3;
    /** Height jitter (blocks) so band edges are ragged rather than contour lines. */
    public static final double JITTER = 6;

    private HighlandSurface() {}

    /**
     * @param slope  surface slope (degrees)
     * @param y      surface height (blocks, any datum shared with the lines)
     * @param snow   snowline height
     * @param tree   treeline height
     * @param range  range weight (cell.highlandRange)
     * @param jitter noise in [-1, 1]
     */
    public static Cover choose(double slope, double y, double snow, double tree, double range, double jitter) {
        if (range < MIN_RANGE) return Cover.BIOME;
        double h = y + JITTER * jitter;
        if (h < tree) return slope >= FOREST_CLIFF_SLOPE ? Cover.CLIFF : Cover.BIOME;
        if (slope >= CLIFF_SLOPE) return Cover.CLIFF;
        if (slope >= SCREE_SLOPE) return Cover.SCREE;
        return h >= snow ? Cover.SNOW : Cover.MEADOW;
    }
}
