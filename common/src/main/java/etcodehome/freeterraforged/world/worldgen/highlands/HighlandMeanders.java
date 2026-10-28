package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.*;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/**
 * Meandering channels inside valley axes (PLAN-RH4 R3, RESEARCH-RH4 §3).
 * <p>
 * The valley axis (smoothed drainage centreline) is split into stems (main-channel chains). Along each stem the
 * channel heading relative to the axis follows a Kinoshita sine-generated curve
 * {@code θ = ω sin φ + ω³ (Js cos 3φ − Jf sin 3φ)} in channel arc length, with arc wavelength M ≈ (10–14) channel
 * widths × sinuosity. The bend amplitude ω falls on steep or confined reaches, varies between straight and strongly
 * meandering stretches by a slow regime field, tapers to zero at mouths, lakes and large junctions, and is capped so
 * the meander belt never reaches a neighbouring channel or folds inside an axis bend. Lateral offsets are applied in
 * the axis' curvilinear frame, so the channel always winds around its own valley axis.
 */
final class HighlandMeanders {
    /** Largest bend amplitude (radians): sinuosity ≈ 2, loops never touch (cutoffs begin near 2.2). */
    static final double OMEGA_MAX = 1.55;
    /** Kinoshita skewness range (Js; flatness Jf = Js/2): real meanders ~1/32; larger values fold loops at high ω. */
    static final double SKEW = .04;
    /** Bend amplitude ceiling after bend-to-bend irregularity: loop necks stay open (they touch near 2.2). */
    static final double OMEGA_LIMIT = 1.9;
    /** Meander wavelength range in channel widths (Leopold & Wolman: 10–14, mean ≈ 11). */
    static final double WAVELENGTH_MIN = 10, WAVELENGTH_MAX = 14;
    /** Regime field wavelength (blocks): alternates straighter and strongly meandering stretches. */
    static final double REGIME_WAVELENGTH = 2400;
    /** Hard cap of the meander belt half-width (blocks); keeps valleys within the plan index influence. */
    static final double BELT_MAX = 160;
    /** Integration step (blocks of channel arc length). */
    static final double STEP = 2;
    /** Channel samples are this many channel arc-wavelength fractions apart (at most 16 blocks). */
    static final double SAMPLES_PER_WAVELENGTH = 12;
    /** Channel nodes per reach (index granularity). */
    static final int REACH_NODES = 12;
    /** Clearance (blocks) kept between neighbouring channels' banks. */
    static final double CLEARANCE = 10;
    /** Same-stem samples closer than this along the stem are not neighbours; tributary mouths likewise. */
    static final double SELF_RANGE = 640, MOUTH_RANGE = 400;
    /** Share of well-developed loops (at full meander strength) cut off at their neck, leaving an oxbow. */
    static final double OXBOW_CHANCE = .3;
    /** Loops qualify when their apex offset is at least this many channel widths and the loop is this sinuous. */
    static final double OXBOW_MIN_APEX = 2.5, OXBOW_MIN_LOOP = 1.5;
    /**
     * Loop offsets (fractions of the apex offset): the neck is cut where the channel crosses OXBOW_NECK, the oxbow
     * keeps the loop beyond OXBOW_ARC, and holds open water beyond OXBOW_WATER.
     */
    static final double OXBOW_NECK = .1, OXBOW_ARC = .4, OXBOW_WATER = .6;
    /** Wetland margin (blocks) around open oxbow water, and the bank kept beside the channel (beyond its half width). */
    static final double OXBOW_MARGIN = 3, OXBOW_BANK = 3;
    /** Open-water half width of an oxbow in channel widths (oxbows narrow as they fill). */
    static final double OXBOW_WIDTH = .35;
    /** Relative bend-to-bend variation of wavelength and amplitude. */
    static final double IRREGULARITY = .45;
    /** Water slope range over which meandering fades from full to 10% (steeper reaches run nearly straight). */
    static final double FLAT_SLOPE = .006, STEEP_SLOPE = .03;
    /** Belt smoothing radius along the axis (blocks). */
    static final double BELT_SMOOTHING = 96;
    /** Total smoothing span (blocks) of the meander frame. */
    static final double FRAME_SMOOTHING = 320;

