package etcodehome.freeterraforged.world.worldgen.highlands;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.Continentalness;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;

/**
 * RH10 P0: drowned-coast metrics, measured on a box around a site. Land/sea comes from the coast edge field against the
 * preset's coast control point, sampled once on a {@link #NAV_GRID}-block lattice; the {@link #GRID}-block metric mask
 * is every eighth sample of it, so both masks agree. Two scopes are reported side by side: the sea within
 * {@link #NEAR_SHORE} of land, and the drowned scope (those sea points whose coast sample reports a drowned-region
 * weight of at least {@link #DROWNED_WEIGHT}); islands, edge slope, plan cost and the drowned share are global.
 *
 * <ul>
 * <li>islands: 4-connected land components on the GRID mask that do not touch the box border (see {@link #islands} for the padded whole-island census);</li>
 * <li>sheltered: scoped points at least {@link #SHELTER_DEPTH} deep whose effective fetch (mean free-water run over
 * {@link #FETCH_RAYS} compass rays, each capped at {@link #FETCH_CAP}; a ray leaving the box counts as open to the
 * cap) is at most {@link #SHELTER_FETCH};</li>
 * <li>navigable: water eroded by one NAV_GRID cell (a 6-block channel keeps its centre cell), the share of scoped
 * points at least {@link #NAV_DEPTH} deep that lie in an eroded component touching the box border (the open sea);</li>
 * <li>harbors: sheltered GRID components of at least {@link #HARBOR_AREA} square blocks that connect to the open sea.</li>
 * </ul>
 */
final class HighlandDrownedProbe {
    static final int GRID = 16, NAV_GRID = 2, FETCH_RAYS = 16, FETCH_STEP = 8, FETCH_CAP = 1500, SHELTER_FETCH = 400,
        SHELTER_DEPTH = 3, NAV_DEPTH = 2, HARBOR_AREA = 20000, NEAR_SHORE = 1500, CHAIN_GAP = 300;
    /** Drowned scope threshold (region weight), and the side and column lattice (blocks) of the drowned cost box. */
    static final double DROWNED_WEIGHT = .5;
    static final int COST_BOX = 2048, COST_STEP = 16;

    /** Metrics of one scope: sea points (GRID lattice) with their area, fetch, navigability, harbors, ocean band and column cost. */
    record Scope(int samples, double seaArea, double shelteredShare, double navigableShare, double harborsPer25km2,
                 double oceanBandShare, double nanosPerColumn) {
        String summary() {
            return String.format(Locale.ROOT, "n=%d seaArea=%.0f sheltered=%.1f%% navigable=%.1f%% harbors/25km2=%.2f oceanBand=%.1f%% ns/column=%.0f",
                samples, seaArea, 100 * shelteredShare, 100 * navigableShare, harborsPer25km2, 100 * oceanBandShare, nanosPerColumn);
        }
    }

    /**
     * {@code nearShore} and {@code drowned} are the two scopes; {@code drownedShare} is the share of the RH9 coastline
     * (traced on the unbound standalone coast) where the bound coast's drowned weight is at least DROWNED_WEIGHT;
     * {@code drownedBoxNanos} is the column cost (all lookups on a COST_STEP lattice) of the COST_BOX box with the highest
     * mean weight inside the box, centred at ({@code boxX}, {@code boxZ}).
     */
    record Report(Scope nearShore, Scope drowned, int islands, double islandsPer10km2, int[] islandSizes, int islandsInChains,
                  double edgeSlopeP10, double edgeSlopeP50, double edgeSlopeP90, int planStep, long planBuildNanos,
                  double drownedShare, double drownedBoxNanos, int boxX, int boxZ) {
        int samples() { return nearShore.samples; }
        String summary(String label) {
            return String.format(Locale.ROOT,
                "RHD %s nearShore[%s] drowned[%s] islands=%d islands/10km2=%.2f sizes(<300,<1000,<3000,more)=%d/%d/%d/%d chained=%d "
                    + "edgeSlope p10=%.2e p50=%.2e p90=%.2e planStep=%d planBuildMs=%.0f drownedShare=%.1f%% drownedBox=(%d,%d) boxNs/column=%.0f",
                label, nearShore.summary(), drowned.summary(), islands, islandsPer10km2, islandSizes[0], islandSizes[1], islandSizes[2], islandSizes[3],
                islandsInChains, edgeSlopeP10, edgeSlopeP50, edgeSlopeP90, planStep, planBuildNanos / 1e6, 100 * drownedShare, boxX, boxZ, drownedBoxNanos);
        }
    }

    /** The shared lattices a scope is measured on. */
    private record Frame(boolean[] land, int w, int gw, int step, boolean[] open, int[] waterId, double[] depth, boolean[] band, float[] cost) {}

    private HighlandDrownedProbe() {}

    static Report measure(GeneratorContext context, int originX, int originZ, int radius) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var levels = context.levels;
        int w = 2 * radius / NAV_GRID + 1, step = GRID / NAV_GRID, gw = 2 * radius / GRID + 1;
        int x0 = originX - radius, z0 = originZ - radius;

        boolean[] land = new boolean[w * w];
        IntStream.range(0, w).parallel().forEach(j -> {
            for (int i = 0; i < w; i++) land[j * w + i] = coast.sample(x0 + i * NAV_GRID, z0 + j * NAV_GRID).edge() > coastEdge;
        });
        boolean[] gridLand = new boolean[gw * gw];
        for (int j = 0; j < gw; j++) for (int i = 0; i < gw; i++) gridLand[j * gw + i] = land[j * step * w + i * step];

        // Near-shore scope: one column lookup per scoped sea point gives depth, ocean band and cost for every scope.
        float[] near = distanceToLand(gridLand, gw);
        boolean[] scoped = new boolean[gw * gw];
        double[] depth = new double[gw * gw];
        boolean[] band = new boolean[gw * gw];
        float[] cost = new float[gw * gw];
        double[] weight = new double[gw * gw];
        var cell = new Cell();
        for (int j = 0; j < gw; j++) for (int i = 0; i < gw; i++) {
            int at = j * gw + i;
            if (gridLand[at] || near[at] * GRID > NEAR_SHORE) continue;
            scoped[at] = true;
            weight[at] = coast.sample(x0 + i * GRID, z0 + j * GRID).drowned();
            long start = System.nanoTime();
            context.lookup.applyCell(cell.reset(), x0 + i * GRID, z0 + j * GRID, false, true);
            cost[at] = System.nanoTime() - start;
            depth[at] = -(cell.height - levels.water) * levels.terrainScaleFactor;
            band[at] = depth[at] >= 1 && CellSampler.Field.CONTINENT.read(cell, context.heightmap) < Continentalness.COAST.min();
        }
        boolean[] drowned = new boolean[gw * gw];
        for (int at = 0; at < drowned.length; at++) drowned[at] = scoped[at] && weight[at] >= DROWNED_WEIGHT;

        // Islands: land components not touching the box border; chained when within CHAIN_GAP of another island.
        var parts = label(gridLand, gw);
        boolean[] border = borderTouching(parts.id, gw, parts.area.length - 1);
        int islands = 0, chained = 0;
        int[] sizes = new int[4];
        boolean[] chain = new boolean[parts.area.length];
        int reach = CHAIN_GAP / GRID;
        for (int id = 1; id < parts.area.length; id++) {
            if (border[id]) continue;
            islands++;
            double diameter = 2 * Math.sqrt(parts.area[id] * (double) GRID * GRID / Math.PI);
            sizes[diameter < 300 ? 0 : diameter < 1000 ? 1 : diameter < 3000 ? 2 : 3]++;
        }
        for (int j = 0; j < gw; j++) for (int i = 0; i < gw; i++) {
            int id = parts.id[j * gw + i];
            if (id == 0 || border[id] || chain[id]) continue;
            search:
            for (int dj = -reach; dj <= reach; dj++) for (int di = -reach; di <= reach; di++) {
                int nj = j + dj, ni = i + di;
                if (nj < 0 || ni < 0 || nj >= gw || ni >= gw || Math.hypot(di, dj) * GRID > CHAIN_GAP) continue;
                int other = parts.id[nj * gw + ni];
                if (other == 0 || other == id || border[other]) continue;
                chain[id] = chain[other] = true;
                break search;
            }
        }
        for (int id = 1; id < chain.length; id++) if (chain[id]) chained++;

        // Navigability water: erode the water by one cell; its components touching the box border are the open sea.
        boolean[] eroded = new boolean[w * w];
        for (int j = 0; j < w; j++) for (int i = 0; i < w; i++) {
            int at = j * w + i;
            eroded[at] = !land[at] && (i == 0 || !land[at - 1]) && (i == w - 1 || !land[at + 1]) && (j == 0 || !land[at - w]) && (j == w - 1 || !land[at + w]);
        }
        var water = label(eroded, w);
        var frame = new Frame(land, w, gw, step, borderTouching(water.id, w, water.area.length - 1), water.id, depth, band, cost);
        var nearScope = scope(frame, scoped);
        var drownedScope = scope(frame, drowned);

        // Edge slope at the land/sea crossings of the GRID rows, as HighlandCoastProbe's transects measure it.
        var slopes = new ArrayList<Double>();
        for (int j = 0; j < gw; j++) for (int i = 1; i < w; i++) {
            int at = j * step * w + i;
            if (land[at] == land[at - 1]) continue;
            int x = x0 + i * NAV_GRID, z = z0 + j * GRID;
            slopes.add(coast.edgeSlope(x, z, coast.sample(x, z).edge()));
        }
        double[] sorted = slopes.stream().mapToDouble(Double::doubleValue).sorted().toArray();

