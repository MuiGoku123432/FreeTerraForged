package etcodehome.freeterraforged.world.worldgen.highlands;

import etcodehome.freeterraforged.data.worldgen.preset.settings.RollingHighlandsSettings;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.continent.Continent;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.Network;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.RiverCarverSettings.RiverZone;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;
import static etcodehome.freeterraforged.world.worldgen.highlands.HighlandMath.*;

/** Owns the RH terrain/hydrology pipeline; legacy generators never enter this path. */
public final class RollingHighlandsContinent implements Continent {
    /** Valley floors: vanilla low peaks-and-valleys slice (normal side) and moderately flat erosion. */
    static final double VALLEY_WEIRDNESS = -.12, VALLEY_EROSION = .1;
    static final float WETLAND_EROSION = .78F;
    static final double CHANNEL_DEPTH = 4, MAX_FADE = 384, CONFLUENCE_RAPID = .25, FLOOR_MARGIN = 1.5, FLOOR_SOFTNESS = 4, FLOOR_COAST_RAMP = 6, SUPPORT_SLOPE = .5;
    /** Water-relative floor (RH6 F3): reach (blocks beyond a channel's banks) and fall (per block) of the pit-fill cone. */
    static final double FLOOR_REACH = 64, PIT_SLOPE = .08;
    /** Current speed model (normalized 0..1 as stored in ChunkFlowField). */
    static final double FLOW_BASE = .45, FLOW_SLOPE = 6, FLOW_MIN = .3, LAKE_DRIFT = .18, OUTLET_DRIFT = 128;
    /** Confluence apex rounding (fillet radius in channel half widths) and the largest scour (fraction of bed depth). */
    static final double FILLET = 1.2, SCOUR = .8;
    /** Distance range (blocks) from a higher channel over which a floodplain's meander-belt widening fades in. */
    static final double BELT_YIELD_NEAR = 24, BELT_YIELD_FAR = 120;
    /** Oxbow open-water depth at the centre and wetland margin width (blocks). */
    static final double OXBOW_DEPTH = 2.5, OXBOW_MARGIN = 8;
    /** Creek section (RH5): share of the river valley floor width kept, and bed depth (blocks, before texture). */
    static final double CREEK_FLOOR = .3, CREEK_BED = 1.6;
    /** Narrowest channel half width (blocks): a creek is never less than two blocks of water across. */
    static final double MIN_RADIUS = 1;
    static final double BANK_RUN = 16, MIN_OUTER_RUN = 64, MAX_OUTER_RUN = 512, CREASE_SOFTNESS = 24;
    private static final Rivermap NO_LEGACY_RIVERS = new Rivermap(0, 0, new Network[0], GenWarp.EMPTY);
    private final HighlandCoast coast;
    private final HighlandLand land;
    private final HighlandDrainage drainage;
    private final RollingHighlandsSettings settings;
    private final Levels levels;
    private final double coastEdge;
    private final long classSeed, stackSeed, marshSeed;
    private final double coastMarker;
    /** FTF wetland chance (default 0.5) mapped onto the wetland noise threshold. */
    private final double wetlandLow;
    /** FTF main-river settings (bed depth, bank heights, bank width) shape every RH channel and valley. */
    private final etcodehome.freeterraforged.data.worldgen.preset.settings.RiverSettings.River river;
    private final etcodehome.freeterraforged.world.worldgen.cell.CellPopulator oceans;
    private final etcodehome.freeterraforged.world.worldgen.noise.module.Noise beachNoise;
    /** Shallow-ocean edge control point and shelf depth (blocks): the shoreface descends from the coastline to the shelf. */
    private final double shallowOceanEdge, shelfDepth;
    static final int MAX_PLANS = 8;
    private final java.util.concurrent.ConcurrentHashMap<Long, PlanEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong useClock = new java.util.concurrent.atomic.AtomicLong();
    private final ThreadLocal<LastPlan> lastPlan = new ThreadLocal<>();
    private volatile boolean closed;

    public RollingHighlandsContinent(GeneratorContext context) {
        this.settings = context.preset.terrain().highlands();
        settings.validate(context.preset.world());
        context.preset.terrain().rollingHighlands = settings;
        this.levels = context.levels;
        this.coast = new HighlandCoast(context);
        this.land = HighlandLand.create(context);
        this.drainage = new HighlandDrainage(coast, land, settings.uplandSpacing(), context.preset.world().controlPoints, context.preset.rivers().lakes.chance, context.preset.rivers().lakes.depth,
            settings.valleyDepth(), context.preset.rivers().riverCount, settings.meanders() / 100.0, context.preset.rivers().lakes.sizeMax);
        this.coastEdge = context.preset.world().controlPoints.coast;
        this.classSeed = context.seed.root() * 0x9E3779B97F4A7C15L + 0x52484632L;
        this.stackSeed = mix(context.seed.root() ^ 0x5348394358L);
        this.marshSeed = mix(context.seed.root() ^ 0x524831304DL);
        this.coastMarker = context.preset.world().controlPoints.coastMarker();
        this.river = context.preset.rivers().mainRivers;
        this.wetlandLow = .95 - clamp(context.preset.rivers().wetlands.chance, 0, 1);
        // Oceans, shelves and coast use the legacy FTF ocean populators so ocean biomes, depths and the
        // coast-to-shelf transition match the other continent types.
        var world = context.preset.world();
        var oceanClimate = etcodehome.freeterraforged.world.worldgen.cell.terrain.ClimateParameterSampler.make(
            context.seed.offset(89031), context.preset.climate().biomeShape.biomeSize, context.preset.terrain().general.globalHorizontalScale);
        this.oceans = new etcodehome.freeterraforged.world.worldgen.cell.continent.ContinentLerper3(
            etcodehome.freeterraforged.world.worldgen.cell.terrain.Populators.makeDeepOcean(context.seed.next(), levels, world.properties.oceanDepth, oceanClimate),
            etcodehome.freeterraforged.world.worldgen.cell.terrain.Populators.makeShallowOcean(levels, world.properties.oceanDepth, oceanClimate),
            etcodehome.freeterraforged.world.worldgen.cell.terrain.Populators.makeCoast(levels, oceanClimate),
            world.controlPoints.deepOcean, world.controlPoints.shallowOcean, world.controlPoints.coast);
        this.beachNoise = etcodehome.freeterraforged.world.worldgen.noise.module.Noises.mul(
            etcodehome.freeterraforged.world.worldgen.noise.module.Noises.perlin2(context.seed.next(), 20, 1), levels.scale(5));
        this.shallowOceanEdge = world.controlPoints.shallowOcean;
        this.shelfDepth = etcodehome.freeterraforged.world.worldgen.cell.terrain.Populators.shallowOceanDepth(world.properties.oceanDepth);
    }
    /**
     * Owner plans: lock-free lookups with a per-thread last-plan fast path; each plan is built once (concurrent
     * requests for the same owner wait on the same future) and the cache keeps the MAX_PLANS most recently used.
     * A failed provisional drainage build uses the existing logged river-less fallback. Final lake-resolution
     * failures remain visible rather than publishing inconsistent water. Cancellation is not cached.
     */
    private HighlandPlanIndex plan(int x, int z) {
        if (closed) throw new IllegalStateException("Rolling Highlands owner is closed");
        long key = PosUtil.pack(x, z);
        LastPlan last = lastPlan.get();
        if (last != null && last.key == key) return last.index;
        PlanEntry entry = cache.get(key);
        if (entry == null) {
            PlanEntry created = new PlanEntry(new java.util.concurrent.CompletableFuture<>());
            entry = cache.putIfAbsent(key, created);
            if (entry == null) {
                entry = created;
                try {
                    created.future.complete(buildIndex(x, z));
                } catch (RuntimeException | Error failure) {
                    created.future.completeExceptionally(failure);
                    cache.remove(key, created);
                    throw failure;
                }
                evict();
            }
        }
        entry.lastUse = useClock.incrementAndGet();
        HighlandPlanIndex index = entry.future.join();
        lastPlan.set(new LastPlan(key, index));
        return index;
    }
    private HighlandPlanIndex buildIndex(int x, int z) {
        HighlandPlanIndex provisional;
        try {
            provisional = new HighlandPlanIndex(drainage.build(x, z), HighlandRangeRelief.build(land, coast, x, z));
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            etcodehome.freeterraforged.FTFCommon.LOGGER.warn("Rolling Highlands: continent {},{} has no river plan ({}); generating it without rivers",
                x, z, failure.getMessage());
            return new HighlandPlanIndex(drainage.emptyPlan(x, z));
        }
        // Resolution errors must remain visible, rather than silently publishing a riverless continent.
        return resolveLakes(provisional);
    }

