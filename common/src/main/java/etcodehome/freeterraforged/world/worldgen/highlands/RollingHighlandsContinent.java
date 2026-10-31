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
    static final double CHANNEL_DEPTH = 4, SHORE_RATE = .4, MAX_FADE = 384, CONFLUENCE_RAPID = .25, LAKE_RIM = 12, FLOOR_MARGIN = 1.5, FLOOR_SOFTNESS = 4, FLOOR_COAST_RAMP = 6, SUPPORT_SLOPE = .5;
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
    /** Lake shoreline (RH5 S): how far (depth units) the real ground contour may pull the planned shoreline inward. */
    static final double SHORE_BLEND = 12, SHORE_ADVANCE = 2, SHORE_CHANNEL_NEAR = 24, SHORE_CHANNEL_FAR = 64;
    /**
     * Beach (RH6 L4): low ground beside a lake is held up along a ramp that starts BEACH_FLUSH above the water at the
     * planned shoreline, rises one block per BEACH_RUN blocks and levels off BEACH_TOP above the start, instead of a
     * flat shelf one block above the water whose edge reads as a stone step.
     */
    static final double BEACH_FLUSH = .5, BEACH_RUN = 5, BEACH_TOP = 1, BEACH_MIN_GRADIENT = .02;
    /** Effective depth over which a lake bed settles from the real ground to (at least) the planned depth. */
    static final double BED_SETTLE = 2;
    static final double BANK_RUN = 16, MIN_OUTER_RUN = 64, MAX_OUTER_RUN = 512, CREASE_SOFTNESS = 24;
    private static final Rivermap NO_LEGACY_RIVERS = new Rivermap(0, 0, new Network[0], GenWarp.EMPTY);
    private final HighlandCoast coast;
    private final HighlandLand land;
    private final HighlandDrainage drainage;
    private final RollingHighlandsSettings settings;
    private final Levels levels;
    private final double coastEdge;
    private final long classSeed;
    private final double coastMarker;
    /** FTF wetland chance (default 0.5) mapped onto the wetland noise threshold. */
    private final double wetlandLow;
    /** FTF main-river settings (bed depth, bank heights, bank width) shape every RH channel and valley. */
    private final etcodehome.freeterraforged.data.worldgen.preset.settings.RiverSettings.River river;
    private final etcodehome.freeterraforged.world.worldgen.cell.CellPopulator oceans;
    private final etcodehome.freeterraforged.world.worldgen.noise.module.Noise beachNoise;
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
    }
    /**
     * Owner plans: lock-free lookups with a per-thread last-plan fast path; each plan is built once (concurrent
     * requests for the same owner wait on the same future) and the cache keeps the MAX_PLANS most recently used.
     * A plan that cannot be built is replaced by a deterministic river-less plan for that owner and logged once,
     * so world generation never fails on a single continent. Cancellation is not cached.
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
        try {
            return new HighlandPlanIndex(drainage.build(x, z), HighlandRangeRelief.build(land, coast, x, z));
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            etcodehome.freeterraforged.FTFCommon.LOGGER.warn("Rolling Highlands: continent {},{} has no river plan ({}); generating it without rivers",
                x, z, failure.getMessage());
            return new HighlandPlanIndex(drainage.emptyPlan(x, z));
        }
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
    HighlandDrainage drainage() { return drainage; }
    HighlandPlanIndex planOf(int ownerX, int ownerZ) { return plan(ownerX, ownerZ); }
    /** Test access: builds the owner plan at a block position afresh (uncached), for cost measurements. */
    HighlandPlanIndex rebuildAt(double x, double z) { var s = coast.sample(x, z); return buildIndex(s.ownerX(), s.ownerZ()); }
    /** Diagnostics: the land surface before any valley, lake or wetland shaping, in blocks above sea level. */
    double uncarvedLand(double x, double z) {
        var location = coast.sample(x, z);
        if (location.edge() <= coastEdge) return 0;
        return drainage.landBlend(location.edge()) * land.height(x, z);
    }
    /** Diagnostics: the rendered land before channel, lake and wetland shaping. */
    double incisedLand(double x, double z) {
        var location = coast.sample(x, z);
        if (location.edge() <= coastEdge) return 0;
        var index = plan(location.ownerX(), location.ownerZ());
        double blend = drainage.landBlend(location.edge());
        return floored(blend, blend * land.height(x, z) - index.incision(x, z), index.near(x, z));
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
        cell.hasRiverWater = false;
        cell.riverWaterLevel = levels.water;
        cell.riverMask = 1;
        cell.riverZone = RiverZone.None;
        cell.hasFlow = false;
        cell.erosionMask = false;
        cell.waterTable = 0;
        cell.highlandRange = 0; cell.highlandSnowline = 0; cell.highlandTreeline = 0; cell.highlandFoot = 0;
        cell.continentX = location.centerX(); cell.continentZ = location.centerZ();
        cell.continentId = (float) noise(971, location.ownerX(), location.ownerZ(), 1.37);
        cell.continentEdge = (float) location.edge();
        double blend = drainage.landBlend(location.edge());
        cell.beachNoise = beachNoise.compute((float) x, (float) z, 0);
        if (location.edge() <= coastEdge) {
            oceans.apply(cell, (float) x, (float) z);
            cell.terrainErosion = cell.erosion;
            cell.climateHeight = cell.height;
            return;
        }
        // Legacy FTF land (regions, mountains, Blender) also writes the native terrain type, erosion and weirdness.
        // Valleys only carve into it, so interfluves never depend on which river is nearest.
        var index = plan(location.ownerX(), location.ownerZ());
        // Rendered land = legacy land lowered by the plan's smooth stream-power incision: uplands keep every legacy
        // detail, and valleys deepen exactly where the drainage computed on this same land says they should.
        var hits = index.near(x, z);
        // RH7 D: on ranges the drainage relief cuts the valleys, so the plan's incision (computed on the uncarved range)
        // fades out toward the range core instead of stacking on it.
        double incision = index.incision(x, z) * (1 - land.rangeCore(x, z));
        double raw = blend * (land.apply(cell, x, z) - dissection(index, x, z, hits)) - incision;
        var lake = index.lake(x, z);
        double surface = floored(blend, raw, hits);
        double height = carveValleys(cell, x, z, location.edge(), index, hits, surface);
        if (!cell.hasRiverWater) {
            var oxbow = index.oxbow(x, z);
            if (oxbow != null) height = shapeOxbow(cell, x, z, oxbow, height);
        }
        if (lake != null && lake.depth() > -HighlandBasins.MAX_SHORE - SHORE_ADVANCE) {
            // Channels at another level than the lake (outlets, rivers passing below it, rapids above it) hold the
            // shoreline back; channels at the lake's own level (RH6 L2/L3 inflows) may be reached by it.
            double channelDistance = Double.POSITIVE_INFINITY;
            for (int k = 0; k < hits.count; k++) if (Math.abs(hits.water[k] - lake.water()) > .25) channelDistance = Math.min(channelDistance, hits.distance[k]);
            height = shapeLake(cell, x, z, index, lake, height, raw, channelDistance);
        }
        // Soft compression in the last CEILING_BAND blocks below the world top (RH7 M): tall ranges flatten toward
        // CEILING_GAP blocks below the top instead of being cut off.
        double ceiling = levels.worldHeight - levels.waterY - CEILING_BAND;
        if (height > ceiling) height = ceiling + (CEILING_BAND - CEILING_GAP) * (1 - Math.exp(-(height - ceiling) / (CEILING_BAND - CEILING_GAP)));
        cell.height = normalized(height);
        cell.climateHeight = normalized(Math.min(ceiling, index.climateHeight(x, z)));
        rangeBands(cell, height, blend * cell.highlandFoot - incision);
        classify(cell);
    }
    /**
     * Lake bed and shore. Below the surface the bed follows the planning depth (at most 16 deep). Around it the
     * ground descends to one block above the water over a band whose depth range grows with the height it has to
     * descend, so high surroundings get wider shores. The range stays inside the basin's shore region (REGION_DEPTH), so
     * the effect is zero where lake sampling stops.
     */
    private double shapeLake(Cell cell, double x, double z, HighlandPlanIndex plan, HighlandBasins.Sample lake, double height, double raw,
                             double channelDistance) {
        double water = lake.water(), depth = lake.depth();
        // RH5 S: off the channels, the shoreline follows the real ground (unfloored land) at the lake's level: it
        // retreats up to SHORE_BLEND depth units inside the planning field where the ground rises (bays, points,
        // islands) and advances up to SHORE_ADVANCE (RH6: 2, so low flats beside a lake no longer become shallow sheets
        // and the bed never drops more than 2 below the planned depth) where it is low, so outlines trace terrain
        // contours instead of the planning grid's lines. Near channels (inlets, outlets) it does not advance, so lake
        // water never reaches a channel running below the lake level. Continuous: fades with the lake's proximity and the
        // channel distance.
        if (!cell.hasRiverWater) {
            double proximity = lake.proximity();
            double advance = SHORE_ADVANCE * proximity * smooth(SHORE_CHANNEL_NEAR, SHORE_CHANNEL_FAR, channelDistance);
            depth = clamp(water - raw, depth - SHORE_BLEND * proximity, depth + advance);
        }
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
            // RH6 L4: away from its shoreline the bed lies at least at the planned depth, so legacy bumps under the water
            // do not turn the lake into a shallow sheet (the shoreline itself still follows the real ground).
            double bed = Math.max(depth, lake.depth() * smooth(0, BED_SETTLE, depth));
            return water - Math.min(bed, 16);
        }
        if (cell.hasRiverWater) return height;
        // Depth units: the shore band widens with the height it has to descend.
        double range = clamp(Math.max(0, height - water) * SHORE_RATE, 4, HighlandBasins.MAX_SHORE);
        double weight = smooth(-range, 0, depth) * lake.proximity();
        double shore = Math.min(height, water + 1 - depth);
        double shaped = lerp(height, shore, weight);
        // Rim: rendered land carries legacy detail the plan grid does not see, so ground just outside the shoreline
        // can dip below the lake surface. Hold it above the water near the shore along a beach ramp (RH6 L4): distance
        // from the planned shoreline is the planned depth over its horizontal gradient.
        double gradient = BEACH_MIN_GRADIENT;
        var east = plan.lake(x + 2, z);
        var south = plan.lake(x, z + 2);
        if (east != null && south != null && east.lake() == lake.lake() && south.lake() == lake.lake())
            gradient = Math.max(gradient, Math.hypot(east.depth() - lake.depth(), south.depth() - lake.depth()) / 2);
        double beach = water + BEACH_FLUSH + Math.min(BEACH_TOP, Math.max(0, -lake.depth()) / gradient / BEACH_RUN);
        return Math.max(shaped, lerp(shaped, beach, smooth(-LAKE_RIM, 0, depth) * lake.proximity()));
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
                    if (lower > 0) wetland *= 1 - lower * (1 - smooth(water + 1, water + 6, scratch.carve[j]));
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
            // Near a lower connected channel (a confluence), the higher channel's surface descends toward it at a
            // rapid's gradient, so the two waters meet without a step.
            double water = hits.water[wetOwner];
            for (int j = 0; j < hits.count; j++) {
                if (j == wetOwner || hits.water[j] >= water || !index.connected(hits.reach[j], hits.reach[wetOwner])) continue;
                water = Math.min(water, hits.water[j] + CONFLUENCE_RAPID * Math.max(0, hits.distance[j] - scratch.radius[j]));
            }
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
        HighlandValley.Section[] section = new HighlandValley.Section[64];
        double[] carve = new double[64], wetland = new double[64], radius = new double[64];
        int[] close = new int[64];
        Scratch ensure(int size) {
            if (carve.length < size) {
                int n = Math.max(size, carve.length * 2);
                section = new HighlandValley.Section[n]; carve = new double[n]; wetland = new double[n]; radius = new double[n]; close = new int[n];
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
