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
    private static Components label(boolean[] mask, int w) {
        int[] id = new int[mask.length], queue = new int[mask.length], area = new int[16];
        int count = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || id[start] != 0) continue;
            if (++count == area.length) area = Arrays.copyOf(area, count * 2);
            int head = 0, tail = 0;
            id[start] = count;
            queue[tail++] = start;
            while (head < tail) {
                int at = queue[head++], i = at % w;
                area[count]++;
                if (i > 0 && mask[at - 1] && id[at - 1] == 0) { id[at - 1] = count; queue[tail++] = at - 1; }
                if (i < w - 1 && mask[at + 1] && id[at + 1] == 0) { id[at + 1] = count; queue[tail++] = at + 1; }
                if (at >= w && mask[at - w] && id[at - w] == 0) { id[at - w] = count; queue[tail++] = at - w; }
                if (at + w < mask.length && mask[at + w] && id[at + w] == 0) { id[at + w] = count; queue[tail++] = at + w; }
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