    private HighlandPlanIndex resolveLakes(HighlandPlanIndex provisional) {
        long started = System.nanoTime();
        var original = provisional.plan(); var candidates = original.basins();
        if (candidates.count() == 0) return provisional;
        var windows = candidates.prepare((x, z) -> {
            Cell cell = SCRATCH.get().lakeCell;
            var location = coast.sample(x, z);
            if (location.ownerX() != original.ownerX() || location.ownerZ() != original.ownerZ()) return Double.NEGATIVE_INFINITY;
            var hits = provisional.near(x, z);
            double blend = drainage.landBlend(location.edge());
            cell.reset();
            // Do not count channel support as an independent basin wall.
            double raw = rawLand(cell,x,z,location.edge(),provisional,hits,provisional.incision(x,z)*(1-land.rangeCore(x,z)));
            return Math.min(raw, groundBeforeLakes(cell, x, z, location.edge(), provisional, hits, blend, raw));
        });
        double[] caps = candidates.levelCaps();
        var coarse = bindLakes(provisional, candidates, windows, caps, started);
        etcodehome.freeterraforged.FTFCommon.LOGGER.debug("Lake coarse owner {},{}: {} candidates, {} bodies in {} ms", original.ownerX(), original.ownerZ(), candidates.count(), coarse.plan().basins().count(), (System.nanoTime()-started)/1e6);
        long fineStarted = System.nanoTime();
        var fine = candidates.refine(coarse.plan().basins(), body -> (x,z) -> {
            Cell cell = SCRATCH.get().lakeCell;
            var location = coast.sample(x,z);
            if (location.ownerX() != original.ownerX() || location.ownerZ() != original.ownerZ()) return Double.NEGATIVE_INFINITY;
            var hits = coarse.near(x,z);
            double blend = drainage.landBlend(location.edge()); cell.reset();
            double raw = rawLand(cell,x,z,location.edge(),coarse,hits,coarse.incision(x,z)*(1-land.rangeCore(x,z)));
            double ground = Math.min(raw, groundBeforeLakes(cell,x,z,location.edge(),coarse,hits,blend,raw));
            double water = (cell.riverWaterLevel-levels.water)*levels.terrainScaleFactor;
            // A flowing outlet at the body's own level is an intentional opening. Its bed is not a dry-bank spill.
            if (cell.hasRiverWater && cell.hasFlow && Math.abs(water-body.water()) < .25 && body.depth(x,z) <= 0)
                ground = Math.max(ground,body.water()+HighlandBasins.FREEBOARD);
            return ground;
        });
        var resolved = bindLakes(coarse, candidates, fine, caps, fineStarted);
        etcodehome.freeterraforged.FTFCommon.LOGGER.debug("Lake fine owner {},{}: {} bodies in {} ms", original.ownerX(), original.ownerZ(), resolved.plan().basins().count(), (System.nanoTime()-fineStarted)/1e6);
        Cell cell = new Cell();
        for (int pass = 0; pass < 4; pass++) {
            var current = resolved;
            boolean changed = current.plan().basins().constrainBanks((x,z) -> {
                var location = coast.sample(x,z);
                if (location.ownerX() != original.ownerX() || location.ownerZ() != original.ownerZ()) return Double.NEGATIVE_INFINITY;
                var hits = current.near(x,z); cell.reset();
                double blend = drainage.landBlend(location.edge());
                double raw = rawLand(cell,x,z,location.edge(),current,hits,current.incision(x,z)*(1-land.rangeCore(x,z)));
                double height = groundBeforeLakes(cell,x,z,location.edge(),current,hits,blend,raw);
                return cell.hasRiverWater ? Double.POSITIVE_INFINITY : levels.scale(normalized(height));
            }, levels.water*levels.terrainScaleFactor, caps);
            if (!changed) {
                etcodehome.freeterraforged.FTFCommon.LOGGER.debug("Lake final owner {},{}: {} bodies, {} bank passes in {} ms", original.ownerX(), original.ownerZ(), resolved.plan().basins().count(), pass+1, (System.nanoTime()-started)/1e6);
                return resolved;
            }
            resolved = bindLakes(current,candidates,fine,caps,System.nanoTime());
        }
        throw new IllegalStateException("Lake banks did not converge after final river shaping");
    }