    private static final int TABLE = 220;
    private static final double TABLE_MAX = 2.2;
    /** Peak lateral offset as a fraction of the arc wavelength, and sinuosity 1/J0(ω), per ω. */
    private static final double[] AMPLITUDE = new double[TABLE + 1], SINUOSITY = new double[TABLE + 1];
    static {
        for (int i = 0; i <= TABLE; i++) {
            double omega = i * TABLE_MAX / TABLE, amp = 0, j0 = 0;
            int steps = 400;
            for (int k = 0; k < steps; k++) {
                double t = (k + .5) / steps;
                amp += Math.sin(omega * Math.sin(2 * Math.PI * t * .25)) * .25 / steps;
                j0 += Math.cos(omega * Math.sin(Math.PI * t)) / steps;
            }
            AMPLITUDE[i] = amp; SINUOSITY[i] = 1 / Math.max(.05, j0);
        }
    }
    static double amplitude(double omega) { return table(AMPLITUDE, omega); }
    static double sinuosity(double omega) { return table(SINUOSITY, omega); }
    private static double table(double[] t, double omega) {
        double f = clamp(omega, 0, TABLE_MAX) / TABLE_MAX * TABLE;
        int i = Math.min(TABLE - 1, (int) f);
        return lerp(t[i], t[i + 1], f - i);
    }
    /** Largest ω whose peak offset is at most {@code fraction} of the arc wavelength. */
    static double omegaFor(double fraction) {
        if (fraction <= 0) return 0;
        if (fraction >= AMPLITUDE[TABLE]) return TABLE_MAX;
        int lo = 0, hi = TABLE;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (AMPLITUDE[mid] > fraction) hi = mid; else lo = mid; }
        double t = (fraction - AMPLITUDE[lo]) / Math.max(1e-12, AMPLITUDE[hi] - AMPLITUDE[lo]);
        return (lo + t) * TABLE_MAX / TABLE;
    }

    record Result(List<HighlandDrainage.Node> samples, List<Double> levels, List<Integer> lakeOf,
                  List<HighlandDrainage.Reach> reaches, List<HighlandDrainage.AxisNode> axis, List<OxbowSite> oxbows) {}
    /** Oxbow geometry and the channel nodes {@code nodeFrom..nodeTo} beside it (its level is their lowest water). */
    record OxbowSite(double[] x, double[] z, double[] halfWidth, int nodeFrom, int nodeTo) {}

    private final HighlandDrainage drainage;
    private final HighlandDrainage.AxisSamples ax;
    private final int[] endpoint, linkFirst, mainChild, parent;
    private final boolean[] channel;
    private final int gridNodes;
    private final HighlandBasins basins;
    private final long seed;

    HighlandMeanders(HighlandDrainage drainage, HighlandDrainage.AxisSamples axis, int[] endpoint, int[] linkFirst, int[] mainChild,
                     int[] parent, boolean[] channel, int gridNodes, HighlandBasins basins) {
        this.drainage = drainage; this.ax = axis; this.endpoint = endpoint; this.linkFirst = linkFirst;
        this.mainChild = mainChild; this.parent = parent; this.channel = channel; this.gridNodes = gridNodes; this.basins = basins;
        this.seed = drainage.seed() + 0x5EED_4D45L;
    }

    /** Channel width (blocks) from drainage area, as rendered (RollingHighlandsContinent radius × 2, without noise). */
    private double width(int j) {
        double q = Math.max(.01, ax.area[j] / (drainage.spacing() * drainage.spacing()));
        return 2 * clamp(16 * Math.pow(q, .35), 4, 12) * lerp(.3, 1, ax.head[j]);
    }

    Result build() {
        int na = ax.size;
        // Stems: main-channel chains of axis samples. A stem starts at a root, or at a tributary's first sample (with
        // the junction sample prepended as its u = 0 point).
        int[] axMain = new int[na];
        Arrays.fill(axMain, -1);
        for (int i = 0; i < gridNodes; i++) {
            if (!channel[i] || parent[i] < 0 || linkFirst[i] < 0 || mainChild[parent[i]] != i) continue;
            axMain[endpoint[parent[i]]] = linkFirst[i];
        }
        for (int j = 0; j < na; j++) {
            int p = ax.parent[j];
            if (p >= 0 && ax.grid[p] == ax.grid[j]) axMain[p] = j;
        }
        List<int[]> stems = new ArrayList<>();
        int[] stemOf = new int[na], stemPos = new int[na];
        double[] stemU = new double[na];
        int[] buffer = new int[na + 1];
        for (int j = 0; j < na; j++) {
            int p = ax.parent[j];
            if (p >= 0 && axMain[p] == j) continue;
            int len = 0;
            if (p >= 0) buffer[len++] = p;
            for (int v = j; v >= 0; v = axMain[v]) buffer[len++] = v;
            int[] pts = Arrays.copyOf(buffer, len);
            int s = stems.size();
            stems.add(pts);
            double u = 0;
            for (int q = 0; q < len; q++) {
                if (q > 0) u += Math.hypot(ax.x[pts[q]] - ax.x[pts[q - 1]], ax.z[pts[q]] - ax.z[pts[q - 1]]);
                if (q == 0 && p >= 0) continue;
                stemOf[pts[q]] = s; stemPos[pts[q]] = q; stemU[pts[q]] = u;
            }
        }
        double[] clearance = neighbourClearance(stems, stemOf, stemU);

        List<HighlandDrainage.Node> samples = new ArrayList<>();
        List<Double> levels = new ArrayList<>();
        List<Integer> lakeOf = new ArrayList<>();
        List<HighlandDrainage.Reach> reaches = new ArrayList<>();
        List<HighlandDrainage.AxisNode> axisOut = new ArrayList<>();
        List<OxbowSite> oxbows = new ArrayList<>();
        int[] stemFirst = new int[stems.size()], stemLast = new int[stems.size()];
        List<Double> sampleU = new ArrayList<>();
        for (int s = 0; s < stems.size(); s++) {
            int[] pts = stems.get(s);
            int k = pts.length;
            boolean root = ax.parent[pts[0]] < 0;
            double[] u = new double[k], px = new double[k], pz = new double[k], nx = new double[k], nz = new double[k];
            for (int q = 0; q < k; q++) {
                px[q] = ax.x[pts[q]]; pz[q] = ax.z[pts[q]];
                if (q > 0) u[q] = u[q - 1] + Math.hypot(px[q] - px[q - 1], pz[q] - pz[q - 1]);
            }
            // Meander frame: offsets are applied to the real axis along the normals of the axis smoothed over
            // FRAME_SMOOTHING (ends fixed), so loops follow the valley's general course instead of folding inside
            // every tight axis turn; with no meander the channel is exactly the axis.
            double[] ox = px.clone(), oz = pz.clone();
            px = smoothAlong(ox, u); pz = smoothAlong(oz, u);
            for (int q = 0; q < k; q++) {
                int a = Math.max(0, q - 1), b = Math.min(k - 1, q + 1);
                double tx = px[b] - px[a], tz = pz[b] - pz[a], tl = Math.hypot(tx, tz);
                if (tl < 1e-9) { nx[q] = q > 0 ? nx[q - 1] : 0; nz[q] = q > 0 ? nz[q - 1] : 1; continue; }
                nx[q] = -tz / tl; nz[q] = tx / tl;
            }
            double[] omega = new double[k], wave = new double[k], skew = new double[k], flat = new double[k], cap = new double[k];
            // Clearance per stem sample; a tributary's lowest MOUTH_RANGE is held clear of its parent's built channel.
            double[] clear = new double[k];
            for (int q = 0; q < k; q++) clear[q] = clearance[pts[q]];
            if (!root) mouthClearance(pts, u, ox, oz, stemOf[pts[0]], stemU[pts[0]], stemFirst, stemLast, samples, sampleU, clear);
            planStem(pts, u, px, pz, clear, omega, wave, skew, flat, cap);

            // Kinoshita channel; selected well-developed loops are cut off at their neck: the loop stays as an oxbow
            // and the channel takes a smooth shortcut across the neck (the rest of the channel is unchanged).
            double phase0 = 2 * Math.PI * ((mix(seed ^ pts[Math.min(1, k - 1)] * 0x9E3779B97F4A7C15L) & 0xFFFF) / 65536.0);
            Path path = integrate(k, u, px, pz, omega, wave, skew, cap, phase0);
            List<Loop> cutoffs = chooseCutoffs(s, pts, u, path);
            if (!cutoffs.isEmpty()) path = cutNecks(path, cutoffs);
            double[] su = path.su(), sy = path.sy(), sm = path.sm();
            int steps = path.steps(), ptr;

            // Tributary mouths end on the parent's channel: nearest parent channel node to the junction.
            int join = -1;
            double dx0 = 0, dz0 = 0, blend = 1;
            if (!root) {
                int ps = stemOf[pts[0]];
                double uj = stemU[pts[0]], best = Double.POSITIVE_INFINITY;
                for (int c = stemFirst[ps]; c <= stemLast[ps]; c++) {
                    if (Math.abs(sampleU.get(c) - uj) > MOUTH_RANGE) continue;
                    var node = samples.get(c);
                    double d = Math.hypot(node.x() - px[0], node.z() - pz[0]);
                    if (d < best) { best = d; join = c; }
                }
                if (join < 0) join = stemLast[ps];
                dx0 = samples.get(join).x() - px[0]; dz0 = samples.get(join).z() - pz[0];
                blend = clamp(3 * Math.hypot(dx0, dz0), 48, 256);
            }
            int first = samples.size();
            int previous = join;
            if (root) {
                int r = pts[0];
                previous = samples.size();
                samples.add(new HighlandDrainage.Node(ax.x[r], ax.z[r], 0, 0, ax.area[r], -1, 1));
                levels.add(0.0); lakeOf.add(-1); sampleU.add(0.0);
            }
            stemFirst[s] = first;
            // Emit channel nodes along arc length.
            double[] belt = new double[k];
            double sinceEmit = 0;
            int reachFirst = samples.size(), reachDown = previous;
            double reachUMin = 0, reachUMax = 0;
            ptr = 0;
            for (int q = 1; q < steps; q++) {
                double seg = Math.hypot(su[q] - su[q - 1], sy[q] - sy[q - 1]);
                sinceEmit += seg;
                double spacing = clamp(sm[q] / SAMPLES_PER_WAVELENGTH, 6, 16);
                if (sinceEmit < spacing && q < steps - 1) continue;
                sinceEmit = 0;
                double at = su[q];
                while (ptr < k - 2 && u[ptr + 1] <= at) ptr++;
                while (ptr > 0 && u[ptr] > at) ptr--;
                int nxt = Math.min(k - 1, ptr + 1);
                double t = k > 1 ? clamp((at - u[ptr]) / Math.max(1e-9, u[ptr + 1] - u[ptr]), 0, 1) : 0;
                double axisX = lerp(ox[ptr], ox[nxt], t), axisZ = lerp(oz[ptr], oz[nxt], t);
                double normX = lerp(nx[ptr], nx[nxt], t), normZ = lerp(nz[ptr], nz[nxt], t), nl = Math.max(1e-9, Math.hypot(normX, normZ));
                double cx = axisX + sy[q] * normX / nl, cz = axisZ + sy[q] * normZ / nl;
                if (!root) {
                    double fade = 1 - smooth(0, blend, at);
                    cx += dx0 * fade; cz += dz0 * fade;
                }
                if (samples.size() >= HighlandDrainage.MAX_REFINED_NODES) throw new IllegalStateException("RH profile refinement budget exceeded");
                var location = drainage.coast().sample(cx, cz);
                double ground = drainage.planningHeight(cx, cz, location.edge());
                double level = lerp(ax.level[pts[ptr]], ax.level[pts[nxt]], t);
                var lake = basins.sample(cx, cz);
                boolean flooded = lake != null && lake.depth() > 0;
                if (flooded) level = lake.water();
                if (location.edge() <= drainage.coastEdge()) level = 0;
                int index = samples.size();
                samples.add(new HighlandDrainage.Node(cx, cz, ground, 0, lerp(ax.area[pts[ptr]], ax.area[pts[nxt]], t), previous,
                    lerp(ax.head[pts[ptr]], ax.head[pts[nxt]], t)));
                levels.add(level); lakeOf.add(flooded ? lake.lake() : -1); sampleU.add(at);
                previous = index;
                // Belt: the largest offset within half a wavelength of each axis sample.
                double offset = Math.hypot(cx - axisX, cz - axisZ);
                for (int b = ptr; b >= 0 && at - u[b] <= Math.max(32, wave[b] * .5); b--) belt[b] = Math.max(belt[b], offset);
                for (int b = ptr + 1; b < k && u[b] - at <= Math.max(32, wave[b] * .5); b++) belt[b] = Math.max(belt[b], offset);
                reachUMin = Math.min(reachUMin, at); reachUMax = Math.max(reachUMax, at);
                if (index - reachFirst + 1 >= REACH_NODES || q == steps - 1) {
                    reaches.add(axisReach(reachDown, reachFirst, index, reachUMin, reachUMax, u, axisOut.size()));
                    reachFirst = index + 1; reachDown = index; reachUMin = at; reachUMax = at;
                }
            }
            if (!cutoffs.isEmpty() && stemFirst[s] < samples.size())
                placeOxbows(cutoffs, u, ox, oz, nx, nz, stemFirst[s], samples, sampleU, lakeOf, oxbows, belt);
            stemLast[s] = samples.size() - 1;
            if (stemLast[s] < stemFirst[s]) stemLast[s] = stemFirst[s];
            belt = smoothBelt(belt, u);
            for (int q = 0; q < k; q++) axisOut.add(new HighlandDrainage.AxisNode(ox[q], oz[q], Math.min(BELT_MAX + 24, belt[q])));
        }
        return new Result(samples, levels, lakeOf, reaches, axisOut, oxbows);
    }

    /** Integrated channel in the axis frame: along-axis position, lateral offset and local arc wavelength per step. */
    private record Path(double[] su, double[] sy, double[] sm, int steps) {}
    /**
     * A cut-off loop: neck indices {@code first..last} into the integrated path, the oxbow arc in the axis frame
     * (along, lateral) with its open-water half width per point, the channel width, along-axis span and wavelength.
     */
    private record Loop(int first, int last, double[] along, double[] lateral, double[] half, double width, double from, double to, double wave) {}

    /**
     * Integrates the Kinoshita heading in channel arc length, removes the slow drift left by varying ω, and holds the
     * offset inside the cap.
     */
    private Path integrate(int k, double[] u, double[] px, double[] pz, double[] omega, double[] wave, double[] skew, double[] cap,
                           double phase0) {
        int maxSteps = (int) Math.min(4_000_000, 4 * u[k - 1] / STEP + 64);
        double[] su = new double[64], sy = new double[64], sm = new double[64];
        int steps = 0;
        double along = 0, lateral = 0, phase = phase0;
        int ptr = 0;
        while (along < u[k - 1] && steps < maxSteps) {
            while (ptr < k - 2 && u[ptr + 1] <= along) ptr++;
            while (ptr > 0 && u[ptr] > along) ptr--;
            double t = k > 1 ? clamp((along - u[ptr]) / Math.max(1e-9, u[ptr + 1] - u[ptr]), 0, 1) : 0;
            int nxt = Math.min(k - 1, ptr + 1);
            double m = lerp(wave[ptr], wave[nxt], t), js = lerp(skew[ptr], skew[nxt], t);
            // Natural irregularity: bend length and amplitude vary from bend to bend.
            double bx = lerp(px[ptr], px[nxt], t), bz = lerp(pz[ptr], pz[nxt], t);
            double w = lerp(omega[ptr], omega[nxt], t) * (1 + IRREGULARITY * (2 * noise(seed + 21, bx, bz, 1.3 * m) - 1));
            w = Math.min(w, OMEGA_LIMIT);
            double rate = 1 + IRREGULARITY * (2 * noise(seed + 22, bx, bz, .9 * m) - 1);
            // Bends never outgrow the cap at their actual length (a clamped loop would flatten into parallel limbs).
            w = Math.min(w, omegaFor(lerp(cap[ptr], cap[nxt], t) * rate / Math.max(8, m)));
            double theta = w * Math.sin(phase) + w * w * w * (js * Math.cos(3 * phase) - .5 * js * Math.sin(3 * phase));
            if (steps == su.length) { su = Arrays.copyOf(su, steps * 2); sy = Arrays.copyOf(sy, steps * 2); sm = Arrays.copyOf(sm, steps * 2); }
            su[steps] = along; sy[steps] = lateral; sm[steps] = m; steps++;
            along += Math.cos(theta) * STEP;
            lateral += Math.sin(theta) * STEP;
            phase += 2 * Math.PI * STEP * rate / Math.max(8, m);
        }
        if (steps == su.length) { su = Arrays.copyOf(su, steps + 1); sy = Arrays.copyOf(sy, steps + 1); sm = Arrays.copyOf(sm, steps + 1); }
        su[steps] = u[k - 1]; sy[steps] = lateral; sm[steps] = wave[k - 1]; steps++;
        double[] trend = boxFilter(boxFilter(sy, sm, steps), sm, steps);
        ptr = 0;
        for (int q = 0; q < steps; q++) {
            while (ptr < k - 2 && u[ptr + 1] <= su[q]) ptr++;
            while (ptr > 0 && u[ptr] > su[q]) ptr--;
            double t = k > 1 ? clamp((su[q] - u[ptr]) / Math.max(1e-9, u[ptr + 1] - u[ptr]), 0, 1) : 0;
            double limit = lerp(cap[ptr], cap[Math.min(k - 1, ptr + 1)], t);
            sy[q] = softClamp(sy[q] - trend[q], limit);
        }
        return new Path(su, sy, sm, steps);
    }

    /**
     * Neck cutoffs: each loop (a run of one lateral sign between inflections) whose apex reaches OXBOW_MIN_APEX
     * channel widths and whose channel is OXBOW_MIN_LOOP times longer than its axis span may, by a deterministic draw,
     * be cut off between the points where it leaves and regains OXBOW_NECK of its apex offset. The loop beyond
     * OXBOW_ARC of the apex offset becomes the oxbow; open water only beyond OXBOW_WATER (the ends silt up first).
     */
    private List<Loop> chooseCutoffs(int s, int[] pts, double[] u, Path path) {
        double chance = OXBOW_CHANCE * Math.min(1, drainage.meander());
        List<Loop> out = new ArrayList<>();
        if (chance <= 0 || pts.length < 3) return out;
        double[] su = path.su(), sy = path.sy(), sm = path.sm();
        int steps = path.steps(), start = 0;
        double lastTo = Double.NEGATIVE_INFINITY;
        for (int q = 1; q <= steps; q++) {
            if (q < steps && Math.signum(sy[q]) == Math.signum(sy[start])) continue;
            int end = q - 1, apex = start, a = start, b = end;
            start = q;
            if (a == 0 || q >= steps) continue; // never at the stem's ends
            for (int r = a; r <= b; r++) if (Math.abs(sy[r]) > Math.abs(sy[apex])) apex = r;
            double peak = Math.abs(sy[apex]);
            double w = width(pts[locate(u, su[apex])]);
            if (peak < OXBOW_MIN_APEX * w || b - a < 4) continue;
            double channelLength = 0;
            for (int r = a + 1; r <= b; r++) channelLength += Math.hypot(su[r] - su[r - 1], sy[r] - sy[r - 1]);
            if (channelLength < OXBOW_MIN_LOOP * Math.max(1, su[b] - su[a])) continue;
            long draw = mix(seed ^ (s * 0x9E3779B97F4A7C15L) ^ (apex * 0xC2B2AE3D27D4EB4FL) ^ 0x0BB0L);
            if ((draw & 0xFFFF) / 65536.0 >= chance) continue;
            int first = a, last = b;
            while (first < apex && Math.abs(sy[first]) < OXBOW_NECK * peak) first++;
            while (last > apex && Math.abs(sy[last]) < OXBOW_NECK * peak) last--;
            // The neck must open downstream by a few channel widths (strongly folded loops are left alone).
            if (su[last] - su[first] < 3 * w || su[first] < lastTo) continue;
            int arcA = first, arcB = last;
            while (arcA < apex && Math.abs(sy[arcA]) < OXBOW_ARC * peak) arcA++;
            while (arcB > apex && Math.abs(sy[arcB]) < OXBOW_ARC * peak) arcB--;
            int count = Math.max(3, (arcB - arcA) / 3 + 1);
            double[] along = new double[count], lateral = new double[count], half = new double[count];
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < count; c++) {
                int r = arcA + (int) Math.round((arcB - arcA) * c / (double) (count - 1));
                along[c] = su[r]; lateral[c] = sy[r];
                half[c] = OXBOW_WIDTH * w * smooth(OXBOW_WATER, OXBOW_WATER + .15, Math.abs(sy[r]) / peak);
                lo = Math.min(lo, su[r]); hi = Math.max(hi, su[r]);
            }
            out.add(new Loop(first, last, along, lateral, half, w, Math.min(lo, su[first]), Math.max(hi, su[last]), sm[apex]));
            lastTo = su[last] + sm[apex];
        }
        return out;
    }

    /** Replaces each cut-off loop's neck stretch by a Hermite shortcut tangent to the channel at both ends. */
    private static Path cutNecks(Path path, List<Loop> loops) {
        double[] su = path.su(), sy = path.sy(), sm = path.sm();
        int n = path.steps();
        double[] ou = new double[n], oy = new double[n], om = new double[n];
        int size = 0, next = 0;
        for (var loop : loops) {
            for (; next <= loop.first(); next++) { ou[size] = su[next]; oy[size] = sy[next]; om[size] = sm[next]; size++; }
            int i = loop.first(), j = loop.last();
            double chord = Math.hypot(su[j] - su[i], sy[j] - sy[i]);
            // Tangents lean mostly on the chord: the cutoff channel runs nearly straight across the neck.
            double cu = (su[j] - su[i]) / Math.max(1e-9, chord), cy = (sy[j] - sy[i]) / Math.max(1e-9, chord);
            double t0u = su[i] - su[i - 1], t0y = sy[i] - sy[i - 1], t1u = su[j + 1] - su[j], t1y = sy[j + 1] - sy[j];
            double l0 = Math.max(1e-9, Math.hypot(t0u, t0y)), l1 = Math.max(1e-9, Math.hypot(t1u, t1y));
            t0u = (.3 * t0u / l0 + .7 * cu) * chord; t0y = (.3 * t0y / l0 + .7 * cy) * chord;
            t1u = (.3 * t1u / l1 + .7 * cu) * chord; t1y = (.3 * t1y / l1 + .7 * cy) * chord;
            int pieces = Math.max(2, (int) Math.ceil(chord / STEP));
            for (int c = 1; c < pieces; c++) {
                double t = c / (double) pieces, t2 = t * t, t3 = t2 * t;
                double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
                if (size >= ou.length) { ou = Arrays.copyOf(ou, size * 2); oy = Arrays.copyOf(oy, size * 2); om = Arrays.copyOf(om, size * 2); }
                ou[size] = h00 * su[i] + h10 * t0u + h01 * su[j] + h11 * t1u;
                oy[size] = h00 * sy[i] + h10 * t0y + h01 * sy[j] + h11 * t1y;
                om[size] = lerp(sm[i], sm[j], t);
                size++;
            }
            next = j;
        }
        for (; next < n; next++) {
            if (size >= ou.length) { ou = Arrays.copyOf(ou, size * 2); oy = Arrays.copyOf(oy, size * 2); om = Arrays.copyOf(om, size * 2); }
            ou[size] = su[next]; oy[size] = sy[next]; om[size] = sm[next]; size++;
        }
        return new Path(ou, oy, om, size);
    }

    /**
     * Places the cut-off loops as oxbows beside the channel. Open water keeps its margin clear of the channel and
     * banks; the silted ends may reach the bank. Dropped when the channel beside it runs through lake or sea water.
     * The belt grows to include the oxbow, so it always lies on the valley floor.
     */
    private void placeOxbows(List<Loop> loops, double[] u, double[] ox, double[] oz, double[] nx, double[] nz, int firstNode,
                             List<HighlandDrainage.Node> samples, List<Double> sampleU, List<Integer> lakeOf, List<OxbowSite> out,
                             double[] belt) {
        int k = u.length;
        for (var cutoff : loops) {
            var loop = cutoff;
            int count = loop.along().length;
            double[] x = new double[count], z = new double[count];
            for (int c = 0; c < count; c++) {
                int ptr = locate(u, loop.along()[c]), nxt = Math.min(k - 1, ptr + 1);
                double t = clamp((loop.along()[c] - u[ptr]) / Math.max(1e-9, u[nxt] - u[ptr]), 0, 1);
                double normX = lerp(nx[ptr], nx[nxt], t), normZ = lerp(nz[ptr], nz[nxt], t), nl = Math.max(1e-9, Math.hypot(normX, normZ));
                x[c] = lerp(ox[ptr], ox[nxt], t) + loop.lateral()[c] * normX / nl;
                z[c] = lerp(oz[ptr], oz[nxt], t) + loop.lateral()[c] * normZ / nl;
            }
            double bank = .5 * loop.width() + OXBOW_BANK;
            int nodeFrom = -1, nodeTo = -1;
            boolean ok = true;
            boolean[] blocked = new boolean[count];
            for (int c = firstNode; c < samples.size() && ok; c++) {
                double cu = sampleU.get(c);
                if (cu < loop.from() - loop.wave() || cu > loop.to() + loop.wave()) continue;
                if (cu >= loop.from() && cu <= loop.to()) {
                    if (lakeOf.get(c) != -1) { ok = false; break; }
                    if (nodeFrom < 0) nodeFrom = c;
                    nodeTo = c;
                }
                var node = samples.get(c);
                if (node.parent() < 0) continue;
                var down = samples.get(node.parent());
                for (int p = 0; p < count; p++) {
                    double need = bank + (loop.half()[p] > 0 ? loop.half()[p] + OXBOW_MARGIN : 0);
                    if (segment(x[p], z[p], node.x(), node.z(), down.x(), down.z()) < need) blocked[p] = true;
                }
            }
            // Silted ends that reach the channel's bank are trimmed; open water must stay clear.
            int a = 0, b = count - 1;
            while (a < b && blocked[a] && loop.half()[a] == 0) a++;
            while (b > a && blocked[b] && loop.half()[b] == 0) b--;
            for (int p = a; p <= b; p++) if (blocked[p]) ok = false;
            if (b - a < 2) ok = false;
            if (ok && (a > 0 || b < count - 1)) {
                x = Arrays.copyOfRange(x, a, b + 1); z = Arrays.copyOfRange(z, a, b + 1);
                loop = new Loop(loop.first(), loop.last(), Arrays.copyOfRange(loop.along(), a, b + 1), Arrays.copyOfRange(loop.lateral(), a, b + 1),
                    Arrays.copyOfRange(loop.half(), a, b + 1), loop.width(), loop.from(), loop.to(), loop.wave());
                count = b - a + 1;
            }
            for (var other : out) for (int p = 0; ok && p < count; p++) for (int o = 0; o < other.x().length; o++)
                if (Math.hypot(other.x()[o] - x[p], other.z()[o] - z[p]) < 2 * OXBOW_MARGIN) { ok = false; break; }
            if (!ok || nodeFrom < 0) continue;
            out.add(new OxbowSite(x, z, loop.half(), nodeFrom, nodeTo));
            for (int c = 0; c < count; c++) {
                double extent = Math.abs(loop.lateral()[c]) + loop.half()[c] + OXBOW_MARGIN;
                for (int q = 0; q < k; q++) if (Math.abs(u[q] - loop.along()[c]) <= 32) belt[q] = Math.max(belt[q], extent);
            }
        }
    }

    private static double segment(double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
        double t = l2 > 1e-12 ? clamp(((x - ax) * dx + (z - az) * dz) / l2, 0, 1) : 0;
        return Math.hypot(x - ax - dx * t, z - az - dz * t);
    }
    private static int locate(double[] u, double at) {
        int lo = 0, hi = u.length - 2;
        if (hi < 0) return 0;
        while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (u[mid] <= at) lo = mid; else hi = mid - 1; }
        return lo;
    }

    private static HighlandDrainage.Reach axisReach(int down, int first, int last, double uMin, double uMax, double[] u, int base) {
        int from = 0, to = u.length - 1;
        while (from < u.length - 1 && u[from + 1] <= uMin) from++;
        while (to > 0 && u[to - 1] >= uMax) to--;
        if (to <= from) to = Math.min(u.length - 1, from + 1);
        if (to <= from) from = Math.max(0, to - 1);
        return new HighlandDrainage.Reach(down, first, last, base + from, base + to);
    }

    /**
     * Per axis sample of one stem: bend amplitude ω, arc wavelength, Kinoshita skew, and the lateral offset cap.
     */
    private void planStem(int[] pts, double[] u, double[] px, double[] pz, double[] clear,
                          double[] omega, double[] wave, double[] skew, double[] flat, double[] cap) {
        int k = pts.length;
        double strength = drainage.meander();
        // Slopes (water along the axis and ground) averaged over ±128 blocks.
        double[] slope = new double[k], groundSlope = new double[k];
        for (int q = 0; q < k; q++) {
            int a = q, b = q;
            while (a > 0 && u[q] - u[a - 1] <= 128) a--;
            while (b < k - 1 && u[b + 1] - u[q] <= 128) b++;
            double run = Math.max(16, u[b] - u[a]);
            slope[q] = Math.abs(ax.level[pts[b]] - ax.level[pts[a]]) / run;
            groundSlope[q] = Math.abs(ax.ground[pts[b]] - ax.ground[pts[a]]) / run;
        }
        // Distances along the stem to features where the channel must run along the axis: lakes and the sea,
        // and junctions with tributaries carrying at least a fifth of the flow.
        double[] toFeature = new double[k];
        boolean[] feature = new boolean[k];
        for (int q = 0; q < k; q++) feature[q] = ax.lake[pts[q]] != -1;
        markJunctions(pts, feature);
        double last = Double.NEGATIVE_INFINITY;
        for (int q = 0; q < k; q++) { if (feature[q]) last = u[q]; toFeature[q] = u[q] - last; }
        last = Double.POSITIVE_INFINITY;
        for (int q = k - 1; q >= 0; q--) { if (feature[q]) last = u[q]; toFeature[q] = Math.min(toFeature[q], last - u[q]); }
        // Axis curvature radius (minimum over ±64 blocks): offsets beyond it would fold on the inside of a bend.
        double[] radius = new double[k];
        for (int q = 0; q < k; q++) {
            if (q == 0 || q == k - 1) { radius[q] = Double.POSITIVE_INFINITY; continue; }
            double ax1 = px[q] - px[q - 1], az1 = pz[q] - pz[q - 1], ax2 = px[q + 1] - px[q], az2 = pz[q + 1] - pz[q];
            double turn = Math.abs(Math.atan2(ax1 * az2 - az1 * ax2, ax1 * ax2 + az1 * az2));
            radius[q] = .5 * (Math.hypot(ax1, az1) + Math.hypot(ax2, az2)) / Math.max(1e-6, turn);
        }
        double[] bend = new double[k];
        for (int q = 0; q < k; q++) {
            double r = Double.POSITIVE_INFINITY;
            for (int b = q; b >= 0 && u[q] - u[b] <= 64; b--) r = Math.min(r, radius[b]);
            for (int b = q + 1; b < k && u[b] - u[q] <= 64; b++) r = Math.min(r, radius[b]);
            bend[q] = r;
        }
        for (int q = 0; q < k; q++) {
            int j = pts[Math.max(q, Math.min(1, k - 1))];
            double w = width(j);
            double regime = lerp(.08, 1, smooth(.44, .62, noise(seed + 11, px[q], pz[q], REGIME_WAVELENGTH)));
            // RH water slopes are steeper than real alluvial rivers (compressed relief): main rivers run at 0.5-2%.
            // Loops on steep reaches would put neighbouring loops of one channel blocks apart in level (terraces).
            flat[q] = 1 - smooth(FLAT_SLOPE, STEEP_SLOPE, slope[q]);
            double confined = smooth(.03, .25, groundSlope[q]);
            double target = strength * OMEGA_MAX * regime * (.1 + .9 * flat[q]) * (1 - .75 * confined);
            double lambda = lerp(WAVELENGTH_MIN, WAVELENGTH_MAX, noise(seed + 12, px[q], pz[q], 900)) * w;
            double m = lambda * sinuosity(target);
            double taper = smooth(.1 * m, .6 * m, u[q]) * smooth(.1 * m, .5 * m, toFeature[q]) * ax.head[j];
            double limit = Math.min(Math.min(BELT_MAX, .6 * bend[q]), clear[Math.max(q, Math.min(1, k - 1))]);
            limit = Math.max(0, limit) * taper;
            double w2 = Math.min(target * taper, omegaFor(limit / Math.max(1, m)));
            omega[q] = w2;
            wave[q] = lambda * sinuosity(w2);
            skew[q] = SKEW * (2 * noise(seed + 13, px[q], pz[q], 1500) - 1);
            cap[q] = limit;
        }
        // Smooth ω: moving minimum over ±M/4, then a box average over ±M/4 (no abrupt changes, caps respected).
        double[] min = new double[k];
        for (int q = 0; q < k; q++) {
            double r = .25 * wave[q], v = omega[q];
            for (int b = q; b >= 0 && u[q] - u[b] <= r; b--) v = Math.min(v, omega[b]);
            for (int b = q + 1; b < k && u[b] - u[q] <= r; b++) v = Math.min(v, omega[b]);
            min[q] = v;
        }
        for (int q = 0; q < k; q++) {
            double r = .25 * wave[q], sum = 0; int c = 0;
            for (int b = q; b >= 0 && u[q] - u[b] <= r; b--) { sum += min[b]; c++; }
            for (int b = q + 1; b < k && u[b] - u[q] <= r; b++) { sum += min[b]; c++; }
            omega[q] = sum / Math.max(1, c);
        }
    }

    /**
     * Tributary mouth: the parent channel is already built, so the tributary's belt there may use the whole gap to
     * it (axis sample to parent channel node, less both half widths and the clearance).
     */
    private void mouthClearance(int[] pts, double[] u, double[] x, double[] z, int parentStem, double junctionU, int[] stemFirst,
                                int[] stemLast, List<HighlandDrainage.Node> samples, List<Double> sampleU, double[] clear) {
        for (int q = 1; q < pts.length && u[q] < MOUTH_RANGE; q++) {
            double w = width(pts[q]), best = clear[q];
            for (int c = stemFirst[parentStem]; c <= stemLast[parentStem]; c++) {
                if (Math.abs(sampleU.get(c) - junctionU) > MOUTH_RANGE + 2 * BELT_MAX) continue;
                var node = samples.get(c);
                double q2 = Math.max(.01, node.area() / (drainage.spacing() * drainage.spacing()));
                double wc = 2 * clamp(16 * Math.pow(q2, .35), 4, 12) * lerp(.3, 1, node.head());
                best = Math.min(best, Math.hypot(node.x() - x[q], node.z() - z[q]) - .5 * w - .5 * wc - CLEARANCE);
            }
            clear[q] = Math.max(0, best);
        }
    }

    /** Marks stem samples where a tributary with at least a fifth of the stem's flow joins. */
    private void markJunctions(int[] pts, boolean[] feature) {
        if (junctionArea == null) {
            junctionArea = new double[ax.size];
            for (int j = 0; j < ax.size; j++) {
                int p = ax.parent[j];
                if (p >= 0 && ax.grid[p] != ax.grid[j]) {
                    int g = ax.grid[j];
                    // A tributary link (not the main continuation) joining at p.
                    int gp = parent[g];
                    if (gp >= 0 && mainChild[gp] != g) junctionArea[p] = Math.max(junctionArea[p], ax.area[endpoint[g]]);
                }
            }
        }
        for (int q = 1; q < pts.length; q++) {
            int j = pts[q];
            if (junctionArea[j] > .2 * ax.area[j]) feature[q] = true;
        }
    }
    private double[] junctionArea;

    /**
     * Largest belt half-width per axis sample that keeps this channel clear of every other channel's belt: half the
     * distance to the nearest other axis, less both channels' half widths and a clearance. Samples of the same stem
     * within SELF_RANGE along it, and a tributary's lowest MOUTH_RANGE against its parent stem, do not count.
     */
    private double[] neighbourClearance(List<int[]> stems, int[] stemOf, double[] stemU) {
        int na = ax.size, bucket = 64;
        double reach = 2 * BELT_MAX + 48;
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int j = 0; j < na; j++) grid.computeIfAbsent(key(Math.floorDiv((int) Math.floor(ax.x[j]), bucket), Math.floorDiv((int) Math.floor(ax.z[j]), bucket)), q -> new ArrayList<>()).add(j);
        int[] parentStem = new int[stems.size()];
        for (int s = 0; s < stems.size(); s++) {
            int[] pts = stems.get(s);
            parentStem[s] = ax.parent[pts[0]] < 0 ? -1 : stemOf[pts[0]];
        }
        double[] out = new double[na], widths = new double[na];
        for (int j = 0; j < na; j++) widths[j] = width(j);
        int r = (int) Math.ceil(reach / bucket);
        for (int j = 0; j < na; j++) {
            if (ax.parent[j] < 0) { out[j] = 0; continue; }
            int bx = Math.floorDiv((int) Math.floor(ax.x[j]), bucket), bz = Math.floorDiv((int) Math.floor(ax.z[j]), bucket);
            int sj = stemOf[j];
            double best = reach, wj = widths[j];
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                var list = grid.get(key(bx + dx, bz + dz));
                if (list == null) continue;
                for (int t : list) {
                    if (t == j || ax.parent[t] < 0) continue;
                    int st = stemOf[t];
                    if (st == sj && Math.abs(stemU[t] - stemU[j]) < SELF_RANGE) continue;
                    if (parentStem[st] == sj && stemU[t] < MOUTH_RANGE) continue;
                    if (parentStem[sj] == st && stemU[j] < MOUTH_RANGE) continue;
                    double d = Math.hypot(ax.x[t] - ax.x[j], ax.z[t] - ax.z[j]);
                    double free = .5 * (d - .5 * wj - .5 * widths[t] - CLEARANCE);
                    if (free < best) best = free;
                }
            }
            out[j] = Math.max(0, best);
        }
        return out;
    }

    /**
     * Smooth upper bound of the belt along the stem: a moving maximum over ±2·BELT_SMOOTHING followed by two box means
     * over ±BELT_SMOOTHING never falls below the original (each mean only averages values that bound it), and has no
     * per-sample steps (which the valley-side terraces would turn into visible facets).
     */
    private static double[] smoothBelt(double[] belt, double[] u) {
        int k = belt.length;
        double[] max = new double[k];
        for (int q = 0; q < k; q++) {
            double v = belt[q];
            for (int b = q - 1; b >= 0 && u[q] - u[b] <= 2 * BELT_SMOOTHING; b--) v = Math.max(v, belt[b]);
            for (int b = q + 1; b < k && u[b] - u[q] <= 2 * BELT_SMOOTHING; b++) v = Math.max(v, belt[b]);
            max[q] = v;
        }
        double[] out = max;
        for (int pass = 0; pass < 2; pass++) {
            double[] next = new double[k];
            for (int q = 0; q < k; q++) {
                double sum = out[q]; int n = 1;
                for (int b = q - 1; b >= 0 && u[q] - u[b] <= BELT_SMOOTHING; b--) { sum += out[b]; n++; }
                for (int b = q + 1; b < k && u[b] - u[q] <= BELT_SMOOTHING; b++) { sum += out[b]; n++; }
                next[q] = sum / n;
            }
            out = next;
        }
        return out;
    }

    /** Two box passes along the stem over ±FRAME_SMOOTHING/2 each (window shrinks at the ends, which stay fixed). */
    private static double[] smoothAlong(double[] v, double[] u) {
        double[] out = v;
        for (int pass = 0; pass < 2; pass++) {
            double[] next = new double[v.length];
            for (int q = 0; q < v.length; q++) {
                double half = Math.min(.5 * FRAME_SMOOTHING, Math.min(u[q] - u[0], u[v.length - 1] - u[q]));
                double sumW = 0, acc = 0;
                for (int r = q; r >= 0 && u[q] - u[r] <= half; r--) { acc += out[r]; sumW++; }
                for (int r = q + 1; r < v.length && u[r] - u[q] <= half; r++) { acc += out[r]; sumW++; }
                next[q] = acc / sumW;
            }
            out = next;
        }
        return out;
    }

    /** Running mean over one local wavelength (window varies with position). */
    private static double[] boxFilter(double[] v, double[] window, int n) {
        double[] prefix = new double[n + 1], out = new double[n];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + v[i];
        for (int i = 0; i < n; i++) {
            int half = Math.max(1, (int) (window[i] * .5 / STEP));
            int a = Math.max(0, i - half), b = Math.min(n, i + half + 1);
            out[i] = (prefix[b] - prefix[a]) / (b - a);
        }
        return out;
    }

    /** Identity up to 80% of the limit, then eases into it (never exceeds it). */
    private static double softClamp(double v, double limit) {
        if (limit <= 0) return 0;
        double a = Math.abs(v), knee = .8 * limit;
        if (a <= knee) return v;
        return Math.signum(v) * (knee + .2 * limit * Math.tanh((a - knee) / (.2 * limit)));
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
