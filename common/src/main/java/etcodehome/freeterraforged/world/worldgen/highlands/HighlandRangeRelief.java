package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.Arrays;

/**
 * RH7 D2/D3: drainage-carved relief of the mountain ranges of one continent. The range envelope (uplift) is solved on
 * a STEP-block grid covering the owner's range cores: flow is routed (priority flood from the range foot), drainage
 * area accumulated, and every node's elevation is integrated up from the foot with the stream-power steady-state slope
 * for its area, capped at the threshold hillslope gradient (small areas are hillslopes) and at the uplift (the range shape scaled
 * up, D1). Routing is repeated on the carved surface so the network organises into branching valleys; hillslopes then
 * rise from the smoothed channel lines at the threshold gradient and meet in divides (D3). The result is the carved
 * range surface at each node, interpolated with a cubic B-spline. Rendering only: river planning uses the range shape.
 */
public final class HighlandRangeRelief {
    static final int STEP = 24, COARSE = 6, ITERATIONS = 3;
    /** Threshold hillslope gradient (≈ 39°) at REFERENCE_HEIGHT, and its cap (≈ 50°) for taller ranges. */
    static final double HILLSLOPE = .8, MAX_HILLSLOPE = 1.2;
    /**
     * Channel gradient at the reference area, area exponent (concavity), reference area (blocks²), and the drainage
     * area at which a channel starts (smaller areas are hillslopes rising at HILLSLOPE along the flow path).
     */
    static final double CHANNEL_SLOPE = .35, THETA = .5, REFERENCE_AREA = 1e4, CHANNEL_AREA = 1.5e4;
    /** Range Height (blocks) at which the constants apply; taller ranges scale them (see build). */
    static final double REFERENCE_HEIGHT = 200;
    /** Routing perturbation (blocks) and its wavelength, so flow on the smooth envelope breaks into branches. */
    static final double ROUGHNESS = 14, ROUGHNESS_WAVELENGTH = 260;

    public static final HighlandRangeRelief EMPTY = new HighlandRangeRelief(0, 0, 0, 0, null, 0, 0, 0);

    private final int originX, originZ, width, depth;
    private final float[] cut;
    final long buildNanos;
    /** Diagnostics: cirques (G2) and glaciated channel nodes (G1) in this grid. */
    final int cirques, glacialChannels;

    private HighlandRangeRelief(int originX, int originZ, int width, int depth, float[] cut, long buildNanos, int cirques, int glacialChannels) {
        this.originX = originX; this.originZ = originZ; this.width = width; this.depth = depth; this.cut = cut;
        this.buildNanos = buildNanos; this.cirques = cirques; this.glacialChannels = glacialChannels;
    }

    /** G2 tarns: lake id per node (-1 none), each tarn's level (carved units) and centre (world blocks). */
    private int[] tarnId;
    private double[] tarnLevel, tarnX, tarnZ;
    /** Absolute water height (blocks above sea level) per tarn, resolved lazily by the continent (NaN until then). */
    private double[] tarnWater;
    /** Node bounds per tarn: min x, min z, max x, max z (grid indices). */
    private int[] tarnBox;
    int tarnCount() { return tarnLevel == null ? 0 : tarnLevel.length; }
    double[] tarnCentre(int k) { return new double[] {tarnX[k], tarnZ[k]}; }
    /** World-block bounds of everything {@link #tarn} can assign to tarn k: its nodes' cells, one step around them. */
    double[] tarnBounds(int k) {
        return new double[] {originX + (tarnBox[4 * k] - 1) * STEP, originZ + (tarnBox[4 * k + 1] - 1) * STEP,
            originX + (tarnBox[4 * k + 2] + 1) * STEP, originZ + (tarnBox[4 * k + 3] + 1) * STEP};
    }