    private HighlandPlanIndex bindLakes(HighlandPlanIndex provisional, HighlandBasins candidates,
                                        HighlandBasins.Window[] windows, double[] caps, long started) {
        var original = provisional.plan();
        int n = original.nodes().size();
        int[] parents = new int[n], children = new int[n], lake = new int[n];
        double[] low = new double[n], high = new double[n], target = new double[n], minDrop = new double[n], maxDrop = new double[n];
        for (int i = 0; i < n; i++) {
            var node = original.nodes().get(i); parents[i] = node.parent();
            if (parents[i] >= 0) children[parents[i]]++;
        }
        double[] desired = new double[n], water;
        HighlandBasins basins;
        for (int pass = 0; ; pass++) {
            if (pass >= 16) throw new IllegalStateException("Lake/river constraints did not converge within 16 passes");
            basins = candidates.resolve(windows, caps);
            for (int i = 0; i < n; i++) {
                var node = original.nodes().get(i); int p = parents[i];
                high[i] = target[i] = node.water();
                var body = basins.channelContact(node.x(), node.z(), 20 + 1.35*drainage.halfWidth(node.area(), node.head()));
                lake[i] = body == null ? -1 : body.lake();
                desired[i] = body == null ? 0 : body.water();
                // Upper bounds first: do not pin a body above what one of its contacts can carry.
                if (body != null) high[i] = target[i] = Math.min(high[i], body.water());
                if (p >= 0) {
                    var parent = original.nodes().get(p);
                    maxDrop[i] = children[p] >= 2 || lake[i] >= 0 && lake[p] == lake[i] ? 0
                        : HighlandDrainage.MAX_WATER_SLOPE * Math.hypot(node.x()-parent.x(), node.z()-parent.z());
                }
            }
            double[] ceiling = TreeProfileSolver.solve(parents, low, high, target, minDrop, maxDrop);
            if (basins.constrain(lake, ceiling, caps)) continue;
            // Recheck with exact equality pins only after the whole-body caps and contacts agree.
            for (int i = 0; i < n; i++) if (lake[i] >= 0) low[i] = high[i] = target[i] = desired[i];
            water = TreeProfileSolver.solve(parents, low, high, target, minDrop, maxDrop);
            etcodehome.freeterraforged.FTFCommon.LOGGER.debug("Resolved {} lakes in {} river constraint passes", basins.count(), pass+1);
            break;
        }
        var nodes = new java.util.ArrayList<HighlandDrainage.Node>(n);
        for (int i = 0; i < n; i++) {
            var node = original.nodes().get(i);
            nodes.add(new HighlandDrainage.Node(node.x(), node.z(), node.ground(), water[i], node.area(), node.parent(), node.head()));
            if (lake[i] >= 0 && parents[i] >= 0 && lake[parents[i]] != lake[i]) {
                var outlet = original.nodes().get(parents[i]); basins.setOutlet(lake[i], outlet.x(), outlet.z());
            }
        }
        var oxbows = new java.util.ArrayList<HighlandDrainage.Oxbow>();
        for (var site : original.oxbows()) {
            double level = site.water();
            for (int j = site.nodeFrom(); j <= site.nodeTo(); j++) level = Math.min(level,water[j]);
            if (level > 0) oxbows.add(new HighlandDrainage.Oxbow(site.x(),site.z(),site.halfWidth(),level,site.nodeFrom(),site.nodeTo()));
        }
        var plan = new HighlandDrainage.Plan(original.ownerX(), original.ownerZ(), original.gridStep(), original.gridNodes(), original.landNodes(),
            java.util.List.copyOf(nodes), original.reaches(), original.axis(), java.util.List.copyOf(oxbows), basins,
            original.buildNanos()+System.nanoTime()-started, original.originX(), original.originZ(), original.width(), original.incision(), original.water(), original.climate());
        return new HighlandPlanIndex(plan, provisional.relief);
    }
    private void evict() {
        while (cache.size() > MAX_PLANS) {
            long oldest = Long.MAX_VALUE; Long victim = null;
            for (var e : cache.entrySet()) {
                if (!e.getValue().future.isDone()) continue;
                if (e.getValue().lastUse < oldest) { oldest = e.getValue().lastUse; victim = e.getKey(); }
            }
            if (victim == null) return;
            cache.remove(victim);
        }
    }
    private static final class PlanEntry {
        final java.util.concurrent.CompletableFuture<HighlandPlanIndex> future;
        volatile long lastUse;
        PlanEntry(java.util.concurrent.CompletableFuture<HighlandPlanIndex> future) { this.future = future; }
    }
    private record LastPlan(long key, HighlandPlanIndex index) {}
    /** Test/diagnostic access to the owner plan containing a block position. */
    HighlandPlanIndex planAt(double x, double z) { var s = coast.sample(x, z); return plan(s.ownerX(), s.ownerZ()); }
    HighlandCoast coast() { return coast; }
    /** RH9: the game's climate, for the coast profile's regional climate (built after this continent). */
    public void useClimate(etcodehome.freeterraforged.world.worldgen.cell.climate.Climate climate) {
        coast.profile().climate(climate);
        coast.bindDrowned(land); // RH10: drowned arms switch on only now; nothing samples the continent before this point
    }
    HighlandDrainage drainage() { return drainage; }
    HighlandPlanIndex planOf(int ownerX, int ownerZ) { return plan(ownerX, ownerZ); }
    /** Test access: builds the owner plan at a block position afresh (uncached), for cost measurements. */
    HighlandPlanIndex rebuildAt(double x, double z) { var s = coast.sample(x, z); return buildIndex(s.ownerX(), s.ownerZ()); }
    /** Diagnostics: the land surface before any valley, lake or wetland shaping, in blocks above sea level. */
    double uncarvedLand(double x, double z) {
        var location = coast.sample(x, z);
        if (location.edge() <= coastEdge) return 0;
        return drainage.coastalLand(x, z, location.edge(), land.height(x, z), true);
    }
    /** Diagnostics: the rendered land before channel, lake and wetland shaping. */
    double incisedLand(double x, double z) {
        var location = coast.sample(x, z);
        if (location.edge() <= coastEdge) return 0;
        var index = plan(location.ownerX(), location.ownerZ());
        double blend = drainage.landBlend(location.edge());
        return floored(blend, drainage.coastalLand(x, z, location.edge(), land.height(x, z), true) - index.incision(x, z), index.near(x, z));
    }
    /**
     * Water-relative floor (RH6 F3). Dry ground is held up only where water needs it:
     * <ul>
     * <li>inland land stays FLOOR_MARGIN above sea level (faded in over the first part of the coastal land blend, so
     * shores still meet the sea);</li>
     * <li>within FLOOR_REACH of a channel's banks, ground stays above a gentle cone from FLOOR_MARGIN over that
     * channel's water, so no pit beside a river lies below its water (it would hold standing water: sea-level fill or
     * a Streams Reflowing lake).</li>
     * </ul>
     * Everywhere else the land is the legacy land minus the plan's incision: hollows away from water keep their real
     * shape (RH5 lifted all land to an interpolant of the plan's node levels, which left dead-flat plateaus and a
     * 128-block grid imprint). Smooth maxima throughout, so no creases.
     */
    private double floored(double blend, double surface, HighlandPlanIndex.Hits hits) {
        double ramp = Math.min(1, FLOOR_COAST_RAMP * blend);
        double height = softMax(surface, ramp * FLOOR_MARGIN);
        for (int k = 0; k < hits.count; k++) {
            double beyond = hits.distance[k] - drainage.halfWidth(hits.area[k], hits.head[k]);
            if (beyond >= FLOOR_REACH) continue;
            double level = ramp * (hits.water[k] + FLOOR_MARGIN) - PIT_SLOPE * Math.max(0, beyond);
            if (level <= height - FLOOR_SOFTNESS) continue;
            height = lerp(height, softMax(height, level), 1 - smooth(.5 * FLOOR_REACH, FLOOR_REACH, beyond));
        }
        return height;
    }
    /**
     * RH7 D: range dissection, faded out within DISSECTION_CLEAR blocks of a drawn channel's banks (the channel's own
     * valley takes over there, and the channel's water level was planned on the undissected surface).
     */
    private double dissection(HighlandPlanIndex index, double x, double z, HighlandPlanIndex.Hits hits) {
        double carved = index.relief.surface(x, z);
        if (Double.isNaN(carved)) return 0;
        var frame = land.rangeFrame(x, z);
        double depth = (frame == null ? 0 : land.range(x, z, frame)) - carved;
        if (depth == 0 && carved <= 0) return 0;
        // D4: mid-scale ribs and gullies running down the fall line on hillslopes and crests, none on low valley floors.
        double weight = frame == null ? 0 : smooth(DETAIL_FLOOR, DETAIL_FULL, carved);
        if (weight > 0) {
            double east = index.relief.surface(x + DETAIL_PROBE, z), west = index.relief.surface(x - DETAIL_PROBE, z);
            double south = index.relief.surface(x, z + DETAIL_PROBE), north = index.relief.surface(x, z - DETAIL_PROBE);
            double gx = east - west, gz = south - north;
            // Fades out where the carved surface is flat (no fall line) and on divides and valley lines, where the fall
            // line turns sharply (strong curvature); NaN off the grid edge counts as flat.
            double bend = Math.abs(east + west + south + north - 4 * carved);
            double steep = Double.isNaN(gx + gz) ? 0 : smooth(.05, .25, Math.hypot(gx, gz) / (2 * DETAIL_PROBE)) * (1 - smooth(DETAIL_BEND * .3, DETAIL_BEND, bend));
            if (steep > 0) depth -= weight * steep * DETAIL * land.rangeDetail(x, z, Math.atan2(gz, gx));
        }
        double keep = 1;
        for (int k = 0; k < hits.count; k++) {
            double beyond = hits.distance[k] - drainage.halfWidth(hits.area[k], hits.head[k]);
            keep = Math.min(keep, smooth(DISSECTION_CLEAR * .25, DISSECTION_CLEAR, beyond));
        }
        return depth * keep;
    }
    static final double DISSECTION_CLEAR = 160;
    /** RH7 M: soft-compression band below the world top and the gap it approaches (blocks). */
    static final double CEILING_BAND = 32, CEILING_GAP = 4;
    /** D4 detail amplitude (blocks, ±) and the carved heights over which it fades in. */
    static final double DETAIL = 20, DETAIL_FLOOR = 15, DETAIL_FULL = 70, DETAIL_PROBE = 12, DETAIL_BEND = 6;
    private static double softMax(double a, double b) {
        double h = Math.max(FLOOR_SOFTNESS - Math.abs(a - b), 0) / FLOOR_SOFTNESS;
        return Math.max(a, b) + h * h * FLOOR_SOFTNESS * .25;
    }

    @Override public void apply(Cell cell, float x, float z) { apply(cell, (double) x, z); }

