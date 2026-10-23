package etcodehome.freeterraforged.world.worldgen.cell.climate;

import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;

/** Elevation temperature shaping with explicit inputs; no preset or world lookup. */
public final class TemperatureShaping {
	private TemperatureShaping() {}

	/**
	 * Applies the native cooling curve to an above-ground sample in 0..1 units.
	 * The caller checks the ground threshold and supplies normalized inland elevation
	 * clamped to 0..1, plus its selected altitude cooling strength. Temperature is
	 * clamped to 0..1 here. Float exponent/result rounding matches native worldgen.
	 */
	public static float applyAboveGround(float temperature, float elevation, float strength) {
		float exponent = (float) Math.pow(2.0, elevation * strength);
		float clamped = NoiseUtil.clamp(temperature, 0.0F, 1.0F);
		return (float) Math.pow(clamped, exponent);
	}

	/**
	 * Double-precision variant in -1..1 climate units, for integrations composing
	 * climate before final quantization. Preserves the established double arithmetic
	 * of TerraLatitude schema-1 worlds; it is not bit-identical to native float output.
	 * The owner supplies its ground gate, normalized 0..1 elevation and saved strength.
	 * Do not silently interchange these precision contracts for existing worlds.
	 */
	public static double applyNormalized(double temperature, boolean aboveGround,
			double elevation, double strength) {
		double clamped = Math.max(-1.0, Math.min(1.0, temperature));
		if (!aboveGround) return clamped;
		double zeroToOne = (clamped + 1.0) * 0.5;
		return 2.0 * Math.pow(zeroToOne, Math.pow(2.0, elevation * strength)) - 1.0;
	}
}
