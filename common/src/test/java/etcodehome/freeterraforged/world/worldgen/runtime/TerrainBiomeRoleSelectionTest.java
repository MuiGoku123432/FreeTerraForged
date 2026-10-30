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

    @Test void freezingIsJudgedAtTheWaterHeight() {
        // Vanilla: above y 80 the temperature falls 0.05 per 40 blocks, shifted by a noise of +-8 blocks.
        assertEquals(.175F, TerrainBiomeRoleSelection.coldest(.175F, 80), 1e-6);
        assertEquals(.175F - 32 * .00125F, TerrainBiomeRoleSelection.coldest(.175F, 104), 1e-6);
        assertEquals(.175F - 16 * .00125F, TerrainBiomeRoleSelection.warmest(.175F, 104), 1e-6);
        // RESEARCH-RH6 §5: bloomingnature:cold_river (0.175) at y 101-107 straddles the snow line: neither always open
        // nor always frozen there, so it is never chosen (it produced speckled ice); at y 70 it never freezes.
        for (int y = 101; y <= 107; y++) {
            assertFalse(TerrainBiomeRoleSelection.alwaysOpen(.175F, y), "y " + y);
            assertFalse(TerrainBiomeRoleSelection.alwaysFrozen(.175F, y), "y " + y);
        }
        assertTrue(TerrainBiomeRoleSelection.alwaysOpen(.175F, 70));
        assertTrue(TerrainBiomeRoleSelection.alwaysOpen(.5F, 200));
        assertTrue(TerrainBiomeRoleSelection.alwaysFrozen(0F, 70));
        assertTrue(TerrainBiomeRoleSelection.landFreezes(.175F, 104));
        assertFalse(TerrainBiomeRoleSelection.landFreezes(.175F, 90));
    }

    @Test void riverCandidatesAreSplitByTheirHeightAdjustedTemperature() {
        var frozen = biome(0F); var cold = biome(.175F); var river = biome(.5F);
        var point = net.minecraft.world.level.biome.Climate.parameters(0, 0, 0, 0, 0, 0, 0);
        var temperatures = new TerrainBiomeRoleSelection.RiverTemperatures(java.util.List.of(
            com.mojang.datafixers.util.Pair.of(point, frozen), com.mojang.datafixers.util.Pair.of(point, cold), com.mojang.datafixers.util.Pair.of(point, river)));
        // y 104: cold_river is ambiguous, so open water is only river and frozen water only frozen_river.
        assertEquals(java.util.List.of(river), temperatures.open(104).values().stream().map(com.mojang.datafixers.util.Pair::getSecond).toList());
        assertEquals(java.util.List.of(frozen), temperatures.frozen(104).values().stream().map(com.mojang.datafixers.util.Pair::getSecond).toList());
        // y 70: cold_river never freezes.
        assertEquals(java.util.List.of(cold, river), temperatures.open(70).values().stream().map(com.mojang.datafixers.util.Pair::getSecond).toList());
        // Very high water: no candidate is always open.
        assertNull(temperatures.open(400));
    }
}
