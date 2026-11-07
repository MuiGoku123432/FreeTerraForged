package etcodehome.freeterraforged.world.worldgen.highlands;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.continent.advanced.AdvancedContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.util.Seed;

/** Reuses the Advanced coast policy, retaining lattice identity and precise raw coordinates. */
public final class HighlandCoast extends AdvancedContinentGenerator {
    public final int tectonicScale;
    public final double warpBound;
    private final HighlandCoastProfile profile;
    private final HighlandDrowned drowned;
    private final double coastEdge;

    public HighlandCoast(GeneratorContext context) {
        super(new Seed(context.seed.root()).offset(731993), context);
        this.tectonicScale = context.preset.world().continent.continentScale * 4;
        this.warpBound = Math.round(tectonicScale * .33F) / 2.0;
        this.profile = new HighlandCoastProfile(context);
        this.drowned = new HighlandDrowned(context, this);
        this.coastEdge = context.preset.world().controlPoints.coast;
    }
    public HighlandCoastProfile profile() { return profile; }
    HighlandDrowned drowned() { return drowned; }
    /** RH10: arms stay inert (the RH9 coast) until the land is bound, after the continent is built. */
    void bindDrowned(HighlandLand land) { drowned.bind(land); }
    /** Edge-field slope (edge units per block) at a position whose edge is {@code edge}, from two SLOPE_PROBE steps. */
    public double edgeSlope(double x, double z, double edge) {
        double gx = sample(x + SLOPE_PROBE, z).edge() - edge, gz = sample(x, z + SLOPE_PROBE).edge() - edge;
        return Math.hypot(gx, gz) / SLOPE_PROBE;
    }
    static final double SLOPE_PROBE = 2;
    /**
     * RH9 coves and points: edge-value offset per block of coastline displacement (the edge field's typical slope
     * at the coast, measured by HighlandCoastProbe), and the edge band around the coast point it is applied in. The
     * offset never reaches region borders or skipped cells (edge 0), so owners, bounds and land bridges are unchanged.
     * The fade is wide enough (offset slope ≤ .3 per edge unit) that the warped edge stays monotone in the raw edge.
     */
    static final double EDGE_PER_BLOCK = 2.9e-4, WARP_FULL = .06, WARP_FADE = .35;
    /** {@code drowned}: drowned-region weight 0..1 (0 outside RH10 regions and away from the coast band). */
    public record Sample(int ownerX, int ownerZ, int centerX, int centerZ, double edge, double drowned) {}
    public record Bounds(double minX, double minZ, double maxX, double maxZ) {}

    /** Exact bounds of the limited 3x3 selection regions, expanded by the bounded warp. */
    public Bounds bounds(int ownerX, int ownerZ) {
        var owner = NoiseUtil.cell(seed, ownerX, ownerZ);
        double px = ownerX + owner.x() * jitter, pz = ownerZ + owner.y() * jitter;
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = -minX, maxZ = -minX;
        for (int rz = ownerZ - 1; rz <= ownerZ + 1; rz++) for (int rx = ownerX - 1; rx <= ownerX + 1; rx++) {
            java.util.List<double[]> polygon = new java.util.ArrayList<>(java.util.List.of(
                new double[]{rx, rz}, new double[]{rx + 1.0, rz}, new double[]{rx + 1.0, rz + 1.0}, new double[]{rx, rz + 1.0}));
            for (int cz = rz - 1; cz <= rz + 1; cz++) for (int cx = rx - 1; cx <= rx + 1; cx++) {
                if (cx == ownerX && cz == ownerZ || polygon.isEmpty()) continue;
                var point = NoiseUtil.cell(seed, cx, cz);
                double qx = cx + point.x() * jitter, qz = cz + point.y() * jitter;
                double nx = qx - px, nz = qz - pz;
                // Local coordinates avoid subtracting squared far-world positions.
                double limit = (nx * nx + nz * nz) * .5;
                java.util.List<double[]> clipped = new java.util.ArrayList<>();
                double[] previous = polygon.getLast();
                double before = (previous[0] - px) * nx + (previous[1] - pz) * nz - limit;
                for (double[] current : polygon) {
                    double after = (current[0] - px) * nx + (current[1] - pz) * nz - limit;
                    if ((before <= 0) != (after <= 0)) {
                        double t = before / (before - after);
                        clipped.add(new double[]{previous[0] + t * (current[0] - previous[0]), previous[1] + t * (current[1] - previous[1])});
                    }
                    if (after <= 0) clipped.add(current);
                    previous = current; before = after;
                }
                polygon = clipped;
            }
            for (double[] point : polygon) {
                minX = Math.min(minX, point[0]); maxX = Math.max(maxX, point[0]);
                minZ = Math.min(minZ, point[1]); maxZ = Math.max(maxZ, point[1]);
            }
        }
        return new Bounds(minX * tectonicScale - warpBound - 2, minZ * tectonicScale - warpBound - 2,
            maxX * tectonicScale + warpBound + 2, maxZ * tectonicScale + warpBound + 2);
    }