    public void apply(Cell cell, double x, double z) {
        if (closed) throw new IllegalStateException("Rolling Highlands owner is closed");
        var location = coast.sample(x, z);
        cell.rollingHighlands = true;
        clearWater(cell);
        cell.waterTable = 0;
        cell.highlandRange = 0; cell.highlandSnowline = 0; cell.highlandTreeline = 0; cell.highlandFoot = 0;
        cell.highlandCoast = 0; cell.highlandCoastClimate = HighlandCoastProfile.CALIFORNIA;
        cell.continentX = location.centerX(); cell.continentZ = location.centerZ();
        cell.continentId = (float) noise(971, location.ownerX(), location.ownerZ(), 1.37);
        cell.continentEdge = (float) location.edge();
        double blend = drainage.landBlend(location.edge());
        cell.beachNoise = beachNoise.compute((float) x, (float) z, 0);
        if (location.edge() <= coastEdge) {
            oceans.apply(cell, (float) x, (float) z);
            // Shoreface: beyond the shallow-ocean point the legacy lerp eases the floor up to sea level by edge value, which
            // leaves wide one-block-deep flats wherever the edge field lingers just under the coast point (in-game
            // 2026-11-04: dirt/gravel/clay patchwork seabeds). The floor instead deepens with distance from the shoreline.
            if (location.edge() > shallowOceanEdge) {
                // RH9: below sea cliffs the floor drops within a few blocks (the run shortens with the cliff weight).
                double shoreface = SHOREFACE_RUN * (1 - CLIFF_SHOREFACE * coast.profile().shore(x, z).cliff());
                double run = Math.min(1, shoreDistance(x, z, location.edge()) / shoreface);
                cell.height = Math.min(cell.height, normalized(-shelfDepth * Math.pow(run, 2.0 / 3)));
            }
            // RH9 X: sea stacks off cliff coasts (ocean ground only, so drainage never sees them).
            double floor = (cell.height - levels.water) * levels.terrainScaleFactor, stack = seaStack(cell, x, z, location.edge(), floor);
            if (stack > floor) { cell.height = normalized(stack); cell.highlandCoast = 1; }
            cell.terrainErosion = cell.erosion;
            cell.climateHeight = cell.height;
            return;
        }
        // Legacy FTF land (regions, mountains, Blender) also writes the native terrain type, erosion and weirdness.
        // Valleys only carve into it, so interfluves never depend on which river is nearest.
        var index = plan(location.ownerX(), location.ownerZ());
        // RH7 D: on ranges the drainage relief cuts the valleys, so the plan's incision (computed on the uncarved range)
        // fades out toward the range core instead of stacking on it.
        double incision = index.incision(x, z) * (1 - land.rangeCore(x, z));
        double height = groundBeforeTarns(cell, x, z, location.edge(), index, blend, incision);
        double ceiling0 = levels.worldHeight - levels.waterY - CEILING_BAND;
        if (!cell.hasRiverWater) {
            var tarn = index.relief.tarn(x, z);
            if (tarn != null) height = shapeTarn(cell, index, tarn, height, ceiling0);
        }
        // Soft compression in the last CEILING_BAND blocks below the world top (RH7 M): tall ranges flatten toward
        // CEILING_GAP blocks below the top instead of being cut off.
        double ceiling = levels.worldHeight - levels.waterY - CEILING_BAND;
        if (height > ceiling) height = ceiling + (CEILING_BAND - CEILING_GAP) * (1 - Math.exp(-(height - ceiling) / (CEILING_BAND - CEILING_GAP)));
        cell.height = normalized(height);
        cell.climateHeight = normalized(Math.min(ceiling, index.climateHeight(x, z)));
        rangeBands(cell, height, blend * cell.highlandFoot - incision);
        coastFace(cell, x, z, location.edge());
        classify(cell);
    }
    /**
     * Sea shore, after the shared climate pass (it tags whole biome regions near the coast as COAST, which only some
     * lookup paths later turn into beaches, so beaches came and went with cache state). RH land keeps its own terrain;
     * low dry ground within the coast profile's beach width (W on beaches, W_b in front of bluffs; none below sea
     * cliffs) becomes BEACH, with the beach noise cleared so its continentalness reads as coast and it takes the beach
     * biome.
     *
     * @param landTerrain the cell's terrain as {@link #apply} left it, before the climate pass
     */
    public void shore(Cell cell, etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain landTerrain, double x, double z) {
        if (cell.continentEdge <= coastEdge) return;
        if (cell.terrain == TerrainType.COAST || cell.terrain == TerrainType.SHALLOW_OCEAN) cell.terrain = landTerrain;
        if (cell.hasRiverWater || cell.terrain.isWetland()) return;
        double height = (cell.height - levels.water) * levels.terrainScaleFactor;
        if (height <= 0) return;
        if (marsh(cell, height, x, z)) return;
        var profile = coast.profile().shore(x, z);
        if (profile.cliff() >= .5) return;
        if (height > HighlandCoastProfile.BERM + HighlandCoastProfile.DUNE * profile.dunes() + 1) return;
        double width = (profile.beach() * profile.width() + profile.bluff() * HighlandCoastProfile.bluffWidth(profile.width()))
            / (profile.beach() + profile.bluff());
        if (shoreDistance(x, z, cell.continentEdge) > width) return;
        cell.terrain = TerrainType.BEACH;
        cell.beachNoise = 0;
        // Vanilla places beaches on the coast in the low weirdness slice at erosion levels 3-4; the land's own slice
        // would give forest or windswept biomes there. Erosion levels 0-2 stay (stony shore).
        cell.weirdness = (float) clamp(cell.weirdness, BEACH_WEIRDNESS_MIN, BEACH_WEIRDNESS_MAX);
        if (cell.erosion >= BEACH_EROSION_MIN) cell.erosion = (float) Math.min(cell.erosion, BEACH_EROSION_MAX);
    }
    /**
     * RH10 calm bays (D-16): low ground at the edge of bay water is marsh, WETLAND terrain with the legacy wetland erosion,
     * which selects a swamp-type biome. It needs a ria weight of at least MARSH_WEIGHT, ground at most MARSH_HEIGHT blocks
     * above the water within MARSH_REACH blocks of it, no river valley (the river-mouth beach stays), and falls on the
     * patches where a slow noise is at least MARSH_PATCH, so marsh and small beaches alternate along a bay shore. Inputs are
     * position-only (and the ground, which every lookup path computes alike), so the tag does not depend on the path.
     */
    private boolean marsh(Cell cell, double height, double x, double z) {
        if (height > MARSH_HEIGHT || cell.riverMask < MARSH_RIVER) return false;
        if (coast.drowned().ria(x, z) < MARSH_WEIGHT || noise(marshSeed, x, z, MARSH_SCALE) < MARSH_PATCH) return false;
        if (shoreDistance(x, z, cell.continentEdge) > MARSH_REACH) return false;
        cell.terrain = TerrainType.WETLAND;
        cell.erosionMask = true;
        cell.erosion = WETLAND_EROSION;
        cell.terrainErosion = cell.erosion;
        return true;
    }
    /** Marsh: minimum ria weight, height (blocks above water) and reach from the water (blocks), river valley mask floor, patch threshold and wavelength (blocks). */
    static final double MARSH_WEIGHT = .5, MARSH_HEIGHT = 1.5, MARSH_REACH = 24, MARSH_RIVER = .15, MARSH_PATCH = .45, MARSH_SCALE = 160;
    /**
     * RH9 M: coastal scarp weight (bluff faces from W_b, sea-cliff faces from the waterline, fading over COAST_FACE
     * blocks behind the face) and the shore's climate class, within the coastal band only.
     */
    private void coastFace(Cell cell, double x, double z, double edge) {
        if (edge - coastEdge >= HighlandDrainage.COAST_BAND) return;
        var shore = coast.profile().shore(x, z);
        cell.highlandCoastClimate = HighlandCoastProfile.climateClass(shore);
        if (shore.bluff() + shore.cliff() <= 0) return;
        double d = shoreDistance(x, z, edge), wb = HighlandCoastProfile.bluffWidth(shore.width());
        double cliff = 1 - smooth(HighlandDrainage.CLIFF_FACE, HighlandDrainage.CLIFF_FACE + COAST_FACE, d);
        double bluff = smooth(wb - 1, wb, d) * (1 - smooth(wb + HighlandDrainage.BLUFF_SCARP, wb + HighlandDrainage.BLUFF_SCARP + COAST_FACE, d));
        cell.highlandCoast = (float) (shore.cliff() * cliff + shore.bluff() * bluff);
    }
    /** Blocks behind a scarp's face over which its rock cover weight fades. */
    static final double COAST_FACE = 8;
    /**
     * Blocks from the coastline, from the edge value's distance to the coast point over its local slope: close to exact
     * near the shore, larger where the edge field levels off (the middle of a strait or bay), infinite where it is flat.
     */
    private double shoreDistance(double x, double z, double edge) {
        double slope = coast.edgeSlope(x, z, edge);
        return slope > 0 ? Math.abs(edge - coastEdge) / slope : Double.POSITIVE_INFINITY;
    }
    /**
     * RH9 X: ground height (blocks above water) of a sea stack at an ocean column, or {@code floor} where there is none.
     * One candidate per STACK_SPACING grid cell (jittered centre, radius, top); it stands where the shore at its centre
     * is a sea cliff (weight ≥ STACK_CLIFF), with chance STACK_CHANCE (×PNW_STACKS on cool/wet coasts), and its centre
     * lies in the sea STACK_NEAR + radius to STACK_FAR blocks from the coastline. The outline is roughened by noise, the
     * sides drop over STACK_SIDE blocks to the floor and the top is rough. The tallest stack here writes its coast
     * climate class to the cell (its rock palette). RH10: the chance falls with the drowned-region weight at the centre.
     */
    private double seaStack(Cell cell, double x, double z, double edge, double floor) {
        if (edge < coastEdge - STACK_EDGE_REACH) return floor;
        int gx = (int) Math.floor(x / STACK_SPACING), gz = (int) Math.floor(z / STACK_SPACING);
        double best = floor;
        for (int i = gx - 1; i <= gx + 1; i++) for (int j = gz - 1; j <= gz + 1; j++) {
            long h = mix(stackSeed ^ (i * 0x9e3779b97f4a7c15L) ^ (j * 0x632be59bd9b4e019L));
            double roll = unit(h);
            if (roll >= STACK_CHANCE * PNW_STACKS) continue;
            double cx = (i + unit(mix(h + 1))) * STACK_SPACING, cz = (j + unit(mix(h + 2))) * STACK_SPACING;
            double r = STACK_MIN_RADIUS + (STACK_MAX_RADIUS - STACK_MIN_RADIUS) * unit(mix(h + 3));
            double outline = r * (1 + STACK_ROUGH * (2 * noise(stackSeed, x, z, STACK_ROUGH_SCALE) - 1));
            double rho = Math.hypot(x - cx, z - cz);
            if (rho >= outline) continue;
            var shore = coast.profile().shore(cx, cz);
            if (shore.cliff() < STACK_CLIFF) continue;
            byte climate = HighlandCoastProfile.climateClass(shore);
            double chance = STACK_CHANCE * (climate == HighlandCoastProfile.PNW ? PNW_STACKS : 1);
            if (roll >= chance) continue;
            var centre = coast.sample(cx, cz);
            double centreEdge = centre.edge();
            if (centreEdge > coastEdge) continue;
            // RH10: fewer stacks in drowned regions, so they do not clog the arms.
            if (roll >= chance * (1 - centre.drowned())) continue;
            double d = shoreDistance(cx, cz, centreEdge);
            if (d < STACK_NEAR + r || d > STACK_FAR) continue;
            double top = STACK_MIN_TOP + (STACK_MAX_TOP - STACK_MIN_TOP) * unit(mix(h + 4)) - STACK_TOP_ROUGH * noise(stackSeed + 1, x, z, 3);
            double height = floor + (top - floor) * (1 - smooth(outline - STACK_SIDE, outline, rho));
            if (height > best) { best = height; cell.highlandCoastClimate = climate; }
        }
        return best;
    }
    private static double unit(long h) { return (h >>> 11) * 0x1.0p-53; }
    /** Sea-stack grid spacing, chance and cool/wet multiplier, minimum centre cliff weight (SPEC-RH9 X). */
    static final double STACK_SPACING = 48, STACK_CHANCE = .25, PNW_STACKS = 1.5, STACK_CLIFF = .6;
    /** Stack radius, outline roughness (fraction) and its wavelength, side drop, top range and roughness (blocks). */
    static final double STACK_MIN_RADIUS = 1.5, STACK_MAX_RADIUS = 6, STACK_ROUGH = .25, STACK_ROUGH_SCALE = 4,
        STACK_SIDE = 1.5, STACK_MIN_TOP = 5, STACK_MAX_TOP = 25, STACK_TOP_ROUGH = 2;
    /** Centre distance from the coastline (blocks: STACK_NEAR + radius to STACK_FAR); edge reach that skips the search. */
    static final double STACK_NEAR = 10, STACK_FAR = 60, STACK_EDGE_REACH = .04;
    /** Shoreface run (blocks from the coastline to the shelf depth) and how much of it a sea cliff takes away. */
    static final double SHOREFACE_RUN = 48, CLIFF_SHOREFACE = .8;
    /** Vanilla's low weirdness slice (normal side) and erosion levels 3-4, inset from the slice boundaries. */
    static final double BEACH_WEIRDNESS_MIN = -.26, BEACH_WEIRDNESS_MAX = -.06, BEACH_EROSION_MIN = -.2225, BEACH_EROSION_MAX = .44;
    /**
     * RH7 G2 tarn in a cirque floor. The planned water sits at the tarn's level above the land under the range, taken
     * once at the tarn's centre so the surface is flat; it is lowered to one block below the lowest real ground on the
     * footprint's rim (TARN_RIM band), so the ground itself holds it. Inside the footprint, ground below the water is
     * flooded (bed at least one block, up to TARN_BED toward the middle, below the water); on the rim, ground lower
     * than one block above the water is raised to that only to cover rim sampling between RIM_STRIDE samples.
     */
    private double shapeTarn(Cell cell, HighlandPlanIndex index, HighlandRangeRelief.Tarn tarn, double height, double ceiling) {
        double water = index.relief.tarnWater(tarn, t -> {
            var site = coast.sample(t.centerX(), t.centerZ());
            var scratch = new Cell();
            land.apply(scratch, t.centerX(), t.centerZ());
            double incision = index.incision(t.centerX(), t.centerZ()) * (1 - land.rangeCore(t.centerX(), t.centerZ()));
            double planned = drainage.landBlend(site.edge()) * (scratch.highlandFoot + t.level()) - incision;
            double[] box = index.relief.tarnBounds(t.id());
            double rim = Double.POSITIVE_INFINITY;
            for (double z = box[1]; z <= box[3]; z += RIM_STRIDE) for (double x = box[0]; x <= box[2]; x += RIM_STRIDE) {
                var sample = index.relief.tarn(x, z);
                if (sample == null || sample.id() != t.id() || sample.weight() > TARN_RIM) continue;
                rim = Math.min(rim, groundBeforeTarns(scratch, x, z));
            }
            return Math.min(planned, rim - 1);
        });
        if (water <= 1 || water + 2 > ceiling) return height;
        // The shoreline follows the ground's own contour at the water level inside the tarn's footprint.
        if (tarn.weight() > TARN_RIM && height < water) {
            height = Math.min(height, water - 1 - TARN_BED * smooth(.5, 1, tarn.weight()));
            cell.riverWaterLevel = normalized(water); cell.hasRiverWater = true;
            cell.terrain = TerrainType.RIVER; cell.riverZone = RiverZone.Riverbed;
            cell.riverMask = 0;
            return height;
        }
        return tarn.weight() > TARN_RIM ? height : Math.max(height, water + 1);
    }
    /** RH7 G2: extra tarn bed depth toward its middle (blocks), and the footprint weight below which ground is a rim. */
    static final double TARN_BED = 4, TARN_RIM = .15;
    /** Spacing (blocks) of the rim samples that set a tarn's water. */
    static final double RIM_STRIDE = 2;

