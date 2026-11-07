package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** RH10 drowned-coast gates and reports. */
class HighlandDrownedTest {
    static final int SITE_X = HighlandCoastTest.SITE_X, SITE_Z = HighlandCoastTest.SITE_Z, RADIUS = HighlandCoastTest.RADIUS;

    /** P0: the drowned-coast metrics and the RH9 coast cost on today's tree, for the user world and ROCKIES. */
    @Tag("probe")
    @Test void reportsDrowned() throws Exception {
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        HighlandDrownedProbe.measure(context, SITE_X, SITE_Z, RADIUS); // warm the owner plans so ns/column is column cost
        var user = HighlandDrownedProbe.measure(context, SITE_X, SITE_Z, RADIUS);
        System.out.println(user.summary("user"));
        Files.writeString(Path.of("build/rh10-drowned-user.txt"), user.summary("user") + "\n");
        assertTrue(user.samples() > 0, "no sea in the user box: " + user.summary("user"));

        HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS); // warm
        var coast = HighlandCoastProbe.measure(context, SITE_X, SITE_Z, RADIUS);
        System.out.println(coast.summary("user"));
        Files.writeString(Path.of("build/rh10-coast-baseline.txt"), coast.summary("user") + "\n");

        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            var report = HighlandDrownedProbe.measure(rockies, SITE_X, SITE_Z, RADIUS);
            System.out.println(report.summary("rockies"));
            Files.writeString(Path.of("build/rh10-drowned-rockies.txt"), report.summary("rockies") + "\n");
            assertTrue(report.samples() > 0, "no sea in the ROCKIES box: " + report.summary("rockies"));
        }
    }

    /**
     * P0: ranked candidate boxes for later plans. Fjord candidates are ROCKIES coast boxes where PNW shore meets a
     * range core; ria candidates are user-world boxes with river mouths. Widens the ROCKIES scan once if no box qualifies.
     */
    @Tag("probe")
    @Test void scansSites() throws Exception {
        int box = 4096, radius = 16384;
        boolean widened = false;
        List<HighlandDrownedProbe.Site> fjords;
        try (var rockies = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            fjords = fjordCandidates(HighlandDrownedProbe.scanSites(rockies, SITE_X, SITE_Z, radius, box));
            if (fjords.isEmpty()) {
                widened = true;
                radius = 32768;
                fjords = fjordCandidates(HighlandDrownedProbe.scanSites(rockies, SITE_X, SITE_Z, radius, box));
            }
        }
        var context = WorldgenFixture.shared(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED);
        var rias = HighlandDrownedProbe.scanSites(context, SITE_X, SITE_Z, 16384, box).stream()
            .filter(site -> site.mouths() > 0)
            .sorted(Comparator.comparingInt(HighlandDrownedProbe.Site::mouths).thenComparingInt(HighlandDrownedProbe.Site::crossings).reversed())
            .limit(5).toList();
        var out = new StringBuilder("fjord candidates (ROCKIES, seed " + HighlandPlanformProbe.USER_SEED + ", centre " + SITE_X + "," + SITE_Z
            + ", radius " + radius + (widened ? " widened from 16384" : "") + ", box " + box + "): x z crossings pnwShare rangeCore mouths\n");
        fjords.forEach(site -> out.append(line(site)));
        out.append("ria candidates (user world, radius 16384, box " + box + "): x z crossings pnwShare rangeCore mouths\n");
        rias.forEach(site -> out.append(line(site)));
        System.out.print(out);
        Files.writeString(Path.of("build/rh10-sites.txt"), out.toString());
        assertTrue(!fjords.isEmpty() && !rias.isEmpty(), "no fjord or ria candidate boxes:\n" + out);
    }

    private static List<HighlandDrownedProbe.Site> fjordCandidates(List<HighlandDrownedProbe.Site> sites) {
        return sites.stream().filter(site -> site.pnwShare() >= .5 && site.rangeCore() > 0)
            .sorted(Comparator.comparingDouble(HighlandDrownedProbe.Site::rangeCore).thenComparingInt(HighlandDrownedProbe.Site::crossings).reversed())
            .limit(5).toList();
    }

    private static String line(HighlandDrownedProbe.Site site) {
        return String.format(java.util.Locale.ROOT, "  %d %d %d %.2f %.2f %d\n", site.x(), site.z(), site.crossings(), site.pnwShare(), site.rangeCore(), site.mouths());
    }
}
