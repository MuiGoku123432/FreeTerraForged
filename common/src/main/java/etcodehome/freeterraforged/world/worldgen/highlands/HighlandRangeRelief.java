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
    /** Threshold hillslope gradient (≈ 39°). */
    static final double HILLSLOPE = .8;
    /**
     * Channel gradient at the reference area, area exponent (concavity), reference area (blocks²), and the drainage
     * area at which a channel starts (smaller areas are hillslopes rising at HILLSLOPE along the flow path).
     */
    static final double CHANNEL_SLOPE = .35, THETA = .5, REFERENCE_AREA = 1e4, CHANNEL_AREA = 1.5e4;
    /** Routing perturbation (blocks) and its wavelength, so flow on the smooth envelope breaks into branches. */
    static final double ROUGHNESS = 14, ROUGHNESS_WAVELENGTH = 260;

    public static final HighlandRangeRelief EMPTY = new HighlandRangeRelief(0, 0, 0, 0, null, 0);

    private final int originX, originZ, width, depth;
    private final float[] cut;
    final long buildNanos;

    private HighlandRangeRelief(int originX, int originZ, int width, int depth, float[] cut, long buildNanos) {
        this.originX = originX; this.originZ = originZ; this.width = width; this.depth = depth; this.cut = cut;
        this.buildNanos = buildNanos;
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

        double[] surface = new double[n], area = new double[n];
        int[] order = new int[n], receiver = new int[n];
        float[] rough = new float[n];
        for (int i = 0; i < n; i++) if (range[i]) {
            rough[i] = (float) (ROUGHNESS * (2 * HighlandMath.noise(seed + 7307, originX + i % width * STEP, originZ + i / width * STEP, ROUGHNESS_WAVELENGTH) - 1));
            surface[i] = envelope[i] + rough[i];
        }
        double[] carved = envelope.clone();
        int count = 0;
        for (int it = 0; it < ITERATIONS; it++) {
            count = route(surface, range, width, depth, order, receiver);
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
                double slope = area[i] < CHANNEL_AREA ? HILLSLOPE : Math.min(HILLSLOPE, CHANNEL_SLOPE * Math.pow(area[i] / REFERENCE_AREA, -THETA));
                carved[i] = Math.min(envelope[i], carved[r] + distance * slope);
            }
            for (int i = 0; i < n; i++) if (range[i]) surface[i] = carved[i] + rough[i];
        }

        hillslopes(carved, envelope, area, receiver, range, width, depth, seed, originX, originZ);
        float[] cut = new float[n];
        for (int i = 0; i < n; i++) if (range[i]) cut[i] = (float) carved[i];
        return new HighlandRangeRelief(originX, originZ, width, depth, cut, System.nanoTime() - started);
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
    private static double wall(double distance) {
        return HILLSLOPE * (Math.sqrt(distance * distance + FLOOR_ROUNDING * FLOOR_ROUNDING) - FLOOR_ROUNDING);
    }

    /**
     * D3: rebuilds every node as the lowest cone rising at HILLSLOPE (Euclidean) from the channel network, capped at
     * the envelope. Channels are segments from each channel node to its receiver along smoothed centre lines, with the
     * stream profile interpolated along them; the range foot is a point source at its own height. Sources propagate
     * Dijkstra-style over the grid (each node keeps the source giving it the lowest surface).
     */
    private static void hillslopes(double[] carved, double[] envelope, double[] area, int[] receiver, boolean[] range, int width, int depth, long seed, int originX, int originZ) {
        int n = width * depth;
        boolean[] channel = new boolean[n];
        for (int i = 0; i < n; i++) channel[i] = range[i] && area[i] >= CHANNEL_AREA;
        // Main upstream donor (largest channel donor) of each channel node.
        int[] main = new int[n];
        Arrays.fill(main, -1);
        for (int i = 0; i < n; i++) {
            int r = receiver[i];
            if (!channel[i] || r < 0 || !channel[r]) continue;
            if (main[r] < 0 || area[i] > area[main[r]]) main[r] = i;
        }
        double[] px = new double[n], pz = new double[n], qx = new double[n], qz = new double[n];
        for (int i = 0; i < n; i++) { px[i] = i % width; pz[i] = i / width; }
        for (int pass = 0; pass < CHANNEL_SMOOTHING; pass++) {
            for (int i = 0; i < n; i++) {
                qx[i] = px[i]; qz[i] = pz[i];
                int r = receiver[i], u = main[i];
                if (!channel[i] || r < 0 || !channel[r] || u < 0) continue;
                qx[i] = .25 * px[u] + .5 * px[i] + .25 * px[r];
                qz[i] = .25 * pz[u] + .5 * pz[i] + .25 * pz[r];
            }
            double[] t = px; px = qx; qx = t; t = pz; pz = qz; qz = t;
        }
        // Wander: channel centre lines are displaced by smooth noise so long valleys are not grid-straight.
        for (int i = 0; i < n; i++) if (channel[i]) {
            double wx = originX + px[i] * STEP, wz = originZ + pz[i] * STEP;
            px[i] += CHANNEL_WANDER * (2 * HighlandMath.noise(seed + 7411, wx, wz, WANDER_WAVELENGTH) - 1);
            pz[i] += CHANNEL_WANDER * (2 * HighlandMath.noise(seed + 7412, wx, wz, WANDER_WAVELENGTH) - 1);
        }
        int[] source = new int[n];
        double[] best = new double[n];
        Arrays.fill(source, -1);
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        MinHeap queue = new MinHeap(n);
        for (int i = 0; i < n; i++) {
            if (!range[i]) continue;
            boolean foot = receiver[i] < 0;
            if (!channel[i] && !foot) continue;
            source[i] = i;
            best[i] = Math.min(carved[i], surface(i, i % width, i / width, carved, receiver, channel, px, pz));
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
                if (!range[j] || done[j]) continue;
                double h = surface(s, jx, jz, carved, receiver, channel, px, pz);
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
        for (int i = 0; i < n; i++) {
            if (!range[i] || best[i] == Double.POSITIVE_INFINITY) continue;
            int ix = i % width, iz = i / width, count = 0;
            double h = Double.NaN;
            for (int d = -1; d < 8; d++) {
                int j = d < 0 ? i : i + DX[d] + DZ[d] * width;
                int jx = d < 0 ? ix : ix + DX[d], jz = d < 0 ? iz : iz + DZ[d];
                if (jx < 0 || jz < 0 || jx >= width || jz >= depth || source[j] < 0) continue;
                int s = source[j];
                boolean repeat = false;
                for (int k = 0; k < count && !repeat; k++) repeat = seen[k] == s;
                if (repeat) continue;
                seen[count++] = s;
                double v = surface(s, ix, iz, carved, receiver, channel, px, pz);
                h = Double.isNaN(h) ? v : HighlandMath.smin(h, v, RIDGE_SOFTNESS);
            }
            joined[i] = h;
        }
        for (int i = 0; i < n; i++) if (range[i] && best[i] < Double.POSITIVE_INFINITY) carved[i] = Math.min(envelope[i], HighlandMath.smin(envelope[i], joined[i], ENVELOPE_SOFTNESS));
    }

    /** Height of the cone (hillslope) from source s's channel segment (or point) at grid position (x, z). */
    private static double surface(int s, double x, double z, double[] carved, int[] receiver, boolean[] channel, double[] px, double[] pz) {
        int r = receiver[s];
        if (!channel[s] || r < 0 || !channel[r])
            return carved[s] + wall(STEP * Math.hypot(x - px[s], z - pz[s]));
        double ax = px[s], az = pz[s], bx = px[r] - ax, bz = pz[r] - az;
        double length = bx * bx + bz * bz;
        double t = length <= 0 ? 0 : HighlandMath.clamp(((x - ax) * bx + (z - az) * bz) / length, 0, 1);
        double floor = carved[s] + (carved[r] - carved[s]) * t;
        return floor + wall(STEP * Math.hypot(x - ax - t * bx, z - az - t * bz));
    }

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1}, DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    /**
     * Priority flood from the range foot (range nodes next to non-range nodes, which drain out of the range): each
     * node's receiver is the neighbour it was reached from. Returns the node count in order (outlets first).
     */
    private static int route(double[] surface, boolean[] range, int width, int depth, int[] order, int[] receiver) {
        int n = width * depth;
        boolean[] seen = new boolean[n];
        var heap = new MinHeap(n);
        for (int iz = 0; iz < depth; iz++) for (int ix = 0; ix < width; ix++) {
            int i = iz * width + ix;
            if (!range[i]) continue;
            boolean edge = ix == 0 || iz == 0 || ix == width - 1 || iz == depth - 1;
            for (int d = 0; d < 4 && !edge; d++) edge = !range[i + DX[d] + DZ[d] * width];
            if (!edge) continue;
            seen[i] = true; receiver[i] = -1;
            heap.push(i, surface[i]);
        }
        int count = 0;
        double[] filled = new double[n];
        while (heap.size > 0) {
            double level = heap.topKey();
            int i = heap.pop();
            filled[i] = level;
            order[count++] = i;
            int ix = i % width, iz = i / width;
            for (int d = 0; d < 8; d++) {
                int jx = ix + DX[d], jz = iz + DZ[d];
                if (jx < 0 || jz < 0 || jx >= width || jz >= depth) continue;
                int j = jz * width + jx;
                if (!range[j] || seen[j]) continue;
                seen[j] = true; receiver[j] = i;
                heap.push(j, Math.max(surface[j], level));
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
