package etcodehome.freeterraforged.world.worldgen.highlands;

import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/** The planning surface is independent of the user-facing mountain/foothill amplitudes. */
public final class HighlandFields {
    private final long seed;
    private final double spacing;

    public HighlandFields(long seed, double spacing) {
        if (!Double.isFinite(spacing) || spacing < 1024 || spacing > 16384) {
            throw new IllegalArgumentException("Rolling Highlands upland spacing must be 1024..16384 blocks");
        }
        this.seed = seed;
        this.spacing = spacing;
    }
    public record Relief(double upland, double ridge, double foothill, double basin) {
        public double height(double mountains, double foothills) { return planningHeight() + mountains * ridge + foothills * foothill; }
        public double planningHeight() { return upland + 40 * ridge + 24 * foothill; }
    }
    public Relief sample(double x, double z) {
        double px = x + .12 * spacing * (2 * noise(seed + 1103, x, z, 1.5 * spacing) - 1);
        double pz = z + .12 * spacing * (2 * noise(seed + 1877, x, z, 1.5 * spacing) - 1);
        double u = (2 * noise(seed + 2741, px, pz, spacing) + noise(seed + 3251, px, pz, .55 * spacing)) / 3;
        double basin = smooth(.62, .8, 1 - u) * (.35 + .65 * smooth(.35, .65, noise(seed + 4271, px, pz, 2 * spacing)));
        double upland = (24 + 96 * smooth(.2, .8, u)) * (1 - .75 * basin);
        double r = (1 - basin) * smooth(.25, .85, ridge(seed + 5393, px, pz, .55 * spacing, 3));
        double q = (1 - basin) * (.3 + .7 * (.65 * noise(seed + 6421, px, pz, .22 * spacing)
            + .35 * ridge(seed + 7481, px, pz, .35 * spacing, 2)));
        return new Relief(upland, r, q, basin);
    }
}
