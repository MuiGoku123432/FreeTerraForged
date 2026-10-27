package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TreeProfileSolverTest {
    @Test void agreesWithExhaustiveIntegerOracle() {
        Random random = new Random(786913);
        for (int trial = 0; trial < 1000; trial++) {
            int n = 2 + random.nextInt(5);
            int[] p = new int[n];
            double[] lo = new double[n], hi = new double[n], target = new double[n], a = new double[n], b = new double[n];
            p[0] = -1;
            for (int i = 1; i < n; i++) {
                p[i] = random.nextInt(i);
                lo[i] = random.nextInt(5); hi[i] = lo[i] + random.nextInt(3);
                target[i] = random.nextInt(9); a[i] = random.nextInt(2); b[i] = a[i] + random.nextInt(4);
            }
            boolean expected = enumerate(0, new double[n], p, lo, hi, a, b);
            if (!expected) {
                assertThrows(TreeProfileSolver.Infeasible.class, () -> TreeProfileSolver.solve(p, lo, hi, target, a, b), "fixture " + trial);
            } else {
                double[] result = TreeProfileSolver.solve(p, lo, hi, target, a, b);
                for (int i = 0; i < n; i++) {
                    assertTrue(result[i] >= lo[i] && result[i] <= hi[i]);
                    if (p[i] >= 0) {
                        double drop = result[i] - result[p[i]];
                        assertTrue(drop >= a[i] && drop <= b[i]);
                    }
                }
            }
        }
    }
    private static boolean enumerate(int i, double[] w, int[] p, double[] lo, double[] hi, double[] a, double[] b) {
        if (i == p.length) return true;
        for (double v = lo[i]; v <= hi[i]; v++) {
            if (p[i] >= 0 && (v - w[p[i]] < a[i] || v - w[p[i]] > b[i])) continue;
            w[i] = v;
            if (enumerate(i + 1, w, p, lo, hi, a, b)) return true;
        }
        return false;
    }
    @Test void invalidGraphAndNonfiniteInputAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TreeProfileSolver.solve(new int[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}, new double[]{0}));
        assertThrows(IllegalArgumentException.class, () -> TreeProfileSolver.solve(new int[]{-1}, new double[]{0}, new double[]{1}, new double[]{Double.NaN}, new double[]{0}, new double[]{0}));
    }
    @Test void regionalFieldsKeepAdjacentFarCoordinatesDistinct() {
        var fields = new HighlandFields(42, 4096);
        assertNotEquals(fields.sample(29_999_980, -29_999_990), fields.sample(29_999_981, -29_999_990));
        assertEquals(fields.sample(-400, -100), fields.sample(-400, -100));
    }
}