    /**
     * G2: floods the carved grid; the closed depression around each cirque centre that is at least TARN_MIN_DEPTH
     * deep becomes a tarn at its spill level less TARN_FREEBOARD.
     */
    private void tarns(double[] carved, boolean[] range, Net net, boolean[] seen, double[] filled) {
        int n = width * depth;
        int[] id = new int[n];
        Arrays.fill(id, -1);
        var found = new java.util.ArrayList<double[]>();
        int[] stack = new int[n];
        for (int s = 0; s < n; s++) {
            if (net.cirque[s] <= .5) continue;
            int cx = (int) Math.round(net.cx[s]), cz = (int) Math.round(net.cz[s]);
            // Local priority flood over a window around the bowl; its edge counts as an outlet (the bowl is small).
            int k = (int) Math.ceil(1.5 * net.cr[s] / STEP) + 2;
            int x0 = Math.max(0, cx - k), z0 = Math.max(0, cz - k), x1 = Math.min(width - 1, cx + k), z1 = Math.min(depth - 1, cz + k);
            if (x0 > cx || z0 > cz || x1 < cx || z1 < cz) continue;
            fillWindow(carved, range, x0, z0, x1, z1, seen, filled);
            int seed = -1;
            double deepest = TARN_MIN_DEPTH;
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                int jx = cx + dx, jz = cz + dz;
                if (jx < 0 || jz < 0 || jx >= width || jz >= depth) continue;
                int j = jz * width + jx;
                if (range[j] && id[j] < 0 && filled[j] - carved[j] >= deepest) { deepest = filled[j] - carved[j]; seed = j; }
            }
            if (seed < 0) continue;
            int lake = found.size(), top = 0, size = 0;
            double level = filled[seed], sx = 0, sz = 0;
            int bx0 = width, bz0 = depth, bx1 = -1, bz1 = -1;
            stack[top++] = seed; id[seed] = lake;
            while (top > 0) {
                int i = stack[--top];
                sx += i % width; sz += i / width; size++;
                bx0 = Math.min(bx0, i % width); bz0 = Math.min(bz0, i / width); bx1 = Math.max(bx1, i % width); bz1 = Math.max(bz1, i / width);
                for (int d = 0; d < 8; d++) {
                    int jx = i % width + DX[d], jz = i / width + DZ[d];
                    if (jx < 0 || jz < 0 || jx >= width || jz >= depth) continue;
                    int j = jz * width + jx;
                    if (jx < x0 || jz < z0 || jx > x1 || jz > z1) continue;
                    if (!range[j] || id[j] >= 0 || filled[j] - carved[j] <= 1e-6 || Math.abs(filled[j] - level) > 1e-6) continue;
                    id[j] = lake; stack[top++] = j;
                }
            }
            found.add(new double[] {level - TARN_FREEBOARD, originX + sx / size * STEP, originZ + sz / size * STEP, bx0, bz0, bx1, bz1});
        }
        if (found.isEmpty()) return;
        int count = found.size();
        tarnId = id;
        tarnLevel = new double[count]; tarnX = new double[count]; tarnZ = new double[count]; tarnWater = new double[count];
        tarnBox = new int[4 * count];
        Arrays.fill(tarnWater, Double.NaN);
        for (int k = 0; k < count; k++) {
            double[] f = found.get(k);
            tarnLevel[k] = f[0]; tarnX[k] = f[1]; tarnZ[k] = f[2];
            for (int b = 0; b < 4; b++) tarnBox[4 * k + b] = (int) f[3 + b];
        }
    }

    /** Priority flood of carved heights inside a window (its edge and non-range nodes drain out) into {@code filled}. */
    private void fillWindow(double[] carved, boolean[] range, int x0, int z0, int x1, int z1, boolean[] seen, double[] filled) {
        var heap = new MinHeap(256);
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int i = z * width + x;
            seen[i] = false; filled[i] = carved[i];
            if (!range[i] || x == x0 || z == z0 || x == x1 || z == z1) { seen[i] = true; if (range[i]) heap.pushGrow(i, carved[i]); }
        }
        while (heap.size > 0) {
            double level = heap.topKey();
            int i = heap.pop();
            for (int d = 0; d < 8; d++) {
                int jx = i % width + DX[d], jz = i / width + DZ[d];
                if (jx < x0 || jz < z0 || jx > x1 || jz > z1) continue;
                int j = jz * width + jx;
                if (seen[j]) continue;
                seen[j] = true;
                filled[j] = Math.max(carved[j], level);
                heap.pushGrow(j, filled[j]);
            }
        }
    }

    /** Tarn sample: id, membership weight (bilinear share of the surrounding nodes in it), level and centre. */
    record Tarn(int id, double weight, double level, double centerX, double centerZ) {}
    Tarn tarn(double x, double z) {
        if (tarnId == null) return null;
        double fx = (x - originX) / STEP, fz = (z - originZ) / STEP;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        if (ix < 0 || iz < 0 || ix >= width - 1 || iz >= depth - 1) return null;
        int a = iz * width + ix, id = Math.max(Math.max(tarnId[a], tarnId[a + 1]), Math.max(tarnId[a + width], tarnId[a + width + 1]));
        if (id < 0) return null;
        double tx = fx - ix, tz = fz - iz;
        double w = (1 - tz) * ((1 - tx) * (tarnId[a] == id ? 1 : 0) + tx * (tarnId[a + 1] == id ? 1 : 0))
            + tz * ((1 - tx) * (tarnId[a + width] == id ? 1 : 0) + tx * (tarnId[a + width + 1] == id ? 1 : 0));
        return new Tarn(id, w, tarnLevel[id], tarnX[id], tarnZ[id]);
    }
    /** Absolute tarn water (blocks above sea level), resolved once per tarn by {@code resolve} (deterministic). */
    double tarnWater(Tarn tarn, java.util.function.ToDoubleFunction<Tarn> resolve) {
        double w = tarnWater[tarn.id()];
        if (Double.isNaN(w)) tarnWater[tarn.id()] = w = resolve.applyAsDouble(tarn);
        return w;
    }

    /** Diagnostic: grayscale image of the cut grid. */
    void dump(java.io.File file) throws java.io.IOException {
        var image = new java.awt.image.BufferedImage(width, depth, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            int v = (int) Math.max(0, Math.min(255, cut[z * width + x]));
            image.setRGB(x, z, v * 0x10101);
        }
        javax.imageio.ImageIO.write(image, "png", file);
    }

    @Override public String toString() {
        if (cut == null) return "relief(empty)";
        int nodes = 0; double max = 0, sum = 0;
        for (float c : cut) if (c > 0) { nodes++; max = Math.max(max, c); sum += c; }
        return String.format(java.util.Locale.ROOT, "relief(origin=%d,%d grid=%dx%d cutNodes=%d mean=%.1f max=%.1f build=%.0fms)",
            originX, originZ, width, depth, nodes, nodes == 0 ? 0 : sum / nodes, max, buildNanos / 1e6);
    }

    /** Cut depth (blocks, ≥ 0) at a position: 0 outside the solved ranges. */
    /**
     * Carved range surface (blocks, the range's share of the height) at a position, or NaN off the grid. The caller
     * subtracts it from the exact range height: interpolating the cut instead would leave the envelope's sharp divide,
     * which the grid cannot represent, printed as a straight crease through the carved relief.
     */
    public double surface(double x, double z) {
        if (cut == null) return Double.NaN;
        double fx = (x - originX) / STEP, fz = (z - originZ) / STEP;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        if (ix < 1 || iz < 1 || ix >= width - 2 || iz >= depth - 2) return Double.NaN;
        double tx = fx - ix, tz = fz - iz;
        // Cubic B-spline: smooth (no cell facets) and, unlike Catmull-Rom, no overshoot beading along sharp divides.
        double result = 0;
        for (int k = -1; k <= 2; k++) {
            int row = (iz + k) * width + ix;
            double v = spline(cut[row - 1], cut[row], cut[row + 1], cut[row + 2], tx);
            result += v * weight(k, tz);
        }
        return result;
    }
    private static double weight(int k, double t) {
        double t2 = t * t, t3 = t2 * t;
        return switch (k) {
            case -1 -> (1 - 3 * t + 3 * t2 - t3) / 6;
            case 0 -> (3 * t3 - 6 * t2 + 4) / 6;
            case 1 -> (-3 * t3 + 3 * t2 + 3 * t + 1) / 6;
            default -> t3 / 6;
        };
    }
    private static double spline(double a, double b, double c, double d, double t) {
        return a * weight(-1, t) + b * weight(0, t) + c * weight(1, t) + d * weight(2, t);
    }

    static HighlandRangeRelief build(HighlandLand land, HighlandCoast coast, int ownerX, int ownerZ) {
        if (land.rangeHeight() <= 0) return EMPTY;
        long started = System.nanoTime();
        var bounds = coast.bounds(ownerX, ownerZ);
        int coarse = STEP * COARSE;
        int cx0 = (int) Math.floor(bounds.minX() / coarse), cz0 = (int) Math.floor(bounds.minZ() / coarse);
        int cw = (int) Math.ceil(bounds.maxX() / coarse) - cx0 + 1, cd = (int) Math.ceil(bounds.maxZ() / coarse) - cz0 + 1;
        // Coarse mask (dilated by one cell) of owned range cores; its bounding box sets the fine grid.
        boolean[] coarseMask = new boolean[cw * cd];
        int mx0 = Integer.MAX_VALUE, mz0 = Integer.MAX_VALUE, mx1 = -1, mz1 = -1;
        for (int iz = 0; iz < cd; iz++) for (int ix = 0; ix < cw; ix++) {
            double x = (cx0 + ix) * (double) coarse, z = (cz0 + iz) * (double) coarse;
            if (land.rangeCore(x, z) <= 0) continue;
            var s = coast.sample(x, z);
            if (s.ownerX() != ownerX || s.ownerZ() != ownerZ) continue;
            coarseMask[iz * cw + ix] = true;
            mx0 = Math.min(mx0, ix); mz0 = Math.min(mz0, iz); mx1 = Math.max(mx1, ix); mz1 = Math.max(mz1, iz);
        }
        if (mx1 < 0) return EMPTY;
        mx0 = Math.max(0, mx0 - 2); mz0 = Math.max(0, mz0 - 2); mx1 = Math.min(cw - 1, mx1 + 2); mz1 = Math.min(cd - 1, mz1 + 2);
        int originX = (cx0 + mx0) * coarse, originZ = (cz0 + mz0) * coarse;
        int width = (mx1 - mx0) * COARSE + 1, depth = (mz1 - mz0) * COARSE + 1, n = width * depth;
        double[] envelope = new double[n], base = new double[n];
        double cap = UPLIFT_CAP * land.rangeHeight();
        boolean[] range = new boolean[n];
        long seed = land.seed();
        // The range shape is evaluated on every second node (COARSE is even, so those include the grid's last row and
        // column) and interpolated between: it is smooth at that scale, and the rendered surface uses the exact shape.
        for (int iz = 0; iz < depth; iz += 2) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("RH range relief interrupted");
            int czi = mz0 + iz / COARSE;
            for (int ix = 0; ix < width; ix += 2) {
                int cxi = mx0 + ix / COARSE;
                // Only near the coarse mask (its 3x3 neighbourhood) can a node lie in a range.
                boolean near = false;
                for (int dz = -1; dz <= 1 && !near; dz++) for (int dx = -1; dx <= 1 && !near; dx++) {
                    int qx = cxi + dx, qz = czi + dz;
                    near = qx >= 0 && qz >= 0 && qx < cw && qz < cd && coarseMask[qz * cw + qx];
                }
                if (!near) continue;
                double x = originX + ix * STEP, z = originZ + iz * STEP;
                var frame = land.rangeFrame(x, z);
                if (frame != null) base[iz * width + ix] = Math.max(0, land.range(x, z, frame));
            }
        }
        for (int iz = 0; iz < depth; iz++) for (int ix = 0; ix < width; ix++) {
            int i = iz * width + ix;
            if ((ix & 1) == 1 && (iz & 1) == 0) base[i] = .5 * (base[i - 1] + base[i + 1]);
            else if ((ix & 1) == 0 && (iz & 1) == 1) base[i] = .5 * (base[i - width] + base[i + width]);
            else if ((ix & 1) == 1) base[i] = .25 * (base[i - width - 1] + base[i - width + 1] + base[i + width - 1] + base[i + width + 1]);
        }
        for (int i = 0; i < n; i++) if (base[i] > 0) {
            envelope[i] = Math.min(UPLIFT * base[i], Math.max(base[i], cap));
            range[i] = true;
        }
        // Routing surface: the envelope, roughened slightly.

        // Ranges taller than REFERENCE_HEIGHT (h = height ratio): steeper channels (steady state S ∝ uplift, so channel
        // steepness grows with h²: the range must also stand h times taller over the same width), steeper threshold
        // hillslopes (√h, capped), and stronger, finer routing roughness so the valleys stay dense.
        double h = Math.max(1, land.rangeHeight() / REFERENCE_HEIGHT);
        double roughness = ROUGHNESS * h, roughnessWavelength = ROUGHNESS_WAVELENGTH / h;
        double hillslope = Math.min(MAX_HILLSLOPE, HILLSLOPE * Math.sqrt(h)), channelSlope = CHANNEL_SLOPE * h * h;
        double[] surface = new double[n], area = new double[n];
        int[] order = new int[n], receiver = new int[n];
        float[] rough = new float[n];
        for (int i = 0; i < n; i++) if (range[i]) {
            rough[i] = (float) (roughness * (2 * HighlandMath.noise(seed + 7307, originX + i % width * STEP, originZ + i / width * STEP, roughnessWavelength) - 1));
            surface[i] = envelope[i] + rough[i];
        }
        double[] carved = envelope.clone();
        // Steady-state channel gradient scales with uplift (S ∝ U for n = 1), so taller ranges keep their relief.
        int count = 0;
        boolean[] seen = new boolean[n];
        double[] filled = new double[n];
        for (int it = 0; it < ITERATIONS; it++) {
            count = route(surface, range, width, depth, order, receiver, seen, filled);
            Arrays.fill(area, 0);
            for (int k = count - 1; k >= 0; k--) {
                int i = order[k];
                area[i] += (double) STEP * STEP;
                if (receiver[i] >= 0) area[receiver[i]] += area[i];
            }
            for (int k = 0; k < count; k++) {
                int i = order[k], r = receiver[i];
                if (r < 0) { carved[i] = Math.min(envelope[i], 0); continue; }
                int dx = i % width - r % width, dz = i / width - r / width;
                double distance = STEP * Math.sqrt(dx * dx + dz * dz);
                double slope = area[i] < CHANNEL_AREA ? hillslope : Math.min(hillslope, channelSlope * Math.pow(area[i] / REFERENCE_AREA, -THETA));
                carved[i] = Math.min(envelope[i], carved[r] + distance * slope);
            }
            for (int i = 0; i < n; i++) if (range[i]) surface[i] = carved[i] + rough[i];
        }

        Net net = hillslopes(carved, envelope, area, receiver, range, width, depth, seed, originX, originZ, hillslope, land.snowlineHeight(), land.rangeHeight(), order, count);
        float[] cut = new float[n];
        for (int i = 0; i < n; i++) if (range[i]) cut[i] = (float) carved[i];
        int cirques = 0, glacial = 0;
        if (net.glacier != null) for (int i = 0; i < n; i++) { if (net.cirque[i] > .5) cirques++; if (net.glacier[i] > .5) glacial++; }
        var relief = new HighlandRangeRelief(originX, originZ, width, depth, cut, System.nanoTime() - started, cirques, glacial);
        if (net.cirque != null) relief.tarns(carved, range, net, seen, filled);
        return relief;
    }

    /**
     * D1: the uplift the valleys are carved from is the range shape scaled by UPLIFT (capped at UPLIFT_CAP of the
     * range height), a steep high block; the threshold hillslopes, not the envelope, then set the peak heights.
     */
    static final double UPLIFT = 1.8, UPLIFT_CAP = 1.5;
    /** Smoothing passes applied to channel centre lines, so D8 steps do not print as stairs and beads. */
    static final int CHANNEL_SMOOTHING = 4;
    /** Valley floor rounding (blocks): the V is band-limited to the grid so diagonal floors do not alias to beads. */
    static final double FLOOR_ROUNDING = 28;
    /** Channel centre-line wander (grid cells) and its wavelength (blocks). */
    static final double CHANNEL_WANDER = 3, WANDER_WAVELENGTH = 360;
    /** Softness (blocks) of the join between carved walls and the uncut envelope. */
    static final double ENVELOPE_SOFTNESS = 12;
    /** Softness (blocks) of divides between neighbouring hillslope cones. */
    static final double RIDGE_SOFTNESS = 8;
    private static double wall(double distance, double hillslope) {
        return hillslope * (Math.sqrt(distance * distance + FLOOR_ROUNDING * FLOOR_ROUNDING) - FLOOR_ROUNDING);
    }

    /**
     * D3: rebuilds every node as the lowest cone rising at HILLSLOPE (Euclidean) from the channel network, capped at
     * the envelope. Channels are segments from each channel node to its receiver along smoothed centre lines, with the
     * stream profile interpolated along them; the range foot is a point source at its own height. Sources propagate
     * Dijkstra-style over the grid (each node keeps the source giving it the lowest surface).
     */
    private static Net hillslopes(double[] carved, double[] envelope, double[] area, int[] receiver, boolean[] range, int width, int depth, long seed, int originX, int originZ, double hillslope,
                                   double snowline, double rangeHeight, int[] routed, int routedCount) {
        int n = width * depth, m = 0;
        int[] nodes = new int[n];
        for (int i = 0; i < n; i++) if (range[i]) nodes[m++] = i;
        boolean[] channel = new boolean[n];
        for (int k = 0; k < m; k++) { int i = nodes[k]; channel[i] = area[i] >= CHANNEL_AREA; }
        // Main upstream donor (largest channel donor) of each channel node.
        int[] main = new int[n];
        Arrays.fill(main, -1);
        for (int k = 0; k < m; k++) {
            int i = nodes[k], r = receiver[i];
            if (!channel[i] || r < 0 || !channel[r]) continue;
            if (main[r] < 0 || area[i] > area[main[r]]) main[r] = i;
        }
        double[] px = new double[n], pz = new double[n], qx = new double[n], qz = new double[n];
        for (int k = 0; k < m; k++) { int i = nodes[k]; px[i] = i % width; pz[i] = i / width; }
        for (int pass = 0; pass < CHANNEL_SMOOTHING; pass++) {
            for (int k = 0; k < m; k++) {
                int i = nodes[k];
                qx[i] = px[i]; qz[i] = pz[i];
                int r = receiver[i], u = main[i];
                if (!channel[i] || r < 0 || !channel[r] || u < 0) continue;
                qx[i] = .25 * px[u] + .5 * px[i] + .25 * px[r];
                qz[i] = .25 * pz[u] + .5 * pz[i] + .25 * pz[r];
            }
            double[] t = px; px = qx; qx = t; t = pz; pz = qz; qz = t;
        }
        // Wander: channel centre lines are displaced by smooth noise so long valleys are not grid-straight.
        for (int k = 0; k < m; k++) if (channel[nodes[k]]) {
            int i = nodes[k];
            double wx = originX + px[i] * STEP, wz = originZ + pz[i] * STEP;
            px[i] += CHANNEL_WANDER * (2 * HighlandMath.noise(seed + 7411, wx, wz, WANDER_WAVELENGTH) - 1);
            pz[i] += CHANNEL_WANDER * (2 * HighlandMath.noise(seed + 7412, wx, wz, WANDER_WAVELENGTH) - 1);
        }
        var net = new Net(carved, receiver, channel, px, pz, hillslope);
        glaciate(net, routed, routedCount, main, area, snowline, rangeHeight);
        int[] source = new int[n];
        double[] best = new double[n];
        Arrays.fill(source, -1);
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        MinHeap queue = new MinHeap(Math.max(16, m));
        for (int k = 0; k < m; k++) {
            int i = nodes[k];
            boolean foot = receiver[i] < 0;
            if (!channel[i] && !foot) continue;
            source[i] = i;
            best[i] = Math.min(carved[i], surface(i, i % width, i / width, net));
            queue.pushGrow(i, best[i]);
        }
        boolean[] done = new boolean[n];
        while (queue.size > 0) {
            int i = queue.pop();
            if (done[i]) continue;
            done[i] = true;
            int s = source[i], ix = i % width, iz = i / width;
            for (int d = 0; d < 8; d++) {
                int jx = ix + DX[d], jz = iz + DZ[d];
                if (jx < 0 || jz < 0 || jx >= width || jz >= depth) continue;
                int j = jz * width + jx;
                // Already reached from this source: its surface there is already best[j].
                if (!range[j] || done[j] || source[j] == s) continue;
                double h = surface(s, jx, jz, net);
                if (h < best[j]) {
                    best[j] = h; source[j] = s;
                    queue.pushGrow(j, h);
                }
            }
        }
        // Ridges: where neighbouring nodes took different sources the crease between their cones is sampled on the
        // grid, which prints as a zipper. Re-evaluate every node against its neighbours' sources and join them with a
        // smooth minimum, rounding the divides slightly.
        double[] joined = new double[n];
        int[] seen = new int[9];
        for (int k = 0; k < m; k++) {
            int i = nodes[k];
            if (best[i] == Double.POSITIVE_INFINITY) continue;
            int ix = i % width, iz = i / width, count = 0;
            double h = Double.NaN;
            for (int d = -1; d < 8; d++) {
                int jx = d < 0 ? ix : ix + DX[d], jz = d < 0 ? iz : iz + DZ[d];
                if (jx < 0 || jz < 0 || jx >= width || jz >= depth) continue;
                int j = jz * width + jx;
                if (source[j] < 0) continue;
                int s = source[j];
                boolean repeat = false;
                for (int q = 0; q < count && !repeat; q++) repeat = seen[q] == s;
                if (repeat) continue;
                seen[count++] = s;
                double v = surface(s, ix, iz, net);
                // G3: divides between glacial sources stay knife-edged (arêtes).
                double soft = RIDGE_SOFTNESS * (1 - ARETE * (net.glacier == null ? 0 : net.glacier[s]));
                h = Double.isNaN(h) ? v : HighlandMath.smin(h, v, soft);
            }
            joined[i] = h;
        }
        for (int k = 0; k < m; k++) { int i = nodes[k]; if (best[i] < Double.POSITIVE_INFINITY) carved[i] = Math.min(envelope[i], HighlandMath.smin(envelope[i], joined[i], ENVELOPE_SOFTNESS)); }
        return net;
    }

    /** Channel network and glacial overprint the hillslope cones are built from. */
    private static final class Net {
        final double[] floor, px, pz; final int[] receiver; final boolean[] channel; final double hillslope;
        /** G: glacier weight, U-valley floor half width (blocks) per channel node; cirque weight, centre, radius per head. */
        double[] glacier, uWidth, cirque, cx, cz, cr, ux, uz;
        Net(double[] carved, int[] receiver, boolean[] channel, double[] px, double[] pz, double hillslope) {
            this.floor = carved.clone(); this.receiver = receiver; this.channel = channel; this.px = px; this.pz = pz; this.hillslope = hillslope;
        }
    }

    /**
     * G: glacial overprint above the snowline. Channels fed from above the snowline carry a glacier down to
     * GLACIER_REACH of the Range Height below it: their valleys get a U cross-section (a floor half width growing with
     * drainage area, walls rising from its edge) and an overdeepened floor (G1). Channel heads above the snowline get a
     * cirque: a bowl centred upslope of the head with a gentle floor and a steep headwall (G2).
     */
    private static void glaciate(Net net, int[] routed, int routedCount, int[] main, double[] area, double snowline, double rangeHeight) {
        if (rangeHeight <= 0 || snowline >= rangeHeight) return;
        int n = net.floor.length;
        double reach = GLACIER_REACH * rangeHeight;
        double[] floor = net.floor;
        // Highest floor upstream of each channel node: the routing order lists receivers before donors.
        int count = 0;
        int[] order = new int[routedCount];
        for (int k = routedCount - 1; k >= 0; k--) if (net.channel[routed[k]]) order[count++] = routed[k];
        double[] top = new double[n];
        for (int c = 0; c < count; c++) {
            int i = order[c], r = net.receiver[i];
            top[i] = Math.max(top[i], floor[i]);
            if (r >= 0 && net.channel[r]) top[r] = Math.max(top[r], top[i]);
        }
        net.glacier = new double[n]; net.uWidth = new double[n];
        net.cirque = new double[n]; net.cx = new double[n]; net.cz = new double[n]; net.cr = new double[n];
        net.ux = new double[n]; net.uz = new double[n];
        double radius = HighlandMath.clamp(CIRQUE_RADIUS * rangeHeight, CIRQUE_MIN, CIRQUE_MAX);
        for (int c = 0; c < count; c++) {
            int i = order[c];
            double g = HighlandMath.smooth(snowline - reach, snowline - .3 * reach, floor[i]) * HighlandMath.smooth(snowline - .1 * reach, snowline, top[i]);
            if (g <= 0) continue;
            net.glacier[i] = g;
            double w = g * Math.min(U_MAX, U_WIDTH * Math.sqrt(area[i] / REFERENCE_AREA));
            net.uWidth[i] = w;
            net.floor[i] = floor[i] - U_DEEPEN * w;
            int r = net.receiver[i];
            // A cirque heads each glacial valley: the node where the valley first reaches CIRQUE_AREA.
            if (r >= 0 && area[i] >= CIRQUE_AREA && (main[i] < 0 || area[main[i]] < CIRQUE_AREA)) {
                double cg = g;
                double dx = net.px[i] - net.px[r], dz = net.pz[i] - net.pz[r], dl = Math.max(1e-9, Math.hypot(dx, dz));
                net.cirque[i] = cg; net.cr[i] = radius; net.ux[i] = dx / dl; net.uz[i] = dz / dl;
                net.cx[i] = net.px[i] + dx / dl * CIRQUE_OFFSET * radius / STEP;
                net.cz[i] = net.pz[i] + dz / dl * CIRQUE_OFFSET * radius / STEP;
            }
        }
    }
    /** G: glacier reach below the snowline (share of Range Height). */
    static final double GLACIER_REACH = .25;
    /** G1: U floor half width at REFERENCE_AREA (blocks), its maximum, the floor rise across it and overdeepening (shares of the half width). */
    static final double U_WIDTH = 16, U_MAX = 120, U_RISE = .25, U_DEEPEN = .35;
    /** G2: drainage area (blocks²) at which a glacial valley's cirque sits. */
    static final double CIRQUE_AREA = 6e4;
    /** G2: cirque radius (share of Range Height, clamped in blocks), centre offset upslope, floor gradient, headwall gradient. */
    static final double CIRQUE_RADIUS = .4, CIRQUE_MIN = 60, CIRQUE_MAX = 200, CIRQUE_OFFSET = .6, CIRQUE_FLOOR = .12, CIRQUE_WALL = 1.6;
    /** G2: cirque floor overdeepening and lip height above the head floor (blocks); tarn minimum depth and freeboard. */
    static final double TARN_DEPTH = 12, LIP_HEIGHT = 2, TARN_MIN_DEPTH = 3, TARN_FREEBOARD = 1;
    /** G3: share of the divide softness removed between glacial sources. */
    static final double ARETE = .8;

    /** Height of the cone (hillslope) from source s's channel segment (or point) at grid position (x, z). */
    private static double surface(int s, double x, double z, Net net) {
        int r = net.receiver[s];
        double w = net.uWidth == null ? 0 : net.uWidth[s], base;
        if (!net.channel[s] || r < 0 || !net.channel[r]) {
            base = net.floor[s] + valleyWall(STEP * Math.hypot(x - net.px[s], z - net.pz[s]), w, net.hillslope);
        } else {
            double ax = net.px[s], az = net.pz[s], bx = net.px[r] - ax, bz = net.pz[r] - az;
            double length = bx * bx + bz * bz;
            double t = length <= 0 ? 0 : HighlandMath.clamp(((x - ax) * bx + (z - az) * bz) / length, 0, 1);
            double floor = net.floor[s] + (net.floor[r] - net.floor[s]) * t;
            if (net.uWidth != null) w += (net.uWidth[r] - w) * t;
            base = floor + valleyWall(STEP * Math.hypot(x - ax - t * bx, z - az - t * bz), w, net.hillslope);
        }
        if (net.cirque == null || net.cirque[s] <= 0) return base;
        // Horseshoe: the headwall rises upslope and to the sides of the centre; downvalley the bowl opens into the valley.
        double ox = STEP * (x - net.cx[s]), oz = STEP * (z - net.cz[s]);
        double along = ox * net.ux[s] + oz * net.uz[s], across = -ox * net.uz[s] + oz * net.ux[s];
        double cg = net.cirque[s], radius = net.cr[s], d = Math.hypot(Math.max(0, along), across);
        double inner = .6 * radius;
        // Overdeepened floor (TARN_DEPTH) closed downvalley by a rock lip that rises back to the head's floor where the
        // bowl meets the valley, so the floor holds a tarn.
        double deepen = cg * TARN_DEPTH, lip = (deepen + LIP_HEIGHT) / (CIRQUE_OFFSET * radius);
        double bowl = net.floor[s] - deepen + CIRQUE_FLOOR * Math.min(d, inner) + CIRQUE_WALL * Math.max(0, d - inner)
            + lip * Math.max(0, -along) + (1 - cg) * radius * net.hillslope;
        return Math.min(base, bowl);
    }
    /** Valley wall at a distance from the centre line: V (rounded floor) or, with a U half width, a parabolic floor then walls. */
    private static double valleyWall(double distance, double uWidth, double hillslope) {
        if (uWidth <= 0) return wall(distance, hillslope);
        double inside = Math.min(distance, uWidth) / uWidth;
        return U_RISE * uWidth * inside * inside + wall(Math.max(0, distance - uWidth), hillslope);
    }

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1}, DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    /**
     * Priority flood from the range foot (range nodes next to non-range nodes, which drain out of the range): each
     * node's receiver is the neighbour it was reached from. Returns the node count in order (outlets first).
     */
    private static int route(double[] surface, boolean[] range, int width, int depth, int[] order, int[] receiver, boolean[] seen, double[] filled) {
        Arrays.fill(seen, false);
        var heap = new MinHeap(1 << 16);
        for (int iz = 0; iz < depth; iz++) for (int ix = 0; ix < width; ix++) {
            int i = iz * width + ix;
            if (!range[i]) continue;
            boolean edge = ix == 0 || iz == 0 || ix == width - 1 || iz == depth - 1;
            for (int d = 0; d < 4 && !edge; d++) edge = !range[i + DX[d] + DZ[d] * width];
            if (!edge) continue;
            seen[i] = true; receiver[i] = -1;
            heap.pushGrow(i, surface[i]);
        }
        int count = 0;
        // Barnes et al. (2014) priority flood: nodes raised to the current level (pit cells) go through a FIFO
        // instead of the heap; the order stays receivers before donors.
        int[] pit = new int[64];
        int pitHead = 0, pitTail = 0;
        while (heap.size > 0 || pitHead < pitTail) {
            int i;
            double level;
            if (pitHead < pitTail) { i = pit[pitHead++]; level = filled[i]; }
            else { level = heap.topKey(); i = heap.pop(); filled[i] = level; }
            if (pitHead == pitTail) { pitHead = 0; pitTail = 0; }
            order[count++] = i;
            int ix = i % width, iz = i / width;
            for (int d = 0; d < 8; d++) {
                int jx = ix + DX[d], jz = iz + DZ[d];
                if (jx < 0 || jz < 0 || jx >= width || jz >= depth) continue;
                int j = jz * width + jx;
                if (!range[j] || seen[j]) continue;
                seen[j] = true; receiver[j] = i;
                if (surface[j] <= level) {
                    filled[j] = level;
                    if (pitTail == pit.length) pit = Arrays.copyOf(pit, pitTail * 2);
                    pit[pitTail++] = j;
                } else heap.pushGrow(j, surface[j]);
            }
        }
        // Steepest descent on the filled surface (the flood parent only across flats), so flow converges into the
        // surface's hollows instead of fanning out from the foot. A strictly lower neighbour was ordered earlier.
        for (int k = 0; k < count; k++) {
            int i = order[k];
            if (receiver[i] < 0) continue;
            int ix = i % width, iz = i / width;
            double best = 0;
            for (int d = 0; d < 8; d++) {
                int jx = ix + DX[d], jz = iz + DZ[d];
                if (jx < 0 || jz < 0 || jx >= width || jz >= depth) continue;
                int j = jz * width + jx;
                if (!range[j]) continue;
                double drop = (filled[i] - filled[j]) / (d < 4 ? 1 : Math.sqrt(2));
                if (drop > best) { best = drop; receiver[i] = j; }
            }
        }
        return count;
    }

    /** Binary min-heap of node indices keyed by double, ties by index (deterministic). */
    private static final class MinHeap {
        int[] node; double[] key; int size;
        MinHeap(int capacity) { node = new int[capacity]; key = new double[capacity]; }
        double topKey() { return key[0]; }
        void pushGrow(int i, double k) {
            if (size == node.length) { node = Arrays.copyOf(node, size * 2); key = Arrays.copyOf(key, size * 2); }
            push(i, k);
        }
        void push(int i, double k) {
            int c = size++;
            while (c > 0) {
                int p = (c - 1) >> 1;
                if (!less(k, i, key[p], node[p])) break;
                node[c] = node[p]; key[c] = key[p]; c = p;
            }
            node[c] = i; key[c] = k;
        }
        int pop() {
            int top = node[0];
            int last = node[--size]; double lk = key[size];
            int c = 0;
            while (true) {
                int l = 2 * c + 1;
                if (l >= size) break;
                int m = l + 1 < size && less(key[l + 1], node[l + 1], key[l], node[l]) ? l + 1 : l;
                if (!less(key[m], node[m], lk, last)) break;
                node[c] = node[m]; key[c] = key[m]; c = m;
            }
            node[c] = last; key[c] = lk;
            return top;
        }
        private static boolean less(double a, int ai, double b, int bi) { return a < b || a == b && ai < bi; }
    }
}

