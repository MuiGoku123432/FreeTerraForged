package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Immutable saved generation identity; UI edits replace this record instead of mutating live worlds. Terrain shape
 * itself comes from the normal FTF Terrain settings; these controls cover what Rolling Highlands adds on top:
 * the broad upland uplift, stream-power valley depth and valley floor width.
 *
 * @param uplandSpacing distance between broad uplands and basins (blocks)
 * @param uplandHeight  extra height of the uplands above the basins (blocks)
 * @param valleyDepth   stream-power incision strength (percent; 100 = calibrated default)
 * @param valleyWidth   reference flat valley-floor width (blocks)
 * @param meanders      meander strength (percent; 0 = channels follow their valley axis, 100 = default, 200 = maximum)
 * @param rangeHeight   crest height of the RH mountain ranges above the land (blocks; 0 = no ranges)
 * @param rangeLength   typical length scale of the RH mountain ranges (blocks)
 */
public record RollingHighlandsSettings(int modelVersion, int uplandSpacing, int uplandHeight, int valleyDepth, int valleyWidth, int meanders,
                                       int rangeHeight, int rangeLength) {
    /**
     * Generation model. 3 = RH3 (legacy FTF land, stream-power drainage, FTF river profile, dam lakes); 4 = RH4
     * (jittered-lattice routing, meandering channels with oxbows, natural tributary mouths, rounded confluences);
     * 5 = RH5 (flat routing friction, creek-to-river hierarchy, structural lakes independent of River Count, tarns,
     * contour shorelines, smoothed climate height, mountain ranges); 6 = RH6 (water-relative floor, low-envelope
     * planning nodes, thalweg-following valley axes).
     */
    public static final int MODEL_VERSION = 6;
    public static final int DEFAULT_MEANDERS = 100;
    /** RH5 mountain ranges: off by default (0 blocks); length scale 6144 blocks. */
    public static final int DEFAULT_RANGE_HEIGHT = 0, DEFAULT_RANGE_LENGTH = 6144;
    public static final int MAX_RANGE_HEIGHT = 240, MIN_RANGE_LENGTH = 2048, MAX_RANGE_LENGTH = 16384;
    public static final RollingHighlandsSettings DEFAULT = new RollingHighlandsSettings(MODEL_VERSION, 4096, 80, 100, 256, DEFAULT_MEANDERS);

    /** Profile with the default meander strength and no ranges. */
    public RollingHighlandsSettings(int modelVersion, int uplandSpacing, int uplandHeight, int valleyDepth, int valleyWidth) {
        this(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, DEFAULT_MEANDERS);
    }
    /** Profile without mountain ranges. */
    public RollingHighlandsSettings(int modelVersion, int uplandSpacing, int uplandHeight, int valleyDepth, int valleyWidth, int meanders) {
        this(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, meanders, DEFAULT_RANGE_HEIGHT, DEFAULT_RANGE_LENGTH);
    }
    public RollingHighlandsSettings withMeanders(int value) {
        return new RollingHighlandsSettings(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, value, rangeHeight, rangeLength);
    }
    public RollingHighlandsSettings withRanges(int height, int length) {
        return new RollingHighlandsSettings(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, meanders, height, length);
    }
    public static final Codec<RollingHighlandsSettings> CODEC = RecordCodecBuilder.<RollingHighlandsSettings>create(i -> i.group(
        integer(1, Integer.MAX_VALUE).fieldOf("modelVersion").forGetter(RollingHighlandsSettings::modelVersion),
        integer(4096, 8192).fieldOf("uplandSpacing").forGetter(RollingHighlandsSettings::uplandSpacing),
        // Optional so that older model presets reach the version check and get its actionable message.
        integer(0, 160).optionalFieldOf("uplandHeight", 80).forGetter(RollingHighlandsSettings::uplandHeight),
        integer(25, 200).optionalFieldOf("valleyDepth", 100).forGetter(RollingHighlandsSettings::valleyDepth),
        integer(64, 512).fieldOf("valleyWidth").forGetter(RollingHighlandsSettings::valleyWidth),
        integer(0, 200).optionalFieldOf("meanders", DEFAULT_MEANDERS).forGetter(RollingHighlandsSettings::meanders),
        integer(0, MAX_RANGE_HEIGHT).optionalFieldOf("rangeHeight", DEFAULT_RANGE_HEIGHT).forGetter(RollingHighlandsSettings::rangeHeight),
        integer(MIN_RANGE_LENGTH, MAX_RANGE_LENGTH).optionalFieldOf("rangeLength", DEFAULT_RANGE_LENGTH).forGetter(RollingHighlandsSettings::rangeLength)
    ).apply(i, RollingHighlandsSettings::new)).validate(s -> s.modelVersion == MODEL_VERSION
        ? DataResult.success(s) : DataResult.error(() -> unsupported(s.modelVersion)));

    private static String unsupported(int version) {
        return version < MODEL_VERSION
            ? "Rolling Highlands model " + version + " was an earlier experimental build and is no longer generated; create a new world (model " + MODEL_VERSION + ")"
            : "Unsupported Rolling Highlands model version " + version + " (this build generates model " + MODEL_VERSION + ")";
    }

    private static Codec<Integer> integer(int min, int max) {
        return Codec.DOUBLE.comapFlatMap(value -> Double.isFinite(value) && value == Math.rint(value) && value >= min && value <= max
            ? DataResult.success(value.intValue()) : DataResult.error(() -> "Expected a finite integer in " + min + ".." + max), Integer::doubleValue);
    }

    public void validate(WorldSettings world) {
        if (modelVersion != MODEL_VERSION) throw new IllegalArgumentException(unsupported(modelVersion));
        if (uplandSpacing < 4096 || uplandSpacing > 8192 || uplandHeight < 0 || uplandHeight > 160
            || valleyDepth < 25 || valleyDepth > 200 || valleyWidth < 64 || valleyWidth > 512 || meanders < 0 || meanders > 200
            || rangeHeight < 0 || rangeHeight > MAX_RANGE_HEIGHT || rangeLength < MIN_RANGE_LENGTH || rangeLength > MAX_RANGE_LENGTH) {
            throw new IllegalArgumentException("Unsupported Rolling Highlands profile; check model version and control ranges");
        }
        if (world.continent.continentScale < 1000 || world.continent.continentScale > 5000) {
            throw new IllegalArgumentException("Experimental Rolling Highlands supports continent scale 1000..5000");
        }
        double span = world.continent.continentScale * 4 * 3.33 + 4;
        // Mirrors the routing grid budget (HighlandDrainage.MAX_GRID_NODES).
        int step = 64;
        while (Math.pow(Math.ceil(span / step) + 3, 2) > 131_072) step *= 2;
        if (uplandSpacing < step * 8) throw new IllegalArgumentException("This continent scale needs upland spacing >= " + step * 8);
        if (world.properties.worldHeight - world.properties.seaLevel < 320) {
            throw new IllegalArgumentException("Rolling Highlands needs at least 320 blocks of height above sea level");
        }
        if (!(world.controlPoints.inland > world.controlPoints.coast)) {
            throw new IllegalArgumentException("Rolling Highlands requires inland control point > coast");
        }
    }
}