    public Sample sample(double rawX, double rawZ) {
        var base = undrowned(rawX, rawZ);
        if (!(base.edge() > 0) || !drowned.inBand(base.edge())) return base;
        var at = drowned.at(rawX, rawZ, base.edge());
        if (at.drowned() == 0 && at.edge() == base.edge()) return base;
        return new Sample(base.ownerX(), base.ownerZ(), base.centerX(), base.centerZ(), at.edge(), at.drowned());
    }

    /** The RH9 coast sample: the edge before the drowned transform (RH10 reads region orientation from it). */
    Sample undrowned(double rawX, double rawZ) {
        // A single bounded, precise warp avoids the legacy high-frequency coastal cliff modulation.
        double x = (rawX + warpBound * (2 * HighlandMath.noise(seed + 11003L, rawX, rawZ, tectonicScale * .225) - 1)) / tectonicScale;
        double z = (rawZ + warpBound * (2 * HighlandMath.noise(seed + 19001L, rawX, rawZ, tectonicScale * .225) - 1)) / tectonicScale;
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z), ownerX = ix, ownerZ = iz;
        double nearest = Double.POSITIVE_INFINITY, pointX = x, pointZ = z;
        for (int cz = iz - 1; cz <= iz + 1; cz++) {
            for (int cx = ix - 1; cx <= ix + 1; cx++) {
                var v = NoiseUtil.cell(seed, cx, cz);
                double px = cx + v.x() * jitter, pz = cz + v.y() * jitter;
                double d = (x - px) * (x - px) + (z - pz) * (z - pz);
                if (d < nearest) { nearest = d; pointX = px; pointZ = pz; ownerX = cx; ownerZ = cz; }
            }
        }
        double edgeDistance = Double.POSITIVE_INFINITY, sumX = 0, sumZ = 0;
        for (int cz = ownerZ - 1; cz <= ownerZ + 1; cz++) {
            for (int cx = ownerX - 1; cx <= ownerX + 1; cx++) {
                if (cx == ownerX && cz == ownerZ) continue;
                var v = NoiseUtil.cell(seed, cx, cz);
                double px = cx + v.x() * jitter, pz = cz + v.y() * jitter;
                double dx = px - pointX, dz = pz - pointZ;
                double distance = ((x - (px + pointX) * .5) * dx + (z - (pz + pointZ) * .5) * dz);
                edgeDistance = Math.min(edgeDistance, distance * distance / (dx * dx + dz * dz));
                sumX += px; sumZ += pz;
            }
        }
        double edge = shouldSkip(ownerX, ownerZ) ? 0 : NoiseUtil.map(NoiseUtil.sqrt(getVariedDistanceValue(ownerX, ownerZ, (float) edgeDistance)), 0.05F, 0.25F, 0.2F);
        if (edge > 0) edge += profile.warp(rawX, rawZ) * EDGE_PER_BLOCK * (1 - HighlandMath.smooth(WARP_FULL, WARP_FADE, Math.abs(edge - coastEdge)));
        return new Sample(ownerX, ownerZ, (int) ((pointX * .65 + sumX / 8 * .35) * tectonicScale),
            (int) ((pointZ * .65 + sumZ / 8 * .35) * tectonicScale), edge, 0);
    }
}
