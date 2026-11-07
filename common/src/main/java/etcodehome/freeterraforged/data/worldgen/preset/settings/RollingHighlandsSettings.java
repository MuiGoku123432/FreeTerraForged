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
 * @param rangesReplaceMountains with ranges on, the legacy FTF mountain terrain is mostly replaced by the ranges
 *                      (its weight falls to REPLACED_MOUNTAIN_WEIGHT), so mountains live in ranges (RH6 D-b)
 * @param snowline      glacial snowline as a percentage of the Range Height above the range foot (RH7 G; 100 = no
 *                      glaciation)
 * @param rangeRidges   parallel ridges across each range (RH8; 1 = the single model-7 crest)
 */
public record RollingHighlandsSettings(int modelVersion, int uplandSpacing, int uplandHeight, int valleyDepth, int valleyWidth, int meanders,
                                       int rangeHeight, int rangeLength, boolean rangesReplaceMountains, int snowline,
                                       int rangeRidges) {
    /**
     * Generation model. 3 = RH3 (legacy FTF land, stream-power drainage, FTF river profile, dam lakes); 4 = RH4
     * (jittered-lattice routing, meandering channels with oxbows, natural tributary mouths, rounded confluences);
     * 5 = RH5 (flat routing friction, creek-to-river hierarchy, structural lakes independent of River Count, tarns,
     * contour shorelines, smoothed climate height, mountain ranges); 6 = RH6 (water-relative floor, tilted valley
     * floors, thalweg-following valley axes, valley lakes with clean junctions, ranges that replace the legacy
     * mountains); 7 = RH7 (drainage-carved ranges, Range Height up to 640, no legacy mountains under ranges);
     * 8 = RH9 (coast: wide beaches, bluffs and sea cliffs by climate, coves and points);
     * 9 = RH10 (drowned coast: flooded river-valley bays, fjords and island chains; the coastline moves inland along
     * valleys, land stays above sea level).
     */
    public static final int MODEL_VERSION = 9;
    public static final int DEFAULT_MEANDERS = 100;
    /** RH5 mountain ranges: off by default (0 blocks); length scale 6144 blocks. */
    public static final int DEFAULT_RANGE_HEIGHT = 0, DEFAULT_RANGE_LENGTH = 6144;
    public static final int MAX_RANGE_HEIGHT = 640, MIN_RANGE_LENGTH = 2048, MAX_RANGE_LENGTH = 16384;
    /** RH7 G: default snowline (percent of Range Height above the range foot) and its range. */
    public static final int DEFAULT_SNOWLINE = 60, MIN_SNOWLINE = 20, MAX_SNOWLINE = 100;
    /** RH8: ridges per range (optional; 1 keeps model-7 worlds unchanged, so no model bump). */
    public static final int DEFAULT_RANGE_RIDGES = 1, MAX_RANGE_RIDGES = 4;
    /**
     * Continent settings RH is calibrated and verified against (the FTF default before upstream 9a507eec). A preset
     * switched to Rolling Highlands starts from these; the shared FTF default no longer matches them.
     */
    public static final int BASELINE_CONTINENT_SCALE = 4000;
    public static final float BASELINE_CONTINENT_JITTER = 0.8F, BASELINE_CONTINENT_SKIPPING = 0.2F, BASELINE_CONTINENT_SIZE_VARIANCE = 0.6161F;
    /**
     * Highest supported continent jitter. RH drainage needs ocean between neighbouring continents; at jitter 1.0
     * neighbouring land can touch (seed 42 at the FTF defaults), which RH does not route ("land bridge requires
     * shared ports").
     */
    public static final float MAX_CONTINENT_JITTER = 0.8F;

    /** Sets the continent controls RH is calibrated for; the continent type is left alone. */
    public static void applyContinentBaseline(WorldSettings.Continent continent) {
        continent.continentScale = BASELINE_CONTINENT_SCALE;
        continent.continentJitter = BASELINE_CONTINENT_JITTER;
        continent.continentSkipping = BASELINE_CONTINENT_SKIPPING;
        continent.continentSizeVariance = BASELINE_CONTINENT_SIZE_VARIANCE;
    }
    public static final RollingHighlandsSettings DEFAULT = new RollingHighlandsSettings(MODEL_VERSION, 4096, 80, 100, 256, DEFAULT_MEANDERS);
    /**
     * Profile a new Rolling Highlands world starts from (RH7, the "Rockies" setup): broad high uplands, wide gently
     * meandering valleys and 400-block glaciated ranges of three parallel ridges (RH8) that replace the legacy
     * mountains. DEFAULT stays the
     * calibration profile the tests measure against.
     */
    public static final RollingHighlandsSettings ROCKIES = new RollingHighlandsSettings(MODEL_VERSION, 6144, 120, 120, 448, 80,
        400, DEFAULT_RANGE_LENGTH, true, DEFAULT_SNOWLINE, 3);
    /** Share of the legacy mountain terrain weight kept when ranges replace mountains (RH7 D-c: none). */
    public static final float REPLACED_MOUNTAIN_WEIGHT = 0F;

    /** Profile with the default meander strength and no ranges. */
    public RollingHighlandsSettings(int modelVersion, int uplandSpacing, int uplandHeight, int valleyDepth, int valleyWidth) {
        this(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, DEFAULT_MEANDERS);
    }
    /** Profile without mountain ranges. */
    public RollingHighlandsSettings(int modelVersion, int uplandSpacing, int uplandHeight, int valleyDepth, int valleyWidth, int meanders) {
        this(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, meanders, DEFAULT_RANGE_HEIGHT, DEFAULT_RANGE_LENGTH);
    }
    /** Profile whose ranges (if any) replace the legacy mountains. */
    public RollingHighlandsSettings(int modelVersion, int uplandSpacing, int uplandHeight, int valleyDepth, int valleyWidth, int meanders,
                                    int rangeHeight, int rangeLength) {
        this(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, meanders, rangeHeight, rangeLength, true);
    }
    /** Profile with the default snowline. */
    public RollingHighlandsSettings(int modelVersion, int uplandSpacing, int uplandHeight, int valleyDepth, int valleyWidth, int meanders,
                                    int rangeHeight, int rangeLength, boolean rangesReplaceMountains) {
        this(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, meanders, rangeHeight, rangeLength, rangesReplaceMountains, DEFAULT_SNOWLINE);
    }
    /** Profile with one ridge per range. */
    public RollingHighlandsSettings(int modelVersion, int uplandSpacing, int uplandHeight, int valleyDepth, int valleyWidth, int meanders,
                                    int rangeHeight, int rangeLength, boolean rangesReplaceMountains, int snowline) {
        this(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, meanders, rangeHeight, rangeLength, rangesReplaceMountains, snowline, DEFAULT_RANGE_RIDGES);
    }
    public RollingHighlandsSettings withMeanders(int value) {
        return new RollingHighlandsSettings(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, value, rangeHeight, rangeLength, rangesReplaceMountains, snowline, rangeRidges);
    }
    public RollingHighlandsSettings withRanges(int height, int length) {
        return new RollingHighlandsSettings(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, meanders, height, length, rangesReplaceMountains, snowline, rangeRidges);
    }
    public RollingHighlandsSettings withRangesReplaceMountains(boolean value) {
        return new RollingHighlandsSettings(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, meanders, rangeHeight, rangeLength, value, snowline, rangeRidges);
    }
    public RollingHighlandsSettings withSnowline(int value) {
        return new RollingHighlandsSettings(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, meanders, rangeHeight, rangeLength, rangesReplaceMountains, value, rangeRidges);
    }
    public RollingHighlandsSettings withRangeRidges(int value) {
        return new RollingHighlandsSettings(modelVersion, uplandSpacing, uplandHeight, valleyDepth, valleyWidth, meanders, rangeHeight, rangeLength, rangesReplaceMountains, snowline, value);
    }
    /** Whether the legacy mountain terrain is scaled down for ranges (ranges on and the toggle set). */
    public boolean replacesMountains() { return rangeHeight > 0 && rangesReplaceMountains; }
    public static final Codec<RollingHighlandsSettings> CODEC = RecordCodecBuilder.<RollingHighlandsSettings>create(i -> i.group(
        integer(1, Integer.MAX_VALUE).fieldOf("modelVersion").forGetter(RollingHighlandsSettings::modelVersion),
        integer(4096, 8192).fieldOf("uplandSpacing").forGetter(RollingHighlandsSettings::uplandSpacing),
        // Optional so that older model presets reach the version check and get its actionable message.
        integer(0, 160).optionalFieldOf("uplandHeight", 80).forGetter(RollingHighlandsSettings::uplandHeight),
        integer(25, 200).optionalFieldOf("valleyDepth", 100).forGetter(RollingHighlandsSettings::valleyDepth),
        integer(64, 512).fieldOf("valleyWidth").forGetter(RollingHighlandsSettings::valleyWidth),
        integer(0, 200).optionalFieldOf("meanders", DEFAULT_MEANDERS).forGetter(RollingHighlandsSettings::meanders),
        integer(0, MAX_RANGE_HEIGHT).optionalFieldOf("rangeHeight", DEFAULT_RANGE_HEIGHT).forGetter(RollingHighlandsSettings::rangeHeight),
        integer(MIN_RANGE_LENGTH, MAX_RANGE_LENGTH).optionalFieldOf("rangeLength", DEFAULT_RANGE_LENGTH).forGetter(RollingHighlandsSettings::rangeLength),
        Codec.BOOL.optionalFieldOf("rangesReplaceMountains", true).forGetter(RollingHighlandsSettings::rangesReplaceMountains),
        integer(MIN_SNOWLINE, MAX_SNOWLINE).optionalFieldOf("snowline", DEFAULT_SNOWLINE).forGetter(RollingHighlandsSettings::snowline),
        integer(1, MAX_RANGE_RIDGES).optionalFieldOf("rangeRidges", DEFAULT_RANGE_RIDGES).forGetter(RollingHighlandsSettings::rangeRidges)
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
            || rangeHeight < 0 || rangeHeight > MAX_RANGE_HEIGHT || rangeLength < MIN_RANGE_LENGTH || rangeLength > MAX_RANGE_LENGTH
            || snowline < MIN_SNOWLINE || snowline > MAX_SNOWLINE || rangeRidges < 1 || rangeRidges > MAX_RANGE_RIDGES) {
            throw new IllegalArgumentException("Unsupported Rolling Highlands profile; check model version and control ranges");
        }
        if (world.continent.continentScale < 1000 || world.continent.continentScale > 5000) {
            throw new IllegalArgumentException("Experimental Rolling Highlands supports continent scale 1000..5000");
        }
        if (world.continent.continentJitter > MAX_CONTINENT_JITTER + 1e-4F) {
            throw new IllegalArgumentException("Experimental Rolling Highlands supports continent jitter up to " + MAX_CONTINENT_JITTER);
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
