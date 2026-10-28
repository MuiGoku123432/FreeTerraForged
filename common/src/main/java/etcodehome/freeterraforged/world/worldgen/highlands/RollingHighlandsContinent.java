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
    static final double CHANNEL_DEPTH = 4, SHORE_RATE = .4, MAX_FADE = 384, CONFLUENCE_RAPID = .25, LAKE_RIM = 12;
    /** Current speed model (normalized 0..1 as stored in ChunkFlowField). */
    static final double FLOW_BASE = .45, FLOW_SLOPE = 6, FLOW_MIN = .3, LAKE_DRIFT = .18, OUTLET_DRIFT = 128;
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
            settings.valleyDepth(), context.preset.rivers().riverCount);
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
            return new HighlandPlanIndex(drainage.build(x, z));
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
    HighlandPlanIndex planOf(int ownerX, int ownerZ) { return plan(ownerX, ownerZ); }
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
        return drainage.landBlend(location.edge()) * land.height(x, z) - plan(location.ownerX(), location.ownerZ()).incision(x, z);
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
        cell.continentX = location.centerX(); cell.continentZ = location.centerZ();
        cell.continentId = (float) noise(971, location.ownerX(), location.ownerZ(), 1.37);
        cell.continentEdge = (float) location.edge();
        double blend = drainage.landBlend(location.edge());
        cell.beachNoise = beachNoise.compute((float) x, (float) z, 0);
        if (location.edge() <= coastEdge) {
            oceans.apply(cell, (float) x, (float) z);
            cell.terrainErosion = cell.erosion;
            return;
        }
        // Legacy FTF land (regions, mountains, Blender) also writes the native terrain type, erosion and weirdness.
        // Valleys only carve into it, so interfluves never depend on which river is nearest.
        var index = plan(location.ownerX(), location.ownerZ());
        // Rendered land = legacy land lowered by the plan's smooth stream-power incision: uplands keep every legacy
        // detail, and valleys deepen exactly where the drainage computed on this same land says they should.
        double surface = blend * land.apply(cell, x, z) - index.incision(x, z);
        double height = carveValleys(cell, x, z, location.edge(), index, index.near(x, z), surface);
        var lake = index.lake(x, z);
        if (lake != null && lake.depth() > -HighlandBasins.MAX_SHORE) height = shapeLake(cell, x, z, index, lake, height);
        // Reserve ceiling headroom before any water/bed is handed to final generation.
        double ceiling = levels.worldHeight - levels.waterY - 24;
        if (height > ceiling) height = ceiling + 16 * (1 - Math.exp(-(height - ceiling) / 16));
        cell.height = normalized(height);
        classify(cell);
    }
    /**
     * Lake bed and shore. Below the surface the bed follows the planning depth (at most 16 deep). Around it the
     * ground descends to one block above the water over a band whose depth range grows with the height it has to
     * descend, so high surroundings get wider shores. The range stays inside the basin's shore region (REGION_DEPTH), so
     * the effect is zero where lake sampling stops.
     */
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
            return water - Math.min(depth, 16);
        }
        if (cell.hasRiverWater) return height;
        // Depth units: the shore band widens with the height it has to descend.
        double range = clamp(Math.max(0, height - water) * SHORE_RATE, 4, HighlandBasins.MAX_SHORE);
        double weight = smooth(-range, 0, depth) * lake.proximity();
        double shore = Math.min(height, water + 1 - depth);
        double shaped = lerp(height, shore, weight);
        // Rim: rendered land carries legacy detail the plan grid does not see, so ground just outside the shoreline
        // can dip below the lake surface. Hold it at least one block above the water near the shore.
        return Math.max(shaped, lerp(shaped, water + 1, smooth(-LAKE_RIM, 0, depth) * lake.proximity()));
    }

    /**
     * Composes every nearby reach's valley section.
     * <ul>
     * <li>Wet: a point inside any reach's wet radius belongs to the wet reach with the lowest bed, so a channel is
     * never erased by a neighbouring valley floor.</li>
     * <li>Dry: the two lowest candidates (including the land) are joined with a smooth minimum only where both lie
     * on outer valley slopes and below the land, then lifted onto the support cone of every channel (one block
     * above its water at the bank, falling one block per block). Channels at different levels therefore sit on
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
            double radius = clamp(16 * Math.pow(q, .35), 4, 12) * lerp(.3, 1, head) * dynamic;
            double hydraulic = clamp(Math.pow(q / .1, .3), .5, 2) * settings.valleyWidth() / 256.0;
            double floorHalf = river.bankWidth * dynamic * pinch * hydraulic * lerp(1, .4, confinement) * head * head;
            double fadeRun = Math.min(MAX_FADE, river.bankWidth * dynamic * pinch * Math.max(.5, hydraulic)
                * (5 + Math.max(0, land - water) / 100) * lerp(1, .6, confinement));
            double bedDepth = Math.max(2, 2.3 + .3 * depthVar + river.bedDepth * clamp(q / .3, 0, 1) * flat) * lerp(.4, 1, head);
            var shape = new FtfValley.Shape(radius, Math.max(3, (river.maxBankHeight - river.minBankHeight) * dynamic * side), floorHalf,
                Math.max(MIN_OUTER_RUN, fadeRun), bedDepth, Math.max(1, river.maxBankHeight - river.minBankHeight) + .4 * terrace - .6 * drainage,
                terrace, drainage);
            var section = FtfValley.sample(distance, land, water, shape);
            maxInfluence = Math.max(maxInfluence, section.influence());
            support = Math.max(support, water + 1 - Math.max(0, distance - shape.bankEnd()));
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
                    * smooth(floorStart, floorStart + 12, distance) * (1 - smooth(floorEnd - 32, floorEnd, distance)) * head;
            }
            scratch.wetland[k] = wetland;
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
                // Lowest ground carved by any clearly lower river (same-river neighbours share the level).
                double others = Double.POSITIVE_INFINITY;
                for (int j = 0; j < hits.count; j++) if (j != k && hits.water[j] < water - 2) others = Math.min(others, scratch.carve[j]);
                wetland *= smooth(water + 1, water + 6, others);
                if (wetland > 0) carve = lerp(carve, Math.min(carve, water + hummock), wetland);
                if (wetland > maxWetland) { maxWetland = wetland; wetlandOwner = k; }
            }
            double outerness = 1 - scratch.section[k].influence();
            if (carve < c1) { c2 = c1; o2 = o1; c1 = carve; o1 = outerness; owner = k; ownerSection = scratch.section[k]; }
            else if (carve < c2) { c2 = carve; o2 = outerness; }
        }
        cell.riverMask = (float) (1 - maxInfluence);
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
            setFlow(cell, speed / flowWeight, flowX, flowZ);
            return Math.min(wetSection.height(), water - 1);
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
        Scratch ensure(int size) {
            if (carve.length < size) {
                int n = Math.max(size, carve.length * 2);
                section = new HighlandValley.Section[n]; carve = new double[n]; wetland = new double[n]; radius = new double[n];
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
