package etcodehome.freeterraforged.world.worldgen.highlands;

import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.clamp;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.noise;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.smooth;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.climate.Climate;

/**
 * RH9 coast fields (SPEC-RH9 A-1 to A-4), functions of position only so the drainage plan and the renderer agree.
 * A slow hardness field picks the coast type (soft: beach, harder: bluff, hardest: sea cliff) and pushes hard rock
 * out as points; with a cove-scale noise it also drives the coastline warp. The regional climate (before altitude
 * shaping) leans the character: cool/wet coasts (PNW) more cliff and narrower beaches, warm/dry coasts (Baja) wider
 * beaches with dunes.
 */
public final class HighlandCoastProfile {
    public enum Type { BEACH, BLUFF, CLIFF }

    /**
     * Coast at a position: type weights (sum 1, blended over the transitions), dry beach width and scarp height
     * (blocks), dune amount 0..1, hardness 0..1 and the raw regional climate (-1..1; 0 without a climate).
     */
    public record Shore(double beach, double bluff, double cliff, double width, double height, double dunes,
                        double hardness, double temperature, double moisture) {
        public Type dominant() {
            return beach >= bluff && beach >= cliff ? Type.BEACH : bluff >= cliff ? Type.BLUFF : Type.CLIFF;
        }
    }

    /** Largest coastline displacement (blocks): seaward on hard rock (points), landward on soft rock (coves). */
    public static final double WARP_AMPLITUDE = 200;
    /** Hardness and cove noise wavelengths, width / height variation wavelengths (blocks). */
    static final double HARDNESS_SCALE = 1400, COVE_SCALE = 420, WIDTH_SCALE = 700, HEIGHT_SCALE = 900;
    /**
     * Hardness thresholds: the 55th and 80th percentiles of the single-octave noise, so about 55% beach, 25% bluff,
     * 20% cliff (A-1). BLEND is the half width of each transition (about 25-30 blocks either side at the noise's
     * typical slope, A-2).
     */
    static final double BLUFF_AT = .5225, CLIFF_AT = .6491, BLEND = .015;
    /** Hardness added on cool/wet coasts (about +10 points of cliff share, A-3). */
    static final double PNW_HARDNESS = .05;
    /** Dry beach width and scarp heights (blocks, A-1). */
    static final double MIN_WIDTH = 15, MAX_WIDTH = 40, MIN_BLUFF = 6, MAX_BLUFF = 16, MIN_CLIFF = 15, MAX_CLIFF = 40;

    /**
     * RH10 fjord walls: how far the cliff weight moves toward 1 under a full fjord weight. The cliff height is no longer lifted toward MAX_CLIFF
     * there (11-16 removed FJORD_CLIFF_LIFT, which made every wall about 40 blocks): the wall height comes from the local relief in
     * {@code HighlandDrainage.coastalLand}.
     */
    static final double FJORD_CLIFF = .9;

    /**
     * RH10 calm bays (D-16): under a full ria weight the beach weight moves {@code RIA_BEACH} of the way to 1 (bluff and cliff
     * shrink with it), the dry beach width shrinks to {@code RIA_WIDTH} of its value (more and smaller beaches), and scarp
     * heights are capped at the top of the bluff range ({@link #MAX_BLUFF}), so bays have no tall sea cliffs.
     */
    static final double RIA_BEACH = .6, RIA_WIDTH = .6;

    private final long seed;
    private volatile Climate climate;
    private volatile HighlandDrowned drowned;

    public HighlandCoastProfile(GeneratorContext context) {
        this.seed = HighlandMath.mix(context.seed.root() ^ 0x5248394321L);
    }

    /** Regional climate source; set once, after {@code Climate.make}, before any plan is built. */
    void climate(Climate climate) { this.climate = climate; }
    /** RH10 drowned layer, for fjord rock walls; set once by the coast that owns both. */
    void drowned(HighlandDrowned drowned) { this.drowned = drowned; }

