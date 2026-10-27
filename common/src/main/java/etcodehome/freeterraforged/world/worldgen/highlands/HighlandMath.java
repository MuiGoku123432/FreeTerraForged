package etcodehome.freeterraforged.world.worldgen.highlands;

/** Pure block-coordinate math. No mutable RNG, Minecraft state, or chunk-cache dependencies. */
public final class HighlandMath {
    private HighlandMath() {}

    public static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    public static double smooth(double t) {
        t = clamp(t, 0, 1);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }
    public static double smooth(double lo, double hi, double v) { return smooth((v - lo) / (hi - lo)); }
    public static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    public static long mix(long v) {
        v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        return v ^ (v >>> 31);
    }
    private static double gradient(long seed, int x, int z, double dx, double dz) {
        return switch ((int) mix(seed ^ (x * 0x9e3779b97f4a7c15L) ^ (z * 0x632be59bd9b4e019L)) & 7) {
            case 0 -> dx; case 1 -> -dx; case 2 -> dz; case 3 -> -dz;
            case 4 -> (dx + dz) * .7071067811865476;
            case 5 -> (dx - dz) * .7071067811865476;
            case 6 -> (-dx + dz) * .7071067811865476;
            default -> (-dx - dz) * .7071067811865476;
        };
    }
    /** Bounded [0,1] gradient noise with double coordinates, including at the world border. */
    public static double noise(long seed, double x, double z, double scale) {
        x /= scale; z /= scale;
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double dx = x - ix, dz = z - iz;
        return clamp(.5 + .8 * lerp(
            lerp(gradient(seed, ix, iz, dx, dz), gradient(seed, ix + 1, iz, dx - 1, dz), smooth(dx)),
            lerp(gradient(seed, ix, iz + 1, dx, dz - 1), gradient(seed, ix + 1, iz + 1, dx - 1, dz - 1), smooth(dx)), smooth(dz)), 0, 1);
    }
    public static double ridge(long seed, double x, double z, double scale, int octaves) {
        double sum = 0, weight = 1, weights = 0;
        for (int i = 0; i < octaves; i++) {
            double r = 1 - Math.abs(2 * noise(seed + i * 7919L, x, z, scale) - 1);
            sum += weight * r * r;
            weights += weight; weight *= .5; scale *= .5;
        }
        return sum / weights;
    }
}
