package etcodehome.freeterraforged.world.worldgen.highlands;

import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/**
 * FTF's river valley shape, ported from {@code UpliftRiverCarver} into Rolling Highlands block units with an
 * explicit water level (RH supplies the drainage plan's level instead of the continental water table):
 * <ol>
 * <li>riverbed: water − depth · smoothstep(1 − d²/r1²);</li>
 * <li>bank step: water → floor over a short run, with FTF terracing (3 steps) and gully notches;</li>
 * <li>flat valley floor at water + bank height, with terrace/drainage bumpiness;</li>
 * <li>fadeout: floor → surrounding land, terraced (5 steps) with talus shelves and cliff faces.</li>
 * </ol>
 * Pure function; continuous in distance (each zone meets the next at the same height).
 */
final class FtfValley {
    private FtfValley() {}

    /** Cross-valley rise of the valley floor (blocks per block of distance from the valley axis). */
    static final double FLOOR_TILT = .035;

    /** Zone widths (blocks) and shaping inputs for one channel at one point. */
    record Shape(double bedRadius, double bankRun, double floorHalf, double fadeRun, double bedDepth,
                 double floorRise, double terrace, double drainage) {
        double bankEnd() { return bedRadius + bankRun; }
        double floorEnd() { return bankEnd() + floorHalf; }
        double reach() { return floorEnd() + fadeRun; }
    }

    /** Single-line valley (channel on its axis): the RH3 section. */
    static HighlandValley.Section sample(double distance, double land, double water, Shape s) {
        return sample(distance, distance, 0, land, water, s);
    }

    /**
     * Two-line valley (RH4): the bed and bank step follow the channel; the floor and fadeout follow the valley axis,
     * with the floor widened by the local meander belt and the valley's outer edge kept where possible. The bank rises from the water to the valley surface at that
     * point (the floor where the channel is inside it, a cut bank where a bend meets the valley side), so the
     * section is continuous for any channel position and equals the single-line section when the channel lies on
     * the axis.
     */
    static HighlandValley.Section sample(double channel, double axis, double belt, double land, double water, Shape s) {
        double d = Math.abs(channel), a = Math.abs(axis);
        // The belt widens the floor inside the valley's own extent (the side slope steepens, at most twofold) rather
        // than pushing the valley sides outward into neighbouring valleys.
        double r1 = s.bedRadius(), r2 = s.bankEnd(), r3 = s.floorEnd() + belt, r4 = Math.max(s.reach(), r3 + .5 * s.fadeRun());
        // RH6: the floor rises gently away from the valley axis (a floodplain sloping up to its valley sides) instead of
        // lying dead flat at one level across the whole valley.
        double floor = water + s.floorRise() + FLOOR_TILT * a;
        if (d < r1) {
            double influence = 1 - d * d / (r1 * r1);
            influence = influence * influence * (3 - 2 * influence);
            return new HighlandValley.Section(water - s.bedDepth() * influence, 1, true, 0);
        }
        double valley, influence;
        int zone;
        if (a < r3) { valley = floor; influence = 1; zone = 2; }
        else if (a < r4) {
            double raw = (a - r3) / (r4 - r3);
            double progress = terracing(raw, s.terrace(), s.drainage(), 5);
            double slopeMask = raw * (1 - raw) * 4;
            progress = Math.max(0, progress - s.drainage() * .25 * slopeMask);
            double smooth = progress * progress * (3 - 2 * progress);
            valley = lerp(floor, land, smooth); influence = 1 - smooth; zone = 3;
        } else { valley = land; influence = 0; zone = 4; }
        if (d < r2) {
            double progress = terracing((d - r1) / (r2 - r1), s.terrace(), s.drainage(), 3);
            double arc = progress * (1 - progress) * 4;
            progress = Math.max(0, progress - s.drainage() * .3 * arc);
            return new HighlandValley.Section(lerp(water, Math.max(water, valley), progress * progress * (3 - 2 * progress)), 1, false, 1);
        }
        return new HighlandValley.Section(valley, influence, false, zone);
    }

    /** FTF {@code applyTerracing}: talus shelves (t^3.5) below a cliff face, broken up by drainage. */
    static double terracing(double progress, double terrace, double drainage, double steps) {
        progress = clamp(progress, 0, 1);
        double strength = clamp(Math.max(0, terrace - drainage * 1.5) * 1.75, 0, 1);
        if (strength <= 0) return progress;
        double scaled = progress * steps, floor = Math.floor(scaled), fract = scaled - floor;
        double cliffBias = clamp(.78 - drainage * .14, .55, .88);
        double talus = clamp(.14 + drainage * .22, .04, .45);
        double stepped;
        if (fract < cliffBias) {
            stepped = Math.pow(fract / cliffBias, 3.5) * talus;
        } else {
            double t = (fract - cliffBias) / (1 - cliffBias);
            stepped = lerp(talus, 1, lerp(t * t, t * t * (3 - 2 * t), .5));
        }
        return lerp(progress, (floor + stepped) / steps, .9 * strength);
    }
}
