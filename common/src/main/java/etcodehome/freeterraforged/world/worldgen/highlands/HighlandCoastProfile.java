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

    private final long seed;
    private volatile Climate climate;

    public HighlandCoastProfile(GeneratorContext context) {
        this.seed = HighlandMath.mix(context.seed.root() ^ 0x5248394321L);
    }

    /** Regional climate source; set once, after {@code Climate.make}, before any plan is built. */
    void climate(Climate climate) { this.climate = climate; }

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
        double width = (MIN_WIDTH + (MAX_WIDTH - MIN_WIDTH) * noise(seed + 3, x, z, WIDTH_SCALE)) * (1 + .4 * baja) * (1 - .2 * pnw);
        double n = noise(seed + 4, x, z, HEIGHT_SCALE);
        double bluffHeight = MIN_BLUFF + (MAX_BLUFF - MIN_BLUFF) * n, cliffHeight = MIN_CLIFF + (MAX_CLIFF - MIN_CLIFF) * n;
        double height = bluff + cliff > 0 ? (bluff * bluffHeight + cliff * cliffHeight) / (bluff + cliff) : bluffHeight;
        return new Shore(beach, bluff, cliff, width, height, baja, hardness, temperature, moisture);
    }

    /** Signed coastline displacement (blocks; positive moves the coast seaward), |warp| ≤ WARP_AMPLITUDE. */
    public double warp(double x, double z) {
        double hard = clamp((hardness(x, z) - .5) / .25, -1, 1);
        double cove = clamp((noise(seed + 2, x, z, COVE_SCALE) - .5) / .25, -1, 1);
        return WARP_AMPLITUDE * clamp(.45 * hard + .55 * cove, -1, 1);
    }

    private double hardness(double x, double z) { return noise(seed + 1, x, z, HARDNESS_SCALE); }
}
