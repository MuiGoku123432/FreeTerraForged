package etcodehome.freeterraforged.world.worldgen.highlands;

import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/** Smooth lateral geometry. Water profile and wet width are independent of dry valley width. */
public final class HighlandValley {
    private HighlandValley() {}
    public record Section(double height, double influence, boolean wet, int zone) {}

    public static Section sample(double distance, double land, double water, double depth,
                                 double wetRadius, double bankRun, double dryWidth, double outerRun) {
        if (wetRadius <= 0 || bankRun <= 0 || dryWidth <= 0 || outerRun <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Valley dimensions must be positive");
        }
        double d = Math.abs(distance), floorStart = wetRadius + bankRun, fadeStart = floorStart + dryWidth * .5;
        if (d <= wetRadius) return new Section(water - depth * (1 - smooth(d / wetRadius)), 1, true, 0);
        if (d <= floorStart) return new Section(water + 2 * smooth((d - wetRadius) / bankRun), 1, false, 1);
        if (d <= fadeStart) return new Section(water + 2 + 2 * smooth((d - floorStart) / (dryWidth * .5)), 1, false, 2);
        double blend = smooth((d - fadeStart) / outerRun);
        return new Section(lerp(water + 4, land, blend), 1 - blend, false, blend < 1 ? 3 : 4);
    }
}
