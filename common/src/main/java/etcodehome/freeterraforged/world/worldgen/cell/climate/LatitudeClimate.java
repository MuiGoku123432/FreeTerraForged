package etcodehome.freeterraforged.world.worldgen.cell.climate;

import etcodehome.freeterraforged.data.worldgen.preset.settings.ClimateSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.LatitudeSettings;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;

/** Constant-work geographic layer; no terrain acquisition, new noise, or mutable sampling state. */
public final class LatitudeClimate {
    private static final double[] KNOTS = {0.0, 0.20, 0.35, 0.65, 0.85, 1.0};
    private static final double[] MOISTURE = {0.85, 0.65, 0.20, 0.70, 0.40, 0.15};
    private final LatitudeSettings settings;
    private final Range temperature;
    private final Range moisture;

    public LatitudeClimate(ClimateSettings settings) {
        this.settings = settings.latitude;
        this.temperature = new Range(settings.temperature);
        this.moisture = new Range(settings.moisture);
    }

    public LatitudeSettings settings() { return settings; }

    public double fraction(double geographicZ) {
        double distance = Math.abs(geographicZ - (double) settings.equatorZ());
        if (!settings.repeating()) return Math.min(1.0, distance / settings.poleDistance());
        // Fold each 2D period into equator -> pole -> equator. Reduce in block units
        // before dividing to retain precision at remote coordinates and short periods.
        double period = 2.0 * settings.poleDistance();
        double phase = distance % period;
        return Math.min(phase, period - phase) / settings.poleDistance();
    }

    public static float temperatureTarget(double u) {
        return (float) (1.0 - u * u * (3.0 - 2.0 * u));
    }

    public static float moistureTarget(double u) {
        for (int i = 1; i < KNOTS.length; i++) {
            if (u <= KNOTS[i]) {
                double t = (u - KNOTS[i - 1]) / (KNOTS[i] - KNOTS[i - 1]);
                double s = t * t * (3.0 - 2.0 * t);
                return (float) (MOISTURE[i - 1] + s * (MOISTURE[i] - MOISTURE[i - 1]));
            }
        }
        return (float) MOISTURE[MOISTURE.length - 1];
    }

    public float temperature(float regional, double geographicZ) {
        if (!settings.enabled() || settings.temperatureStrength() == 0.0F) return regional;
        return blend(regional, temperature.apply(temperatureTarget(fraction(geographicZ))), settings.temperatureStrength());
    }

    public float moisture(float regional, double geographicZ) {
        if (!settings.enabled() || settings.moistureStrength() == 0.0F) return regional;
        return blend(regional, moisture.apply(moistureTarget(fraction(geographicZ))), settings.moistureStrength());
    }

    public static float blend(float regional, float target, float strength) {
        if (strength == 0.0F) return regional;
        if (strength == 1.0F) return target;
        return (1.0F - strength) * regional + strength * target;
    }

    /** Diagnostic-only allocation; hot-path blending returns scalars. */
    public Targets targets(double geographicZ) {
        double u = fraction(geographicZ);
        float t = temperatureTarget(u);
        float m = moistureTarget(u);
        return new Targets(u, t, m, temperature.apply(t), moisture.apply(m));
    }

    public record Targets(double fraction, float temperature, float moisture, float rangedTemperature, float rangedMoisture) {}

    /** Existing range/bias operations applied to a bounded 0..1 geographic target, once. */
    private record Range(float min, float max, float bias, float exponent, boolean power) {
        Range(ClimateSettings.RangeValue range) {
            this(range.getMin(), range.getMax(), range.getOffsetBias(),
                (float) Math.pow(2.0, -range.distributionBias), range.distributionBias != 0.0F);
        }

        float apply(float value) {
            if (power) value = (float) Math.pow(value, exponent);
            value = min + value * (max - min);
            return NoiseUtil.clamp(value + bias, min, max);
        }
    }
}