    /**
     * Ground before tarn shaping at a position (blocks above sea level), as {@link #apply} renders it; a wet column
     * (river, lake, wetland pool) counts as its water surface. Uses {@code scratch} as working state.
     */
    double groundBeforeTarns(Cell scratch, double x, double z) {
        var location = coast.sample(x, z);
        if (location.edge() <= coastEdge) return 0;
        clearWater(scratch);
        var index = plan(location.ownerX(), location.ownerZ());
        double incision = index.incision(x, z) * (1 - land.rangeCore(x, z));
        double height = groundBeforeTarns(scratch, x, z, location.edge(), index, drainage.landBlend(location.edge()), incision);
        if (!scratch.hasRiverWater) return height;
        return Math.min(height, (scratch.riverWaterLevel - levels.water) * levels.terrainScaleFactor);
    }

    /**
     * Rendered land = legacy land lowered by the plan's smooth stream-power incision: uplands keep every legacy detail,
     * and valleys deepen exactly where the drainage computed on this same land says they should. Then channels, oxbows
     * and lakes. Writes their cell fields; the legacy land also writes the native terrain type, erosion and weirdness.
     */
    private double groundBeforeTarns(Cell cell, double x, double z, double edge, HighlandPlanIndex index, double blend, double incision) {
        var hits = index.near(x, z);
        double raw = rawLand(cell,x,z,edge,index,hits,incision);
        var lake = index.lake(x, z);
        double height = groundBeforeLakes(cell, x, z, edge, index, hits, blend, raw);
        if (lake != null) height = shapeLake(cell, x, z, index, lake, height);
        else if (!cell.hasRiverWater) {
            var basins = index.plan().basins();
            height = basins.shapeBank(x,z,height);
            // Later erosion/relaxation must not cut the one-block barrier that final containment validated.
            if (Double.isFinite(basins.adjacentWater(x,z))) cell.erosionMask = true;
        }
        return height;
    }

