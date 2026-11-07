package etcodehome.freeterraforged.world.worldgen.highlands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.ToDoubleFunction;
import com.mojang.datafixers.util.Pair;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler.Field;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/** Throwaway biome-balance census (investigation only, not a gate). */
class BiomeCensusProbe {
    static final long[] SEEDS = {0, 42, 8675309};
    static final int HALF = 24576, STEP = 128;
    static final float[] TEMP = {-0.45F, -0.15F, 0.2F, 0.55F};
    static final float[] HUMID = {-0.35F, -0.1F, 0.1F, 0.3F};
    static final float[] EROSION = {-0.78F, -0.375F, -0.2225F, 0.05F, 0.45F, 0.55F};
    static final float[] CONT = {-0.19F, -0.11F, 0.03F, 0.3F};
    static final float[] WEIRD_ABS = {0.05F, 0.26666668F, 0.4F, 0.56666666F, 0.7666667F, 0.93333334F};

    static final class Census {
        final String label;
        final Map<String, Long> biomes = new TreeMap<>();
        final long[] temp = new long[5], humid = new long[5], erosion = new long[7], cont = new long[5], weird = new long[7];
        long land, water, saturatedTemp, saturatedHumid, positiveWeird;
        double tempSum, humidSum;
        Census(String label) { this.label = label; }

        void add(String biome, float t, float h, float c, float e, float w) {
            land++;
            biomes.merge(biome, 1L, Long::sum);
            temp[band(t, TEMP)]++; humid[band(h, HUMID)]++; erosion[band(e, EROSION)]++; cont[band(c, CONT)]++;
            weird[band(Math.abs(w), WEIRD_ABS)]++;
            if (w > 0) positiveWeird++;
            if (Math.abs(t) >= .98F) saturatedTemp++;
            if (Math.abs(h) >= .98F) saturatedHumid++;
            tempSum += t; humidSum += h;
        }

        String report() {
            StringBuilder out = new StringBuilder();
            out.append(String.format(Locale.ROOT, "== %s: land samples=%d (FTF water cells excluded=%d)%n", label, land, water));
            out.append(String.format(Locale.ROOT, "mean temp=%.3f mean humid=%.3f |t|>=.98: %.1f%% |h|>=.98: %.1f%% weirdness>0: %.1f%%%n",
                tempSum / land, humidSum / land, pct(saturatedTemp), pct(saturatedHumid), pct(positiveWeird)));
            out.append("temperature bands 0-4 (frozen..hot): ").append(shares(temp)).append('\n');
            out.append("humidity bands 0-4 (arid..humid):    ").append(shares(humid)).append('\n');
            out.append("erosion levels 0-6:                  ").append(shares(erosion)).append('\n');
            out.append("continentalness (ocean/coast/near/mid/far): ").append(shares(cont)).append('\n');
            out.append("weirdness |w| (valley/low/mid/high/peak/high/mid): ").append(shares(weird)).append('\n');
            biomes.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> out.append(String.format(Locale.ROOT, "  %-28s %6.2f%%%n", e.getKey(), pct(e.getValue()))));
            return out.toString();
        }

