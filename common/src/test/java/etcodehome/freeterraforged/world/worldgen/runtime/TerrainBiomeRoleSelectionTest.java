package etcodehome.freeterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** RH5 W: Rolling Highlands water roles follow the whole biome patch, and water freezes only beside freezing land. */
class TerrainBiomeRoleSelectionTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static Holder<Biome> biome(float temperature) {
        return Holder.direct(new Biome.BiomeBuilder().hasPrecipitation(true).temperature(temperature).downfall(.5F)
            .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
            .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build());
    }

    @Test void patchIsWaterWhenAtLeastHalfItsCornersAre() {
        assertFalse(TerrainBiomeRoleSelection.mostlyWater(false, false, false, false));
        assertFalse(TerrainBiomeRoleSelection.mostlyWater(true, false, false, false));
        assertFalse(TerrainBiomeRoleSelection.mostlyWater(false, false, false, true));
        assertTrue(TerrainBiomeRoleSelection.mostlyWater(true, false, false, true));
        assertTrue(TerrainBiomeRoleSelection.mostlyWater(false, true, true, false));
        assertTrue(TerrainBiomeRoleSelection.mostlyWater(true, true, true, true));
    }

    @Test void freezingFollowsTheSnowLine() {
        // Vanilla frozen_river (0.0), snowy taiga (-0.5) and snowy plains (0.0) freeze; river (0.5), taiga (0.25) do not.
        assertTrue(TerrainBiomeRoleSelection.freezes(biome(0F)));
        assertTrue(TerrainBiomeRoleSelection.freezes(biome(-.5F)));
        assertFalse(TerrainBiomeRoleSelection.freezes(biome(.25F)));
        assertFalse(TerrainBiomeRoleSelection.freezes(biome(.5F)));
    }
}
