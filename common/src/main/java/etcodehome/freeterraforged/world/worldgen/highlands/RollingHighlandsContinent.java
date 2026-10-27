package etcodehome.freeterraforged.world.worldgen.highlands;

import java.util.LinkedHashMap;
import java.util.Map;
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
    static final double PROMINENCE_LOW = 16, PROMINENCE_HIGH = 176;
    static final double CHANNEL_DEPTH = 4, SHORE_RATE = .4;
    /** Current speed model (normalized 0..1 as stored in ChunkFlowField). */
    static final double FLOW_BASE = .45, FLOW_SLOPE = 6, FLOW_MIN = .3, LAKE_DRIFT = .18, OUTLET_DRIFT = 128;
    static final double BANK_RUN = 16, MIN_OUTER_RUN = 64, MAX_OUTER_RUN = 512, CREASE_SOFTNESS = 24;
    private static final Rivermap NO_LEGACY_RIVERS = new Rivermap(0, 0, new Network[0], GenWarp.EMPTY);
    private final HighlandCoast coast;
    private final HighlandFields fields;
    private final HighlandDrainage drainage;
    private final RollingHighlandsSettings settings;
    private final Levels levels;
    private final double coastEdge;
    private final long classSeed;
    private final double coastMarker;
    private final etcodehome.freeterraforged.world.worldgen.cell.CellPopulator oceans;
    private final etcodehome.freeterraforged.world.worldgen.noise.module.Noise beachNoise;
    private final Map<Long, HighlandPlanIndex> cache = new LinkedHashMap<>(16, .75F, true);
    private volatile boolean closed;

    public RollingHighlandsContinent(GeneratorContext context) {
        this.settings = context.preset.terrain().highlands();
        settings.validate(context.preset.world());
        context.preset.terrain().rollingHighlands = settings;
        this.levels = context.levels;
        this.coast = new HighlandCoast(context);
        this.fields = new HighlandFields(context.seed.root() + context.preset.terrain().general.terrainSeedOffset, settings.uplandSpacing());
        this.drainage = new HighlandDrainage(coast, fields, settings.uplandSpacing(), context.preset.world().controlPoints);
        this.coastEdge = context.preset.world().controlPoints.coast;
        this.classSeed = context.seed.root() * 0x9E3779B97F4A7C15L + 0x52484632L;
        this.coastMarker = context.preset.world().controlPoints.coastMarker();
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
    private synchronized HighlandPlanIndex plan(int x, int z) {
        if (closed) throw new IllegalStateException("Rolling Highlands owner is closed");
        long key = PosUtil.pack(x, z);
        HighlandPlanIndex existing = cache.get(key);
        if (existing != null) return existing;
        // At most one build per owner, with no calls into raw/filtered chunk caches.
        HighlandPlanIndex built = new HighlandPlanIndex(drainage.build(x, z));
        if (cache.size() == 8) cache.remove(cache.keySet().iterator().next());
        cache.put(key, built);
        return built;
    }
    /** Test/diagnostic access to the owner plan containing a block position. */
    HighlandPlanIndex planAt(double x, double z) { var s = coast.sample(x, z); return plan(s.ownerX(), s.ownerZ()); }
    HighlandCoast coast() { return coast; }

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
        cell.terrainRegionId = (float) noise(173, x, z, settings.uplandSpacing());
        cell.terrainRegionEdge = 1;
        cell.terrainRegionCenterX = (float) x; cell.terrainRegionCenterZ = (float) z;
        double blend = drainage.landBlend(location.edge());
        cell.beachNoise = beachNoise.compute((float) x, (float) z, 0);
        if (location.edge() <= coastEdge) {
            oceans.apply(cell, (float) x, (float) z);
            cell.terrainErosion = cell.erosion;
            return;
        }
        var relief = fields.sample(x, z);
        // Visible land is a pure field; valleys only carve into it, so interfluves never depend on which
        // river is nearest.
        double land = blend * relief.height(settings.mountainHeight(), settings.foothillRelief());
        var index = plan(location.ownerX(), location.ownerZ());
        double height = carveValleys(cell, x, z, location.edge(), index.near(x, z), land);
        var lake = index.lake(x, z);
        if (lake != null && lake.depth() > -HighlandBasins.MAX_SHORE) height = shapeLake(cell, x, z, index, lake, height);
        // Reserve ceiling headroom before any water/bed is handed to final generation.
        double ceiling = levels.worldHeight - levels.waterY - 24;
        if (height > ceiling) height = ceiling + 16 * (1 - Math.exp(-(height - ceiling) / 16));
        cell.height = normalized(height);
        classify(cell, x, z, relief, height);
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
        return lerp(height, shore, weight);
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
    private double carveValleys(Cell cell, double x, double z, double edge, HighlandPlanIndex.Hits hits, double land) {
        double maxInfluence = 0, support = Double.NEGATIVE_INFINITY;
        double flowX = 0, flowZ = 0, speed = 0, flowWeight = 0;
        int wetOwner = -1;
        HighlandValley.Section wetSection = null;
        // Kept out of the climate coast band, where shared climate code would reclassify WETLAND as coast/ocean.
        double wetlandMask = smooth(.45, .65, noise(classSeed + 7, x, z, 220)) * smooth(coastMarker, coastMarker + .1, edge);
        double hummock = 1.6 * (2 * noise(classSeed + 8, x, z, 24) - 1) + .6 * (2 * noise(classSeed + 9, x, z, 9) - 1);
        double area2 = (double) settings.uplandSpacing() * settings.uplandSpacing();
        Scratch scratch = SCRATCH.get().ensure(hits.count);
        for (int k = 0; k < hits.count; k++) {
            double water = hits.water[k], distance = hits.distance[k], confinement = hits.confinement[k];
            double q = Math.max(.01, hits.area[k] / area2);
            double head = hits.head[k];
            // Channel heads start as narrow, shallow streams and widen to the discharge-based river size.
            double radius = clamp(16 * Math.pow(q, .35), 4, 12) * lerp(.3, 1, head);
            double channelDepth = CHANNEL_DEPTH * lerp(.4, 1, head);
            double dryWidth = settings.valleyWidth() * clamp(Math.pow(q, .2), .5, 1.5) * lerp(1, .3, confinement) * lerp(.35, 1, head);
            double slopeLimit = lerp(.45, .9, confinement);
            double outer = clamp(1.875 * Math.max(0, land - water - 4) / slopeLimit, MIN_OUTER_RUN, MAX_OUTER_RUN);
            var section = HighlandValley.sample(distance, land, water, channelDepth, radius, BANK_RUN, dryWidth, outer);
            maxInfluence = Math.max(maxInfluence, section.influence());
            support = Math.max(support, water + 1 - Math.max(0, distance - (radius + BANK_RUN)));
            double carve = section.height();
            scratch.section[k] = section;
            scratch.carve[k] = carve;
            // Floodplain wetlands: broad, flat, low valley floors of larger rivers hold pools and hummocks at the
            // river's water level. The weight is a product of continuous factors, so the composition stays smooth.
            double wetland = 0;
            if (wetlandMask > 0 && !section.wet()) {
                double floorStart = radius + BANK_RUN, floorEnd = floorStart + dryWidth * .5;
                wetland = wetlandMask * smooth(.04, .15, q) * (1 - smooth(.006, .02, hits.slope[k])) * (1 - smooth(40, 90, water))
                    * smooth(floorStart, floorStart + 12, distance) * (1 - smooth(floorEnd - 32, floorEnd, distance)) * head;
            }
            scratch.wetland[k] = wetland;
            if (section.wet()) {
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
            cell.riverWaterLevel = normalized(hits.water[wetOwner]);
            cell.riverZone = RiverZone.Riverbed;
            cell.hasRiverWater = true; cell.terrain = TerrainType.RIVER;
            setFlow(cell, speed / flowWeight, flowX, flowZ);
            return wetSection.height();
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
     * Biome-facing parameters from the final carved surface. Weirdness stays on vanilla's normal (negative)
     * side like every legacy FTF terrain; its magnitude encodes peaks-and-valleys from ridge prominence,
     * suppressed in valleys and at low elevation. Erosion falls with prominence (flat floors high, ridges low)
     * and stays out of vanilla's swamp/windswept levels 5-6. Coast and beach classification is left to the
     * shared climate/lookup pipeline, exactly as for legacy terrain.
     */
    private void classify(Cell cell, double x, double z, HighlandFields.Relief relief, double height) {
        double outsideValley = smooth(0, .6, cell.riverMask);
        double ridge = clamp(.8 * relief.ridge() + .3 * relief.foothill() * (1 - relief.ridge()), 0, 1);
        double prominence = ridge * outsideValley * smooth(PROMINENCE_LOW, PROMINENCE_HIGH, height);
        prominence = clamp(prominence + .08 * (2 * noise(classSeed, x, z, 300) - 1), 0, 1);
        cell.weirdness = (float) -peaksAndValleysMagnitude(prominence);
        cell.erosion = (float) clamp(lerp(.057, -.53, prominence) + .1 * (2 * noise(classSeed + 1, x, z, 200) - 1), -1, .44);
        cell.terrainErosion = cell.erosion;
        if (!cell.hasRiverWater && cell.terrain != TerrainType.WETLAND) {
            cell.terrain = prominence > .62 && height > 120 ? TerrainType.MOUNTAIN_CHAIN
                : relief.basin() > .25 && prominence < .3 ? TerrainType.FLATS : TerrainType.HILLS;
        }
    }
    /**
     * Prominence to |weirdness|. Breakpoints are calibrated so the default profile's land shares of vanilla's
     * low/mid/high/peak slices (about 60/33/6/1%) match legacy FTF terrain at the same settings.
     */
    static double peaksAndValleysMagnitude(double prominence) {
        if (prominence < .32) return lerp(.06, .2667, prominence / .32);
        if (prominence < .78) return lerp(.2667, .4, (prominence - .32) / .46);
        if (prominence < .83) return lerp(.4, .5667, (prominence - .78) / .05);
        return lerp(.5667, .7, clamp((prominence - .83) / .17, 0, 1));
    }
    private static final class Scratch {
        HighlandValley.Section[] section = new HighlandValley.Section[64];
        double[] carve = new double[64], wetland = new double[64];
        Scratch ensure(int size) {
            if (carve.length < size) {
                int n = Math.max(size, carve.length * 2);
                section = new HighlandValley.Section[n]; carve = new double[n]; wetland = new double[n];
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
    @Override public synchronized void close() { closed = true; cache.clear(); coast.close(); }
}