    /** Legacy land on the coastal rise (RH9 coast profile, as the plan sees it) lowered by the plan's incision. */
    private double rawLand(Cell cell, double x, double z, double edge, HighlandPlanIndex index, HighlandPlanIndex.Hits hits,
                           double incision) {
        return drainage.coastalLand(x, z, edge, land.apply(cell,x,z)-dissection(index,x,z,hits), true)-incision;
    }

    private double groundBeforeLakes(Cell cell, double x, double z, double edge, HighlandPlanIndex index,
                                    HighlandPlanIndex.Hits hits, double blend, double raw) {
        double surface = floored(blend, raw, hits);
        double height = carveValleys(cell, x, z, edge, index, hits, surface);
        if (!cell.hasRiverWater) {
            var oxbow = index.oxbow(x, z);
            if (oxbow != null) height = shapeOxbow(cell, x, z, oxbow, height);
        }
        return height;
    }

    private void clearWater(Cell cell) {
        cell.hasRiverWater = false;
        cell.riverWaterLevel = levels.water;
        cell.riverMask = 1;
        cell.riverZone = RiverZone.None;
        cell.hasFlow = false;
        cell.erosionMask = false;
    }

    /** Excavate the finalized water body. Residual dry-bank sealing is handled separately with a fixed budget. */
    private double shapeLake(Cell cell, double x, double z, HighlandPlanIndex plan, HighlandBasins.Sample lake, double height) {
        double water = lake.water(), depth = lake.depth();
        if (depth > 0) {
            boolean channel = cell.hasFlow;
            cell.riverWaterLevel = normalized(water); cell.hasRiverWater = true;
            cell.terrain = TerrainType.RIVER; cell.riverZone = RiverZone.Riverbed;
            cell.riverMask = 0;
            // Open water is calm: a channel crossing the lake keeps a damped current, and the water drifts
            // toward the outlet within OUTLET_DRIFT blocks of it.
            double ox = plan.basinsOutletX(lake.lake()), oz = plan.basinsOutletZ(lake.lake());
            double toOutlet = Double.isNaN(ox) ? Double.POSITIVE_INFINITY : Math.hypot(ox - x, oz - z);
            if (toOutlet < OUTLET_DRIFT) {
                setFlow(cell, LAKE_DRIFT * (1 - toOutlet / OUTLET_DRIFT) + .01, ox - x, oz - z);
            } else if (channel) {
                double angle = ((cell.flowAngle & 0x1F) / 32.0) * Math.PI * 2;
                setFlow(cell, LAKE_DRIFT, Math.cos(angle), Math.sin(angle));
            } else {
                cell.hasFlow = false; cell.flowAngle = 0;
            }
            // The depth cap limits excavation; it must never fill an already deeper valley.
            return Math.min(height, water - Math.min(depth, 16));
        }
        return height;
    }

    /**
     * Oxbow (abandoned meander loop on the valley floor): still, shallow water within the open-water half width;
     * around it and along its silted-up ends a wetland margin lowered toward the water level with hummocks. Only
     * lowers the floor, and only where the valley floor (not a slope) lies under it.
     */
    private double shapeOxbow(Cell cell, double x, double z, HighlandPlanIndex.OxbowSample oxbow, double height) {
        double level = oxbow.water(), d = oxbow.distance(), half = oxbow.halfWidth();
        double floor = 1 - smooth(0, .3, cell.riverMask);
        if (floor <= 0) return height;
        if (d < half && height > level - 1) {
            double depth = OXBOW_DEPTH * (1 - (d / half) * (d / half));
            cell.riverWaterLevel = normalized(level); cell.hasRiverWater = true;
            cell.terrain = TerrainType.RIVER; cell.riverZone = RiverZone.Riverbed;
            cell.hasFlow = false; cell.flowAngle = 0;
            return Math.min(height, level - Math.max(.5, depth));
        }
        double margin = floor * (1 - smooth(half + OXBOW_MARGIN * .5, half + OXBOW_MARGIN, d));
        if (margin <= 0) return height;
        double hummock = .5 + .5 * noise(classSeed + 31, x, z, 7);
        // Never more than half a block below the water of the channel owning this floor (its banks stay sealed).
        double owner = (cell.riverWaterLevel - levels.water) * levels.terrainScaleFactor;
        double shaped = Math.max(Math.max(level + .2, owner - .5), lerp(height, Math.min(height, level + hummock), margin));
        if (margin > .5) { cell.terrain = TerrainType.WETLAND; cell.erosionMask = true; }
        return Math.min(height, shaped);
    }

