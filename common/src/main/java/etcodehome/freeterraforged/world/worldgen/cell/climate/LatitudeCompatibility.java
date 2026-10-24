package etcodehome.freeterraforged.world.worldgen.cell.climate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import com.google.gson.JsonParser;
import etcodehome.freeterraforged.data.worldgen.preset.settings.LatitudeSettings;

/** Known external-engine checks. No dependency classes are loaded and no external profile is rewritten. */
public final class LatitudeCompatibility {
    private LatitudeCompatibility() {}

    public static void validateMods(LatitudeSettings profile, Predicate<String> loaded) {
        if (!profile.enabled()) return;
        for (String id : new String[]{"naturaltemperature", "terralatitude", "fnf_altitude"}) {
            if (loaded.test(id)) {
                throw new IllegalStateException("Native latitude cannot run alongside " + id
                    + ". Disable native latitude for the external climate setup, or use a new native world without that mod.");
            }
        }
    }

    /** Pinned TerraLatitude schema 1 marker; read only before an owned Overworld begins generation. */
    public static void validateSavedExternalProfile(Path worldRoot, LatitudeSettings nativeProfile, Predicate<String> loaded) {
        Path file = worldRoot.resolve("terralatitude-climate.json");
        if (Files.notExists(file)) return;
        try {
            if (Files.size(file) > 1_048_576L) throw new IOException("Profile exceeds 1 MiB");
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var schema = root.get("schema");
            var enabled = root.get("enabled");
            if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()
                || schema.getAsBigDecimal().intValueExact() != 1 || enabled == null || !enabled.isJsonPrimitive()
                || !enabled.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("Unknown TerraLatitude profile marker");
            }
            if (!enabled.getAsBoolean()) return;
            if (nativeProfile.enabled()) {
                throw new IllegalStateException("This save uses TerraLatitude. Restore its original climate setup; native migration is not supported.");
            }
            if (!loaded.test("terralatitude") || !loaded.test("naturaltemperature")) {
                throw new IllegalStateException("This save requires TerraLatitude and Natural Temperature. Restore its original dependencies before generating chunks.");
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Cannot safely load external climate profile " + file + ": " + failure.getMessage(), failure);
        }
    }
}
