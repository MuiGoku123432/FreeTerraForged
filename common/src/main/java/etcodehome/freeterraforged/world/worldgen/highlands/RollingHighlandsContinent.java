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
    private static final Rivermap NO_LEGACY_RIVERS = new Rivermap(0, 0, new Network[0], GenWarp.EMPTY);
    private final HighlandCoast coast;
    private final HighlandFields fields;
    private final HighlandDrainage drainage;
    private final RollingHighlandsSettings settings;
    private final Levels levels;
    private final double coastEdge, oceanDepth;
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
        this.oceanDepth = context.preset.world().properties.oceanDepth;
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
        cell.waterTable = 0;
        cell.continentX = location.centerX(); cell.continentZ = location.centerZ();
        cell.continentId = (float) noise(971, location.ownerX(), location.ownerZ(), 1.37);
        cell.continentEdge = (float) location.edge();
        cell.terrainRegionId = (float) noise(173, x, z, settings.uplandSpacing());
        cell.terrainRegionEdge = 1;
        cell.terrainRegionCenterX = (float) x; cell.terrainRegionCenterZ = (float) z;
        double blend = drainage.landBlend(location.edge());
        if (location.edge() <= coastEdge) {
            double h = -oceanDepth * smooth((coastEdge - location.edge()) / Math.max(.01, coastEdge));
            cell.height = normalized(h);
            cell.terrain = h < -20 ? TerrainType.DEEP_OCEAN : TerrainType.SHALLOW_OCEAN;
            cell.erosion = .5F; cell.weirdness = .1F;
            cell.terrainErosion = cell.erosion;
            return;
        }
        var relief = fields.sample(x, z);
        double land = blend * relief.height(settings.mountainHeight(), settings.foothillRelief());
        double height = land;
        var index = plan(location.ownerX(), location.ownerZ());
        var hit = index.nearest(x, z);
        cell.terrain = relief.ridge() > .65 ? TerrainType.MOUNTAIN_CHAIN : relief.basin() > .25 ? TerrainType.FLATS : TerrainType.HILLS;
        cell.erosion = (float) lerp(.5, -.6, relief.ridge());
        cell.weirdness = (float) lerp(.15, .65, relief.ridge());
        if (hit != null) {
            // Neighboring outlet basins share one divide envelope. Low regional saddles retain passes.
            double divide = hit.divide() * smooth(35, 85, relief.upland());
            double ridge = lerp(relief.ridge(), .65 * relief.ridge() + .35 * divide,
                1 - smooth(1024, HighlandPlanIndex.INFLUENCE, hit.distance()));
            land = blend * (relief.planningHeight() + settings.mountainHeight() * ridge
                + settings.foothillRelief() * relief.foothill());
            height = land;
            double q = Math.max(.01, hit.area() / ((double) settings.uplandSpacing() * settings.uplandSpacing()));
            double radius = clamp(16 * Math.pow(q, .35), 4, 12);
            double dryWidth = settings.valleyWidth() * clamp(Math.pow(q, .2), .5, 1.5) * lerp(1, .3, hit.confinement());
            double outer = clamp(1.875 * Math.abs(land - hit.water() - 4) / .35, 128, 1024);
            var section = HighlandValley.sample(hit.distance(), land, hit.water(), 4, radius, 16, dryWidth, outer);
            height = section.height();
            cell.riverMask = (float) (1 - section.influence());
            if (section.influence() > 0) {
                cell.riverWaterLevel = normalized(hit.water());
                cell.riverZone = RiverZone.values()[section.zone()];
                if (section.wet()) {
                    cell.hasRiverWater = true; cell.terrain = TerrainType.RIVER;
                    cell.erosion = .5F; cell.weirdness = 0;
                    cell.hasFlow = true;
                    cell.flowAngle = etcodehome.freeterraforged.world.worldgen.ChunkFlowField.pack(.5F, Math.atan2(hit.flowZ(), hit.flowX()));
                }
            }
        }
        var lake = index.lake(x, z);
        if (lake != null && lake.depth() > -8) {
            double lakeBed = lake.water() - clamp(lake.depth(), -8, 16);
            double lakeBlend = smooth(-8, 0, lake.depth());
            if (!cell.hasRiverWater) height = lerp(height, lakeBed, lakeBlend);
            if (lake.depth() > 0) {
                height = lakeBed;
                cell.riverWaterLevel = normalized(lake.water()); cell.hasRiverWater = true;
                cell.terrain = TerrainType.RIVER; cell.riverZone = RiverZone.Riverbed;
                cell.riverMask = 0; cell.erosion = .5F; cell.weirdness = 0;
                cell.hasFlow = false;
            }
        }
        // Reserve ceiling headroom before any water/bed is handed to final generation.
        double ceiling = levels.worldHeight - levels.waterY - 24;
        if (height > ceiling) height = ceiling + 16 * (1 - Math.exp(-(height - ceiling) / 16));
        if (!cell.hasRiverWater && height < 5) cell.terrain = TerrainType.BEACH;
        cell.height = normalized(height);
        cell.terrainErosion = cell.erosion;
    }
    private float normalized(double height) { return levels.water + (float) (height / levels.terrainScaleFactor); }
    @Override public float getEdgeValue(float x, float z) { return (float) coast.sample(x, z).edge(); }
    @Override public long getNearestCenter(float x, float z) {
        var s = coast.sample(x, z); return PosUtil.pack(s.centerX(), s.centerZ());
    }
    @Override public Rivermap getRivermap(int x, int z) { return NO_LEGACY_RIVERS; }
    @Override public synchronized void close() { closed = true; cache.clear(); coast.close(); }
}