    /**
     * Composes every nearby reach's valley section.
     * <ul>
     * <li>Wet: a point inside any reach's wet radius belongs to the wet reach with the lowest bed, so a channel is
     * never erased by a neighbouring valley floor.</li>
     * <li>Dry: the two lowest candidates (including the land) are joined with a smooth minimum only where both lie
     * on outer valley slopes and below the land, then lifted onto the support cone of every channel (one block
     * above its water at the bank, falling half a block per block). Channels at different levels therefore sit on
     * terraces instead of being cut away, and dry ground never lies below adjacent channel water.</li>
     * </ul>
     * Every term is continuous in position and neutral when a reach enters or leaves query range.
     */
    private double carveValleys(Cell cell, double x, double z, double edge, HighlandPlanIndex index, HighlandPlanIndex.Hits hits, double land) {
        double maxInfluence = 0, support = Double.NEGATIVE_INFINITY;
        double flowX = 0, flowZ = 0, speed = 0, flowWeight = 0;
        int wetOwner = -1;
        HighlandValley.Section wetSection = null;
        // Kept out of the climate coast band, where shared climate code would reclassify WETLAND as coast/ocean.
        double wetlandMask = smooth(wetlandLow, wetlandLow + .2, noise(classSeed + 7, x, z, 220)) * smooth(coastMarker + .1, coastMarker + .25, edge);
        double hummock = 1.6 * (2 * noise(classSeed + 8, x, z, 24) - 1) + .6 * (2 * noise(classSeed + 9, x, z, 9) - 1);
        double area2 = (double) settings.uplandSpacing() * settings.uplandSpacing();
        Scratch scratch = SCRATCH.get().ensure(hits.count);
        // Channels close enough to affect another valley's belt widening (see below).
        int closeCount = 0;
        for (int k = 0; k < hits.count; k++) if (hits.distance[k] < BELT_YIELD_FAR) scratch.close[closeCount++] = k;
        // FTF valley texture (UpliftRiverCarver noises, same scales and value ranges): width, asymmetry, pinch, depth,
        // terrace and gully/rivulet drainage masks.
        double widthVar = noise(classSeed + 8241, x, z, 150), asymmetry = noise(classSeed + 1193, x, z, 250);
        double pinch = clamp(1 + 2 * noise(classSeed + 6204, x, z, 360), .05, 1.95);
        double depthVar = noise(classSeed + 3912, x, z, 100), terrace = noise(classSeed + 5510, x, z, 200);
        double gully = 1 - noise(classSeed + 9876, x, z, 65), rivulet = 1 - noise(classSeed + 5432, x, z, 20);
        double drainage = .7 * gully * gully + .3 * rivulet * rivulet * rivulet;
        double dynamic = 1 + .35 * widthVar, side = 1 + .4 * asymmetry;
        for (int k = 0; k < hits.count; k++) {
            double water = hits.water[k], distance = hits.distance[k], confinement = hits.confinement[k];
            double q = Math.max(.01, hits.area[k] / area2);
            double head = hits.head[k];
            double flat = 1 - smooth(.002, .03, hits.slope[k]);
            // Channel size from discharge (hydraulic geometry); heads start as narrow shallow streams. Valley floor and
            // fadeout follow FTF's main-river bank width, pinch and relief-scaled fadeout, widened with drainage area
            // (floor ~ A^0.3) and narrowed where the valley is confined.
            // RH5: creeks (small drainage area) have a narrow channel, a shallow bed, low banks and a narrow floor; all
            // of it grows continuously into the established-river section as the drainage area reaches riverArea.
            double creek = this.drainage.creekness(hits.area[k]);
            double radius = Math.max(MIN_RADIUS, this.drainage.halfWidth(hits.area[k], head) * dynamic);
            double hydraulic = clamp(Math.pow(q / .1, .3), .5, 2) * settings.valleyWidth() / 256.0;
            double floorHalf = river.bankWidth * dynamic * pinch * hydraulic * lerp(1, .4, confinement) * head * head * lerp(1, CREEK_FLOOR, creek);
            double fadeRun = Math.min(MAX_FADE, river.bankWidth * dynamic * pinch * Math.max(.5, hydraulic)
                * (5 + Math.max(0, land - water) / 100) * lerp(1, .6, confinement));
            double bedDepth = lerp(Math.max(2, 2.3 + .3 * depthVar + river.bedDepth * clamp(q / .3, 0, 1) * flat) * lerp(.4, 1, head),
                CREEK_BED + .3 * depthVar, creek);
            double bankRun = Math.max(3, (river.maxBankHeight - river.minBankHeight) * dynamic * side) * lerp(1, .5, creek);
            // The axis floor contains the meander belt; the valley still ends inside the index influence.
            double belt = hits.belt[k], axisDistance = hits.axis[k];
            // The belt widens this floor only away from higher channels: a floodplain never undercuts a tributary
            // or a neighbouring river running above it (continuous in both distance and level difference).
            for (int n = 0; n < closeCount && belt > 0; n++) {
                int j = scratch.close[n];
                if (j == k) continue;
                double higher = smooth(.5, 2, hits.water[j] - water);
                if (higher > 0) belt *= 1 - higher * (1 - smooth(BELT_YIELD_NEAR, BELT_YIELD_FAR, hits.distance[j]));
            }
            double outer = Math.min(Math.max(MIN_OUTER_RUN, fadeRun), HighlandPlanIndex.INFLUENCE - 8 - belt - (radius + bankRun + floorHalf));
            var shape = new FtfValley.Shape(radius, bankRun, floorHalf, Math.max(8, outer), bedDepth,
                (Math.max(1, river.maxBankHeight - river.minBankHeight) + .4 * terrace - .6 * drainage) * lerp(1, .5, creek), terrace, drainage);
            var section = FtfValley.sample(distance, axisDistance, belt, land, water, shape);
            maxInfluence = Math.max(maxInfluence, section.influence());
            support = Math.max(support, water + 1 - SUPPORT_SLOPE * Math.max(0, distance - shape.bankEnd()));
            double carve = section.height();
            scratch.section[k] = section;
            scratch.carve[k] = carve;
            scratch.radius[k] = radius;
            // Floodplain wetlands: broad, flat, low valley floors of larger rivers hold pools and hummocks at the
            // river's water level. The weight is a product of continuous factors, so the composition stays smooth.
            double wetland = 0;
            if (wetlandMask > 0 && !section.wet()) {
                double floorStart = shape.bankEnd(), floorEnd = shape.floorEnd();
                wetland = wetlandMask * smooth(.04, .15, q) * (1 - smooth(.006, .02, hits.slope[k])) * (1 - smooth(40, 90, water))
                    * smooth(floorStart, floorStart + 12, distance) * (1 - smooth(floorEnd + belt - 32, floorEnd + belt, axisDistance)) * head;
            }
            scratch.wetland[k] = wetland;
        }
        // Confluence form (RH4 R5), within each junction's radius and fading to nothing at its edge:
        // - rounded tongue: the wet test uses a smooth union of the two touching channels' wet fields, so the dry apex
        //   between them is rounded instead of a knife edge;
        // - scour pool: the bed deepens toward the junction, more for wide angles and large tributaries (Best 1988);
        // - bar: a shallow submerged bar just downstream of the mouth on the tributary side.
        double fillet = Double.POSITIVE_INFINITY, scour = 0, bar = 0;
        int filletOwner = -1;
        for (int candidate : index.junctionCandidates(x, z)) {
            var junction = index.junction(candidate);
            double jx = x - junction.x(), jz = z - junction.z();
            if (jx * jx + jz * jz >= HighlandPlanIndex.JUNCTION_REACH * HighlandPlanIndex.JUNCTION_REACH) continue;
            double dj = Math.sqrt(jx * jx + jz * jz);
            double mainWidth = 2 * this.drainage.halfWidth(junction.mainArea(), 1);
            double reach = clamp(3 * mainWidth, 24, HighlandPlanIndex.JUNCTION_REACH);
            if (dj >= reach) continue;
            double fade = 1 - smooth(.5 * reach, reach, dj);
            int a = -1, b = -1;
            for (int k = 0; k < hits.count; k++) {
                if (!index.touches(hits.reach[k], junction.node())) continue;
                double e = hits.distance[k] - scratch.radius[k];
                if (a < 0 || e < hits.distance[a] - scratch.radius[a]) { b = a; a = k; }
                else if (b < 0 || e < hits.distance[b] - scratch.radius[b]) b = k;
            }
            if (a >= 0 && b >= 0) {
                double k = FILLET * Math.min(scratch.radius[a], scratch.radius[b]) * fade;
                double e = smin(hits.distance[a] - scratch.radius[a], hits.distance[b] - scratch.radius[b], k);
                if (e < fillet) { fillet = e; filletOwner = a; }
            }
            double ratio = clamp(junction.tributaryArea() / Math.max(1, junction.mainArea()), 0, 1);
            double strength = SCOUR * lerp(.4, 1, smooth(30, 90, junction.angle())) * lerp(.3, 1, smooth(.05, .5, ratio));
            scour = Math.max(scour, strength * (1 - smooth(0, 1.5 * mainWidth, dj)));
            double bx = junction.x() + junction.flowX() * 1.5 * mainWidth + junction.sideX() * .3 * mainWidth;
            double bz = junction.z() + junction.flowZ() * 1.5 * mainWidth + junction.sideZ() * .3 * mainWidth;
            double along = ((x - bx) * junction.flowX() + (z - bz) * junction.flowZ()) / (1.2 * mainWidth);
            double across = ((x - bx) * junction.sideX() + (z - bz) * junction.sideZ()) / (.3 * mainWidth);
            bar = Math.max(bar, (1 - smooth(.5, 1, Math.hypot(along, across))) * smooth(.05, .3, ratio) * fade);
        }
        // Wet ownership: the wet channel with the lowest bed owns the cell (parallel channels are joined upstream by
        // stream capture in the plan, so channels meet at confluences rather than side by side).
        for (int k = 0; k < hits.count; k++) {
            var section = scratch.section[k];
            if (!section.wet()) continue;
            double carve = scratch.carve[k], distance = hits.distance[k], radius = scratch.radius[k];
            if (wetSection == null || carve < wetSection.height()) { wetOwner = k; wetSection = section; }
            // Current: downstream along the channel, turning gently toward the centre near the banks, fastest
            // mid-channel and on steep reaches. Overlapping channels (confluences) blend by weight.
            double across = clamp(distance / radius, 0, 1), w = 1 - across + 1e-3;
            double inward = distance > 1e-6 ? .35 * across / distance : 0;
            double dx = hits.flowX[k] - hits.offsetX[k] * inward, dz = hits.flowZ[k] - hits.offsetZ[k] * inward;
            double norm = Math.max(1e-9, Math.hypot(dx, dz));
            flowX += w * dx / norm; flowZ += w * dz / norm; flowWeight += w;
            speed += w * clamp(FLOW_BASE + FLOW_SLOPE * hits.slope[k], FLOW_MIN, 1) * (1 - .5 * across * across);
        }
        // Wetland pools need containment: fade them where another valley lies within a few blocks of (or below)
        // this river's level, otherwise the pool would sit above a neighbouring valley slope.
        double c1 = land, c2 = Double.POSITIVE_INFINITY, o1 = 1, o2 = 1, maxWetland = 0;
        int owner = -1, wetlandOwner = -1;
        HighlandValley.Section ownerSection = null;
        for (int k = 0; k < hits.count; k++) {
            double carve = scratch.carve[k], wetland = scratch.wetland[k], water = hits.water[k];
            if (wetland > 0) {
                // Pools hold this reach's level only where no lower reach (a meander loop further downstream, another
                // river) carves near that level: continuous in both the level difference and the other reach's ground.
                for (int j = 0; j < hits.count && wetland > 0; j++) {
                    if (j == k) continue;
                    double lower = smooth(.5, 1.5, water - hits.water[j]);
                    if (lower <= 0) continue;
                    // RH8: the lower reach's own wetland floods its ground down to its level, which also counts.
                    double other = scratch.carve[j];
                    if (scratch.wetland[j] > 0) other = lerp(other, Math.min(other, hits.water[j] + hummock), scratch.wetland[j]);
                    wetland *= 1 - lower * (1 - smooth(water + 1, water + 6, other));
                }
                if (wetland > 0) carve = lerp(carve, Math.min(carve, water + hummock), wetland);
                if (wetland > maxWetland) { maxWetland = wetland; wetlandOwner = k; }
            }
            double outerness = 1 - scratch.section[k].influence();
            if (carve < c1) { c2 = c1; o2 = o1; c1 = carve; o1 = outerness; owner = k; ownerSection = scratch.section[k]; }
            else if (carve < c2) { c2 = carve; o2 = outerness; }
        }
        cell.riverMask = (float) (1 - maxInfluence);
        boolean filled = false;
        if (wetOwner < 0 && fillet < 0 && filletOwner >= 0) {
            // Inside the rounded confluence apex: shallow water of the nearer channel.
            wetOwner = filletOwner; wetSection = scratch.section[filletOwner]; filled = true;
        }
        if (wetOwner >= 0) {
            // Propagate the existing rapid envelope through all locally connected reaches. A junction can sit one
            // node from a reach boundary; limiting this to direct neighbours gives its tributary a different
            // surface from the main channel when a downstream lake lowers their profile.
            System.arraycopy(hits.water,0,scratch.joinedWater,0,hits.count);
            for (int pass = 0; pass < hits.count-1; pass++) {
                boolean changed = false;
                for (int i = 0; i < hits.count; i++) for (int j = 0; j < hits.count; j++) {
                    if (i == j || scratch.joinedWater[j] >= scratch.joinedWater[i]
                        || !index.connected(hits.reach[j],hits.reach[i])) continue;
                    double cap = scratch.joinedWater[j]+CONFLUENCE_RAPID*Math.max(0,hits.distance[j]-scratch.radius[j]);
                    if (cap < scratch.joinedWater[i]) { scratch.joinedWater[i] = cap; changed = true; }
                }
                if (!changed) break;
            }
            double water = scratch.joinedWater[wetOwner];
            cell.riverWaterLevel = normalized(water);
            cell.riverZone = RiverZone.Riverbed;
            cell.hasRiverWater = true; cell.terrain = TerrainType.RIVER;
            if (flowWeight > 0) setFlow(cell, speed / flowWeight, flowX, flowZ);
            else setFlow(cell, FLOW_MIN, hits.flowX[wetOwner], hits.flowZ[wetOwner]);
            double bed = filled ? water - 1 - Math.min(2, -.5 * fillet) : Math.min(wetSection.height(), water - 1);
            // Scour deepens the bed in proportion to its depth; the bar lifts it toward one block below the water.
            bed = water - (water - bed) * (1 + scour);
            return Math.max(bed, lerp(bed, water - 1, bar));
        }
        double softness = CREASE_SOFTNESS * Math.min(o1, o2) * smooth(0, 8, land - c2);
        double height = Math.max(smin(c1, c2, softness), support);
        if (owner >= 0 && ownerSection.influence() > 0) {
            cell.riverWaterLevel = normalized(hits.water[owner]);
            cell.riverZone = RiverZone.values()[ownerSection.zone()];
        }
        if (wetlandOwner >= 0) {
            // Legacy wetland contract: WETLAND terrain, protected from erosion filters, standing water wherever the
            // ground lies below the river level (pools are enclosed by the floor and hummocks). Classification uses
            // the strongest wetland weight, a continuous field, rather than which valley happens to be lowest.
            double level = hits.water[wetlandOwner];
            boolean pool = maxWetland > .02 && height < level - .05;
            if (maxWetland > .5 || pool) {
                cell.terrain = TerrainType.WETLAND;
                cell.erosionMask = true;
            }
            if (pool) {
                cell.riverWaterLevel = normalized(level);
                cell.hasRiverWater = true;
                cell.riverZone = RiverZone.Riverbed;
            }
        }
        return height;
    }
    /**
     * Biome-facing parameters: the legacy land populators already wrote FTF-native terrain type, erosion and
     * weirdness (so shares match legacy worlds). Carved valleys pull weirdness toward vanilla's low slice and
     * erosion toward flatter values, so valley floors never keep a mountain classification.
     */
    /**
     * RH7 S: on ranges, the snowline sits Snowline% of the Range Height above the range foot and the treeline
     * TREELINE_GAP of the Range Height below it. Biome parameters follow the column's own carved height: above the
     * treeline toward vanilla's mountain-slope slice (meadow, grove, snowy slopes), above the snowline toward its peak
     * slice; valleys cut below the treeline keep the land's own classification. Surfaces (ScreeFeature) read the same
     * lines from the cell.
     */
    private void rangeBands(Cell cell, double height, double foot) {
        double weight = smooth(RANGE_WEIGHT_LOW, RANGE_WEIGHT_HIGH, cell.highlandRange);
        if (weight <= 0 || land.rangeHeight() <= 0) { cell.highlandRange = 0; return; }
        double snow = foot + land.snowlineHeight(), tree = snow - TREELINE_GAP * land.rangeHeight();
        cell.highlandSnowline = normalized(snow);
        cell.highlandTreeline = normalized(tree);
        double slope = weight * smooth(tree - BAND_BLEND, tree + BAND_BLEND, height);
        double peak = weight * smooth(snow - BAND_BLEND, snow + BAND_BLEND, height);
        if (slope <= 0) return;
        cell.weirdness = (float) lerp(lerp(cell.weirdness, HighlandLand.RANGE_SLOPE_WEIRDNESS, slope), HighlandLand.RANGE_PEAK_WEIRDNESS, peak);
        cell.erosion = (float) lerp(lerp(cell.erosion, HighlandLand.RANGE_SLOPE_EROSION, slope), HighlandLand.RANGE_PEAK_EROSION, peak);
        if (slope > .5) cell.terrain = TerrainType.MOUNTAIN_CHAIN;
    }
    /** RH7 S: range weight band, treeline below the snowline (share of Range Height), band blending (blocks). */
    static final double RANGE_WEIGHT_LOW = .15, RANGE_WEIGHT_HIGH = .45, TREELINE_GAP = .25, BAND_BLEND = 12;

