package etcodehome.freeterraforged.world.worldgen.cell.climate;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.continent.Continent;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.util.Seed;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Focused offline tests. The frozen oracle exercises the pre-change implementation, not rewritten formulas. */
public final class LatitudeClimateTest {
    private static int checks;
    private static final Continent LAND = new Continent() {
        public void apply(Cell cell, float x, float z) {}
        public float getEdgeValue(float x, float z) { return 1.0F; }
        public long getNearestCenter(float x, float z) { return 0; }
        public Rivermap getRivermap(int x, int z) { throw new UnsupportedOperationException(); }
        public void close() {}
    };

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        codec(); geography(); repeatingGeography(); ranges(); regression(); ownership(); coordinatePath(); compatibility(); diagnosticRequests();
        System.out.println("Native latitude offline checks passed: " + checks);
    }

    private static void codec() {
        ClimateSettings settings = ClimateSettings.makeDefault();
        JsonObject legacy = ClimateSettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow().getAsJsonObject();
        check(!legacy.has("latitude"), "default omission preserves existing preset fingerprints");
        check(!ClimateSettings.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow().latitude.enabled(), "absent legacy");
        settings.latitude = LatitudeSettings.DEFAULT.withEnabled(true).withGeometry(-29_999_999, 1).withStrengths(0, 1);
        JsonObject encoded = ClimateSettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow().getAsJsonObject();
        check(ClimateSettings.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().latitude.equals(settings.latitude), "round trip");
        var nbt = ClimateSettings.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, settings).getOrThrow();
        check(ClimateSettings.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, nbt).getOrThrow().latitude.equals(settings.latitude), "NBT round trip");
        ClimateSettings copy = settings.copy();
        copy.latitude = copy.latitude.withStrengths(1, 0);
        check(settings.latitude.moistureStrength() == 1, "copy independence");
        for (String field : new String[]{"enabled", "schemaVersion", "model", "equatorZ", "poleDistance", "temperatureStrength", "moistureStrength"}) {
            JsonObject invalid = encoded.deepCopy();
            invalid.getAsJsonObject("latitude").remove(field);
            rejects(invalid, "missing " + field);
        }
        for (JsonElement invalid : new JsonElement[]{JsonNull.INSTANCE, new com.google.gson.JsonPrimitive(false), new JsonObject()}) {
            JsonObject object = legacy.deepCopy(); object.add("latitude", invalid); rejects(object, "invalid object " + invalid);
        }
        for (String field : new String[]{"equatorZ", "poleDistance", "schemaVersion"}) {
            JsonObject invalid = encoded.deepCopy(); invalid.getAsJsonObject("latitude").addProperty(field, 1.5); rejects(invalid, "fractional " + field);
        }
        for (float value : new float[]{-0.01F, 1.01F, Float.NaN, Float.POSITIVE_INFINITY}) {
            for (String field : new String[]{"temperatureStrength", "moistureStrength"}) {
                JsonObject invalid = encoded.deepCopy(); invalid.getAsJsonObject("latitude").addProperty(field, value); rejects(invalid, "strength " + value);
            }
        }
        for (int value : new int[]{0, -1, 30_000_001}) {
            JsonObject invalid = encoded.deepCopy(); invalid.getAsJsonObject("latitude").addProperty("poleDistance", value); rejects(invalid, "distance " + value);
        }
        for (int value : new int[]{-30_000_001, 30_000_001}) {
            JsonObject invalid = encoded.deepCopy(); invalid.getAsJsonObject("latitude").addProperty("equatorZ", value); rejects(invalid, "equator " + value);
        }
        for (String field : new String[]{"equatorZ", "poleDistance", "schemaVersion", "temperatureStrength", "moistureStrength"}) {
            JsonObject invalidType = encoded.deepCopy(); invalidType.getAsJsonObject("latitude").addProperty(field, true);
            rejects(invalidType, "boolean numeric " + field);
        }
        JsonObject invalidBoolean = encoded.deepCopy(); invalidBoolean.getAsJsonObject("latitude").addProperty("enabled", 1);
        rejects(invalidBoolean, "numeric boolean");
        JsonObject invalid = encoded.deepCopy(); invalid.getAsJsonObject("latitude").addProperty("enabled", false);
        invalid.getAsJsonObject("latitude").addProperty("model", "ftf:unknown"); rejects(invalid, "disabled unknown model");
    }

    private static void rejects(JsonObject value, String label) {
        check(ClimateSettings.CODEC.parse(JsonOps.INSTANCE, value).error().isPresent(), label);
    }

    private static void geography() {
        var settings = ClimateSettings.makeDefault();
        settings.latitude = LatitudeSettings.DEFAULT.withEnabled(true).withGeometry(-12345, 50000);
        var model = new LatitudeClimate(settings);
        float previous = 1;
        for (int i = 0; i <= 10000; i++) {
            double distance = i * 5.0;
            double u = model.fraction(-12345 + distance);
            check(u == model.fraction(-12345 - distance), "hemisphere symmetry");
            float t = LatitudeClimate.temperatureTarget(u);
            check(t <= previous && t >= 0, "temperature monotonicity"); previous = t;
            float m = LatitudeClimate.moistureTarget(u);
            check(m >= 0 && m <= 1, "bounded moisture");
        }
        bits(1, LatitudeClimate.temperatureTarget(0), "equator");
        bits(0, LatitudeClimate.temperatureTarget(1), "pole");
        settings.latitude = settings.latitude.withRepeating(false);
        check(new LatitudeClimate(settings).fraction(30_000_000) == 1, "legacy cap");
        double[] knots = {0, .2, .35, .65, .85, 1};
        float[] moisture = {.85F, .65F, .2F, .7F, .4F, .15F};
        for (int i = 0; i < knots.length; i++) {
            bits(moisture[i], LatitudeClimate.moistureTarget(knots[i]), "moisture knot");
            near(moisture[i], LatitudeClimate.moistureTarget(Math.max(0, knots[i] - 1e-8)), "left continuous");
            near(moisture[i], LatitudeClimate.moistureTarget(Math.min(1, knots[i] + 1e-8)), "right continuous");
        }
        bits(-0.0F, LatitudeClimate.blend(-0.0F, 1, 0), "zero strength exact sign");
        bits(.4F, LatitudeClimate.blend(.2F, .6F, .5F), "supplied midpoint");
    }

    private static void repeatingGeography() {
        var settings = ClimateSettings.makeDefault();
        for (boolean repeating : new boolean[]{false, true}) {
            var profile = LatitudeSettings.DEFAULT.withEnabled(true).withRepeating(repeating);
            var json = LatitudeSettings.CODEC.encodeStart(JsonOps.INSTANCE, profile).getOrThrow();
            check(LatitudeSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().equals(profile), "saved model round trip");
            check(profile.repeating() == repeating, "explicit repeat choice");
            var nbt = LatitudeSettings.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, profile).getOrThrow();
            check(LatitudeSettings.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, nbt).getOrThrow().equals(profile), "saved NBT model round trip");
            settings.latitude = profile;
            var climateJson = ClimateSettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow();
            check(ClimateSettings.CODEC.parse(JsonOps.INSTANCE, climateJson).getOrThrow().latitude.equals(profile), "preset keeps model");
        }
        for (int equator : new int[]{-30_000_000, -12345, 0, 30_000_000}) {
            for (int distance : new int[]{1, 7, 5000, 30_000_000}) {
                settings.latitude = LatitudeSettings.DEFAULT.withEnabled(true).withGeometry(equator, distance).withStrengths(1, 1);
                var model = new LatitudeClimate(settings);
                for (int k = -8; k <= 8; k++) {
                    double z = equator + (double) k * distance;
                    check(model.fraction(z) == (Math.abs(k) % 2), "alternating equator and pole");
                    for (double seam : new double[]{z - distance * 1e-6, z + distance * 1e-6}) {
                        near(LatitudeClimate.temperatureTarget(model.fraction(z)), LatitudeClimate.temperatureTarget(model.fraction(seam)), "temperature seam continuity");
                        near(LatitudeClimate.moistureTarget(model.fraction(z)), LatitudeClimate.moistureTarget(model.fraction(seam)), "moisture seam continuity");
                    }
                }
                for (int i = 0; i <= 200; i++) {
                    double z = -30_000_000D + i * 300_000D;
                    double u = model.fraction(z);
                    check(u >= 0 && u <= 1, "periodic bounds");
                    check(u == model.fraction(2D * equator - z), "repeating reflection");
                    check(u == model.fraction(z + 2D * distance), "block period at world limits");
                    bits(model.temperature(.4F, z), model.temperature(.4F, z + 2D * distance), "temperature repeats");
                    bits(model.moisture(.4F, z), model.moisture(.4F, z + 2D * distance), "moisture repeats");
                }
                settings.latitude = settings.latitude.withRepeating(false);
                var legacy = new LatitudeClimate(settings);
                check(legacy.fraction(equator + 2D * distance) == 1, "saved v1 remains polar beyond pole");
                check(legacy.fraction(equator - 2D * distance) == 1, "saved v1 negative cap");
                for (int i = 0; i <= 100; i++) {
                    double z = equator + distance * (i / 100D);
                    check(model.fraction(z) == legacy.fraction(z), "first equator-to-pole leg unchanged");
                }
                settings.latitude = settings.latitude.withRepeating(true).withStrengths(0, 0);
                var zero = new LatitudeClimate(settings);
                bits(-0.0F, zero.temperature(-0.0F, equator + 2D * distance), "repeating zero temperature exact");
                bits(-0.0F, zero.moisture(-0.0F, equator + 2D * distance), "repeating zero moisture exact");
            }
        }
        settings.latitude = LatitudeSettings.DEFAULT.withEnabled(true).withGeometry(0, 5000).withStrengths(1, 1);
        var model = new LatitudeClimate(settings);
        check(model.fraction(0) == 0 && model.fraction(5000) == 1 && model.fraction(10000) == 0, "user 5000-block example");
        check(!settings.latitude.withRepeating(false).equals(settings.latitude), "repeat changes profile identity for preview");
    }

    private static void ranges() {
        var settings = ClimateSettings.makeDefault();
        settings.latitude = LatitudeSettings.DEFAULT.withEnabled(true).withStrengths(1, 1);
        for (float bias : new float[]{-1, -.2F, 0, .2F, 1}) for (float distribution : new float[]{-3, 0, 3}) {
            settings.temperature = new ClimateSettings.RangeValue(0, 1, 1, .1F, .9F, bias, distribution);
            var model = new LatitudeClimate(settings);
            for (int i = 0; i <= 100; i++) {
                // Invert no curve: use the actual geographic target as the source to the legacy range graph.
                final float target = model.targets(i * 500.0).temperature();
                Noise targetSource = new Noise() {
                    public float compute(float x, float z, int seed) { return target; }
                    public float minValue() { return 0; }
                    public float maxValue() { return 1; }
                    public Noise mapAll(Visitor visitor) { return visitor.apply(this); }
                    public com.mojang.serialization.MapCodec<? extends Noise> codec() { throw new UnsupportedOperationException(); }
                };
                bits(settings.temperature.apply(targetSource).compute(0, 0, 0), model.targets(i * 500.0).rangedTemperature(), "existing range graph");
            }
        }
    }

    private static void regression() {
        var world = WorldSettings.makeDefault();
        var levels = new Levels(world.properties);
        for (long seed : new long[]{0, 1, -1, 2147483648L, Long.MAX_VALUE}) for (int preset = 0; preset < 3; preset++) {
            var settings = ClimateSettings.makeDefault();
            if (preset == 1) { settings.moisture.distributionBias = -1.5F; settings.temperature.min = .2F; settings.coastalMoistureBoost = 0; }
            if (preset == 2) { settings.altitudeCoolingStrength = 0; settings.rainShadowStrength = 3; settings.moisture.offsetBias = -.15F; }
            PresetManager.PM.climateSettings = settings.copy();
            settings.latitude = LatitudeSettings.DEFAULT.withGeometry(-321, 1234).withStrengths(.2F, .9F);
            var oldSeed = new Seed(seed); var newSeed = new Seed(seed);
            var old = new LegacyClimateModuleFixture(oldSeed, LAND, world.controlPoints, settings, levels);
            var legacy = new ClimateModule(newSeed, LAND, world.controlPoints, settings, levels);
            check(oldSeed.next() == newSeed.next(), "seed sequence");
            for (float t : new float[]{0, .5F, 1}) for (float m : new float[]{0, .5F, 1}) {
                settings.latitude = LatitudeSettings.DEFAULT.withEnabled(true).withStrengths(t, m);
                var nativeModule = new ClimateModule(new Seed(seed), LAND, world.controlPoints, settings, levels);
                for (int i = 0; i < 120; i++) {
                    int x = i * 71 - 4231, z = i * 1031 - 60001;
                    Cell a = cell(levels, i), b = cell(levels, i), c = cell(levels, i);
                    old.apply(a, x, z, x, z, true); legacy.apply(b, x, z, x, z, true); nativeModule.apply(c, x, z, x, z, true);
                    bits(a.temperature, b.temperature, "disabled temperature"); bits(a.moisture, b.moisture, "disabled moisture");
                    if (t == 0) bits(a.temperature, c.temperature, "independent zero temperature");
                    if (m == 0) bits(a.moisture, c.moisture, "independent zero moisture");
                    bits(a.height, c.height, "height"); bits(a.erosion, c.erosion, "erosion"); bits(a.weirdness, c.weirdness, "weirdness");
                    bits(a.biomeRegionId, c.biomeRegionId, "region"); bits(a.macroBiomeId, c.macroBiomeId, "macro region");
                    check(a.terrain == c.terrain, "terrain identity");
                    var ar = old.sampleRaw(x, z); var cr = nativeModule.sampleRaw(x, z);
                    bits(ar.temperature(), cr.temperature(), "raw temperature"); bits(ar.moisture(), cr.moisture(), "raw moisture");
                    if (t == 1 && c.height <= levels.ground) bits(nativeModule.latitude().targets(z).rangedTemperature() * 2 - 1, c.temperature, "full temperature");
                    if (m == 1) bits(old.modifyMoisture(c.height, nativeModule.latitude().targets(z).rangedMoisture()) * 2 - 1, c.moisture, "one moisture shaping pass");
                }
            }
        }
    }

    private static Cell cell(Levels levels, int i) {
        Cell cell = new Cell(); cell.terrain = TerrainType.FLATS;
        cell.height = switch (i % 4) { case 0 -> levels.water; case 1 -> levels.ground; case 2 -> levels.ground + .1F; default -> 1.0F; };
        return cell;
    }

    private static void ownership() throws Exception {
        var world = WorldSettings.makeDefault(); var levels = new Levels(world.properties);
        var settings = ClimateSettings.makeDefault();
        settings.latitude = LatitudeSettings.DEFAULT.withEnabled(true);
        var a = new ClimateModule(new Seed(12), LAND, world.controlPoints, settings, levels);
        Cell expected = cell(levels, 3); a.apply(expected, 351, 283, 351, 283, true);
        settings.altitudeCoolingStrength = 0; settings.coastalMoistureBoost = 1; settings.rainShadowStrength = 0;
        settings.latitude = settings.latitude.withGeometry(1234, 1); settings.moisture.min = 1;
        PresetManager.PM.climateSettings = settings;
        var b = new ClimateModule(new Seed(13), LAND, world.controlPoints, settings, levels);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 100; i++) tasks.add(() -> {
            Cell other = cell(levels, 3); b.apply(other, 351, 283, 351, 283, true);
            Cell actual = cell(levels, 3); a.apply(actual, 351, 283, 351, 283, true);
            return Float.floatToIntBits(actual.temperature) == Float.floatToIntBits(expected.temperature)
                && Float.floatToIntBits(actual.moisture) == Float.floatToIntBits(expected.moisture);
        });
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(tasks)) check(result.get(), "concurrent owner isolation");
        }
    }

    private static void coordinatePath() {
        var world = WorldSettings.makeDefault(); var levels = new Levels(world.properties);
        var settings = ClimateSettings.makeDefault();
        settings.temperature.min = 0; settings.temperature.max = 1; settings.temperature.offsetBias = 0;
        settings.latitude = LatitudeSettings.DEFAULT.withEnabled(true).withGeometry(30_000_000, 2).withStrengths(1, 0);
        var module = new ClimateModule(new Seed(0), LAND, world.controlPoints, settings, levels);
        var climate = new Climate(0, Noises.constant(17), Noises.constant(29), 300, levels, module);
        var heightmap = new Heightmap(null, null, LAND, climate, levels, world.controlPoints, 1, Noises.zero());
        Cell cell = cell(levels, 0);
        heightmap.applyClimate(cell, 0, 29_999_999, true);
        bits(0, cell.temperature, "integer position must survive float boundary");
        Cell displaced = cell(levels, 0);
        module.apply(displaced, 123, 456, 0, 29_999_999D, false);
        bits(cell.temperature, displaced.temperature, "regional displacement must not move equator");
    }

    private static void compatibility() throws Exception {
        var off = LatitudeSettings.DEFAULT; var on = off.withEnabled(true);
        LatitudeCompatibility.validateMods(off, id -> true);
        for (String id : new String[]{"naturaltemperature", "terralatitude", "fnf_altitude"}) {
            fails(() -> LatitudeCompatibility.validateMods(on, id::equals), "active conflict " + id);
        }
        LatitudeCompatibility.validateMods(on, id -> false);
        var directory = Files.createTempDirectory("ftf-latitude-test-");
        var file = directory.resolve("terralatitude-climate.json");
        try {
            LatitudeCompatibility.validateSavedExternalProfile(directory, on, id -> false);
            Files.writeString(file, "{\"schema\":1,\"enabled\":true}");
            fails(() -> LatitudeCompatibility.validateSavedExternalProfile(directory, off, id -> false), "missing external dependencies");
            fails(() -> LatitudeCompatibility.validateSavedExternalProfile(directory, on, id -> true), "no migration");
            LatitudeCompatibility.validateSavedExternalProfile(directory, off, id -> true);
            Files.writeString(file, "{\"schema\":1,\"enabled\":false}");
            LatitudeCompatibility.validateSavedExternalProfile(directory, on, id -> false);
            Files.writeString(file, "{\"schema\":2,\"enabled\":false}");
            fails(() -> LatitudeCompatibility.validateSavedExternalProfile(directory, off, id -> false), "unknown marker");
        } finally { Files.deleteIfExists(file); Files.delete(directory); }
    }

    private static void diagnosticRequests() {
        check(LatitudeDiagnosticPoints.transect(0, 0, 0, 0, 0).isEmpty(), "empty diagnostic");
        var duplicates = LatitudeDiagnosticPoints.transect(1, -1, 0, 0, 3);
        check(duplicates.size() == 3 && duplicates.get(0).equals(duplicates.get(2)), "duplicate diagnostic");
        var points = LatitudeDiagnosticPoints.transect(1, -1, -2, 3, 3);
        check(points.get(2).equals(new LatitudeDiagnosticPoints.Point(-3, 5)), "diagnostic order");
        check(LatitudeDiagnosticPoints.transect(0, 0, 0, 0, 4096).size() == 4096, "diagnostic cap");
        for (int count : new int[]{-1, 4097}) {
            try { LatitudeDiagnosticPoints.transect(0, 0, 0, 0, count); throw new AssertionError("diagnostic count"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        try { LatitudeDiagnosticPoints.transect(30_000_000, 0, Integer.MAX_VALUE, 0, 2); throw new AssertionError("diagnostic overflow"); }
        catch (IllegalArgumentException expected) { checks++; }
        var world = WorldSettings.makeDefault(); var levels = new Levels(world.properties);
        var settings = ClimateSettings.makeDefault(); settings.latitude = LatitudeSettings.DEFAULT.withEnabled(true).withStrengths(1, 1);
        var module = new ClimateModule(new Seed(0), LAND, world.controlPoints, settings, levels);
        var cell = cell(levels, 2); module.apply(cell, 0, 0, 0, 0, true);
        var diagnostic = module.diagnose(0, 0, 0, cell.height);
        bits(cell.moisture, diagnostic.shapedMoisture() * 2 - 1, "diagnostic normal composition");
        check(Float.floatToIntBits(cell.moisture) != Float.floatToIntBits(diagnostic.blendedMoisture() * 2 - 1), "detect bypassed shaping");
        check(Float.floatToIntBits(cell.moisture) != Float.floatToIntBits(module.modifyMoisture(cell.height, diagnostic.shapedMoisture()) * 2 - 1), "detect doubled shaping");
    }

    private static void fails(Runnable action, String label) {
        try { action.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError(label);
    }
    private static void bits(float expected, float actual, String label) {
        check(Float.floatToIntBits(expected) == Float.floatToIntBits(actual), label + ": " + expected + " != " + actual);
    }
    private static void near(float expected, float actual, String label) { check(Math.abs(expected - actual) <= 1e-6F, label); }
    private static void check(boolean result, String label) { checks++; if (!result) throw new AssertionError(label); }
}