    public Shore shore(double x, double z) {
        double temperature = 0, moisture = 0;
        var source = climate;
        if (source != null) {
            var raw = source.sampleRegional((float) x, (float) z);
            temperature = raw.temperature(); moisture = raw.moisture();
        }
        double pnw = smooth(.05, .45, (moisture - temperature) * .5), baja = smooth(.05, .45, (temperature - moisture) * .5);
        double hardness = clamp(hardness(x, z) + PNW_HARDNESS * pnw, 0, 1);
        double cliff = smooth(CLIFF_AT - BLEND, CLIFF_AT + BLEND, hardness);
        double bluff = smooth(BLUFF_AT - BLEND, BLUFF_AT + BLEND, hardness) - cliff;
        double beach = 1 - bluff - cliff;
        // RH10: under fjord regions the shore is rock wall. The cliff weight moves toward 1 and bluff and beach shrink with it,
        // so the weights still sum to 1; the climate class (hence the PNW rock palette) is unchanged. Typing reads only the raw
        // climate, so this cannot recurse. Heads: where a river enters, the river-mouth fade turns the wall to a small flat.
        var layer = drowned;
        double fjord = layer == null ? 0 : layer.fjord(x, z), toward = FJORD_CLIFF * fjord;
        if (toward > 0) {
            cliff += toward * (1 - cliff);
            bluff *= 1 - toward; beach *= 1 - toward;
        }
        // RH10: calm bays. Same weights-sum-to-1 move as the fjord walls, toward beach; the climate class and palette are unchanged.
        double ria = layer == null ? 0 : layer.ria(x, z) * (1 - fjord), calm = RIA_BEACH * ria;
        if (calm > 0) {
            beach += calm * (1 - beach);
            bluff *= 1 - calm; cliff *= 1 - calm;
        }
        double width = (MIN_WIDTH + (MAX_WIDTH - MIN_WIDTH) * noise(seed + 3, x, z, WIDTH_SCALE)) * (1 + .4 * baja) * (1 - .2 * pnw) * (1 - (1 - RIA_WIDTH) * ria);
        double n = noise(seed + 4, x, z, HEIGHT_SCALE);
        double bluffHeight = MIN_BLUFF + (MAX_BLUFF - MIN_BLUFF) * n, cliffHeight = MIN_CLIFF + (MAX_CLIFF - MIN_CLIFF) * n;
        cliffHeight += ria * (Math.min(cliffHeight, MAX_BLUFF) - cliffHeight);
        double height = bluff + cliff > 0 ? (bluff * bluffHeight + cliff * cliffHeight) / (bluff + cliff) : bluffHeight;
        return new Shore(beach, bluff, cliff, width, height, baja, hardness, temperature, moisture);
    }

    /** Signed coastline displacement (blocks; positive moves the coast seaward), |warp| ≤ WARP_AMPLITUDE. */
    public double warp(double x, double z) {
        double hard = clamp((hardness(x, z) - .5) / .25, -1, 1);
        double cove = clamp((noise(seed + 2, x, z, COVE_SCALE) - .5) / .25, -1, 1);
        return WARP_AMPLITUDE * clamp(.45 * hard + .55 * cove, -1, 1);
    }

    /** Coast climate classes (A-3): middle (0, also off the coast), cool/wet, warm/dry. */
    public static final byte CALIFORNIA = 0, PNW = 1, BAJA = 2;
    /** Climate class of a shore: PNW or Baja where that lean is more than half on, otherwise California. */
    public static byte climateClass(Shore shore) { return classOf(shore.moisture(), shore.temperature()); }
    /**
     * Climate class at a position from the raw regional climate alone (CALIFORNIA when no climate is bound). It does not
     * evaluate {@link #shore}, so RH10 region typing can use it where the shore itself reads the drowned layer.
     */
    byte climateClassAt(double x, double z) {
        var source = climate;
        if (source == null) return CALIFORNIA;
        var raw = source.sampleRegional((float) x, (float) z);
        return classOf(raw.moisture(), raw.temperature());
    }
    private static byte classOf(double moisture, double temperature) {
        double lean = (moisture - temperature) * .5;
        return smooth(.05, .45, lean) > .5 ? PNW : smooth(.05, .45, -lean) > .5 ? BAJA : CALIFORNIA;
    }
    /** Dry beach in front of a bluff's scarp (blocks): half the beach width, 8-20 (A-1). */
    public static double bluffWidth(double width) { return clamp(width * .5, MIN_BLUFF_BEACH, MAX_BLUFF_BEACH); }
    /** Beach berm height at {@code d} blocks from the waterline: rises over BERM_RUN to BERM. */
    public static double berm(double d) { return BERM * smooth(0, BERM_RUN, d); }
    /** Dune height at {@code d} blocks inland on a beach: hummocks over the back of the beach, up to DUNE blocks. */
    public double dune(double x, double z, double d, Shore shore) {
        if (shore.dunes() <= 0 || d < .4 * shore.width()) return 0;
        double band = smooth(.4 * shore.width(), .7 * shore.width(), d) * (1 - smooth(shore.width(), shore.width() + DUNE_BACK, d));
        return band <= 0 ? 0 : shore.dunes() * DUNE * band * noise(seed + 5, x, z, DUNE_SCALE);
    }
    /** Beach berm top and its rise (blocks); dune height, hummock wavelength and back slope (blocks). */
    public static final double BERM = 2.5, BERM_RUN = 6, DUNE = 4, DUNE_SCALE = 20, DUNE_BACK = 16;
    static final double MIN_BLUFF_BEACH = 8, MAX_BLUFF_BEACH = 20;

    private double hardness(double x, double z) { return noise(seed + 1, x, z, HARDNESS_SCALE); }
}