        var plan = continent.rebuildAt(originX, originZ).plan();
        double share = drownedShare(context, originX, originZ, radius);
        int[] box = drownedBox(weight, gw, x0, z0);
        return new Report(nearScope, drownedScope, islands, nearScope.samples > 0 ? islands * 1e7 / nearScope.seaArea : Double.NaN, sizes, chained,
            HighlandCoastProbe.quantile(sorted, .1), HighlandCoastProbe.quantile(sorted, .5), HighlandCoastProbe.quantile(sorted, .9),
            plan.gridStep(), plan.buildNanos(), share, boxNanos(context, box[0], box[1]), box[0], box[1]);
    }

    /** Metrics of the scoped sea points of a frame. */
    private static Scope scope(Frame f, boolean[] scoped) {
        int gw = f.gw, w = f.w, step = f.step;
        int samples = 0, wet = 0, band = 0;
        double nanos = 0;
        for (int at = 0; at < scoped.length; at++) {
            if (!scoped[at]) continue;
            samples++;
            nanos += f.cost[at];
            if (f.depth[at] < 1) continue;
            wet++;
            if (f.band[at]) band++;
        }
        double seaArea = (double) samples * GRID * GRID;
        // Sheltered water by effective fetch (always judged against all land of the frame).
        boolean[] sheltered = new boolean[gw * gw];
        int deepEnough = 0, shelteredCount = 0;
        for (int j = 0; j < gw; j++) for (int i = 0; i < gw; i++) {
            int at = j * gw + i;
            if (!scoped[at] || f.depth[at] < SHELTER_DEPTH) continue;
            deepEnough++;
            if (!sheltered(f.land, w, i * step, j * step)) continue;
            sheltered[at] = true;
            shelteredCount++;
        }
        boolean[] navigable = new boolean[gw * gw];
        int navTotal = 0, navOpen = 0;
        for (int j = 0; j < gw; j++) for (int i = 0; i < gw; i++) {
            int at = j * gw + i;
            if (!scoped[at] || f.depth[at] < NAV_DEPTH) continue;
            navTotal++;
            int id = f.waterId[j * step * w + i * step];
            if (id == 0 || !f.open[id]) continue;
            navigable[at] = true;
            navOpen++;
        }
        // Harbors: sheltered components big enough and connected to the open sea.
        var coves = label(sheltered, gw);
        boolean[] reaches = new boolean[coves.area.length];
        for (int at = 0; at < gw * gw; at++) if (sheltered[at] && navigable[at]) reaches[coves.id[at]] = true;
        int harbors = 0;
        for (int id = 1; id < coves.area.length; id++) if (reaches[id] && coves.area[id] * (double) GRID * GRID >= HARBOR_AREA) harbors++;
        return new Scope(samples, seaArea, ratio(shelteredCount, deepEnough), ratio(navOpen, navTotal),
            samples > 0 ? harbors * 25e6 / seaArea : Double.NaN, ratio(band, wet), samples > 0 ? nanos / samples : Double.NaN);
    }

    /**
     * Share of the RH9 coastline length (the unbound standalone coast, so arm shores do not inflate it) lying where
     * the bound coast's drowned weight is at least DROWNED_WEIGHT; each traced segment takes its start point's weight.
     */
    static double drownedShare(GeneratorContext context, int originX, int originZ, int radius) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var lines = HighlandCoastProbe.coastlines(new HighlandCoast(context), context.preset.world().controlPoints.coast, originX, originZ, radius);
        double total = 0, drowned = 0;
        for (var line : lines) for (int i = 1; i < line.size(); i++) {
            double length = Math.hypot(line.get(i)[0] - line.get(i - 1)[0], line.get(i)[1] - line.get(i - 1)[1]);
            total += length;
            if (coast.sample(line.get(i - 1)[0], line.get(i - 1)[1]).drowned() >= DROWNED_WEIGHT) drowned += length;
        }
        return total > 0 ? drowned / total : Double.NaN;
    }

    /** Centre of the COST_BOX tile of the site box with the highest mean weight (tiles of the GRID lattice). */
    private static int[] drownedBox(double[] weight, int gw, int x0, int z0) {
        int tile = COST_BOX / GRID, tiles = gw / tile;
        double best = -1;
        int[] centre = {x0 + COST_BOX / 2, z0 + COST_BOX / 2};
        for (int tj = 0; tj < tiles; tj++) for (int ti = 0; ti < tiles; ti++) {
            double sum = 0;
            for (int j = tj * tile; j < (tj + 1) * tile; j++) for (int i = ti * tile; i < (ti + 1) * tile; i++) sum += weight[j * gw + i];
            if (sum > best) { best = sum; centre = new int[]{x0 + ti * COST_BOX + COST_BOX / 2, z0 + tj * COST_BOX + COST_BOX / 2}; }
        }
        return centre;
    }

    /** Warm column cost (ns) of every COST_STEP lattice column of the COST_BOX box centred at (cx, cz): land and sea alike. */
    static double boxNanos(GeneratorContext context, int cx, int cz) {
        var cell = new Cell();
        double nanos = 0;
        for (int pass = 0; pass < 2; pass++) {
            int columns = 0;
            long start = System.nanoTime();
            for (int z = cz - COST_BOX / 2; z < cz + COST_BOX / 2; z += COST_STEP) for (int x = cx - COST_BOX / 2; x < cx + COST_BOX / 2; x += COST_STEP) {
                context.lookup.applyCell(cell.reset(), x, z, false, true);
                columns++;
            }
            nanos = (double) (System.nanoTime() - start) / columns;
        }
        return nanos;
    }

    /** Mask image colours: land, RH9 (model-8) sea, drowned-arm sea, and the drowned-weight tint blended over land and RH9 sea. */
    private static final int MASK_LAND = 0x7a7a62, MASK_SEA = 0x163a65, MASK_ARM = 0x00e5ff, MASK_TINT = 0xff3366;

    /**
     * Drowned-arm water of a box: columns (GRID lattice) that are land on the standalone RH9 coast and sea on the bound
     * coast. {@code count} is the arm components (4-connected) of at least {@link #MIN_ARM} columns; length is the largest
     * distance of an arm column from RH9 sea, width twice the largest distance of an arm column from land, both per
     * component in blocks.
     */
    record Arms(int count, double areaKm2, double largestKm2, double lengthMedian, double lengthMax, double widthMedian, double widthMax) {
        String summary() {
            return String.format(Locale.ROOT, "arms=%d area=%.2fkm2 largest=%.2fkm2 length median=%.0f max=%.0f width median=%.0f max=%.0f",
                count, areaKm2, largestKm2, lengthMedian, lengthMax, widthMedian, widthMax);
        }
    }
    static final int MIN_ARM = 4;

    static Arms arms(GeneratorContext context, int originX, int originZ, int radius) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var standalone = new HighlandCoast(context);
        double coastEdge = context.preset.world().controlPoints.coast;
        int gw = 2 * radius / GRID + 1, x0 = originX - radius, z0 = originZ - radius;
        boolean[] arm = new boolean[gw * gw], rh9Sea = new boolean[gw * gw], boundLand = new boolean[gw * gw];
        IntStream.range(0, gw).parallel().forEach(j -> {
            for (int i = 0; i < gw; i++) {
                double x = x0 + i * GRID, z = z0 + j * GRID;
                boolean land = coast.sample(x, z).edge() > coastEdge, base = standalone.sample(x, z).edge() > coastEdge;
                boundLand[j * gw + i] = land;
                rh9Sea[j * gw + i] = !base;
                arm[j * gw + i] = base && !land;
            }
        });
        float[] fromSea = distanceToLand(rh9Sea, gw), fromLand = distanceToLand(boundLand, gw);
        var parts = label(arm, gw);
        float[] length = new float[parts.area.length], width = new float[parts.area.length];
        for (int at = 0; at < arm.length; at++) {
            if (!arm[at]) continue;
            int id = parts.id[at];
            length[id] = Math.max(length[id], fromSea[at]);
            width[id] = Math.max(width[id], fromLand[at]);
        }
        var lengths = new ArrayList<Double>();
        var widths = new ArrayList<Double>();
        int count = 0;
        double area = 0, largest = 0;
        for (int id = 1; id < parts.area.length; id++) {
            if (parts.area[id] < MIN_ARM) continue;
            count++;
            double km2 = parts.area[id] * (double) GRID * GRID / 1e6;
            area += km2;
            largest = Math.max(largest, km2);
            lengths.add((double) length[id] * GRID);
            widths.add(2.0 * width[id] * GRID);
        }
        double[] l = lengths.stream().mapToDouble(Double::doubleValue).sorted().toArray(), v = widths.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return new Arms(count, area, largest, count > 0 ? HighlandCoastProbe.quantile(l, .5) : 0, count > 0 ? l[l.length - 1] : 0,
            count > 0 ? HighlandCoastProbe.quantile(v, .5) : 0, count > 0 ? v[v.length - 1] : 0);
    }

    /**
     * Fjord arm census of a box (16-block lattice). An arm is a 4-connected component of fjord-scope water: columns that
     * are land on the standalone coast, sea on the bound coast, and whose fjord weight is at least {@link #DROWNED_WEIGHT};
     * only arms with a geodesic length of at least {@link #MIN_FJORD_ARM} blocks count. Per arm: {@code lengths} is the
     * geodesic length (8-connected, inside the arm, steps preferring the middle of the water) from the standalone coastline to
     * the farthest column; the trunk is the path from the mouth to that column, a side arm is the extra path of another head (local maxima of the geodesic
     * distance at least {@link #HEAD_GAP} blocks from an accepted head) that is at least {@link #SIDE_MIN} blocks long
     * before it joins an earlier path (heads are local maxima of the path cost). Local width is twice the largest distance to land within two cells of a path
     * column; {@code trunkWidths} holds each trunk's widest local width (the channel is as wide as its widest reach) and
     * {@code sideWidths} each side arm's median local width, leaving out its last {@link #SIDE_JUNCTION} blocks at the junction
     * (where it opens into the channel it branches from).
     * {@code soundRegions} of {@code armRegions} fjord regions (their dominant region under arm columns) hold water at
     * least {@link #SOUND_WIDTH} across.
     */
    record Fjords(int arms, int armsWithSide, double[] lengths, double[] trunkWidths, double[] sideWidths, double maxWidth,
                  int armRegions, int soundRegions, int soundCells) {
        String summary() {
            return String.format(Locale.ROOT, "fjords arms=%d withSide=%d length median=%.0f p95=%.0f max=%.0f trunkWidth median=%.0f sideWidth median=%.0f (n=%d) maxWidth=%.0f regions=%d soundRegions=%d soundCells=%d",
                arms, armsWithSide, median(lengths), HighlandCoastProbe.quantile(lengths, .95), lengths.length > 0 ? lengths[lengths.length - 1] : 0,
                median(trunkWidths), median(sideWidths), sideWidths.length, maxWidth, armRegions, soundRegions, soundCells);
        }
        private static double median(double[] sorted) { return sorted.length > 0 ? HighlandCoastProbe.quantile(sorted, .5) : Double.NaN; }
    }
    /** Paths prefer the middle of the water: a step costs {@code 1 + CENTRE_BIAS / halfWidthInCells}; lengths are the true path lengths. */
    static final int MIN_FJORD_ARM = 300, HEAD_GAP = 300, SIDE_MIN = 150, SIDE_JUNCTION = 150, SOUND_WIDTH = 1000, CENTRE_BIAS = 3;

    /**
     * The geodesic structure of the arm water of a box (16-block lattice): the arm mask (flooded land in the fjord scope or,
     * for bays, outside it), the standalone sea, distance to land in cells, the path tree from the standalone coastline
     * ({@code parent}, true path length {@code trail}) and the candidate heads (local maxima of the path cost) per component.
     */
    private record Network(int gw, int x0, int z0, boolean[] arm, boolean[] rh9Sea, boolean[] boundLand, float[] dLand, float[] trail, int[] parent,
                           Components parts, java.util.Map<Integer, ArrayList<Integer>> heads) {}

    /** One traced arm: its true length, widest trunk reach, side-arm widths, and the number of accepted heads (1 is a single channel). */
    private record Arm(int id, double length, double trunkWidth, List<Double> sideWidths, int heads) {
        boolean side() { return !sideWidths.isEmpty(); }
    }

    private static Network network(GeneratorContext context, int originX, int originZ, int radius, boolean fjordScope) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var drowned = coast.drowned();
        var standalone = new HighlandCoast(context);
        double coastEdge = context.preset.world().controlPoints.coast;
        int gw = 2 * radius / GRID + 1, x0 = originX - radius, z0 = originZ - radius;
        boolean[] arm = new boolean[gw * gw], rh9Sea = new boolean[gw * gw], boundLand = new boolean[gw * gw];
        IntStream.range(0, gw).parallel().forEach(j -> {
            for (int i = 0; i < gw; i++) {
                double x = x0 + i * GRID, z = z0 + j * GRID;
                boolean land = coast.sample(x, z).edge() > coastEdge, base = standalone.sample(x, z).edge() > coastEdge;
                boundLand[j * gw + i] = land;
                rh9Sea[j * gw + i] = !base;
                arm[j * gw + i] = base && !land && (drowned.fjord(x, z) >= DROWNED_WEIGHT) == fjordScope;
            }
        });
        float[] dLand = distanceToLand(boundLand, gw);
        float[] g = new float[gw * gw];
        Arrays.fill(g, Float.POSITIVE_INFINITY);
        var queue = new java.util.PriorityQueue<Long>();
        for (int at = 0; at < arm.length; at++) {
            if (!arm[at] || !touches(rh9Sea, gw, at)) continue;
            g[at] = 0;
            queue.add((long) Float.floatToIntBits(0) << 32 | at);
        }
        int[] parent = new int[gw * gw];
        float[] trail = new float[gw * gw];
        Arrays.fill(parent, -1);
        while (!queue.isEmpty()) {
            long top = queue.poll();
            int at = (int) top, i = at % gw, j = at / gw;
            if (Float.intBitsToFloat((int) (top >>> 32)) > g[at]) continue;
            for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                int ni = i + di, nj = j + dj;
                if ((di == 0 && dj == 0) || ni < 0 || nj < 0 || ni >= gw || nj >= gw || !arm[nj * gw + ni]) continue;
                float step = di == 0 || dj == 0 ? 1 : 1.4142135f, next = g[at] + step * (1 + CENTRE_BIAS / Math.max(1, dLand[nj * gw + ni]));
                if (next < g[nj * gw + ni]) {
                    g[nj * gw + ni] = next; parent[nj * gw + ni] = at; trail[nj * gw + ni] = trail[at] + step;
                    queue.add((long) Float.floatToIntBits(next) << 32 | (nj * gw + ni));
                }
            }
        }
        var parts = label(arm, gw, !fjordScope);
        // Heads per component: local maxima of g, greedily accepted from the farthest, at least HEAD_GAP apart.
        var heads = new java.util.HashMap<Integer, ArrayList<Integer>>();
        for (int at = 0; at < arm.length; at++) {
            if (!arm[at] || Float.isInfinite(g[at]) || trail[at] * GRID < SIDE_MIN || !localMax(g, arm, gw, at)) continue;
            heads.computeIfAbsent(parts.id[at], k -> new ArrayList<>()).add(at);
        }
        return new Network(gw, x0, z0, arm, rh9Sea, boundLand, dLand, trail, parent, parts, heads);
    }

    /** The traced arms of a network: components whose longest path is at least MIN_FJORD_ARM blocks, with trunk and side-arm widths. */
    private static List<Arm> trace(Network n) {
        var arms = new ArrayList<Arm>();
        boolean[] used = new boolean[n.gw * n.gw];
        int gap = HEAD_GAP / GRID, gw = n.gw;
        for (var entry : n.heads.entrySet()) {
            var heads = entry.getValue();
            heads.sort((a, b) -> Float.compare(n.trail[b], n.trail[a]));
            if (n.trail[heads.get(0)] * GRID < MIN_FJORD_ARM) continue;
            var accepted = new ArrayList<Integer>();
            for (int head : heads) {
                boolean apart = true;
                for (int other : accepted) apart &= Math.hypot(head % gw - other % gw, head / gw - other / gw) >= gap;
                if (apart) accepted.add(head);
            }
            double trunk = 0;
            var sides = new ArrayList<Double>();
            for (int h = 0; h < accepted.size(); h++) {
                var path = new ArrayList<Double>();
                int cells = 0;
                for (int at = accepted.get(h); at >= 0 && !used[at]; at = n.parent[at]) {
                    used[at] = true;
                    cells++;
                    path.add(2.0 * GRID * windowMax(n.dLand, n.arm, gw, at));
                }
                if (h == 0) trunk = path.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                else if (cells * GRID >= SIDE_MIN) sides.add(medianOf(path.subList(0, Math.max(1, path.size() - SIDE_JUNCTION / GRID))));
            }
            arms.add(new Arm(entry.getKey(), (double) n.trail[accepted.get(0)] * GRID, trunk, sides, accepted.size()));
        }
        return arms;
    }

    static Fjords fjords(GeneratorContext context, int originX, int originZ, int radius) {
        var drowned = ((RollingHighlandsContinent) context.heightmap.continent()).coast().drowned();
        var n = network(context, originX, originZ, radius, true);
        var arms = trace(n);
        var lengths = new ArrayList<Double>();
        var trunkWidths = new ArrayList<Double>();
        var sideWidths = new ArrayList<Double>();
        var regions = new java.util.HashSet<Long>();
        var soundRegions = new java.util.HashSet<Long>();
        int withSide = 0, soundCells = 0;
        double maxWidth = 0;
        for (var arm : arms) {
            lengths.add(arm.length);
            trunkWidths.add(arm.trunkWidth);
            sideWidths.addAll(arm.sideWidths);
            if (arm.side()) withSide++;
        }
        for (int at = 0; at < n.arm.length; at++) {
            if (!n.arm[at]) continue;
            double width = 2.0 * GRID * n.dLand[at];
            maxWidth = Math.max(maxWidth, width);
            var region = drowned.dominant(n.x0 + at % n.gw * GRID, n.z0 + at / n.gw * GRID, true);
            if (region == null) continue;
            long key = ((long) region.i() << 32) ^ (region.j() & 0xffffffffL);
            regions.add(key);
            if (width >= SOUND_WIDTH) { soundRegions.add(key); soundCells++; }
        }
        double[] l = lengths.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return new Fjords(l.length, withSide, l, trunkWidths.stream().mapToDouble(Double::doubleValue).sorted().toArray(),
            sideWidths.stream().mapToDouble(Double::doubleValue).sorted().toArray(), maxWidth, regions.size(), soundRegions.size(), soundCells);
    }

    /**
     * Ria bay census of a box (16-block lattice), on the same path network as the fjord census but over flooded land outside
     * the fjord scope. A bay is a 4-connected component whose longest path from the standalone coastline is at least
     * {@link #MIN_FJORD_ARM} blocks. Per bay: {@code mouth} is the water width of the widest entrance along the standalone coastline (the largest 8-connected run of
     * columns with a 4-neighbour in the standalone sea, times GRID), {@code inner} the inscribed width (twice the largest distance to land),
     * {@code heads} the accepted heads (2 or more: branching), whether every column shares one owner, and whether the
     * bay reaches the open sea (a column of it lies in water that survives a one-cell erosion and touches the box border).
     */
    record Bays(int count, double[] mouths, double[] inners, double[] ratios, int branching, int narrowMouth, int singleOwner, int connected, int[] heads, int closed, int closedCells, int armCells) {
        String summary() {
            return String.format(Locale.ROOT, "bays=%d mouth median=%.0f inner median=%.0f max=%.0f ratio(mouth/inner) median=%.2f narrowMouth(<=.5)=%d branching=%d singleOwner=%d connected=%d closed=%d (%.1f%% of the flooded area)",
                count, median(mouths), median(inners), inners.length > 0 ? inners[inners.length - 1] : 0, median(ratios), narrowMouth, branching, singleOwner, connected, closed, 100.0 * closedCells / Math.max(1, armCells));
        }
        double closedShare() { return (double) closedCells / Math.max(1, armCells); }
        private static double median(double[] sorted) { return sorted.length > 0 ? HighlandCoastProbe.quantile(sorted, .5) : Double.NaN; }
    }
    /** A bay's mouth counts as narrow when it is at most this fraction of its inner width. */
    static final double NARROW_MOUTH = .5;
    /** A flooded water body with no column touching the standalone sea is closed (a lagoon behind a bar); only bodies of at least this many columns that do not reach the box border (where the sea may lie outside) count. */
    static final int CLOSED_MIN = 16;

    static Bays bays(GeneratorContext context, int originX, int originZ, int radius) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var n = network(context, originX, originZ, radius, false);
        var arms = trace(n);
        int gw = n.gw, count = arms.size();
        // Open water by one-cell erosion of all water (standalone sea and arms), components touching the box border.
        boolean[] water = new boolean[gw * gw], eroded = new boolean[gw * gw];
        for (int at = 0; at < water.length; at++) water[at] = n.rh9Sea[at] || n.arm[at];
        for (int j = 0; j < gw; j++) for (int i = 0; i < gw; i++) {
            int at = j * gw + i;
            eroded[at] = water[at] && (i == 0 || water[at - 1]) && (i == gw - 1 || water[at + 1]) && (j == 0 || water[at - gw]) && (j == gw - 1 || water[at + gw]);
        }
        var open = label(eroded, gw);
        boolean[] border = borderTouching(open.id, gw, open.area.length - 1);
        var mouth = new java.util.HashMap<Integer, Integer>();
        boolean[] mouthCell = new boolean[gw * gw];
        var inner = new java.util.HashMap<Integer, Float>();
        var owner = new java.util.HashMap<Integer, Long>();
        var mixed = new java.util.HashSet<Integer>();
        var reaches = new java.util.HashSet<Integer>();
        for (int at = 0; at < n.arm.length; at++) {
            if (!n.arm[at]) continue;
            int id = n.parts.id[at], i = at % gw, j = at / gw;
            if ((i > 0 && n.rh9Sea[at - 1]) || (i < gw - 1 && n.rh9Sea[at + 1]) || (j > 0 && n.rh9Sea[at - gw]) || (j < gw - 1 && n.rh9Sea[at + gw])) { mouthCell[at] = true; mouth.merge(id, 1, Integer::sum); }
            inner.merge(id, n.dLand[at], Math::max);
            var site = coast.sample(n.x0 + i * GRID, n.z0 + j * GRID);
            long key = ((long) site.ownerX() << 32) ^ (site.ownerZ() & 0xffffffffL);
            if (owner.putIfAbsent(id, key) != null && owner.get(id) != key) mixed.add(id);
            if (eroded[at] && border[open.id[at]]) reaches.add(id);
        }
        // Widest entrance: the largest 8-connected run of mouth columns of each bay (a bay with several entrances is judged by its widest one).
        var runs = label(mouthCell, gw, true);
        var entrance = new java.util.HashMap<Integer, Integer>();
        for (int at = 0; at < mouthCell.length; at++) if (mouthCell[at]) entrance.merge(n.parts.id[at], runs.area[runs.id[at]], Math::max);
        int closed = 0, closedCells = 0, armCells = 0;
        for (int at = 0; at < n.arm.length; at++) if (n.arm[at]) armCells++;
        boolean[] cut = borderTouching(n.parts.id, gw, n.parts.area.length - 1);
        for (int id = 1; id < n.parts.area.length; id++) if (n.parts.area[id] >= CLOSED_MIN && !mouth.containsKey(id) && !cut[id]) { closed++; closedCells += n.parts.area[id]; }
        var mouths = new ArrayList<Double>();
        var inners = new ArrayList<Double>();
        var ratios = new ArrayList<Double>();
        var headCounts = new int[count];
        int branching = 0, narrow = 0, single = 0, connected = 0, k = 0;
        for (var arm : arms) {
            double m = (double) entrance.getOrDefault(arm.id, 0) * GRID, w = 2.0 * GRID * inner.get(arm.id);
            mouths.add(m); inners.add(w); ratios.add(m / w);
            headCounts[k++] = arm.heads;
            if (arm.heads >= 2) branching++;
            if (m <= NARROW_MOUTH * w) narrow++;
            if (!mixed.contains(arm.id)) single++;
            if (reaches.contains(arm.id)) connected++;
        }
        return new Bays(count, mouths.stream().mapToDouble(Double::doubleValue).sorted().toArray(), inners.stream().mapToDouble(Double::doubleValue).sorted().toArray(),
            ratios.stream().mapToDouble(Double::doubleValue).sorted().toArray(), branching, narrow, single, connected, headCounts, closed, closedCells, armCells);
    }

    /** One bay census over several boxes: lists concatenated (sorted), counts summed. */
    static Bays poolBays(List<Bays> parts) {
        double[] mouths = parts.stream().flatMapToDouble(b -> Arrays.stream(b.mouths())).sorted().toArray();
        double[] inners = parts.stream().flatMapToDouble(b -> Arrays.stream(b.inners())).sorted().toArray();
        double[] ratios = parts.stream().flatMapToDouble(b -> Arrays.stream(b.ratios())).sorted().toArray();
        return new Bays(mouths.length, mouths, inners, ratios, parts.stream().mapToInt(Bays::branching).sum(), parts.stream().mapToInt(Bays::narrowMouth).sum(),
            parts.stream().mapToInt(Bays::singleOwner).sum(), parts.stream().mapToInt(Bays::connected).sum(), parts.stream().flatMapToInt(b -> Arrays.stream(b.heads())).toArray(), parts.stream().mapToInt(Bays::closed).sum(),
            parts.stream().mapToInt(Bays::closedCells).sum(), parts.stream().mapToInt(Bays::armCells).sum());
    }

    /** One census over several boxes: arm lists concatenated (sorted), counts summed, the widest water kept. */
    static Fjords pool(List<Fjords> parts) {
        double[] lengths = parts.stream().flatMapToDouble(f -> Arrays.stream(f.lengths())).sorted().toArray();
        double[] trunks = parts.stream().flatMapToDouble(f -> Arrays.stream(f.trunkWidths())).sorted().toArray();
        double[] sides = parts.stream().flatMapToDouble(f -> Arrays.stream(f.sideWidths())).sorted().toArray();
        return new Fjords(lengths.length, parts.stream().mapToInt(Fjords::armsWithSide).sum(), lengths, trunks, sides,
            parts.stream().mapToDouble(Fjords::maxWidth).max().orElse(0), parts.stream().mapToInt(Fjords::armRegions).sum(),
            parts.stream().mapToInt(Fjords::soundRegions).sum(), parts.stream().mapToInt(Fjords::soundCells).sum());
    }

    /**
     * Wall height on transects from fjord water: land height (blocks above water, from the real column lookup) at
     * {@link #WALL_NEAR} and {@link #WALL_FAR} blocks inland (along the edge gradient) of arm shore columns of a box, up to
     * {@link #WALL_TRANSECTS} evenly strided transects per box; medians over all transects of all boxes.
     */
    record Walls(int transects, double near, double far) {
        String summary() { return String.format(Locale.ROOT, "walls n=%d median h%d=%.1f h%d=%.1f ratio=%.2f", transects, WALL_NEAR, near, WALL_FAR, far, near / far); }
    }
    static final int WALL_NEAR = 64, WALL_FAR = 512, WALL_TRANSECTS = 300;

    /** Arm water columns (flooded land in the fjord scope) with a land neighbour, on the GRID lattice of a box, as {x, z}. */
    static List<int[]> armShore(GeneratorContext context, int[] box, int radius) { return armShore(context, box, radius, true); }

    /** Arm shore columns of a box: fjord-scope arms, or (not {@code fjordScope}) ria bays. */
    static List<int[]> armShore(GeneratorContext context, int[] box, int radius, boolean fjordScope) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var standalone = new HighlandCoast(context);
        double coastEdge = context.preset.world().controlPoints.coast;
        int gw = 2 * radius / GRID + 1, x0 = box[0] - radius, z0 = box[1] - radius;
        boolean[] arm = new boolean[gw * gw], land = new boolean[gw * gw];
        IntStream.range(0, gw).parallel().forEach(j -> {
            for (int i = 0; i < gw; i++) {
                double x = x0 + i * GRID, z = z0 + j * GRID;
                boolean l = coast.sample(x, z).edge() > coastEdge;
                land[j * gw + i] = l;
                arm[j * gw + i] = !l && standalone.sample(x, z).edge() > coastEdge && (coast.drowned().fjord(x, z) >= DROWNED_WEIGHT) == fjordScope;
            }
        });
        var shore = new ArrayList<int[]>();
        for (int j = 1; j < gw - 1; j++) for (int i = 1; i < gw - 1; i++) {
            int at = j * gw + i;
            if (arm[at] && (land[at - 1] || land[at + 1] || land[at - gw] || land[at + gw])) shore.add(new int[]{x0 + i * GRID, z0 + j * GRID});
        }
        return shore;
    }

    static Walls walls(GeneratorContext context, int[][] boxes, int radius) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var near = new ArrayList<Double>();
        var far = new ArrayList<Double>();
        var cell = new Cell();
        for (int[] box : boxes) {
            var shore = armShore(context, box, radius);
            int stride = Math.max(1, shore.size() / WALL_TRANSECTS);
            for (int k = 0; k < shore.size(); k += stride) {
                int[] at = shore.get(k);
                double e = coast.sample(at[0], at[1]).edge();
                double gx = coast.sample(at[0] + 8, at[1]).edge() - coast.sample(at[0] - 8, at[1]).edge(), gz = coast.sample(at[0], at[1] + 8).edge() - coast.sample(at[0], at[1] - 8).edge(), norm = Math.hypot(gx, gz);
                if (norm == 0 || !(e > 0)) continue;
                gx /= norm; gz /= norm;
                near.add(height(context, cell, at[0] + gx * WALL_NEAR, at[1] + gz * WALL_NEAR));
                far.add(height(context, cell, at[0] + gx * WALL_FAR, at[1] + gz * WALL_FAR));
            }
        }
        double[] n = near.stream().mapToDouble(Double::doubleValue).sorted().toArray(), f = far.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return new Walls(n.length, n.length > 0 ? HighlandCoastProbe.quantile(n, .5) : Double.NaN, f.length > 0 ? HighlandCoastProbe.quantile(f, .5) : Double.NaN);
    }

    /**
     * Fjord wall census (11-16, promoted from the untracked DrownedShapeProbe walls and wallPalette): per fjord shoreline column of a box, the
     * ground {@link #WALL_INLAND} blocks inland (the wall height: the plan measured 8 blocks, but a face that runs wall / 4 blocks is only a fifth up
     * at 8; 64 is the distance of the older {@link #walls} census, past every face run of a wall up to 250 blocks; the ground at
     * {@link #SHOULDER_INLAND} blocks is kept for the record) (perpendicular to the shoreline, along the edge gradient; heightmap ground, blocks above water),
     * the local land height there (the surface before any coast shaping, blocks) and whether the column is rim-zone (outside every warped disc,
     * {@code columnWeight} 0 at the column {@link #SHOULDER_INLAND} blocks inland, effective fjord weight from an arm). Per wall column with an effective fjord weight of at least {@link #DROWNED_WEIGHT}
     * {@link #FACE_INLAND} blocks inland, the coast rock palette class its cell carries and the cliff face weight (highlandCoast); a wall column is
     * one with a face weight above .5. At most {@link #WALL_TRANSECTS} non-rim and {@link #WALL_TRANSECTS} rim-zone columns per box, evenly strided.
     */
    record WallCensus(double[] ground, double[] shoulder, double[] land, boolean[] rim, byte[] climate, boolean[] face, boolean[] fjord) {
        int count(boolean rimOnly) { int n = 0; for (boolean r : rim) if (r || !rimOnly) n++; return n; }
        private double[] pick(double[] values, boolean rimOnly) {
            return IntStream.range(0, values.length).filter(k -> rim[k] || !rimOnly).mapToDouble(k -> values[k]).toArray();
        }
        /** Spearman rank correlation of wall height with land height (ties by average rank). */
        double spearman(boolean rimOnly) {
            double[] a = ranks(pick(ground, rimOnly)), b = ranks(pick(land, rimOnly));
            int n = a.length;
            if (n < 3) return Double.NaN;
            double ma = Arrays.stream(a).average().orElse(0), mb = Arrays.stream(b).average().orElse(0), sab = 0, saa = 0, sbb = 0;
            for (int k = 0; k < n; k++) { sab += (a[k] - ma) * (b[k] - mb); saa += (a[k] - ma) * (a[k] - ma); sbb += (b[k] - mb) * (b[k] - mb); }
            return sab / Math.sqrt(saa * sbb);
        }
        /** Median wall height over the columns whose land height lies in [lo, hi), and their count in {@code n[0]}. */
        double median(double lo, double hi, boolean rimOnly, int[] n) {
            double[] walls = IntStream.range(0, ground.length).filter(k -> (rim[k] || !rimOnly) && land[k] >= lo && land[k] < hi).mapToDouble(k -> ground[k]).sorted().toArray();
            n[0] = walls.length;
            return walls.length > 0 ? HighlandCoastProbe.quantile(walls, .5) : Double.NaN;
        }
        double quantile(double q, boolean rimOnly) {
            double[] walls = pick(ground, rimOnly);
            Arrays.sort(walls);
            return walls.length > 0 ? HighlandCoastProbe.quantile(walls, q) : Double.NaN;
        }
        /** Wall columns (fjord weight at least .5, cliff face weight above .5) of the given coast climate class. */
        int palette(byte climateClass, boolean rimOnly) {
            int n = 0;
            for (int k = 0; k < climate.length; k++) if (fjord[k] && face[k] && climate[k] == climateClass && (rim[k] || !rimOnly)) n++;
            return n;
        }
        int wallColumns(boolean rimOnly) {
            int n = 0;
            for (int k = 0; k < climate.length; k++) if (fjord[k] && face[k] && (rim[k] || !rimOnly)) n++;
            return n;
        }
        /** Median ground {@link #SHOULDER_INLAND} blocks inland (the middle of the face run) of the columns in a land height class, for the record. */
        private double shoulderMedian(double lo, double hi, boolean rimOnly) {
            double[] walls = IntStream.range(0, shoulder.length).filter(k -> (rim[k] || !rimOnly) && land[k] >= lo && land[k] < hi).mapToDouble(k -> shoulder[k]).sorted().toArray();
            return walls.length > 0 ? HighlandCoastProbe.quantile(walls, .5) : Double.NaN;
        }
        String summary(boolean rimOnly) {
            int[] low = new int[1], high = new int[1];
            double lowMedian = median(-1e9, WALL_LOW_LAND, rimOnly, low), highMedian = median(WALL_HIGH_LAND, 1e9, rimOnly, high);
            return String.format(Locale.ROOT, "%s walls n=%d spearman=%.3f median(land>=%.0f)=%.1f n=%d median(land<%.0f)=%.1f n=%d ratio=%.2f p50=%.1f p90=%.1f (ground %d blocks inland; at %d: high %.1f low %.1f) | palette wall columns=%d PNW=%d CALIFORNIA=%d BAJA=%d",
                rimOnly ? "rim-zone" : "all", count(rimOnly), spearman(rimOnly), WALL_HIGH_LAND, highMedian, high[0], WALL_LOW_LAND, lowMedian, low[0], highMedian / lowMedian,
                quantile(.5, rimOnly), quantile(.9, rimOnly), WALL_INLAND, SHOULDER_INLAND, shoulderMedian(WALL_HIGH_LAND, 1e9, rimOnly), shoulderMedian(-1e9, WALL_LOW_LAND, rimOnly), wallColumns(rimOnly), palette(HighlandCoastProfile.PNW, rimOnly),
                palette(HighlandCoastProfile.CALIFORNIA, rimOnly), palette(HighlandCoastProfile.BAJA, rimOnly));
        }
        private static double[] ranks(double[] values) {
            int n = values.length;
            Integer[] order = IntStream.range(0, n).boxed().sorted(Comparator.comparingDouble(k -> values[k])).toArray(Integer[]::new);
            double[] rank = new double[n];
            for (int i = 0; i < n; ) {
                int j = i;
                while (j + 1 < n && values[order[j + 1]] == values[order[i]]) j++;
                for (int k = i; k <= j; k++) rank[order[k]] = (i + j) / 2.0;
                i = j + 1;
            }
            return rank;
        }
    }
    /** The columns of several census parts as one census. */
    static WallCensus pool(WallCensus... parts) {
        return new WallCensus(concat(parts, WallCensus::ground), concat(parts, WallCensus::shoulder), concat(parts, WallCensus::land), concatFlags(parts, WallCensus::rim),
            concatBytes(parts), concatFlags(parts, WallCensus::face), concatFlags(parts, WallCensus::fjord));
    }
    private static double[] concat(WallCensus[] parts, java.util.function.Function<WallCensus, double[]> of) {
        return Arrays.stream(parts).flatMapToDouble(part -> Arrays.stream(of.apply(part))).toArray();
    }
    private static boolean[] concatFlags(WallCensus[] parts, java.util.function.Function<WallCensus, boolean[]> of) {
        int n = Arrays.stream(parts).mapToInt(part -> of.apply(part).length).sum(), at = 0;
        boolean[] out = new boolean[n];
        for (var part : parts) for (boolean flag : of.apply(part)) out[at++] = flag;
        return out;
    }
    private static byte[] concatBytes(WallCensus[] parts) {
        int n = Arrays.stream(parts).mapToInt(part -> part.climate().length).sum(), at = 0;
        byte[] out = new byte[n];
        for (var part : parts) for (byte b : part.climate()) out[at++] = b;
        return out;
    }
    /** Inland distance of the wall height (blocks), of the palette sample, and the land height classes of the wall ratio (blocks). */
    static final int WALL_INLAND = 64, SHOULDER_INLAND = 8, FACE_INLAND = 2, SHORELINE_REACH = 2 * GRID;
    static final double WALL_LOW_LAND = 60, WALL_HIGH_LAND = 150;

    /** Wall census of a box (see {@link WallCensus}); the boxes are {@code {x, z}} centres of 2 x radius blocks. */
    static WallCensus wallCensus(GeneratorContext context, int[][] boxes, int radius) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        var drowned = coast.drowned();
        var land = drowned.land();
        double coastEdge = context.preset.world().controlPoints.coast;
        var ground = new ArrayList<Double>(); var shoulder = new ArrayList<Double>(); var landHeight = new ArrayList<Double>(); var rims = new ArrayList<Boolean>();
        var climates = new ArrayList<Byte>(); var faces = new ArrayList<Boolean>(); var fjords = new ArrayList<Boolean>();
        var cell = new Cell();
        for (int[] box : boxes) {
            // {x, z, unit gradient x, unit gradient z, distance to the shoreline}, split by the disc weight at the wall column
            var plain = new ArrayList<double[]>(); var rimZone = new ArrayList<double[]>();
            for (int[] at : armShore(context, box, radius)) {
                double gx = coast.sample(at[0] + 8, at[1]).edge() - coast.sample(at[0] - 8, at[1]).edge(), gz = coast.sample(at[0], at[1] + 8).edge() - coast.sample(at[0], at[1] - 8).edge(), norm = Math.hypot(gx, gz);
                if (norm == 0) continue;
                gx /= norm; gz /= norm;
                double reach = -1;
                for (int s = 0; s <= SHORELINE_REACH && reach < 0; s++) if (coast.sample(at[0] + gx * s, at[1] + gz * s).edge() > coastEdge) reach = s;
                if (reach < 0) continue;
                (drowned.columnWeight(at[0] + gx * (reach + SHOULDER_INLAND), at[1] + gz * (reach + SHOULDER_INLAND)) <= 0 ? rimZone : plain).add(new double[]{at[0], at[1], gx, gz, reach});
            }
            for (var group : List.of(plain, rimZone)) {
                int stride = Math.max(1, group.size() / WALL_TRANSECTS);
                for (int k = 0; k < group.size(); k += stride) {
                    double[] at = group.get(k);
                    double wx = at[0] + at[2] * (at[4] + WALL_INLAND), wz = at[1] + at[3] * (at[4] + WALL_INLAND), fx = at[0] + at[2] * (at[4] + FACE_INLAND), fz = at[1] + at[3] * (at[4] + FACE_INLAND);
                    ground.add(height(context, cell, wx, wz));
                    shoulder.add(height(context, cell, at[0] + at[2] * (at[4] + SHOULDER_INLAND), at[1] + at[3] * (at[4] + SHOULDER_INLAND)));
                    landHeight.add(land.height(wx, wz));
                    rims.add(group == rimZone);
                    context.heightmap.apply(cell.reset(), (int) Math.round(fx), (int) Math.round(fz), false);
                    climates.add(cell.highlandCoastClimate);
                    faces.add(cell.highlandCoast > .5);
                    fjords.add(drowned.fjord(fx, fz) >= DROWNED_WEIGHT);
                }
            }
        }
        boolean[] rim = new boolean[rims.size()], face = new boolean[rims.size()], fjord = new boolean[rims.size()];
        byte[] climate = new byte[rims.size()];
        for (int k = 0; k < rim.length; k++) { rim[k] = rims.get(k); face[k] = faces.get(k); fjord[k] = fjords.get(k); climate[k] = climates.get(k); }
        return new WallCensus(ground.stream().mapToDouble(Double::doubleValue).toArray(), shoulder.stream().mapToDouble(Double::doubleValue).toArray(), landHeight.stream().mapToDouble(Double::doubleValue).toArray(), rim, climate, face, fjord);
    }

    /**
     * Fjord shores: {@code samples} arm shore columns and the {@code cliff} ones among them (the shore profile's dominant
     * type); {@code mouths} river-water columns within {@link #MOUTH_WINDOW} blocks of a shore column that touch the sea
     * (a 4-block neighbour with the edge at or below the coast point) and {@code steps} of them whose river water surface
     * is at least 2 blocks from sea level.
     */
    record Shores(int samples, int cliff, int mouths, int steps) {
        String summary() { return String.format(Locale.ROOT, "shores n=%d cliff=%.1f%% riverMouths=%d steps=%d", samples, 100.0 * cliff / Math.max(1, samples), mouths, steps); }
    }
    static final int MOUTH_WINDOW = 32, MOUTH_STEP = 4, MOUTH_SHORES = 400;

    static Shores shores(GeneratorContext context, int[][] boxes, int radius) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var cell = new Cell();
        int samples = 0, cliff = 0, mouths = 0, steps = 0;
        for (int[] box : boxes) {
            var shore = armShore(context, box, radius);
            int stride = Math.max(1, shore.size() / MOUTH_SHORES);
            var seen = new java.util.HashSet<Long>();
            for (int k = 0; k < shore.size(); k++) {
                int[] at = shore.get(k);
                samples++;
                if (coast.profile().shore(at[0], at[1]).dominant() == HighlandCoastProfile.Type.CLIFF) cliff++;
                if (k % stride != 0) continue;
                for (int dz = -MOUTH_WINDOW; dz <= MOUTH_WINDOW; dz += MOUTH_STEP) for (int dx = -MOUTH_WINDOW; dx <= MOUTH_WINDOW; dx += MOUTH_STEP) {
                    int x = at[0] + dx, z = at[1] + dz;
                    if (!seen.add(((long) x << 32) ^ (z & 0xffffffffL))) continue;
                    context.heightmap.apply(cell.reset(), x, z, false);
                    if (!cell.hasRiverWater) continue;
                    boolean sea = coast.sample(x + MOUTH_STEP, z).edge() <= coastEdge || coast.sample(x - MOUTH_STEP, z).edge() <= coastEdge
                        || coast.sample(x, z + MOUTH_STEP).edge() <= coastEdge || coast.sample(x, z - MOUTH_STEP).edge() <= coastEdge;
                    if (!sea) continue;
                    mouths++;
                    if (Math.abs((cell.riverWaterLevel - context.levels.water) * context.levels.terrainScaleFactor) >= 2) steps++;
                }
            }
        }
        return new Shores(samples, cliff, mouths, steps);
    }

    /**
     * Bay shores (D-16), ria boxes pooled: {@code samples} bay shore columns (water columns of ria bays with a land neighbour),
     * the {@code beach}-dominant ones and their dry beach widths ({@code widths}, sorted); the same for the RH9 coast outside
     * every drowned region ({@code outsideSamples}, {@code outsideBeach}, {@code outsideWidths}, traced on the standalone coast);
     * {@code bays} bays (traced arms, in the first {@code marshBoxes} boxes: every lookup builds the owner's plan) and the {@code marshBays} among them with at least {@link #MARSH_MIN} WETLAND columns on
     * the land within {@link #MARSH_LOOK} blocks inland of their shore columns (looked up through the heightmap).
     */
    record BayShores(int samples, int beach, double[] widths, int outsideSamples, int outsideBeach, double[] outsideWidths, int bays, int marshBays, int marshColumns) {
        String summary() {
            return String.format(Locale.ROOT, "bayShores n=%d beach=%.1f%% width median=%.1f | outside n=%d beach=%.1f%% width median=%.1f | marshBays=%d of %d (%d marsh columns)",
                samples, 100.0 * beach / Math.max(1, samples), widths.length > 0 ? HighlandCoastProbe.quantile(widths, .5) : Double.NaN,
                outsideSamples, 100.0 * outsideBeach / Math.max(1, outsideSamples), outsideWidths.length > 0 ? HighlandCoastProbe.quantile(outsideWidths, .5) : Double.NaN,
                marshBays, bays, marshColumns);
        }
    }
    static final int MARSH_MIN = 3, MARSH_LOOK = 24, MARSH_PER_BAY = 40, MARSH_STEP = 4;

    static BayShores bayShores(GeneratorContext context, int[][] boxes, int marshBoxes, int radius) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var standalone = new HighlandCoast(context);
        double coastEdge = context.preset.world().controlPoints.coast;
        var cell = new Cell();
        int samples = 0, beach = 0, outside = 0, outsideBeach = 0, bays = 0, marshBays = 0, marshColumns = 0;
        var widths = new ArrayList<Double>();
        var outsideWidths = new ArrayList<Double>();
        for (int[] box : boxes) {
            var n = network(context, box[0], box[1], radius, false);
            var arms = trace(n);
            int gw = n.gw;
            var byBay = new java.util.HashMap<Integer, List<int[]>>();
            for (int j = 1; j < gw - 1; j++) for (int i = 1; i < gw - 1; i++) {
                int at = j * gw + i;
                if (!n.arm[at] || !(n.boundLand[at - 1] || n.boundLand[at + 1] || n.boundLand[at - gw] || n.boundLand[at + gw])) continue;
                int x = n.x0 + i * GRID, z = n.z0 + j * GRID;
                var shore = coast.profile().shore(x, z);
                samples++;
                if (shore.dominant() == HighlandCoastProfile.Type.BEACH) { beach++; widths.add(shore.width()); }
                byBay.computeIfAbsent(n.parts.id[at], k -> new ArrayList<>()).add(new int[]{x, z});
            }
            for (var arm : marshBoxes-- > 0 ? arms : List.<Arm>of()) {
                bays++;
                var shore = byBay.getOrDefault(arm.id, List.of());
                int stride = Math.max(1, shore.size() / MARSH_PER_BAY), found = 0;
                for (int k = 0; k < shore.size(); k += stride) {
                    int[] at = shore.get(k);
                    double e = coast.sample(at[0], at[1]).edge();
                    double gx = coast.sample(at[0] + 8, at[1]).edge() - coast.sample(at[0] - 8, at[1]).edge(), gz = coast.sample(at[0], at[1] + 8).edge() - coast.sample(at[0], at[1] - 8).edge(), norm = Math.hypot(gx, gz);
                    if (norm == 0 || !(e > 0)) continue;
                    gx /= norm; gz /= norm;
                    for (int d = MARSH_STEP; d <= MARSH_LOOK; d += MARSH_STEP) {
                        context.heightmap.apply(cell.reset(), (int) Math.round(at[0] + gx * d), (int) Math.round(at[1] + gz * d), false);
                        if (cell.terrain == etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType.WETLAND) found++;
                    }
                }
                marshColumns += found;
                if (found >= MARSH_MIN) marshBays++;
            }
            for (var line : HighlandCoastProbe.coastlines(standalone, coastEdge, box[0], box[1], radius)) for (var point : line) {
                if (coast.sample(point[0], point[1]).drowned() > 0) continue;
                var shore = coast.profile().shore(point[0], point[1]);
                outside++;
                if (shore.dominant() == HighlandCoastProfile.Type.BEACH) { outsideBeach++; outsideWidths.add(shore.width()); }
            }
        }
        return new BayShores(samples, beach, widths.stream().mapToDouble(Double::doubleValue).sorted().toArray(), outside, outsideBeach,
            outsideWidths.stream().mapToDouble(Double::doubleValue).sorted().toArray(), bays, marshBays, marshColumns);
    }

    /**
     * Islands of a drowned scope in a box (11-06, D-11-15-1): land components (4-connected on the GRID lattice, at least {@link #MIN_ISLAND} cells)
     * with at least half of their cells in the scope (fjord weight, or ria weight where there is no fjord weight, at least {@link #DROWNED_WEIGHT}),
     * measured WHOLE: the lattice is scanned over the box plus a padding margin, an island is counted once by its anchor (its first cell in row-major
     * order) when the anchor lies inside the reporting box (so overlapping boxes dedupe by anchor, see {@link #poolIslands}), and a component that
     * touches the padded border is never counted: one of at least {@link #MAINLAND_CELLS} cells is mainland-connected coast, a smaller one is
     * {@code unresolved} (the padding doubles from {@link #ISLAND_PAD_START} to {@link #ISLAND_PAD_MAX} while any are left). Per island, sorted by nothing:
     * equivalent diameter (blocks), length and width (extents along the principal axes of its cells, blocks), top height and steepest lattice slope (real
     * column lookups, blocks above water and per block), whether a channel plan node with the creek area or river water lies on it, whether another
     * island is within {@link #CHAIN_GAP}, and {@code interior} (the island the old census counted: no cell on the unpadded box border). {@code regionArea}
     * is the scope's area in the box (land and sea, blocks2); {@code eligibleArea} the part of it in the island shelf window (standalone edge over the
     * shelf edge and seaward of the coast gap) and {@code coastLength} the mainland shoreline in the scope (blocks).
     */
    record Islands(int count, double[] diameters, double[] tops, double[] steepest, boolean[] chained, boolean[] creek, double regionArea,
                   long[] anchors, double[] lengths, double[] widths, boolean[] interior, int unresolved, int pad, double eligibleArea, double coastLength, long nanos) {
        int under(double lo, double hi) {
            int n = 0;
            for (double d : diameters) if (d >= lo && d < hi) n++;
            return n;
        }
        int chainedCount() { int n = 0; for (boolean c : chained) if (c) n++; return n; }
        int creekCount(double lo, double hi) {
            int n = 0;
            for (int k = 0; k < count; k++) if (creek[k] && diameters[k] >= lo && diameters[k] < hi) n++;
            return n;
        }
        Islands withNanos(long elapsed) {
            return new Islands(count, diameters, tops, steepest, chained, creek, regionArea, anchors, lengths, widths, interior, unresolved, pad, eligibleArea, coastLength, elapsed);
        }
        int interiorCount(double lo, double hi) {
            int n = 0;
            for (int k = 0; k < count; k++) if (interior[k] && diameters[k] >= lo && diameters[k] < hi) n++;
            return n;
        }
        double per25km2(double lo, double hi) { return regionArea > 0 ? under(lo, hi) * 25e6 / regionArea : Double.NaN; }
        double medianTop() { return Islands.median(tops); }
        /** Median top of the islands of at least {@code minDiameter} blocks across (equivalent diameter), the like-sized population of D-11-15-3. */
        double medianTop(double minDiameter) {
            return Islands.median(IntStream.range(0, count).filter(k -> diameters[k] >= minDiameter).mapToDouble(k -> tops[k]).toArray());
        }
        double medianSteepest() { return Islands.median(steepest); }
        private static double median(double[] values) {
            double[] sorted = values.clone();
            Arrays.sort(sorted);
            return sorted.length > 0 ? HighlandCoastProbe.quantile(sorted, .5) : Double.NaN;
        }
        String summary() {
            return String.format(Locale.ROOT, "islands=%d region=%.1fkm2 sizes(<300,<1000,<3000,more)=%d/%d/%d/%d big(1-3km)/25km2=%.2f chained=%d creek(<1km)=%d creek(1km+)=%d of %d top median=%.1f steepest median=%.3f",
                count, regionArea / 1e6, under(0, 300), under(300, 1000), under(1000, 3000), under(3000, Double.MAX_VALUE), per25km2(1000, 3000), chainedCount(),
                creekCount(0, 1000), creekCount(1000, Double.MAX_VALUE), under(1000, Double.MAX_VALUE), medianTop(), medianSteepest());
        }
        /** The census diagnostics (D-11-15-1): what the padding recovered, what is unresolved, the eligible window and the coast, the wall time. */
        String diagnostics() {
            int old = 0, oldBig = interiorCount(1000, 3000);
            for (boolean in : interior) if (in) old++;
            return String.format(Locale.ROOT, "census pad=%d unresolved=%d counted=%d (old rule %d, recovered %d) big(1-3km) %d (old rule %d) per25km2 old rule=%.2f; eligible shelf window %.1fkm2 of scope %.1fkm2, mainland coast in scope %.1fkm, %d ms",
                pad, unresolved, count, old, count - old, under(1000, 3000), oldBig, regionArea > 0 ? oldBig * 25e6 / regionArea : Double.NaN, eligibleArea / 1e6, regionArea / 1e6, coastLength / 1e3, nanos / 1_000_000);
        }
        /** Length and width of every island of at least {@code minDiameter} blocks across (equivalent diameter): "diameter LxW" in blocks, longest first. */
        String shapes(double minDiameter) {
            var out = new ArrayList<String>();
            Integer[] order = java.util.stream.IntStream.range(0, count).boxed().filter(k -> diameters[k] >= minDiameter).sorted((a, b) -> Double.compare(lengths[b], lengths[a])).toArray(Integer[]::new);
            for (int k : order) out.add(String.format(Locale.ROOT, "%.0f(%.0fx%.0f%s)", diameters[k], lengths[k], widths[k], interior[k] ? "" : ",clipped"));
            return out.toString();
        }
    }
    /** Smallest island the census counts (lattice cells): smaller specks are noise of the lattice, not islands. */
    static final int MIN_ISLAND = 4;
    /** Census padding (blocks): start and budget; a component of at least MAINLAND_CELLS lattice cells (10 km2) that touches the padded border is mainland-connected coast. */
    static final int ISLAND_PAD_START = 2048, ISLAND_PAD_MAX = 4096, MAINLAND_CELLS = 39063;

    /** Island census of a box (see {@link Islands}): padded whole-island scan, the padding doubling within its budget while components stay unresolved. */
    static Islands islands(GeneratorContext context, int originX, int originZ, int radius, boolean fjordScope) {
        long started = System.nanoTime();
        int pad = ISLAND_PAD_START;
        Islands census = islandPass(context, originX, originZ, radius, fjordScope, pad);
        while (census.unresolved() > 0 && pad < ISLAND_PAD_MAX) {
            pad = Math.min(ISLAND_PAD_MAX, 2 * pad);
            census = islandPass(context, originX, originZ, radius, fjordScope, pad);
        }
        return census.withNanos(System.nanoTime() - started);
    }

    private static Islands islandPass(GeneratorContext context, int originX, int originZ, int radius, boolean fjordScope, int pad) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        var drowned = coast.drowned();
        double coastEdge = context.preset.world().controlPoints.coast, deepOcean = context.preset.world().controlPoints.deepOcean;
        double windowEnd = coastEdge - HighlandDrowned.ISLAND_GAP * HighlandCoast.EDGE_PER_BLOCK;
        int bw = 2 * radius / GRID + 1, margin = pad / GRID, gw = bw + 2 * margin, x0 = originX - radius - margin * GRID, z0 = originZ - radius - margin * GRID;
        boolean[] land = new boolean[gw * gw], scoped = new boolean[gw * gw], eligible = new boolean[gw * gw];
        IntStream.range(0, gw).parallel().forEach(j -> {
            for (int i = 0; i < gw; i++) {
                double x = x0 + i * GRID, z = z0 + j * GRID;
                land[j * gw + i] = coast.sample(x, z).edge() > coastEdge;
                double fjord = drowned.fjord(x, z);
                scoped[j * gw + i] = fjordScope ? fjord >= DROWNED_WEIGHT : fjord < DROWNED_WEIGHT && drowned.ria(x, z) >= DROWNED_WEIGHT;
                if (scoped[j * gw + i] && inBox(i, j, margin, bw)) {
                    double standalone = coast.undrowned(x, z).edge();
                    eligible[j * gw + i] = standalone > deepOcean && standalone < windowEnd;
                }
            }
        });
        double regionArea = 0, eligibleArea = 0;
        for (int at = 0; at < land.length; at++) {
            if (!scoped[at] || !inBox(at % gw, at / gw, margin, bw)) continue;
            regionArea += (double) GRID * GRID;
            if (eligible[at]) eligibleArea += (double) GRID * GRID;
        }
        var parts = label(land, gw);
        boolean[] border = borderTouching(parts.id, gw, parts.area.length - 1);
        int count0 = parts.area.length;
        int[] inScope = new int[count0], anchor = new int[count0];
        boolean[] touchesBox = new boolean[count0];
        Arrays.fill(anchor, -1);
        for (int at = 0; at < land.length; at++) {
            if (!land[at]) continue;
            int id = parts.id[at];
            if (anchor[id] < 0) anchor[id] = at;
            if (scoped[at]) inScope[id]++;
            int i = at % gw, j = at / gw;
            if (inBox(i, j, margin, bw) && (i == margin || j == margin || i == margin + bw - 1 || j == margin + bw - 1)) touchesBox[id] = true;
        }
        var kept = new ArrayList<Integer>();
        int unresolved = 0;
        for (int id = 1; id < count0; id++) {
            if (parts.area[id] < MIN_ISLAND || inScope[id] * 2 < parts.area[id] || !inBox(anchor[id] % gw, anchor[id] / gw, margin, bw)) continue;
            if (!border[id]) kept.add(id);
            else if (parts.area[id] < MAINLAND_CELLS) unresolved++;
        }
        double coastLength = 0;
        for (int at = 0; at < land.length; at++) {
            int id = parts.id[at], i = at % gw, j = at / gw;
            if (!land[at] || !scoped[at] || !border[id] || parts.area[id] < MAINLAND_CELLS || !inBox(i, j, margin, bw)) continue;
            if (i > 0 && !land[at - 1]) coastLength += GRID;
            if (i + 1 < gw && !land[at + 1]) coastLength += GRID;
            if (j > 0 && !land[at - gw]) coastLength += GRID;
            if (j + 1 < gw && !land[at + gw]) coastLength += GRID;
        }
        int count = kept.size();
        var slot = new java.util.HashMap<Integer, Integer>();
        for (int k = 0; k < count; k++) slot.put(kept.get(k), k);
        double[] diameters = new double[count], tops = new double[count], steepest = new double[count], lengths = new double[count], widths = new double[count];
        boolean[] chained = new boolean[count], creek = new boolean[count], interior = new boolean[count];
        long[] anchors = new long[count];
        var cell = new Cell();
        double[] heights = new double[gw * gw];
        Arrays.fill(heights, Double.NaN);
        // Column lookups grouped by owner: the continent keeps only a few owner plans, and alternating owners row by row would rebuild them.
        var byOwner = new java.util.TreeMap<Long, List<Integer>>();
        for (int at = 0; at < land.length; at++) {
            if (!land[at] || !slot.containsKey(parts.id[at])) continue;
            var s = coast.sample(x0 + at % gw * GRID, z0 + at / gw * GRID);
            byOwner.computeIfAbsent(((long) s.ownerX() << 32) ^ (s.ownerZ() & 0xffffffffL), key -> new ArrayList<>()).add(at);
        }
        for (var cells : byOwner.values()) for (int at : cells) {
            Integer k = slot.get(parts.id[at]);
            int i = at % gw, j = at / gw;
            context.lookup.applyCell(cell.reset(), x0 + i * GRID, z0 + j * GRID, false, true);
            double h = (cell.height - context.levels.water) * context.levels.terrainScaleFactor;
            heights[at] = h;
            tops[k] = Math.max(tops[k], h);
            if (cell.hasRiverWater) creek[k] = true;
        }
        int reach = CHAIN_GAP / GRID;
        for (int at = 0; at < land.length; at++) {
            int id = parts.id[at];
            Integer k = slot.get(id);
            if (!land[at] || k == null) continue;
            int i = at % gw, j = at / gw;
            if (i + 1 < gw && parts.id[at + 1] == id) steepest[k] = Math.max(steepest[k], Math.abs(heights[at + 1] - heights[at]) / GRID);
            if (j + 1 < gw && parts.id[at + gw] == id) steepest[k] = Math.max(steepest[k], Math.abs(heights[at + gw] - heights[at]) / GRID);
            if (chained[k]) continue;
            search:
            for (int dj = -reach; dj <= reach; dj++) for (int di = -reach; di <= reach; di++) {
                int nj = j + dj, ni = i + di;
                if (nj < 0 || ni < 0 || nj >= gw || ni >= gw || Math.hypot(di, dj) * GRID > CHAIN_GAP) continue;
                int other = parts.id[nj * gw + ni];
                if (other == 0 || other == id || border[other]) continue;
                chained[k] = true;
                Integer o = slot.get(other);
                if (o != null) chained[o] = true;
                break search;
            }
        }
        for (int k = 0; k < count; k++) {
            int id = kept.get(k);
            diameters[k] = 2 * Math.sqrt(parts.area[id] * (double) GRID * GRID / Math.PI);
            anchors[k] = ((long) (x0 + anchor[id] % gw * GRID) << 32) ^ ((z0 + anchor[id] / gw * GRID) & 0xffffffffL);
            interior[k] = !touchesBox[id];
        }
        shapes(parts.id, gw, slot, lengths, widths);
        // Channel plan nodes with at least the creek area: a drawn creek (or river) on the island.
        double creekArea = continent.drainage().creekArea();
        for (var cells : byOwner.values()) {
            int first = cells.get(0);
            var s = coast.sample(x0 + first % gw * GRID, z0 + first / gw * GRID);
            for (var node : continent.planOf(s.ownerX(), s.ownerZ()).plan().nodes()) {
                if (node.area() < creekArea) continue;
                int i = (int) Math.round((node.x() - x0) / GRID), j = (int) Math.round((node.z() - z0) / GRID);
                if (i < 0 || j < 0 || i >= gw || j >= gw) continue;
                Integer k = slot.get(parts.id[j * gw + i]);
                if (k != null && land[j * gw + i]) creek[k] = true;
            }
        }
        return new Islands(count, diameters, tops, steepest, chained, creek, regionArea, anchors, lengths, widths, interior, unresolved, pad, eligibleArea, coastLength, 0);
    }

    /** Whether lattice cell (i, j) of a padded scan lies in the reporting box (the {@code bw}-cell square inside {@code margin} cells of padding). */
    private static boolean inBox(int i, int j, int margin, int bw) {
        return i >= margin && j >= margin && i < margin + bw && j < margin + bw;
    }

    /** Length and width (blocks) of the islands of {@code slot}: the extents of their cells along the principal axes, a cell counting its {@link #GRID} width. */
    private static void shapes(int[] id, int gw, java.util.Map<Integer, Integer> slot, double[] lengths, double[] widths) {
        int n = lengths.length;
        double[] cnt = new double[n], sx = new double[n], sy = new double[n], sxx = new double[n], syy = new double[n], sxy = new double[n];
        for (int at = 0; at < id.length; at++) {
            Integer k = slot.get(id[at]);
            if (k == null || id[at] == 0) continue;
            double x = at % gw, y = at / gw;
            cnt[k]++; sx[k] += x; sy[k] += y; sxx[k] += x * x; syy[k] += y * y; sxy[k] += x * y;
        }
        double[] cos = new double[n], sin = new double[n], lo = new double[n], hi = new double[n], loB = new double[n], hiB = new double[n];
        for (int k = 0; k < n; k++) {
            double mx = sx[k] / cnt[k], my = sy[k] / cnt[k], vx = sxx[k] / cnt[k] - mx * mx, vy = syy[k] / cnt[k] - my * my, c = sxy[k] / cnt[k] - mx * my;
            double angle = .5 * Math.atan2(2 * c, vx - vy);
            cos[k] = Math.cos(angle); sin[k] = Math.sin(angle);
            lo[k] = loB[k] = Double.MAX_VALUE; hi[k] = hiB[k] = -Double.MAX_VALUE;
        }
        for (int at = 0; at < id.length; at++) {
            Integer k = slot.get(id[at]);
            if (k == null || id[at] == 0) continue;
            double x = at % gw, y = at / gw, along = x * cos[k] + y * sin[k], across = -x * sin[k] + y * cos[k];
            lo[k] = Math.min(lo[k], along); hi[k] = Math.max(hi[k], along);
            loB[k] = Math.min(loB[k], across); hiB[k] = Math.max(hiB[k], across);
        }
        for (int k = 0; k < n; k++) { lengths[k] = (hi[k] - lo[k] + 1) * GRID; widths[k] = (hiB[k] - loB[k] + 1) * GRID; }
    }

    /**
     * Open-sea islands (11-06) of a square scan: land (bound coast) outside every drowned region whose standalone edge is sea,
     * labelled 4-connected on a {@code step}-block lattice (components touching the scan border dropped). Per island: its owner,
     * its largest extent ({@code across}, blocks) and one coast column. {@code belowGuard} counts island land cells whose standalone
     * edge is below the guard edge, {@code outOfBounds} the ones outside their owner's plan bounds, and {@code fullOwners} the owners with at
     * least {@code fullOwnerSea} sea cells in the scan (the ones the scan covers mostly) with {@code inFullOwners} islands among them.
     */
    record OpenIsland(int ownerX, int ownerZ, double across, int[] shore) {}
    record OpenIslands(List<OpenIsland> islands, int fullOwners, int inFullOwners, int belowGuard, int outOfBounds) {
        double perOwner() { return fullOwners > 0 ? (double) inFullOwners / fullOwners : Double.NaN; }
        String summary() {
            double[] across = islands.stream().mapToDouble(OpenIsland::across).sorted().toArray();
            return String.format(Locale.ROOT, "openIslands=%d owners(full)=%d perOwner=%.2f across min=%.0f median=%.0f max=%.0f belowGuard=%d outOfBounds=%d",
                islands.size(), fullOwners, perOwner(), across.length > 0 ? across[0] : Double.NaN, across.length > 0 ? HighlandCoastProbe.quantile(across, .5) : Double.NaN,
                across.length > 0 ? across[across.length - 1] : Double.NaN, belowGuard, outOfBounds);
        }
    }

    static OpenIslands openIslands(GeneratorContext context, int originX, int originZ, int radius, int step, int fullOwnerSea) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var standalone = new HighlandCoast(context);
        var controls = context.preset.world().controlPoints;
        double coastEdge = controls.coast, guard = HighlandDrowned.OPEN_GUARD * controls.deepOcean;
        int gw = 2 * radius / step + 1, x0 = originX - radius, z0 = originZ - radius;
        boolean[] island = new boolean[gw * gw], low = new boolean[gw * gw];
        int[] ownerX = new int[gw * gw], ownerZ = new int[gw * gw];
        boolean[] sea = new boolean[gw * gw];
        IntStream.range(0, gw).parallel().forEach(j -> {
            for (int i = 0; i < gw; i++) {
                double x = x0 + i * step, z = z0 + j * step;
                var sample = coast.sample(x, z);
                int at = j * gw + i;
                ownerX[at] = sample.ownerX(); ownerZ[at] = sample.ownerZ();
                sea[at] = sample.edge() > 0 && sample.edge() <= coastEdge;
                if (sample.edge() <= coastEdge || sample.drowned() > 0 || coast.drowned().openIsland(x, z) <= 0) continue;
                double base = standalone.sample(x, z).edge();
                if (base > coastEdge) continue;
                island[at] = true;
                low[at] = base < guard;
            }
        });
        var seaCells = new java.util.HashMap<Long, Integer>();
        for (int at = 0; at < sea.length; at++) if (sea[at]) seaCells.merge(((long) ownerX[at] << 32) ^ (ownerZ[at] & 0xffffffffL), 1, Integer::sum);
        var parts = label(island, gw);
        boolean[] border = borderTouching(parts.id, gw, parts.area.length - 1);
        int count = parts.area.length - 1;
        int[] minI = new int[count + 1], maxI = new int[count + 1], minJ = new int[count + 1], maxJ = new int[count + 1];
        java.util.Arrays.fill(minI, Integer.MAX_VALUE); java.util.Arrays.fill(minJ, Integer.MAX_VALUE);
        int[][] shore = new int[count + 1][];
        int belowGuard = 0, outOfBounds = 0;
        var bounds = new java.util.HashMap<Long, HighlandCoast.Bounds>();
        for (int at = 0; at < island.length; at++) {
            if (!island[at]) continue;
            int id = parts.id[at], i = at % gw, j = at / gw;
            minI[id] = Math.min(minI[id], i); maxI[id] = Math.max(maxI[id], i); minJ[id] = Math.min(minJ[id], j); maxJ[id] = Math.max(maxJ[id], j);
            if (shore[id] == null && ((i > 0 && !island[at - 1]) || (i < gw - 1 && !island[at + 1]) || (j > 0 && !island[at - gw]) || (j < gw - 1 && !island[at + gw]))) shore[id] = new int[]{x0 + i * step, z0 + j * step};
            if (low[at]) belowGuard++;
            int ox = ownerX[at], oz = ownerZ[at];
            var b = bounds.computeIfAbsent(((long) ox << 32) ^ (oz & 0xffffffffL), key -> coast.bounds(ox, oz));
            double x = x0 + i * step, z = z0 + j * step;
            if (x < b.minX() || x > b.maxX() || z < b.minZ() || z > b.maxZ()) outOfBounds++;
        }
        var islands = new ArrayList<OpenIsland>();
        int fullOwners = 0, inFull = 0;
        for (var entry : seaCells.entrySet()) if (entry.getValue() >= fullOwnerSea) fullOwners++;
        for (int id = 1; id <= count; id++) {
            if (border[id]) continue;
            int first = -1;
            for (int at = 0; at < island.length && first < 0; at++) if (parts.id[at] == id) first = at;
            double across = (Math.max(maxI[id] - minI[id], maxJ[id] - minJ[id]) + 1.0) * step;
            islands.add(new OpenIsland(ownerX[first], ownerZ[first], across, shore[id]));
            if (seaCells.getOrDefault(((long) ownerX[first] << 32) ^ (ownerZ[first] & 0xffffffffL), 0) >= fullOwnerSea) inFull++;
        }
        return new OpenIslands(islands, fullOwners, inFull, belowGuard, outOfBounds);
    }

    /** One island census over several boxes: lists concatenated (an island whose anchor appears in two overlapping boxes counted once), region areas summed. */
    static Islands poolIslands(List<Islands> parts) {
        var seen = new java.util.HashSet<Long>();
        var from = new ArrayList<int[]>();
        for (int p = 0; p < parts.size(); p++) for (int k = 0; k < parts.get(p).count(); k++) if (seen.add(parts.get(p).anchors()[k])) from.add(new int[] {p, k});
        int count = from.size();
        double[] diameters = new double[count], tops = new double[count], steepest = new double[count], lengths = new double[count], widths = new double[count];
        boolean[] chained = new boolean[count], creek = new boolean[count], interior = new boolean[count];
        long[] anchors = new long[count];
        for (int n = 0; n < count; n++) {
            var part = parts.get(from.get(n)[0]);
            int k = from.get(n)[1];
            diameters[n] = part.diameters()[k]; tops[n] = part.tops()[k]; steepest[n] = part.steepest()[k]; lengths[n] = part.lengths()[k]; widths[n] = part.widths()[k];
            chained[n] = part.chained()[k]; creek[n] = part.creek()[k]; interior[n] = part.interior()[k]; anchors[n] = part.anchors()[k];
        }
        return new Islands(count, diameters, tops, steepest, chained, creek, parts.stream().mapToDouble(Islands::regionArea).sum(), anchors, lengths, widths, interior,
            parts.stream().mapToInt(Islands::unresolved).sum(), parts.stream().mapToInt(Islands::pad).max().orElse(0), parts.stream().mapToDouble(Islands::eligibleArea).sum(),
            parts.stream().mapToDouble(Islands::coastLength).sum(), parts.stream().mapToLong(Islands::nanos).sum());
    }

    private static double height(GeneratorContext context, Cell cell, double x, double z) {
        context.lookup.applyCell(cell.reset(), (int) Math.round(x), (int) Math.round(z), false, true);
        return (cell.height - context.levels.water) * context.levels.terrainScaleFactor;
    }

    private static double medianOf(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return sorted.length > 0 ? HighlandCoastProbe.quantile(sorted, .5) : 0;
    }

    private static boolean touches(boolean[] mask, int w, int at) {
        int i = at % w, j = at / w;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            int ni = i + di, nj = j + dj;
            if (ni >= 0 && nj >= 0 && ni < w && nj < w && mask[nj * w + ni]) return true;
        }
        return false;
    }

    private static boolean localMax(float[] g, boolean[] arm, int w, int at) {
        int i = at % w, j = at / w;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            int ni = i + di, nj = j + dj;
            if (ni >= 0 && nj >= 0 && ni < w && nj < w && arm[nj * w + ni] && g[nj * w + ni] > g[at]) return false;
        }
        return true;
    }

    /** Largest distance to land (cells) over the arm columns within two cells of a column: the local half width. */
    private static float windowMax(float[] dLand, boolean[] arm, int w, int at) {
        int i = at % w, j = at / w;
        float best = 0;
        for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
            int ni = i + di, nj = j + dj;
            if (ni >= 0 && nj >= 0 && ni < w && nj < w && arm[nj * w + ni]) best = Math.max(best, dLand[nj * w + ni]);
        }
        return best;
    }

    /**
     * A medial pixel of flooded land that is farther from a plan0 coastal axis than its local half width: its world position, the
     * distance to the nearest axis (infinite when none is within reach) and the half width, in blocks.
     */
    record Outlier(int x, int z, double distance, double halfWidth) {}

    /**
     * D-GC2 fidelity of flooded land against the plan0 valleys on one box. Flooded land is land of the standalone coast that is sea on
     * the bound coast (arms, with their basins and sounds); chamfer distance to dry cells gives the local half width; medial pixels are
     * flooded pixels at least {@link #FIDELITY_MIN_DISTANCE} cells from dry land that no 8-neighbour exceeds, except those whose distance
     * circle reaches the box border (their true maximum may lie outside; counted in {@code clipped}). A medial pixel is {@code within} when
     * the nearest plan0 coastal axis of its owner is at most its half width away. A component of at least {@link #FIDELITY_MIN_COMPONENT}
     * pixels maps to the physical branches ("reaches", owner/branch id) of the axes its passing medial pixels lie on; a fringe (a
     * component with no medial pixel, thinner than four pixels, a shoreline sliver) has no medial axis and maps to the reach of the nearest axis
     * whose arm reach (its index radius, the widest half width the arm can have) holds one of its local maxima; a component with no usable
     * pixel (all clipped by the border), or with a clipped medial pixel and no mapped one (11-13: a wide basin cut by the border leaves only its
     * narrow tongue inside, whose medial pixels cannot stand for the body outside), is {@code clippedComponents}, exempt: its pixels count neither
     * as medial nor as within. {@code mapping} has one line per component, {@code unmapped} counts those without a reach.
     */
    record Fidelity(int boxX, int boxZ, int medial, int within, int clipped, int components, int unmapped, int clippedComponents,
                    List<Outlier> outliers, List<String> mapping, java.util.Set<String> reaches) {
        double share() { return within / (double) Math.max(1, medial); }
        String summary(String label) {
            return String.format(Locale.ROOT, "fidelity %s box %d,%d: medial=%d (%.1f km) within=%d share=%.3f outliers=%d clippedMedial=%d components=%d unmapped=%d clippedComponents=%d reaches=%d",
                label, boxX, boxZ, medial, medial * (double) FIDELITY_PIXEL / 1000, within, share(), outliers.size(), clipped, components, unmapped, clippedComponents, reaches.size());
        }
    }
    static final int FIDELITY_PIXEL = 8, FIDELITY_MIN_COMPONENT = 4, FIDELITY_MIN_DISTANCE = 2;

    static Fidelity fidelity(GeneratorContext context, int originX, int originZ, int radius) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var drowned = coast.drowned();
        var standalone = new HighlandCoast(context);
        double coastEdge = context.preset.world().controlPoints.coast;
        int p = FIDELITY_PIXEL, w = 2 * radius / p + 1, x0 = originX - radius, z0 = originZ - radius;
        boolean[] flooded = new boolean[w * w], dry = new boolean[w * w];
        IntStream.range(0, w).parallel().forEach(j -> {
            for (int i = 0; i < w; i++) {
                double x = x0 + i * p, z = z0 + j * p;
                flooded[j * w + i] = standalone.sample(x, z).edge() > coastEdge && coast.sample(x, z).edge() <= coastEdge;
                dry[j * w + i] = !flooded[j * w + i];
            }
        });
        float[] d = distanceToLand(dry, w);
        var parts = label(flooded, w);
        boolean[] big = new boolean[parts.area.length], hasMedial = new boolean[parts.area.length], usable = new boolean[parts.area.length], mapped = new boolean[parts.area.length], cut = new boolean[parts.area.length];
        int[] medialOf = new int[parts.area.length], withinOf = new int[parts.area.length];
        var outlierIds = new ArrayList<Integer>();
        int components = 0;
        for (int id = 1; id < big.length; id++) if (parts.area[id] >= FIDELITY_MIN_COMPONENT) { big[id] = true; components++; }
        boolean[] localMax = new boolean[w * w];
        for (int at = 0; at < flooded.length; at++) {
            if (!flooded[at] || !big[parts.id[at]] || d[at] < 1 || !fidelityMax(d, flooded, w, at)) continue;
            localMax[at] = true;
            if (d[at] >= FIDELITY_MIN_DISTANCE) hasMedial[parts.id[at]] = true;
        }
        var reachOf = new java.util.TreeMap<Integer, java.util.TreeSet<String>>();
        var all = new java.util.TreeSet<String>();
        var outliers = new ArrayList<Outlier>();
        int medial = 0, within = 0, clipped = 0;
        for (int at = 0; at < localMax.length; at++) {
            if (!localMax[at]) continue;
            int id = parts.id[at], i = at % w, j = at / w;
            boolean isMedial = d[at] >= FIDELITY_MIN_DISTANCE;
            if (!isMedial && hasMedial[id]) continue;
            if (Math.min(Math.min(i, j), Math.min(w - 1 - i, w - 1 - j)) < d[at] + 1) { if (isMedial) { clipped++; cut[id] = true; } continue; }
            double x = x0 + i * p, z = z0 + j * p, halfWidth = d[at] * p, nearest = Double.POSITIVE_INFINITY;
            var site = coast.undrowned(x, z);
            var arms = drowned.arms(site.ownerX(), site.ownerZ());
            var hits = arms.query(x, z);
            int reach = -1;
            for (int k = 0; k < hits.count; k++) if (hits.distance[k] < nearest) { nearest = hits.distance[k]; reach = arms.reach[hits.segment[k]]; }
            boolean pass = isMedial ? nearest <= halfWidth : nearest < Double.POSITIVE_INFINITY;
            usable[id] = true;
            if (isMedial) {
                medial++; medialOf[id]++;
                if (pass) { within++; withinOf[id]++; } else { outliers.add(new Outlier((int) x, (int) z, nearest, halfWidth)); outlierIds.add(id); }
            }
            if (pass) {
                mapped[id] = true;
                String key = site.ownerX() + "," + site.ownerZ() + "/" + reach;
                reachOf.computeIfAbsent(id, k -> new java.util.TreeSet<>()).add(key);
                all.add(key);
            }
        }
        var mapping = new ArrayList<String>();
        int unmapped = 0, clippedComponents = 0;
        long[] sumX = new long[big.length], sumZ = new long[big.length];
        for (int at = 0; at < flooded.length; at++) if (flooded[at]) { sumX[parts.id[at]] += at % w; sumZ[parts.id[at]] += at / w; }
        for (int id = 1; id < big.length; id++) {
            if (!big[id]) continue;
            String where = String.format(Locale.ROOT, "%d,%d pixels=%d", x0 + (int) (sumX[id] / parts.area[id]) * p, z0 + (int) (sumZ[id] / parts.area[id]) * p, parts.area[id]);
            if (!usable[id] || (!mapped[id] && cut[id])) {
                clippedComponents++; medial -= medialOf[id]; within -= withinOf[id]; // judged by nobody: its pixels count neither for nor against
                for (int k = outlierIds.size() - 1; k >= 0; k--) if (outlierIds.get(k) == id) { outliers.remove(k); outlierIds.remove(k); }
                mapping.add("component at " + where + " clipped by the box border (exempt)");
                continue;
            }
            if (!mapped[id]) unmapped++;
            mapping.add("component at " + where + " reaches " + reachOf.getOrDefault(id, new java.util.TreeSet<>()) + (mapped[id] ? "" : " UNMAPPED"));
        }
        outliers.sort(Comparator.comparingDouble(Outlier::distance).reversed());
        return new Fidelity(originX, originZ, medial, within, clipped, components, unmapped, clippedComponents, outliers, mapping, all);
    }

    /** True when no in-box 8-neighbour of a flooded cell has a larger distance (a medial plateau cell counts). */
    private static boolean fidelityMax(float[] d, boolean[] flooded, int w, int at) {
        int i = at % w, j = at / w;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            int ni = i + di, nj = j + dj;
            if ((di == 0 && dj == 0) || ni < 0 || nj < 0 || ni >= w || nj >= w) continue;
            if (flooded[nj * w + ni] && d[nj * w + ni] > d[at]) return false;
        }
        return true;
    }

    /** Pooled census of several boxes: counts summed, outliers and mapping lines concatenated. */
    static Fidelity poolFidelity(List<Fidelity> parts) {
        int medial = 0, within = 0, clipped = 0, components = 0, unmapped = 0, clippedComponents = 0;
        var outliers = new ArrayList<Outlier>();
        var mapping = new ArrayList<String>();
        var reaches = new java.util.TreeSet<String>();
        for (var part : parts) {
            medial += part.medial; within += part.within; clipped += part.clipped; components += part.components; unmapped += part.unmapped;
            clippedComponents += part.clippedComponents; outliers.addAll(part.outliers); mapping.addAll(part.mapping); reaches.addAll(part.reaches);
        }
        return new Fidelity(0, 0, medial, within, clipped, components, unmapped, clippedComponents, outliers, mapping, reaches);
    }

    /**
     * Regions that flood at least {@code minFlooded} pixels (16 blocks) around the given 8 km box centres: the dominant ria or fjord
     * region on a 500-block lattice. (Copied from the untracked DrownedShapeProbe, which scans the whole server window.)
     */
    static List<HighlandDrowned.Region> floodingRegions(GeneratorContext context, int[][] boxes, int radius, int minFlooded) {
        return floodingRegions(context, boxes, radius, minFlooded, 16);
    }

    /** As above, counting the flooded pixels of a region on a {@code step}-block lattice (scaled to 16-block pixels), for wide windows. */
    static List<HighlandDrowned.Region> floodingRegions(GeneratorContext context, int[][] boxes, int radius, int minFlooded, int step) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double coastEdge = context.preset.world().controlPoints.coast, scale = (step / 16.) * (step / 16.);
        var set = new java.util.LinkedHashSet<HighlandDrowned.Region>();
        for (int[] box : boxes) for (double z = box[1] - radius; z <= box[1] + radius; z += 500) for (double x = box[0] - radius; x <= box[0] + radius; x += 500) {
            var r = coast.drowned().dominant(x, z, false);
            if (r != null && regionWeight(r, x, z) > .5) set.add(r);
        }
        var out = new ArrayList<HighlandDrowned.Region>();
        for (var r : set) {
            int n = 0;
            for (double z = r.cz() - r.radius(); z <= r.cz() + r.radius(); z += step) for (double x = r.cx() - r.radius(); x <= r.cx() + r.radius(); x += step) {
                if (Math.hypot(x - r.cx(), z - r.cz()) > r.radius()) continue;
                if (coast.sample(x, z).edge() <= coastEdge && coast.undrowned(x, z).edge() > coastEdge) n++;
            }
            if (n * scale >= minFlooded) out.add(r);
        }
        return out;
    }

    /**
     * Land between arms (11-14, G-11-4): transects every {@link #STRIP_LINE} blocks along both axes of a box on a {@link #STRIP_STEP}-block
     * lattice. A run is land (bound coast) between two flooded columns (sea on the bound coast, land on the unbound one) with nothing but that land
     * between them. Per run: its width, whether it is mainland (the unbound coast is land at its middle) or a raised island, its scope (FJORD
     * where the fjord weight there is above 0 and at least the ria weight, else RIA) and, for runs of at most {@link #STRIP_PEAK_MAX} blocks, the
     * peak ground (blocks above sea level, real column lookups) over up to {@link #STRIP_PEAK_POINTS} points along it (the diagnosis method of the
     * untracked DrownedShapeProbe.strips()).
     */
    record Strip(double width, boolean mainland, boolean fjord, double peak, double x, double z) {}
    static final int STRIP_LINE = 32, STRIP_STEP = 4, STRIP_PEAK_MAX = 150, STRIP_PEAK_POINTS = 12;

    static List<Strip> strips(GeneratorContext context, int[][] boxes, int radius) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var drowned = coast.drowned();
        double coastEdge = context.preset.world().controlPoints.coast;
        var found = new java.util.concurrent.ConcurrentLinkedQueue<Strip>();
        int n = 2 * radius / STRIP_STEP + 1, lines = 2 * radius / STRIP_LINE + 1;
        for (int[] box : boxes) {
            IntStream.range(0, 2 * lines).parallel().forEach(line -> {
                boolean alongX = line < lines;
                double across = (alongX ? box[1] : box[0]) - radius + (line % lines) * STRIP_LINE;
                var cell = new Cell();
                int state = 0, start = 0;
                for (int k = 0; k < n; k++) {
                    double along = (alongX ? box[0] : box[1]) - radius + k * STRIP_STEP;
                    double x = alongX ? along : across, z = alongX ? across : along;
                    double edge = coast.sample(x, z).edge();
                    boolean land = edge > coastEdge, flooded = !land && edge > 0 && coast.undrowned(x, z).edge() > coastEdge;
                    if (flooded) {
                        if (state == 2) found.add(strip(context, coast, drowned, cell, alongX, across, box, radius, start, k, coastEdge));
                        state = 1;
                    } else if (!land) state = 0;
                    else if (state == 1) { state = 2; start = k; }
                }
            });
        }
        return new ArrayList<>(found);
    }

    private static Strip strip(GeneratorContext context, HighlandCoast coast, HighlandDrowned drowned, Cell cell, boolean alongX, double across, int[] box, int radius, int start, int end, double coastEdge) {
        double first = (alongX ? box[0] : box[1]) - radius + start * STRIP_STEP, width = (end - start) * (double) STRIP_STEP, mid = first + width / 2;
        double mx = alongX ? mid : across, mz = alongX ? across : mid;
        boolean mainland = coast.undrowned(mx, mz).edge() > coastEdge;
        double fjord = drowned.fjord(mx, mz), ria = drowned.ria(mx, mz), peak = Double.NaN;
        if (width <= STRIP_PEAK_MAX) {
            peak = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < STRIP_PEAK_POINTS; p++) {
                double a = first + (p + .5) * width / STRIP_PEAK_POINTS;
                peak = Math.max(peak, height(context, cell, alongX ? a : across, alongX ? across : a));
            }
        }
        return new Strip(width, mainland, fjord > 0 && fjord >= ria, peak, mx, mz);
    }

    /**
     * Leftover knolls (11-14, G-11-2 e): land components of a box on a {@code step}-block lattice that do not touch the box border, are under 1 km2
     * and at least {@link #KNOLL_MIN_CELLS} cells, are walled in by flooded sea (at least 95% of the sea cells beside them flooded), are standing land of
     * the unbound coast (at least 90% of their cells; a raised island, chain or open-sea, is land only where the unbound coast is sea, and the island weight
     * fields {@code island()} and {@code openIsland()} are above 0 beyond the windowed raise, so they cannot be the test) and lie outside sound
     * basins (within {@code SOUND_RADIUS} of a sound centre). Each is reported as
     * {@code x,z area}, the centroid and its area in blocks2.
     */
    static final int KNOLL_MIN_CELLS = 4;
    static final double KNOLL_MAX_AREA = 1e6;

    /** The knolls of a box and how the small land components (not on the border, under 1 km2) were sorted: {@code candidates}, those raised (island chain or open sea), those not walled in by flooded sea, those in a sound basin. */
    record Knolls(List<double[]> found, int candidates, int raised, int notWalled, int inSound) {}

    static Knolls knolls(GeneratorContext context, int originX, int originZ, int radius, int step) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var drowned = coast.drowned();
        double coastEdge = context.preset.world().controlPoints.coast;
        int w = 2 * radius / step + 1, x0 = originX - radius, z0 = originZ - radius;
        boolean[] land = new boolean[w * w], flooded = new boolean[w * w];
        IntStream.range(0, w).parallel().forEach(j -> {
            for (int i = 0; i < w; i++) {
                double x = x0 + i * step, z = z0 + j * step, edge = coast.sample(x, z).edge();
                land[j * w + i] = edge > coastEdge;
                flooded[j * w + i] = edge > 0 && edge <= coastEdge && coast.undrowned(x, z).edge() > coastEdge;
            }
        });
        var parts = label(land, w);
        boolean[] border = borderTouching(parts.id, w, parts.area.length - 1);
        int count = parts.area.length - 1;
        long[] sea = new long[count + 1], floodedSea = new long[count + 1];
        double[] sx = new double[count + 1], sz = new double[count + 1];
        int[] standalone = new int[count + 1];
        for (int at = 0; at < land.length; at++) {
            int id = parts.id[at];
            if (id == 0 || border[id] || parts.area[id] < KNOLL_MIN_CELLS || parts.area[id] * (double) step * step >= KNOLL_MAX_AREA) continue;
            int i = at % w, j = at / w;
            double x = x0 + i * step, z = z0 + j * step;
            sx[id] += x; sz[id] += z;
            if (coast.undrowned(x, z).edge() > coastEdge) standalone[id]++;
            for (int k = 0; k < 4; k++) {
                int ni = i + (k == 0 ? 1 : k == 1 ? -1 : 0), nj = j + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (land[nj * w + ni]) continue; // a component off the border has all four neighbours in the lattice
                sea[id]++;
                if (flooded[nj * w + ni]) floodedSea[id]++;
            }
        }
        var found = new ArrayList<double[]>();
        int candidates = 0, raisedCount = 0, notWalled = 0, inSound = 0;
        for (int id = 1; id <= count; id++) {
            if (border[id] || parts.area[id] < KNOLL_MIN_CELLS || parts.area[id] * (double) step * step >= KNOLL_MAX_AREA) continue;
            candidates++;
            if (standalone[id] < .9 * parts.area[id]) { raisedCount++; continue; }
            if (floodedSea[id] < .95 * sea[id]) { notWalled++; continue; }
            double cx = sx[id] / parts.area[id], cz = sz[id] / parts.area[id];
            var sound = drowned.dominant(cx, cz, true);
            if (sound != null && !Double.isNaN(sound.soundX()) && Math.hypot(cx - sound.soundX(), cz - sound.soundZ()) < HighlandDrowned.SOUND_RADIUS) { inSound++; continue; }
            found.add(new double[] {cx, cz, parts.area[id] * (double) step * step});
        }
        return new Knolls(found, candidates, raisedCount, notWalled, inSound);
    }

    /** Rim census (11-13): rays from a region's centre, the step along a ray, and how far out (in region radii) the outermost flooded land is looked for; a ray counts when it has flooded land beyond half a radius. */
    static final int RIM_RAYS = 80, RIM_STEP = 8;
    static final double RIM_FAR = 1.4;

    /**
     * {@code rays}: rays with flooded land beyond half a radius; {@code mean}, {@code sd}: the outermost flooded-land radius over those
     * rays, in radii of the region. A flooded column (land on the standalone coast, sea on the bound one) counts for the region with the
     * largest effective weight there, so a neighbouring region's flood is not taken for this region's rim.
     */
    record Rim(int rays, double mean, double sd) {}

    static Rim rim(GeneratorContext context, HighlandDrowned.Region r) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        double[] reach = new double[RIM_RAYS];
        IntStream.range(0, RIM_RAYS).parallel().forEach(k -> {
            double angle = 2 * Math.PI * k / RIM_RAYS, cos = Math.cos(angle), sin = Math.sin(angle), best = -1;
            for (double d = .3 * r.radius(); d <= RIM_FAR * r.radius(); d += RIM_STEP) {
                double x = r.cx() + d * cos, z = r.cz() + d * sin;
                if (!(coast.sample(x, z).edge() <= coastEdge && coast.undrowned(x, z).edge() > coastEdge)) continue;
                var owner = coast.drowned().dominant(x, z, false);
                if (owner != null && owner.i() == r.i() && owner.j() == r.j()) best = d;
            }
            reach[k] = best > .5 * r.radius() ? best / r.radius() : Double.NaN;
        });
        double[] found = Arrays.stream(reach).filter(v -> !Double.isNaN(v)).toArray();
        double mean = Arrays.stream(found).average().orElse(0), sd = Math.sqrt(Arrays.stream(found).map(v -> (v - mean) * (v - mean)).average().orElse(0));
        return new Rim(found.length, mean, sd);
    }

    /** Every region (flooding or not) whose disc weight is above .5 somewhere on a 500-block lattice around the box centres. */
    static List<HighlandDrowned.Region> regionsAround(GeneratorContext context, int[][] boxes, int radius) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var set = new java.util.LinkedHashSet<HighlandDrowned.Region>();
        for (int[] box : boxes) for (double z = box[1] - radius; z <= box[1] + radius; z += 500) for (double x = box[0] - radius; x <= box[0] + radius; x += 500) {
            var r = coast.drowned().dominant(x, z, false);
            if (r != null && regionWeight(r, x, z) > .5) set.add(r);
        }
        return new ArrayList<>(set);
    }

    /**
     * Island fields (11-15, G-11-2 b): the outermost raised-island radius over {@link #RIM_RAYS} rays from a region's centre (as {@link #rim}, a ray counts
     * when it has island land beyond half a radius), and the radial distribution of raised-island land. Raised island land is land on the bound coast that
     * is sea on the unbound one (diagnosis ringStats()). An island column counts for the region with the largest effective weight there.
     */
    static Rim islandRim(GeneratorContext context, HighlandDrowned.Region r) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        double[] reach = new double[RIM_RAYS];
        IntStream.range(0, RIM_RAYS).parallel().forEach(k -> {
            double angle = 2 * Math.PI * k / RIM_RAYS, cos = Math.cos(angle), sin = Math.sin(angle), best = -1;
            for (double d = .3 * r.radius(); d <= RIM_FAR * r.radius(); d += RIM_STEP) {
                double x = r.cx() + d * cos, z = r.cz() + d * sin;
                if (!(coast.sample(x, z).edge() > coastEdge && coast.undrowned(x, z).edge() <= coastEdge)) continue;
                var owner = coast.drowned().dominant(x, z, false);
                if (owner != null && owner.i() == r.i() && owner.j() == r.j()) best = d;
            }
            reach[k] = best > .5 * r.radius() ? best / r.radius() : Double.NaN;
        });
        double[] found = Arrays.stream(reach).filter(v -> !Double.isNaN(v)).toArray();
        double mean = Arrays.stream(found).average().orElse(0), sd = Math.sqrt(Arrays.stream(found).map(v -> (v - mean) * (v - mean)).average().orElse(0));
        return new Rim(found.length, mean, sd);
    }

    /** Share of raised-island land per tenth of a region radius (index 0 is 0 to .1 R) on a 12-block lattice over the nominal disc. */
    static double[] islandBands(GeneratorContext context, HighlandDrowned.Region r) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        int[] land = new int[10], total = new int[10];
        int step = 12, n = (int) (2 * r.radius() / step) + 1;
        double x0 = r.cx() - r.radius(), z0 = r.cz() - r.radius();
        int[][] part = new int[n][20];
        IntStream.range(0, n).parallel().forEach(j -> {
            for (int i = 0; i < n; i++) {
                double x = x0 + i * step, z = z0 + j * step, d = Math.hypot(x - r.cx(), z - r.cz()) / r.radius();
                if (d >= 1) continue;
                int band = (int) (d * 10);
                part[j][band]++;
                if (coast.sample(x, z).edge() > coastEdge && coast.undrowned(x, z).edge() <= coastEdge) part[j][10 + band]++;
            }
        });
        for (int[] row : part) for (int b = 0; b < 10; b++) { total[b] += row[b]; land[b] += row[10 + b]; }
        double[] share = new double[10];
        for (int b = 0; b < 10; b++) share[b] = total[b] > 0 ? (double) land[b] / total[b] : 0;
        return share;
    }

    /** Annulus of the angular coverage census, as fractions of a region radius. */
    static final double SECTOR_FROM = .3, SECTOR_TO = .9;

    /**
     * Angular coverage of island land (11-15, D-11-15-2): the annulus {@link #SECTOR_FROM} to {@link #SECTOR_TO} R of a region cut into {@code rings} concentric
     * rings and {@code sectors} bearing sectors, on a 12-block lattice; per (ring, sector) cell the share of its lattice points that are raised-island land
     * (as {@link #islandBands}). A closed ring of island land fills one ring in every sector; a broken field does not.
     */
    static double[][] islandCoverage(GeneratorContext context, HighlandDrowned.Region r, int rings, int sectors) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        int step = 12, n = (int) (2 * SECTOR_TO * r.radius() / step) + 1, cells = rings * sectors;
        double x0 = r.cx() - SECTOR_TO * r.radius(), z0 = r.cz() - SECTOR_TO * r.radius();
        int[][] part = new int[n][2 * cells];
        IntStream.range(0, n).parallel().forEach(j -> {
            for (int i = 0; i < n; i++) {
                double x = x0 + i * step, z = z0 + j * step, d = Math.hypot(x - r.cx(), z - r.cz()) / r.radius();
                if (d < SECTOR_FROM || d >= SECTOR_TO) continue;
                int ring = Math.min(rings - 1, (int) ((d - SECTOR_FROM) / (SECTOR_TO - SECTOR_FROM) * rings));
                int sector = Math.min(sectors - 1, (int) ((Math.atan2(z - r.cz(), x - r.cx()) + Math.PI) / (2 * Math.PI) * sectors));
                part[j][ring * sectors + sector]++;
                if (coast.sample(x, z).edge() > coastEdge && coast.undrowned(x, z).edge() <= coastEdge) part[j][cells + ring * sectors + sector]++;
            }
        });
        double[][] share = new double[rings][sectors];
        for (int c = 0; c < cells; c++) {
            int total = 0, land = 0;
            for (int[] row : part) { total += row[c]; land += row[cells + c]; }
            share[c / sectors][c % sectors] = total > 0 ? (double) land / total : 0;
        }
        return share;
    }

    /**
     * The longest unbroken arc of a coverage matrix ({@link #islandCoverage}): the most consecutive bearing sectors (cyclic) of ONE ring whose island share is
     * at least {@code cover}. A closed ring of island land is an arc of every sector the shelf window reaches; a broken field is many short arcs.
     */
    static int longestArc(double[][] coverage, double cover) {
        int best = 0;
        for (double[] ring : coverage) {
            int n = ring.length, start = -1;
            for (int k = 0; k < n; k++) if (ring[k] < cover) { start = k; break; }
            if (start < 0) { best = Math.max(best, n); continue; }
            int run = 0;
            for (int k = 1; k <= n; k++) {
                if (ring[(start + k) % n] >= cover) best = Math.max(best, ++run);
                else run = 0;
            }
        }
        return best;
    }

    /**
     * Ruler-line islands (11-15, G-11-4): raised-island components of a box on a {@code step}-block lattice (4-connected, not touching the border) with their
     * width (twice the largest chamfer distance to the sea, blocks) and length (area over width); a ruler line is one at most {@link #RULER_WIDTH} wide and
     * at least {@link #RULER_LENGTH} long (diagnosis slivers(): 26 chain islands at 34272,-12747). {@code found} lists them as {x, z, width, length}.
     */
    record Rulers(int components, List<double[]> found) {}
    static final double RULER_WIDTH = 80, RULER_LENGTH = 300;

    static Rulers rulerLines(GeneratorContext context, int originX, int originZ, int radius, int step) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        int w = 2 * radius / step + 1, x0 = originX - radius, z0 = originZ - radius;
        boolean[] island = new boolean[w * w];
        IntStream.range(0, w).parallel().forEach(j -> {
            for (int i = 0; i < w; i++) {
                double x = x0 + i * step, z = z0 + j * step;
                island[j * w + i] = coast.sample(x, z).edge() > coastEdge && coast.undrowned(x, z).edge() <= coastEdge;
            }
        });
        var parts = label(island, w);
        boolean[] border = borderTouching(parts.id, w, parts.area.length - 1);
        float[] dist = new float[w * w];
        for (int at = 0; at < dist.length; at++) dist[at] = island[at] ? 1e9f : 0;
        for (int j = 0; j < w; j++) for (int i = 0; i < w; i++) {
            int at = j * w + i;
            if (!island[at]) continue;
            float m = dist[at];
            if (i > 0) m = Math.min(m, dist[at - 1] + 1);
            if (j > 0) m = Math.min(m, dist[at - w] + 1);
            if (i > 0 && j > 0) m = Math.min(m, dist[at - w - 1] + 1.414f);
            if (i < w - 1 && j > 0) m = Math.min(m, dist[at - w + 1] + 1.414f);
            dist[at] = m;
        }
        for (int j = w - 1; j >= 0; j--) for (int i = w - 1; i >= 0; i--) {
            int at = j * w + i;
            if (!island[at]) continue;
            float m = dist[at];
            if (i < w - 1) m = Math.min(m, dist[at + 1] + 1);
            if (j < w - 1) m = Math.min(m, dist[at + w] + 1);
            if (i < w - 1 && j < w - 1) m = Math.min(m, dist[at + w + 1] + 1.414f);
            if (i > 0 && j < w - 1) m = Math.min(m, dist[at + w - 1] + 1.414f);
            dist[at] = m;
        }
        int count = parts.area.length - 1;
        float[] widest = new float[count + 1];
        int[] at0 = new int[count + 1];
        for (int at = 0; at < island.length; at++) {
            int id = parts.id[at];
            if (id == 0 || dist[at] <= widest[id]) continue;
            widest[id] = dist[at];
            at0[id] = at;
        }
        var found = new ArrayList<double[]>();
        int components = 0;
        for (int id = 1; id <= count; id++) {
            if (border[id]) continue;
            components++;
            double width = 2 * widest[id] * step, length = parts.area[id] * (double) step * step / Math.max(step, width);
            if (width <= RULER_WIDTH && length >= RULER_LENGTH) found.add(new double[] {x0 + at0[id] % w * step, z0 + at0[id] / w * step, width, length});
        }
        return new Rulers(components, found);
    }

    /** Region disc weight (the warped disc, see HighlandDrowned.regionWeight). */
    private static double regionWeight(HighlandDrowned.Region r, double x, double z) { return HighlandDrowned.regionWeight(r, x, z); }

    /**
     * The two diagnosis measures of the rejected UAT, printed for comparison (not gated: plan0 valleys also run toward the sea, so an
     * angle share is not a defect test): per region type, the share of flooded-land boundary pixels (12-block lattice) whose tangent lies
     * within 15 degrees of the region's inland axis (isotropic 16.7%), and the arm spacing (centre-to-centre distance of consecutive
     * flooded runs across the inland axis, 6-block steps, runs of at least 18 blocks) with its coefficient of variation.
     * Copied from the untracked DrownedShapeProbe.stats() and spacing().
     */
    static List<String> diagnosisMeasures(GeneratorContext context, List<HighlandDrowned.Region> regions) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double ce = context.preset.world().controlPoints.coast;
        long[][] hist = new long[2][6];
        long[] total = new long[2];
        for (var r : regions) {
            int res = 12, px = (int) (2 * r.radius() / res) + 1;
            double x0 = r.cx() - r.radius(), z0 = r.cz() - r.radius();
            boolean[][] land = new boolean[px][px], flood = new boolean[px][px];
            IntStream.range(0, px).parallel().forEach(j -> {
                for (int i = 0; i < px; i++) {
                    double x = x0 + i * res, z = z0 + j * res;
                    boolean bound = coast.sample(x, z).edge() > ce, base = coast.undrowned(x, z).edge() > ce;
                    land[i][j] = bound && base;
                    flood[i][j] = !bound && base;
                }
            });
            long[] h = new long[6];
            long n = 0;
            for (int j = 3; j < px - 3; j++) for (int i = 3; i < px - 3; i++) {
                if (!flood[i][j] || regionWeight(r, x0 + i * res, z0 + j * res) < .5) continue;
                if (!(land[i + 1][j] || land[i - 1][j] || land[i][j + 1] || land[i][j - 1])) continue;
                double gx = 0, gz = 0;
                for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) if (land[i + di][j + dj]) { gx += di; gz += dj; }
                double length = Math.hypot(gx, gz);
                if (length == 0) continue;
                double a = Math.toDegrees(Math.acos(Math.min(1, Math.abs((-gz / length) * r.gx() + (gx / length) * r.gz()))));
                h[Math.min(5, (int) (a / 15))]++;
                n++;
            }
            int type = r.fjord() ? 0 : 1;
            for (int b = 0; b < 6; b++) hist[type][b] += h[b];
            total[type] += n;
        }
        var lines = new ArrayList<String>();
        lines.add("diagnosis regions=" + regions.size() + " (fjord " + regions.stream().filter(HighlandDrowned.Region::fjord).count() + ")");
        for (int type = 0; type < 2; type++) {
            var gaps = new ArrayList<Double>();
            var widths = new ArrayList<Double>();
            for (var r : regions) {
                if ((r.fjord() ? 0 : 1) != type) continue;
                double qx = -r.gz(), qz = r.gx();
                for (double s = -.6 * r.radius(); s <= .6 * r.radius(); s += 120) {
                    double bx = r.cx() + s * r.gx(), bz = r.cz() + s * r.gz(), prevCentre = Double.NaN, runStart = Double.NaN;
                    for (double a = -.9 * r.radius(); a <= .9 * r.radius(); a += 6) {
                        double x = bx + a * qx, z = bz + a * qz;
                        boolean flooded = regionWeight(r, x, z) >= .9 && coast.sample(x, z).edge() <= ce && coast.undrowned(x, z).edge() > ce;
                        if (flooded && Double.isNaN(runStart)) runStart = a;
                        if (!flooded && !Double.isNaN(runStart)) {
                            double centre = (runStart + a) / 2, width = a - runStart;
                            if (width >= 18) { widths.add(width); if (!Double.isNaN(prevCentre)) gaps.add(centre - prevCentre); prevCentre = centre; }
                            runStart = Double.NaN;
                        }
                    }
                }
            }
            double[] g = gaps.stream().mapToDouble(Double::doubleValue).sorted().toArray(), v = widths.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double mean = Arrays.stream(g).average().orElse(0), sd = Math.sqrt(Arrays.stream(g).map(x -> (x - mean) * (x - mean)).average().orElse(0));
            String name = type == 0 ? "FJORD" : "RIA";
            var share = new StringBuilder();
            for (int b = 0; b < 6; b++) share.append(String.format(Locale.ROOT, " %.0f%%", 100. * hist[type][b] / Math.max(1, total[type])));
            lines.add(String.format(Locale.ROOT, "diagnosis %s boundary pixels=%d share by 15-degree bins vs inland axis:%s (isotropic 16.7%% each%s)", name, total[type], share, type == 0 ? "; diagnosis fjord 0-15 degrees 51%" : ""));
            lines.add(String.format(Locale.ROOT, "diagnosis %s arm runs=%d gaps=%d spacing median=%.0f cv=%.2f arm width median=%.0f (diagnosis: median 630 fjord / 651 ria, cv .64 / .71)",
                name, v.length, g.length, g.length > 0 ? g[g.length / 2] : 0, mean > 0 ? sd / mean : 0, v.length > 0 ? v[v.length / 2] : 0));
        }
        return lines;
    }

    // ---- D-GC2 deciding gate (11-19): river-bearing heads, the final river at each and the arm its root enters ----

    /** Axis run (blocks) above a head at which the plan0 channel must still be drawn for the head to be river-bearing. */
    static final double HEAD_PROBE = 300;
    /** Largest distance (blocks) from the plan0 channel point at HEAD_PROBE to the final-plan channel node taken as its river. */
    static final double MATCH_DIST = 96;
    /** A final root farther than its half width plus this many blocks from block-resolution sea is a dry gap. */
    static final double ROOT_SEA_SLACK = 4;
    /** Arm hits within this many blocks of the nearest one tie with it: a root at a junction lies on both branches that meet there. */
    static final double ARM_TIE = 2;

    /**
     * MATCH: the root's nearest plan0 coastal axis is the head's branch or a branch on its downstream chain; WRONG_BRANCH: any other branch;
     * DRY_GAP: no final channel within MATCH_DIST at the probe point, or the root is not at block-resolution sea; CREEKLESS: plan0 draws no
     * channel HEAD_PROBE blocks above the head (outside the denominator).
     */
    enum Verdict { MATCH, WRONG_BRANCH, DRY_GAP, CREEKLESS }

    /**
     * One head: owner, plan0 branch (index and id, the "plan0 reach"), head position, flooded axis samples below it ({@code arm}: at least
     * one; a head reached through open sea only is a coast head, reported, not gated), the probe point and its plan0 channel point, the
     * final node found there and its distance, the final root (position, channel width, sea distance), the branch id its nearest plan0
     * coastal axis belongs to (source "arm" for HighlandArms, "valley" for the nearest plan0 valley sample when no arm reaches the root)
     * and the verdict; {@code headFinal} is the distance from the head to its final root, or, when no final river is found, to the nearest final
     * channel (diagnostic).
     */
    record Head(String label, int ownerX, int ownerZ, int branch, int reach, double x, double z, int flooded, double run, boolean drawn, double catchment,
                double probeX, double probeZ, double channelX, double channelZ, double finalDistance, double rootX, double rootZ, double rootWidth,
                double rootSea, int armReach, double armDistance, String armSource, Verdict verdict, double headFinal) {
        boolean arm() { return flooded > 0; }
        boolean bearing() { return verdict != Verdict.CREEKLESS; }
        boolean failing() { return verdict == Verdict.WRONG_BRANCH || verdict == Verdict.DRY_GAP; }
        String line() {
            return String.format(Locale.ROOT, "head %s owner %d,%d branch %d (reach %d) at %.0f,%.0f %s flooded=%d run=%.0f drawn=%b catchment=%.0f probe %.0f,%.0f channel %.0f,%.0f final=%.1f root %.0f,%.0f w=%.1f sea=%.1f arm=%d (%s, %.1f) %s headFinal=%.1f",
                label, ownerX, ownerZ, branch, reach, x, z, arm() ? "arm" : "coast", flooded, run, drawn, catchment, probeX, probeZ, channelX, channelZ, finalDistance,
                rootX, rootZ, rootWidth, rootSea, armReach, armSource, armDistance, verdict, headFinal);
        }
    }

    private record Bits(long x, long z) {
        static Bits of(double x, double z) { return new Bits(Double.doubleToLongBits(x), Double.doubleToLongBits(z)); }
    }

    /**
     * The heads of one owner (D-GC2). For every plan0 branch that carries arm segments (HighlandArms), its valley axis is walked upstream
     * from its junction (the root node for a sea root); the head is the first axis sample that is land on the final coast after at least one
     * water sample and from which the axis stays land for HEAD_PROBE blocks (or to the branch end): a spit or island the valley crosses before it
     * re-enters water is not a head (a branch whose junction is land has no head: it is a tributary above the shore). The probe point is the axis sample
     * HEAD_PROBE blocks of axis above the head (the branch's last sample when it is shorter); the head is river-bearing when that sample is a
     * sample of plan0's drawn axis (D-GC8: the physical valley there belongs to the drawn selection at the preset's River Count) and its catchment
     * survives drowning: plan0's area at the probe, less the area above the first sample upstream of the probe where the valley re-enters water
     * (an isthmus between two drowned basins), is at least the drawn threshold (creekArea). The plan0
     * channel point is the nearest point of the plan0 reach (HighlandPlanIndex over plan0) whose axis passes the probe point; the final river is
     * the final-plan channel nearest to it (distance to the refined polyline, HighlandPlanIndex over the final plan) within MATCH_DIST, followed
     * downstream from its reach's downstream node to its root.
     */
    static List<Head> heads(String label, GeneratorContext context, int ownerX, int ownerZ) {
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        var coast = continent.coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var plan0 = continent.basePlan(ownerX, ownerZ);
        var valleys = plan0.valleys();
        var index0 = new HighlandPlanIndex(plan0);
        var drawn = new java.util.HashSet<Bits>();
        for (var a : plan0.axis()) drawn.add(Bits.of(a.x(), a.z()));
        var arms = coast.drowned().arms(ownerX, ownerZ);
        var armReaches = new java.util.HashSet<Integer>();
        for (int s = 0; s < arms.segments(); s++) armReaches.add(arms.reach[s]);
        var finalIndex = continent.planOf(ownerX, ownerZ);
        var finalPlan = finalIndex.plan();
        var nodes = finalPlan.nodes();
        var drainage = continent.drainage();
        var heads = new ArrayList<Head>();
        for (int b = 0; b < valleys.branches(); b++) {
            if (!armReaches.contains(valleys.id()[b])) continue;
            int from = valleys.first()[b], to = valleys.first()[b + 1], water = 0, flooded = 0, head = -1, probe = -1;
            double run = 0;
            for (int s = from; s < to; s++) {
                double x = valleys.x()[s], z = valleys.z()[s];
                if (coast.sample(x, z).edge() <= coastEdge) {
                    water++;
                    if (coast.undrowned(x, z).edge() > coastEdge) flooded++;
                    continue;
                }
                if (water == 0) break; // the junction is land: a tributary above the shore, no head
                // A land sample after water is the head only when the axis stays land for HEAD_PROBE blocks above it (or to the branch end); a spit
                // or island the valley crosses before re-entering water is not (its flooded samples count with the head's).
                int p = s;
                double r = 0;
                boolean dry = true;
                while (p + 1 < to && r < HEAD_PROBE) {
                    r += Math.hypot(valleys.x()[p + 1] - valleys.x()[p], valleys.z()[p + 1] - valleys.z()[p]);
                    p++;
                    if (coast.sample(valleys.x()[p], valleys.z()[p]).edge() <= coastEdge) { dry = false; break; }
                }
                if (dry) { head = s; probe = p; run = r; break; }
                s = p - 1; // continue the walk at the water sample
            }
            if (head < 0) continue;
            double hx = valleys.x()[head], hz = valleys.z()[head], px = valleys.x()[probe], pz = valleys.z()[probe];
            // The catchment that survives drowning: plan0's drainage area at the probe less the area above the first sample upstream of it where
            // the valley re-enters water (an isthmus between two drowned basins keeps only what joins between them).
            double catchment = valleys.area()[probe];
            for (int s = probe + 1; s < to; s++) if (coast.sample(valleys.x()[s], valleys.z()[s]).edge() <= coastEdge) { catchment -= valleys.area()[s]; break; }
            boolean isDrawn = drawn.contains(Bits.of(px, pz));
            if (!isDrawn || catchment < drainage.creekArea()) {
                heads.add(new Head(label, ownerX, ownerZ, b, valleys.id()[b], hx, hz, flooded, run, isDrawn, catchment, px, pz, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1, Double.NaN, "-", Verdict.CREEKLESS, Double.NaN));
                continue;
            }
            var hits = index0.near(px, pz);
            int k = -1;
            for (int h = 0; h < hits.count; h++) if (k < 0 || hits.axis[h] < hits.axis[k]) k = h;
            double cx = k < 0 ? px : px - hits.offsetX[k], cz = k < 0 ? pz : pz - hits.offsetZ[k];
            // The final channel nearest to the plan0 channel point: distance to its refined polyline (HighlandPlanIndex over the final plan), then
            // its reach's downstream node; a channel beyond the index influence counts as none.
            var finalHits = finalIndex.near(cx, cz);
            int nearest = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int h = 0; h < finalHits.count; h++) if (finalHits.distance[h] < best) { best = finalHits.distance[h]; nearest = finalPlan.reaches().get(finalHits.reach[h]).downstream(); }
            if (nearest < 0 || best > MATCH_DIST) {
                var atHead = finalIndex.near(hx, hz);
                double headFinal = Double.POSITIVE_INFINITY;
                for (int h = 0; h < atHead.count; h++) headFinal = Math.min(headFinal, atHead.distance[h]);
                heads.add(new Head(label, ownerX, ownerZ, b, valleys.id()[b], hx, hz, flooded, run, true, catchment, px, pz, cx, cz, best,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1, Double.NaN, "-", Verdict.DRY_GAP, headFinal));
                continue;
            }
            int v = nearest;
            for (int guard = 0; nodes.get(v).parent() >= 0 && guard <= nodes.size(); guard++) v = nodes.get(v).parent();
            var root = nodes.get(v);
            double halfWidth = drainage.halfWidth(root.area(), 1);
            double sea = seaDistance(coast, coastEdge, root.x(), root.z(), halfWidth + ROOT_SEA_SLACK);
            var chain = new java.util.HashSet<Integer>();
            for (int c = b; c >= 0; c = valleys.down()[c]) chain.add(valleys.id()[c]);
            var armHits = arms.query(root.x(), root.z());
            int armReach = -1;
            double armDistance = Double.POSITIVE_INFINITY;
            for (int h = 0; h < armHits.count; h++) if (armHits.distance[h] < armDistance) { armDistance = armHits.distance[h]; armReach = arms.reach[armHits.segment[h]]; }
            boolean onChain = false;
            String source = "arm";
            if (armReach >= 0) {
                for (int h = 0; h < armHits.count; h++) if (armHits.distance[h] <= armDistance + ARM_TIE && chain.contains(arms.reach[armHits.segment[h]])) onChain = true;
            } else {
                source = "valley";
                for (int c = 0; c < valleys.branches(); c++) for (int s = valleys.first()[c]; s < valleys.first()[c + 1]; s++) {
                    double d = Math.hypot(valleys.x()[s] - root.x(), valleys.z()[s] - root.z());
                    if (d < armDistance) { armDistance = d; armReach = valleys.id()[c]; }
                }
                onChain = chain.contains(armReach);
            }
            if (onChain && !chain.contains(armReach)) armReach = chain.stream().filter(id -> {
                for (int h = 0; h < armHits.count; h++) if (arms.reach[armHits.segment[h]] == id) return true;
                return false;
            }).findFirst().orElse(armReach);
            Verdict verdict = !(sea <= halfWidth + ROOT_SEA_SLACK) ? Verdict.DRY_GAP : onChain ? Verdict.MATCH : Verdict.WRONG_BRANCH;
            heads.add(new Head(label, ownerX, ownerZ, b, valleys.id()[b], hx, hz, flooded, run, true, catchment, px, pz, cx, cz, best,
                root.x(), root.z(), 2 * halfWidth, sea, armReach, armDistance, source, verdict, Math.hypot(root.x() - hx, root.z() - hz)));
        }
        return heads;
    }

    /** Distance (2-block rings up to max) from (x, z) to the nearest block-resolution sea sample; 0 inside the sea, infinite beyond max (note: HighlandMouthTest.seaDistance, second copy, not extracted). */
    static double seaDistance(HighlandCoast coast, double coastEdge, double x, double z, double max) {
        if (coast.sample(x, z).edge() <= coastEdge) return 0;
        for (double r = 2; r <= max; r += 2) {
            int m = (int) Math.max(16, Math.ceil(Math.PI * r));
            for (int k = 0; k < m; k++) {
                double a = 2 * Math.PI * k / m;
                if (coast.sample(x + r * Math.cos(a), z + r * Math.sin(a)).edge() <= coastEdge) return r;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /** Counts of a head list: river-bearing arm heads, matched, wrong-branch, dry, creek-less arm heads, coast heads (bearing) and their failures. */
    record HeadCounts(int bearing, int matched, int wrong, int dry, int creekless, int coast, int coastFailing) {
        static HeadCounts of(List<Head> heads) {
            int bearing = 0, matched = 0, wrong = 0, dry = 0, creekless = 0, coast = 0, coastFailing = 0;
            for (var h : heads) {
                if (!h.arm()) { if (h.bearing()) { coast++; if (h.failing()) coastFailing++; } continue; }
                switch (h.verdict()) {
                    case CREEKLESS -> creekless++;
                    case MATCH -> { bearing++; matched++; }
                    case WRONG_BRANCH -> { bearing++; wrong++; }
                    case DRY_GAP -> { bearing++; dry++; }
                }
            }
            return new HeadCounts(bearing, matched, wrong, dry, creekless, coast, coastFailing);
        }
        String summary(String label) {
            return String.format(Locale.ROOT, "dgc2 %s: river-bearing arm heads=%d matched=%d wrong-branch=%d dry=%d creek-less=%d; coast heads (river-bearing, reported)=%d failing=%d",
                label, bearing, matched, wrong, dry, creekless, coast, coastFailing);
        }
    }

    /** Review renders of one head: hillshade and mask (2 km box, 4-block pixels) with the head, the plan0 channel point and the final root marked red. */
    static void renderHead(GeneratorContext context, Head head, Path dir, String stem) throws Exception {
        int cx = (int) Math.round(head.x()), cz = (int) Math.round(head.z());
        var marks = new ArrayList<Outlier>();
        marks.add(new Outlier(cx, cz, 0, 0));
        if (Double.isFinite(head.channelX())) marks.add(new Outlier((int) head.channelX(), (int) head.channelZ(), 0, 0));
        if (Double.isFinite(head.rootX())) marks.add(new Outlier((int) head.rootX(), (int) head.rootZ(), 0, 0));
        HighlandPlanformProbe.render(context, cx, cz, 2048, 4, dir.resolve(stem + ".png"));
        uatMask(context, cx, cz, 2048, 4, marks, dir.resolve(stem + "-mask.png"));
    }

    private static final int MASK_AXIS = 0xffd400, MASK_OUTLIER = 0xff0000;

    /**
     * Review mask of a square area: land, open sea (standalone sea), flooded land (standalone land that is sea on the bound coast),
     * the plan0 coastal axes (the arm segments of each column's owner within a pixel of the column) and the fidelity outliers
     * (a 3 x 3 block of pixels each).
     */
    static void uatMask(GeneratorContext context, int cx, int cz, int size, int res, List<Outlier> outliers, Path out) throws Exception {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var drowned = coast.drowned();
        var standalone = new HighlandCoast(context);
        double coastEdge = context.preset.world().controlPoints.coast;
        int px = size / res;
        int[] rgb = new int[px * px];
        for (int j = 0; j < px; j++) for (int i = 0; i < px; i++) {
            double x = cx - size / 2 + i * res, z = cz - size / 2 + j * res;
            boolean land = coast.sample(x, z).edge() > coastEdge, base = standalone.sample(x, z).edge() > coastEdge;
            rgb[j * px + i] = land ? MASK_LAND : base ? MASK_ARM : MASK_SEA;
            var site = coast.undrowned(x, z);
            var hits = drowned.arms(site.ownerX(), site.ownerZ()).query(x, z);
            for (int k = 0; k < hits.count; k++) if (hits.distance[k] <= res) { rgb[j * px + i] = MASK_AXIS; break; }
        }
        for (var o : outliers) {
            int i = (o.x() - (cx - size / 2)) / res, j = (o.z() - (cz - size / 2)) / res;
            for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) if (i + di >= 0 && j + dj >= 0 && i + di < px && j + dj < px) rgb[(j + dj) * px + i + di] = MASK_OUTLIER;
        }
        var image = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, px, px, rgb, 0, px);
        Files.createDirectories(out.getParent());
        ImageIO.write(image, "png", out.toFile());
    }

    /** Mask image of a square area: land, RH9 sea, drowned-arm sea (land under the standalone coast), tinted by the drowned weight. */
    static void maskImage(GeneratorContext context, int cx, int cz, int size, int res, Path out) throws Exception {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        var standalone = new HighlandCoast(context);
        double coastEdge = context.preset.world().controlPoints.coast;
        int px = size / res;
        int[] rgb = new int[px * px];
        IntStream.range(0, px).parallel().forEach(j -> {
            for (int i = 0; i < px; i++) {
                double x = cx - size / 2 + i * res, z = cz - size / 2 + j * res;
                var sample = coast.sample(x, z);
                boolean land = sample.edge() > coastEdge;
                if (!land && standalone.sample(x, z).edge() > coastEdge) { rgb[j * px + i] = MASK_ARM; continue; }
                rgb[j * px + i] = blend(land ? MASK_LAND : MASK_SEA, MASK_TINT, .4 * sample.drowned());
            }
        });
        var image = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, px, px, rgb, 0, px);
        Files.createDirectories(out.getParent());
        ImageIO.write(image, "png", out.toFile());
    }

    private static int blend(int a, int b, double t) {
        int rgb = 0;
        for (int shift = 16; shift >= 0; shift -= 8) rgb |= (int) Math.round(((a >> shift) & 255) * (1 - t) + ((b >> shift) & 255) * t) << shift;
        return rgb;
    }

    /** A candidate test box: its centre and what its coast crossings offer (fjord: PNW share and range core; ria: river mouths). */
    record Site(int x, int z, int crossings, double pnwShare, double rangeCore, int mouths) {}

    static final int SCAN_ROW = 64, SCAN_STEP = 16, SCAN_INLAND = 2048, SCAN_INLAND_STEP = 128, MOUTH_REACH = 64, MIN_CROSSINGS = 8;

    /**
     * Tiles the area into box-sized squares and describes each one's coast: land/sea crossings on rows every
     * {@link #SCAN_ROW} blocks (found to {@link #SCAN_STEP} blocks), the share whose shore reads as PNW, the largest
     * range core at the crossings and up to {@link #SCAN_INLAND} blocks inland along the edge gradient, and the
     * crossings with river water within {@link #MOUTH_REACH} blocks inland. Squares with fewer than
     * {@link #MIN_CROSSINGS} crossings are left out.
     */
    static List<Site> scanSites(GeneratorContext context, int originX, int originZ, int radius, int box) {
        var coast = ((RollingHighlandsContinent) context.heightmap.continent()).coast();
        double coastEdge = context.preset.world().controlPoints.coast;
        var highlandLand = HighlandLand.create(context);
        var sites = new ArrayList<Site>();
        var cell = new Cell();
        for (int z0 = originZ - radius; z0 < originZ + radius; z0 += box) for (int x0 = originX - radius; x0 < originX + radius; x0 += box) {
            int crossings = 0, pnw = 0, mouths = 0;
            double core = 0;
            for (int z = z0; z < z0 + box; z += SCAN_ROW) {
                boolean previous = coast.sample(x0, z).edge() > coastEdge;
                for (int x = x0 + SCAN_STEP; x < x0 + box; x += SCAN_STEP) {
                    double e = coast.sample(x, z).edge();
                    boolean land = e > coastEdge;
                    if (land == previous) continue;
                    previous = land;
                    double gx = coast.sample(x + 2, z).edge() - e, gz = coast.sample(x, z + 2).edge() - e, norm = Math.hypot(gx, gz);
                    if (norm == 0) continue;
                    gx /= norm; gz /= norm;
                    crossings++;
                    if (HighlandCoastProfile.climateClass(coast.profile().shore(x, z)) == HighlandCoastProfile.PNW) pnw++;
                    for (int d = 0; d <= SCAN_INLAND; d += SCAN_INLAND_STEP) core = Math.max(core, highlandLand.rangeCore(x + gx * d, z + gz * d));
                    for (int d = 0; d <= MOUTH_REACH; d += 8) {
                        context.lookup.applyCell(cell.reset(), (int) Math.round(x + gx * d), (int) Math.round(z + gz * d), false, true);
                        if (!cell.hasRiverWater) continue;
                        mouths++;
                        break;
                    }
                }
            }
            if (crossings >= MIN_CROSSINGS) sites.add(new Site(x0 + box / 2, z0 + box / 2, crossings, (double) pnw / crossings, core, mouths));
        }
        return sites;
    }

    private static double ratio(double part, double whole) { return whole > 0 ? part / whole : Double.NaN; }

    /** Distance (in cells, two-pass chamfer) from every cell of a square mask to the nearest land cell. */
    private static float[] distanceToLand(boolean[] land, int w) {
        float[] d = new float[land.length];
        for (int at = 0; at < d.length; at++) d[at] = land[at] ? 0 : Float.MAX_VALUE / 2;
        float diagonal = (float) Math.sqrt(2);
        for (int j = 0; j < w; j++) for (int i = 0; i < w; i++) {
            int at = j * w + i;
            if (i > 0) d[at] = Math.min(d[at], d[at - 1] + 1);
            if (j > 0) {
                d[at] = Math.min(d[at], d[at - w] + 1);
                if (i > 0) d[at] = Math.min(d[at], d[at - w - 1] + diagonal);
                if (i < w - 1) d[at] = Math.min(d[at], d[at - w + 1] + diagonal);
            }
        }
        for (int j = w - 1; j >= 0; j--) for (int i = w - 1; i >= 0; i--) {
            int at = j * w + i;
            if (i < w - 1) d[at] = Math.min(d[at], d[at + 1] + 1);
            if (j < w - 1) {
                d[at] = Math.min(d[at], d[at + w] + 1);
                if (i < w - 1) d[at] = Math.min(d[at], d[at + w + 1] + diagonal);
                if (i > 0) d[at] = Math.min(d[at], d[at + w - 1] + diagonal);
            }
        }
        return d;
    }

    /**
     * Whether the water at lattice cell (ci, cj) of a square land mask is sheltered: the mean free-water run over
     * FETCH_RAYS compass rays (stepping FETCH_STEP blocks, capped at FETCH_CAP) is at most SHELTER_FETCH. Stops as
     * soon as the runs already sum past that mean, which no later ray can undo.
     */
    private static boolean sheltered(boolean[] land, int w, int ci, int cj) {
        double sum = 0, limit = (double) FETCH_RAYS * SHELTER_FETCH;
        for (int ray = 0; ray < FETCH_RAYS; ray++) {
            double dx = Math.cos(2 * Math.PI * ray / FETCH_RAYS), dz = Math.sin(2 * Math.PI * ray / FETCH_RAYS);
            double run = FETCH_CAP;
            for (int d = FETCH_STEP; d <= FETCH_CAP; d += FETCH_STEP) {
                int i = ci + (int) Math.round(dx * d / NAV_GRID), j = cj + (int) Math.round(dz * d / NAV_GRID);
                if (i < 0 || j < 0 || i >= w || j >= w) break;
                if (land[j * w + i]) { run = d - FETCH_STEP; break; }
            }
            sum += run;
            if (sum > limit) return false;
        }
        return true;
    }

    /** Component ids (1..count, 0 outside the mask) and the cell count of each component (index 0 unused). */
    private record Components(int[] id, int[] area) {}

    /** 4-connected components of a square mask, by iterative breadth-first fill. */
    private static Components label(boolean[] mask, int w) { return label(mask, w, false); }

    /** Components of a square mask, 8-connected when {@code diagonal} (the connectivity of the geodesic paths), else 4-connected. */
    private static Components label(boolean[] mask, int w, boolean diagonal) {
        int[] id = new int[mask.length], queue = new int[mask.length], area = new int[16];
        int count = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || id[start] != 0) continue;
            if (++count == area.length) area = Arrays.copyOf(area, count * 2);
            int head = 0, tail = 0;
            id[start] = count;
            queue[tail++] = start;
            while (head < tail) {
                int at = queue[head++], i = at % w, j = at / w;
                area[count]++;
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                    int ni = i + di, nj = j + dj;
                    if ((di == 0 && dj == 0) || (!diagonal && di != 0 && dj != 0) || ni < 0 || nj < 0 || ni >= w || nj >= w) continue;
                    int next = nj * w + ni;
                    if (mask[next] && id[next] == 0) { id[next] = count; queue[tail++] = next; }
                }
            }
        }
        return new Components(id, Arrays.copyOf(area, count + 1));
    }

    /** Which components (by id) have a cell on the border of the square id grid. */
    private static boolean[] borderTouching(int[] id, int w, int count) {
        boolean[] touches = new boolean[count + 1];
        for (int k = 0; k < w; k++) touches[id[k]] = touches[id[(w - 1) * w + k]] = touches[id[k * w]] = touches[id[k * w + w - 1]] = true;
        return touches;
    }

    public static void main(String[] args) throws Exception {
        try (var context = WorldgenFixture.context(HighlandPlanformProbe.userPreset(), HighlandPlanformProbe.USER_SEED)) {
            System.out.println(measure(context, HighlandCoastTest.SITE_X, HighlandCoastTest.SITE_Z, HighlandCoastTest.RADIUS).summary("user"));
        }
        try (var context = WorldgenFixture.context(BiomeCensusProbe.rockies(), HighlandPlanformProbe.USER_SEED)) {
            System.out.println(measure(context, HighlandCoastTest.SITE_X, HighlandCoastTest.SITE_Z, HighlandCoastTest.RADIUS).summary("rockies"));
        }
    }
}
