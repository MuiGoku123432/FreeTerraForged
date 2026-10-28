package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.*;

class HighlandSettingsTest {
    @BeforeAll static void setup() { WorldgenFixture.initialize(); }
    @Test void savedProfilesAreExplicitAndCopiesIndependent() throws Exception {
        Preset p = Presets.makeFTFDefault(); p.world().continent.continentType = ContinentType.ROLLING_HIGHLANDS;
        var encoded = Preset.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        var decoded = Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(RollingHighlandsSettings.DEFAULT, decoded.terrain().rollingHighlands);
        Preset copy = decoded.copy();
        copy.terrain().rollingHighlands = new RollingHighlandsSettings(RollingHighlandsSettings.MODEL_VERSION, 4096, 120, 100, 256);
        assertEquals(80, decoded.terrain().highlands().uplandHeight());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/rolling-highlands"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/rolling-highlands/preset.json"),
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(encoded) + "\n");
    }
    @Test void incompleteUnknownAndUnsupportedProfilesFail() {
        var json = RollingHighlandsSettings.CODEC.encodeStart(JsonOps.INSTANCE, RollingHighlandsSettings.DEFAULT).getOrThrow().getAsJsonObject();
        json.remove("valleyWidth"); assertTrue(RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        json.addProperty("valleyWidth", 256); json.addProperty("modelVersion", 99);
        assertTrue(RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        // RH1 (model 1) presets are rejected with an actionable message rather than regenerated differently.
        json.addProperty("modelVersion", 1);
        var rh1 = RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).error();
        assertTrue(rh1.isPresent() && rh1.get().message().contains("create a new world"));
        json.addProperty("modelVersion", 2);
        var rh2 = RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).error();
        assertTrue(rh2.isPresent() && rh2.get().message().contains("create a new world"));
        // RH3 (model 3) likewise: RH4 routes and shapes rivers differently.
        json.addProperty("modelVersion", 3);
        var rh3 = RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).error();
        assertTrue(rh3.isPresent() && rh3.get().message().contains("create a new world"));
        // Meanders is optional (default 100) and bounded.
        json.addProperty("modelVersion", RollingHighlandsSettings.MODEL_VERSION); json.remove("meanders");
        assertEquals(100, RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().meanders());
        json.addProperty("meanders", 201);
        assertTrue(RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        json.addProperty("meanders", 0);
        assertEquals(0, RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().meanders());
        json.addProperty("uplandHeight", Double.NaN);
        assertTrue(RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        json.addProperty("uplandHeight", 80); json.addProperty("valleyDepth", Double.NaN);
        assertTrue(RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        json.addProperty("valleyDepth", .5);
        assertTrue(RollingHighlandsSettings.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        var world = WorldSettings.makeDefault(); world.continent.continentScale = 5000;
        assertThrows(IllegalArgumentException.class, () -> new RollingHighlandsSettings(RollingHighlandsSettings.MODEL_VERSION, 2048, 80, 100, 256).validate(world));
    }
    @Test void legacyEnumOrdinalsRemainStable() {
        assertEquals(0, ContinentType.MULTI.ordinal());
        assertEquals(1, ContinentType.SINGLE.ordinal());
        assertEquals(2, ContinentType.MULTI_IMPROVED.ordinal());
        assertEquals(3, ContinentType.UPLIFT.ordinal());
    }
    @Test void waterMetadataCopiesResetsAndLegacyFormulaIsUnchanged() {
        Cell a = new Cell(), b = new Cell();
        a.rollingHighlands = true; a.hasRiverWater = true; a.riverWaterLevel = .25F;
        b.copyFrom(a); assertTrue(RiverWater.isWater(b)); assertEquals(.25F, RiverWater.level(b, .25F));
        b.reset(); assertFalse(b.rollingHighlands); assertFalse(b.hasRiverWater);
        for (int i = 0; i < 1000; i++) {
            b.waterTable = i / 999F; b.globalContinentScale = 4000; b.continentSizeModifier = .7F;
            assertEquals(Float.floatToIntBits(ContinentalHydrology.getComplexWaterHeight(b.waterTable, b.globalContinentScale, b.continentSizeModifier) + .25F),
                Float.floatToIntBits(RiverWater.level(b, .25F)));
        }
    }
}
