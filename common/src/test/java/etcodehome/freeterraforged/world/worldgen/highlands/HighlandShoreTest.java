package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.world.worldgen.biome.Continentalness;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler.Field;

/**
 * Sea shores on the user's world (in-game 2026-11-04 at 10427,18050 and 10891,17853): the sea floor lay one block deep
 * for hundreds of blocks (vanilla turns that depth to dirt, so sand/clay/gravel disks made a patchwork), and forest ran
 * down to the water because RH land never kept a beach.
 */
class HighlandShoreTest {
    @Test void seaFloorDeepensAndLowShoresAreBeaches() throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            var hm = context.heightmap;
            float coastEdge = context.preset.world().controlPoints.coast;
            long sea = 0, oneDeep = 0, shores = 0, beaches = 0, coastal = 0, landCoast = 0;
            Cell c = new Cell(), next = new Cell();
            var profile = ((RollingHighlandsContinent) hm.continent()).coast().profile();
            for (int[] site : new int[][]{{10427, 18050}, {10891, 17853}}) {
                // RH9: rows every 8 over ±384 (the coves/points warp left these sites mostly sea cliff, no low shore).
                for (int z = site[1] - 384; z <= site[1] + 384; z += 8) {
                    context.lookup.applyCell(c.reset(), site[0] - 384, z, false, true);
                    for (int x = site[0] - 384; x < site[0] + 384; x++) {
                        context.lookup.applyCell(next.reset(), x + 1, z, false, true);
                        boolean ocean = c.continentEdge <= coastEdge;
                        if (ocean) {
                            sea++;
                            if (context.levels.waterY - context.levels.scale(c.height) == 1) oneDeep++;
                        } else if (c.terrain == TerrainType.COAST) landCoast++;
                        // A low dry column whose neighbour is the sea.
                        boolean nextOcean = next.continentEdge <= coastEdge;
                        Cell shore = ocean && !nextOcean ? next : !ocean && nextOcean ? c : null;
                        int shoreX = shore == c ? x : x + 1;
                        // RH9: below sea cliffs a low column at the waterline is the cliff's foot, not a beach.
                        if (shore != null && !shore.hasRiverWater && profile.shore(shoreX, z).cliff() < .5) {
                            double h = (shore.height - context.levels.water) * context.levels.terrainScaleFactor;
                            if (h > 0 && h <= HighlandCoastProfile.BERM + 1) {
                                shores++;
                                if (shore.terrain == TerrainType.BEACH) beaches++;
                                float continent = Field.CONTINENT.read(shore, hm);
                                if (continent >= Continentalness.COAST.min() && continent < Continentalness.COAST.max()) coastal++;
                            }
                        }
                        Cell swap = c; c = next; next = swap;
                    }
                }
            }
            String summary = String.format(Locale.ROOT, "sea=%d oneDeep=%.1f%% lowShores=%d beach=%.1f%% coastContinentalness=%.1f%% landCoast=%d",
                sea, 100.0 * oneDeep / sea, shores, 100.0 * beaches / shores, 100.0 * coastal / shores, landCoast);
            System.out.println(summary);
            var failures = new ArrayList<String>();
            // Before: about 60% of these sea columns were one block deep.
            if (oneDeep > (.05 + HighlandDrownedTest.SHARE_TOLERANCE) * sea) failures.add("more than 8% of sea columns one block deep (5% with the D-11-14-2 margin): " + oneDeep + " of " + sea);
            if (shores < 20) failures.add("too few shore crossings to judge");
            if (beaches < (.95 - HighlandDrownedTest.SHARE_TOLERANCE) * shores) failures.add("fewer than 92% (95% with the D-11-14-2 margin) of low sea-shore columns are beach terrain: " + beaches + " of " + shores);
            if (coastal < (.95 - HighlandDrownedTest.SHARE_TOLERANCE) * shores) failures.add("fewer than 92% (95% with the D-11-14-2 margin) of low sea-shore columns read coast continentalness: " + coastal + " of " + shores);
            // The climate pass's region-wide COAST tag only became a beach on some lookup paths.
            if (landCoast > 0) failures.add("land columns keep the region COAST tag");
            assertTrue(failures.isEmpty(), String.join("\n", failures) + "\n" + summary);
        }
    }
}
