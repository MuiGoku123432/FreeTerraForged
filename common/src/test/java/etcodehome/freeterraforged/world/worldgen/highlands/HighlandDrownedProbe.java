package etcodehome.freeterraforged.world.worldgen.highlands;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
 * <li>islands: 4-connected land components on the GRID mask that do not touch the box border;</li>
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
