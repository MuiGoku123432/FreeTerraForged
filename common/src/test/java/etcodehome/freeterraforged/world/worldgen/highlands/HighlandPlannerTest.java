package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;

class HighlandPlannerTest {
    @Test void actualContinentDomainsAndProfiles() {
        for (long seed : new long[]{0, 1, 42, 8675309, -314159}) {
            var preset = Presets.makeFTFDefault();
            try (var context = WorldgenFixture.context(preset, seed); var coast = new HighlandCoast(context)) {
                var fields = new HighlandFields(seed, 4096);
                var builder = new HighlandDrainage(coast, fields, 4096, preset.world().controlPoints);
                for (int ownerX : new int[]{0, -1, 1}) {
                var plan = builder.build(ownerX, 0);
                System.out.println("RH seed=" + seed + " nodes=" + plan.gridNodes() + " land=" + plan.landNodes()
                    + " channels=" + plan.nodes().size() + " ms=" + plan.buildNanos() / 1e6);
                int[] joins = new int[plan.nodes().size()];
                for (var node : plan.nodes()) if (node.parent() >= 0) joins[node.parent()]++;
                int sites = 0;
                for (int j = 0; j < joins.length && sites < 3; j++) {
                    var node = plan.nodes().get(j);
                    if (joins[j] >= 2 && node.water() > 10) {
                        System.out.println("RH junction seed=" + seed + " x=" + node.x() + " z=" + node.z() + " water=" + node.water());
                        sites++;
                    }
                }
                assertTrue(plan.gridNodes() <= HighlandDrainage.MAX_NODES);
                if (ownerX == 0) assertFalse(plan.nodes().isEmpty());
                for (int i = 0; i < plan.nodes().size(); i++) {
                    var node = plan.nodes().get(i);
                    assertTrue(node.parent() < i);
                    if (node.parent() >= 0) assertTrue(node.water() >= plan.nodes().get(node.parent()).water());
                    else assertEquals(0, node.water());
                }
                }
            }
        }
    }
}