        double pct(long n) { return 100.0 * n / Math.max(1, land); }
        String shares(long[] counts) {
            StringJoiner j = new StringJoiner(" / ");
            for (long c : counts) j.add(String.format(Locale.ROOT, "%.1f", pct(c)));
            return j.toString();
        }
    }

    static int band(float v, float[] edges) {
        int i = 0;
        while (i < edges.length && v >= edges[i]) i++;
        return i;
    }

    static boolean ocean(String biome) { return biome.contains("ocean"); }

    static Climate.ParameterList<ResourceKey<Biome>> overworld() {
        var holders = VanillaRegistries.createLookup().lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters();
        List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> points = new ArrayList<>();
        for (var entry : holders.values()) points.add(Pair.of(entry.getFirst(), entry.getSecond().unwrapKey().orElseThrow()));
        return new Climate.ParameterList<>(points);
    }

    static Preset ftf(ContinentType type) {
        Preset preset = Presets.makeFTFDefault();
        preset.world().continent.continentType = type;
        return preset;
    }

    static Preset rockies() {
        Preset preset = ftf(ContinentType.ROLLING_HIGHLANDS);
        var continent = preset.world().continent;
        RollingHighlandsSettings.applyContinentBaseline(continent);
        continent.continentScale = Math.max(1000, Math.min(5000, continent.continentScale));
        preset.world().properties.worldHeight = Math.max(736, preset.world().properties.worldHeight);
        preset.world().properties.worldDepth = Math.max(128, preset.world().properties.worldDepth);
        preset.terrain().rollingHighlands = RollingHighlandsSettings.ROCKIES;
        return preset;
    }

    static Census ftfCensus(String label, Preset preset, Climate.ParameterList<ResourceKey<Biome>> table) throws Exception {
        Census census = new Census(label);
        for (long seed : SEEDS) {
            try (var context = WorldgenFixture.context(preset, seed)) {
                var heightmap = context.heightmap;
                for (int z = -HALF; z < HALF; z += STEP) for (int x = -HALF; x < HALF; x += STEP) {
                    Cell cell = new Cell();
                    heightmap.apply(cell, x, z, true);
                    float t = Field.TEMPERATURE.read(cell, heightmap), h = Field.MOISTURE.read(cell, heightmap);
                    float c = Field.CONTINENT.read(cell, heightmap), e = Field.EROSION.read(cell, heightmap);
                    float w = Field.WEIRDNESS.read(cell, heightmap);
                    String biome = table.findValue(Climate.target(t, h, c, e, 0, w)).location().getPath();
                    if (cell.height <= context.levels.water || ocean(biome)) continue;
                    if (cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland()) { census.water++; continue; }
                    census.add(biome, t, h, c, e, w);
                }
            }
        }
        return census;
    }

    static Census vanillaCensus(Climate.ParameterList<ResourceKey<Biome>> table) {
        Census census = new Census("VANILLA (Minecraft default overworld noise, depth 0)");
        var lookup = VanillaRegistries.createLookup();
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var noises = lookup.lookupOrThrow(Registries.NOISE);
        for (long seed : SEEDS) {
            var router = RandomState.create(settings, noises, seed).router();
            for (int z = -HALF; z < HALF; z += STEP) for (int x = -HALF; x < HALF; x += STEP) {
                var p = new DensityFunction.SinglePointContext(x, 64, z);
                ToDoubleFunction<DensityFunction> at = f -> f.compute(p);
                float t = (float) at.applyAsDouble(router.temperature()), h = (float) at.applyAsDouble(router.vegetation());
                float c = (float) at.applyAsDouble(router.continents()), e = (float) at.applyAsDouble(router.erosion());
                float w = (float) at.applyAsDouble(router.ridges());
                String biome = table.findValue(Climate.target(t, h, c, e, 0, w)).location().getPath();
                if (ocean(biome)) continue;
                census.add(biome, t, h, c, e, w);
            }
        }
        return census;
    }

    @Test void census() throws Exception {
        WorldgenFixture.initialize();
        var table = overworld();
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Seeds %s, %d-block square at %d-block spacing, vanilla overworld table, surface depth 0%n%n",
            Arrays.toString(SEEDS), 2 * HALF, STEP));
        out.append(vanillaCensus(table).report()).append('\n');
        out.append(ftfCensus("FTF UPLIFT (default preset)", ftf(ContinentType.UPLIFT), table).report()).append('\n');
        out.append(ftfCensus("FTF MULTI_IMPROVED", ftf(ContinentType.MULTI_IMPROVED), table).report()).append('\n');
        out.append(ftfCensus("ROLLING_HIGHLANDS (Rockies profile)", rockies(), table).report()).append('\n');
        System.out.println(out);
        Files.writeString(Path.of("build/biome-census.txt"), out);
    }
}