    private void classify(Cell cell) {
        // Only channels, banks and the flat floor (not the fadeout slopes) count as valley bottom.
        double valley = 1 - smooth(0, .15, cell.riverMask);
        if (valley > 0) {
            cell.weirdness = (float) lerp(cell.weirdness, VALLEY_WEIRDNESS, valley);
            cell.erosion = (float) lerp(cell.erosion, VALLEY_EROSION, .7 * valley);
            if (!cell.hasRiverWater && cell.terrain != TerrainType.WETLAND && valley > .5 && cell.terrain == TerrainType.MOUNTAIN_CHAIN) {
                cell.terrain = TerrainType.HILLS;
            }
        }
        if (cell.terrain == TerrainType.WETLAND) {
            // Vanilla's highest erosion level selects swamp (temperate) and mangrove swamp (hot): wet biomes that
            // Streams Reflowing also treats as outlets (#c:is_swamp / #c:is_wet).
            cell.erosion = WETLAND_EROSION;
        }
        cell.terrainErosion = cell.erosion;
        etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.VolcanoPopulator.modifyVolcanoType(cell, levels);
    }

    private static final class Scratch {
        final Cell lakeCell = new Cell();
        HighlandValley.Section[] section = new HighlandValley.Section[64];
        double[] carve = new double[64], wetland = new double[64], radius = new double[64], joinedWater = new double[64];
        int[] close = new int[64];
        Scratch ensure(int size) {
            if (carve.length < size) {
                int n = Math.max(size, carve.length * 2);
                section = new HighlandValley.Section[n]; carve = new double[n]; wetland = new double[n]; radius = new double[n]; joinedWater = new double[n]; close = new int[n];
            }
            return this;
        }
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static void setFlow(Cell cell, double speed, double dx, double dz) {
        cell.flowAngle = etcodehome.freeterraforged.world.worldgen.ChunkFlowField.pack((float) speed, Math.atan2(dz, dx));
        cell.hasFlow = cell.flowAngle != 0;
    }
    private float normalized(double height) { return levels.water + (float) (height / levels.terrainScaleFactor); }
    @Override public float getEdgeValue(float x, float z) { return (float) coast.sample(x, z).edge(); }
    @Override public long getNearestCenter(float x, float z) {
        var s = coast.sample(x, z); return PosUtil.pack(s.centerX(), s.centerZ());
    }
    @Override public Rivermap getRivermap(int x, int z) { return NO_LEGACY_RIVERS; }
    @Override public synchronized void close() { closed = true; cache.clear(); lastPlan.remove(); coast.close(); }
}
