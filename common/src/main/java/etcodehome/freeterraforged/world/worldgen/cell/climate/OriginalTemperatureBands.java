package etcodehome.freeterraforged.world.worldgen.cell.climate;

import etcodehome.freeterraforged.data.worldgen.preset.settings.LatitudeSettings;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.domain.Domain;
import etcodehome.freeterraforged.world.worldgen.noise.domain.Domains;

/** The legacy signed sine-power curve and its two Perlin warps, expressed in radians.
 * Pole distance replaces the legacy scale; no extra seed draws or terrain queries.
 */
final class OriginalTemperatureBands {
    private final LatitudeSettings settings;
    private final int falloff;
    private final Domain broad;
    private final Domain detail;

    OriginalTemperatureBands(LatitudeSettings settings, int falloff, int broadSeed, int detailSeed) {
        this.settings = settings;
        this.falloff = falloff;
        this.broad = Domains.domainPerlin(broadSeed, 4, 2, 4);
        this.detail = Domains.domainPerlin(detailSeed, 1, 1, 1);
    }

    float sample(double x, double z) {
        double radiansPerBlock = Math.PI / settings.poleDistance();
        double phase = (z - settings.equatorZ()) * radiansPerBlock;
        float nx = (float) (x * radiansPerBlock);
        float nz = (float) (phase + Math.PI / 2.0);
        // Warp composition matches warpPerlin(warpPerlin(source, broad), detail).
        float dx = detail.getOffsetX(nx, nz, 0);
        float dz = detail.getOffsetZ(nx, nz, 0);
        double warpedPhase = phase + dz + broad.getOffsetZ(nx + dx, nz + dz, 0);
        if (!settings.repeating()) warpedPhase = Math.max(-Math.PI, Math.min(Math.PI, warpedPhase));
        return curve(warpedPhase, falloff);
    }

    static float curve(double phase, int falloff) {
        // Reduce in double precision before the legacy float lookup, preserving
        // one-block phase changes near world limits even with very short bands.
        double angle = (phase + Math.PI / 2.0) % (2.0 * Math.PI);
        if (angle < 0) angle += 2.0 * Math.PI;
        float sin = NoiseUtil.clamp(NoiseUtil.sin((float) angle), -1.0F, 1.0F);
        float value = NoiseUtil.copySign(NoiseUtil.pow(sin, falloff), sin);
        return NoiseUtil.map(value, -1.0F, 1.0F, 2.0F);
    }
}
