package etcodehome.freeterraforged.world.worldgen.highlands;

/** Exact interval feasibility for a downstream forest, ordered parent before child. */
public final class TreeProfileSolver {
    private TreeProfileSolver() {}
    public static double[] solve(int[] parent, double[] low, double[] high, double[] target, double[] minDrop, double[] maxDrop) {
        int n = parent.length;
        if (low.length != n || high.length != n || target.length != n || minDrop.length != n || maxDrop.length != n) {
            throw new IllegalArgumentException("Profile array lengths differ");
        }
        double[] lo = low.clone(), hi = high.clone(), water = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            if (parent[i] < -1 || parent[i] >= i || !Double.isFinite(lo[i]) || !Double.isFinite(hi[i])
                || !Double.isFinite(target[i]) || !Double.isFinite(minDrop[i]) || !Double.isFinite(maxDrop[i])
                || minDrop[i] < 0 || minDrop[i] > maxDrop[i]) throw new IllegalArgumentException("Invalid profile node " + i);
            if (lo[i] > hi[i]) throw new Infeasible(i, lo[i], hi[i]);
            int p = parent[i];
            if (p >= 0) {
                lo[p] = Math.max(lo[p], lo[i] - maxDrop[i]);
                hi[p] = Math.min(hi[p], hi[i] - minDrop[i]);
            }
        }
        for (int i = 0; i < n; i++) {
            int p = parent[i];
            double a = p < 0 ? lo[i] : Math.max(lo[i], water[p] + minDrop[i]);
            double b = p < 0 ? hi[i] : Math.min(hi[i], water[p] + maxDrop[i]);
            if (a > b) throw new Infeasible(i, a, b);
            water[i] = HighlandMath.clamp(target[i], a, b);
        }
        return water;
    }
    public static final class Infeasible extends IllegalArgumentException {
        public final int node;
        public Infeasible(int node, double lo, double hi) {
            super("No bounded downhill profile at node " + node + ": " + lo + " > " + hi);
            this.node = node;
        }
    }
}
