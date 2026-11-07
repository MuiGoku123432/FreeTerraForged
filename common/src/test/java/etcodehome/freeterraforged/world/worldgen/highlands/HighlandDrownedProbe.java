package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.IntStream;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.Continentalness;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;

/**
 * RH10 P0: drowned-coast metrics, measured on a box around a site. Land/sea comes from the coast edge field against the
 * preset's coast control point, sampled once on a {@link #NAV_GRID}-block lattice; the {@link #GRID}-block metric mask
 * is every eighth sample of it, so both masks agree. Scope is the sea within {@link #NEAR_SHORE} of land (at model 8
 * there are no drowned regions; later plans add a drowned-region scope beside it).
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

    record Report(int samples, double seaArea, int islands, double islandsPer10km2, int[] islandSizes, int islandsInChains,
                  double shelteredShare, double navigableShare, double harborsPer25km2, double oceanBandShare,
                  double edgeSlopeP10, double edgeSlopeP50, double edgeSlopeP90, double nanosPerColumn, int planStep, long planBuildNanos) {
        String summary(String label) {
            return String.format(Locale.ROOT,
                "RHD %s n=%d seaArea=%.0f islands=%d islands/10km2=%.2f sizes(<300,<1000,<3000,more)=%d/%d/%d/%d chained=%d "
                    + "sheltered=%.1f%% navigable=%.1f%% harbors/25km2=%.2f oceanBand=%.1f%% edgeSlope p10=%.2e p50=%.2e p90=%.2e "
                    + "ns/column=%.0f planStep=%d planBuildMs=%.0f",
                label, samples, seaArea, islands, islandsPer10km2, islandSizes[0], islandSizes[1], islandSizes[2], islandSizes[3],
                islandsInChains, 100 * shelteredShare, 100 * navigableShare, harborsPer25km2, 100 * oceanBandShare,
                edgeSlopeP10, edgeSlopeP50, edgeSlopeP90, nanosPerColumn, planStep, planBuildNanos / 1e6);
        }
    }

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

        // Scope, depth and the ocean-band share: one column lookup per scoped sea point.
        float[] near = distanceToLand(gridLand, gw);
        boolean[] scoped = new boolean[gw * gw];
        double[] depth = new double[gw * gw];
        var cell = new Cell();
        int samples = 0, wet = 0, band = 0;
        long nanos = 0;
        for (int j = 0; j < gw; j++) for (int i = 0; i < gw; i++) {
            int at = j * gw + i;
            if (gridLand[at] || near[at] * GRID > NEAR_SHORE) continue;
            scoped[at] = true;
            samples++;
            long start = System.nanoTime();
            context.lookup.applyCell(cell.reset(), x0 + i * GRID, z0 + j * GRID, false, true);
            nanos += System.nanoTime() - start;
            depth[at] = -(cell.height - levels.water) * levels.terrainScaleFactor;
            if (depth[at] < 1) continue;
            wet++;
            if (CellSampler.Field.CONTINENT.read(cell, context.heightmap) < Continentalness.COAST.min()) band++;
        }
        double seaArea = (double) samples * GRID * GRID;

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

        // Sheltered water by effective fetch.
        boolean[] sheltered = new boolean[gw * gw];
        int deepEnough = 0, shelteredCount = 0;
        for (int j = 0; j < gw; j++) for (int i = 0; i < gw; i++) {
            int at = j * gw + i;
            if (!scoped[at] || depth[at] < SHELTER_DEPTH) continue;
            deepEnough++;
            if (!sheltered(land, w, i * step, j * step)) continue;
            sheltered[at] = true;
            shelteredCount++;
        }

        // Navigability: erode the water by one cell, then see which scoped points sit in the open-sea component.
        boolean[] eroded = new boolean[w * w];
        for (int j = 0; j < w; j++) for (int i = 0; i < w; i++) {
            int at = j * w + i;
            eroded[at] = !land[at] && (i == 0 || !land[at - 1]) && (i == w - 1 || !land[at + 1]) && (j == 0 || !land[at - w]) && (j == w - 1 || !land[at + w]);
        }
        var water = label(eroded, w);
        boolean[] open = borderTouching(water.id, w, water.area.length - 1);
        boolean[] navigable = new boolean[gw * gw];
        int navTotal = 0, navOpen = 0;
        for (int j = 0; j < gw; j++) for (int i = 0; i < gw; i++) {
            int at = j * gw + i;
            if (!scoped[at] || depth[at] < NAV_DEPTH) continue;
            navTotal++;
            int id = water.id[j * step * w + i * step];
            if (id == 0 || !open[id]) continue;
            navigable[at] = true;
            navOpen++;
        }

        // Harbors: sheltered components big enough and connected to the open sea.
        var coves = label(sheltered, gw);
        boolean[] reaches = new boolean[coves.area.length];
        for (int at = 0; at < gw * gw; at++) if (sheltered[at] && navigable[at]) reaches[coves.id[at]] = true;
        int harbors = 0;
        for (int id = 1; id < coves.area.length; id++) if (reaches[id] && coves.area[id] * (double) GRID * GRID >= HARBOR_AREA) harbors++;

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
        return new Report(samples, seaArea, islands, samples > 0 ? islands * 1e7 / seaArea : Double.NaN, sizes, chained,
            ratio(shelteredCount, deepEnough), ratio(navOpen, navTotal), samples > 0 ? harbors * 25e6 / seaArea : Double.NaN,
            ratio(band, wet), HighlandCoastProbe.quantile(sorted, .1), HighlandCoastProbe.quantile(sorted, .5),
            HighlandCoastProbe.quantile(sorted, .9), samples > 0 ? (double) nanos / samples : Double.NaN, plan.gridStep(), plan.buildNanos());
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
