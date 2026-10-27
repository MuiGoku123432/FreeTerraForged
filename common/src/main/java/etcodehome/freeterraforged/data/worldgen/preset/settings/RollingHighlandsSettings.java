package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Immutable saved generation identity; UI edits replace this record instead of mutating live worlds. */
public record RollingHighlandsSettings(int modelVersion, int uplandSpacing, int mountainHeight, int foothillRelief, int valleyWidth) {
    public static final RollingHighlandsSettings DEFAULT = new RollingHighlandsSettings(1, 4096, 160, 48, 256);
    public static final Codec<RollingHighlandsSettings> CODEC = RecordCodecBuilder.<RollingHighlandsSettings>create(i -> i.group(
        integer(1, Integer.MAX_VALUE).fieldOf("modelVersion").forGetter(RollingHighlandsSettings::modelVersion),
        integer(4096, 8192).fieldOf("uplandSpacing").forGetter(RollingHighlandsSettings::uplandSpacing),
        integer(32, 256).fieldOf("mountainHeight").forGetter(RollingHighlandsSettings::mountainHeight),
        integer(0, 96).fieldOf("foothillRelief").forGetter(RollingHighlandsSettings::foothillRelief),
        integer(64, 512).fieldOf("valleyWidth").forGetter(RollingHighlandsSettings::valleyWidth)
    ).apply(i, RollingHighlandsSettings::new)).validate(s -> s.modelVersion == 1
        ? DataResult.success(s) : DataResult.error(() -> "Unsupported Rolling Highlands model version " + s.modelVersion));

    private static Codec<Integer> integer(int min, int max) {
        return Codec.DOUBLE.comapFlatMap(value -> Double.isFinite(value) && value == Math.rint(value) && value >= min && value <= max
            ? DataResult.success(value.intValue()) : DataResult.error(() -> "Expected a finite integer in " + min + ".." + max), Integer::doubleValue);
    }

    public void validate(WorldSettings world) {
        if (modelVersion != 1 || uplandSpacing < 4096 || uplandSpacing > 8192 || mountainHeight < 32 || mountainHeight > 256
            || foothillRelief < 0 || foothillRelief > 96 || valleyWidth < 64 || valleyWidth > 512) {
            throw new IllegalArgumentException("Unsupported Rolling Highlands profile; check model version and control ranges");
        }
        if (world.continent.continentScale < 1000 || world.continent.continentScale > 5000) {
            throw new IllegalArgumentException("Experimental Rolling Highlands supports continent scale 1000..5000");
        }
        double span = world.continent.continentScale * 4 * 3.33 + 4;
        int step = 64;
        while (Math.pow(Math.ceil(span / step) + 3, 2) > 65536) step *= 2;
        if (uplandSpacing < step * 8) throw new IllegalArgumentException("This continent scale needs upland spacing >= " + step * 8);
        if (world.properties.worldHeight - world.properties.seaLevel < 320) {
            throw new IllegalArgumentException("Rolling Highlands needs at least 320 blocks of height above sea level");
        }
        if (!(world.controlPoints.inland > world.controlPoints.coast)) {
            throw new IllegalArgumentException("Rolling Highlands requires inland control point > coast");
        }
    }
}
