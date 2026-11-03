package etcodehome.freeterraforged.world.worldgen.highlands;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.data.worldgen.preset.settings.RollingHighlandsSettings;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;

/**
 * RH5 M: mountain ranges. Ranges are long and continuous (crest lines several kilometres long), smooth (no jumps), off
 * at range height 0, and the landscape and water gates still hold with ranges on.
 */
class HighlandRangeTest {
    private static final int RANGE_HEIGHT = 140, RANGE_LENGTH = 6144;

    private static etcodehome.freeterraforged.data.worldgen.preset.settings.Preset withRanges() {
        var preset = HighlandQualityProbe.preset();
        preset.terrain().rollingHighlands = RollingHighlandsSettings.DEFAULT.withRanges(RANGE_HEIGHT, RANGE_LENGTH);
        return preset;
    }

    @Test void rangesAreLongContinuousAndOffAtZero() {
        // The added range height is compared against the same legacy land: with D-b on, the legacy land itself changes
        // (fewer legacy mountains), and legacy land has its own sharp features, so continuity is checked with it off.
        var additive = withRanges();
        additive.terrain().rollingHighlands = additive.terrain().rollingHighlands.withRangesReplaceMountains(false);
        try (var context = WorldgenFixture.context(additive, 0); var flat = WorldgenFixture.context(HighlandQualityProbe.preset(), 0)) {
            var land = HighlandLand.create(context);
            var plain = HighlandLand.create(flat);
            var bounds = ((RollingHighlandsContinent) context.heightmap.continent()).coast().bounds(0, 0);
            int step = 64, w = (int) ((bounds.maxX() - bounds.minX()) / step), h = (int) ((bounds.maxZ() - bounds.minZ()) / step);
            boolean[] crest = new boolean[w * h];
            double worstStep = 0;
            Cell a = new Cell(), b = new Cell();
            for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
                double bx = bounds.minX() + x * step, bz = bounds.minZ() + z * step;
                double core = land.rangeCore(bx, bz);
                crest[z * w + x] = core > .85;
                // Range height 0 adds nothing; with ranges the added height is continuous block to block.
                assertEquals(plain.apply(b.reset(), bx, bz), plain.height(bx, bz), 1e-9);
                if (core > 0 && x % 4 == 0 && z % 4 == 0) {
                    // A block step on the range is no larger than the legacy land's own step there plus the range's
                    // (RH6 R1 compresses legacy relief inside the range, which scales its steps down, never up).
                    double ranged = Math.abs(land.apply(a.reset(), bx + 1, bz) - land.apply(a.reset(), bx, bz));
                    double legacy = Math.abs(plain.apply(b.reset(), bx + 1, bz) - plain.apply(b.reset(), bx, bz));
                    worstStep = Math.max(worstStep, ranged - legacy);
                }
            }
            // Longest crest: the largest bounding-box diagonal of an 8-connected crest component.
            boolean[] seen = new boolean[w * h];
            double longest = 0;
            var queue = new ArrayDeque<Integer>();
            for (int i = 0; i < w * h; i++) {
                if (!crest[i] || seen[i]) continue;
                int minX = Integer.MAX_VALUE, maxX = -1, minZ = Integer.MAX_VALUE, maxZ = -1;
                queue.add(i); seen[i] = true;
                while (!queue.isEmpty()) {
                    int v = queue.poll(), vx = v % w, vz = v / w;
                    minX = Math.min(minX, vx); maxX = Math.max(maxX, vx); minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
                    for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                        int nx = vx + dx, nz = vz + dz;
                        if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                        int j = nz * w + nx;
                        if (crest[j] && !seen[j]) { seen[j] = true; queue.add(j); }
                    }
                }
                longest = Math.max(longest, Math.hypot(maxX - minX, maxZ - minZ) * step);
            }
            String summary = String.format(java.util.Locale.ROOT, "RHM longestCrest=%.0f worstStep=%.3f", longest, worstStep);
            System.out.println(summary);
            assertTrue(longest >= 4000, summary);
            assertTrue(worstStep < 1.5, summary);
        }
    }

    @Test void rangeCrestsReadAsMountainsAndWaterGatesHold() throws Exception {
        var m = HighlandQualityProbe.measure(withRanges(), 0, false);
        System.out.println("ranges " + m.summary());
        assertEquals(0, m.waterSteps(), m.summary());
        assertEquals(0, m.dryJumps(), m.summary());
        assertEquals(0, m.shoreCliffs(), m.summary());
        assertEquals(0, m.belowSea(), m.summary());
        assertTrue(m.flowCoverage() >= .999, m.summary());
        assertTrue(m.positiveWeirdness() == 0, m.summary());
        try (var context = WorldgenFixture.context(withRanges(), 0)) {
            var land = HighlandLand.create(context);
            var plan = ((RollingHighlandsContinent) context.heightmap.continent()).planOf(0, 0).plan();
            assertFalse(plan.nodes().isEmpty(), "drainage plan fell back to no rivers");
            // A crest away from valleys is a mountain for biome selection.
            var bounds = ((RollingHighlandsContinent) context.heightmap.continent()).coast().bounds(0, 0);
            Cell c = new Cell();
            int crests = 0, mountains = 0;
            for (double x = bounds.minX(); x < bounds.maxX(); x += 96) for (double z = bounds.minZ(); z < bounds.maxZ(); z += 96) {
                if (land.rangeCore(x, z) < .97) continue;
                context.heightmap.apply(c.reset(), (int) x, (int) z, false);
                if (c.hasRiverWater || c.riverMask < .5 || c.continentEdge <= context.preset.world().controlPoints.coast) continue;
                crests++;
                if (c.terrain == TerrainType.MOUNTAIN_CHAIN) mountains++;
            }
            assertTrue(crests > 20 && mountains > .6 * crests, "crests=" + crests + " mountains=" + mountains);
        }
    }

    /**
     * RH6 R and RH7 G0/D gates on the rendered surface of the user world with Range Height 200: crests without wild
     * height swings (RH7 relaxes RH6's evenness: dissected crests have peaks and saddles), spurs across the strike, long
     * crests, no channel crossings; the RH7 morphology (PLAN-RH7 §4); the drainage relief costs no more than the river
     * plan; and ranges (which replace legacy mountains) cost no more than +15% heightmap time over the same world
     * without ranges.
     */
    @Test void userRangesHaveEvenCrestsAndSpurs() throws Exception {
        var preset = HighlandPlanformProbe.userPreset();
        preset.terrain().rollingHighlands = preset.terrain().rollingHighlands.withRanges(200, 6144);
        assertTrue(preset.terrain().rollingHighlands.replacesMountains());
        double on, off;
        try (var context = WorldgenFixture.context(preset, HighlandPlanformProbe.USER_SEED)) {
            var m = HighlandRangeProbe.measure(context, HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z);
            System.out.println(m.summary());
            assertTrue(m.evenness() <= .5, m.summary());
            assertTrue(m.spurAlignment() >= .45, m.summary());
            assertTrue(m.longest() >= 4000, m.summary());
            var p = HighlandPlanformProbe.measure("user-ranges", context, HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z);
            System.out.println(p.summary());
            assertEquals(0, p.crossings(), p.summary());
            var g = HighlandMountainProbe.measure(context, HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z);
            System.out.println(g.summary());
            assertTrue(g.widthOverSpacing() >= 1.15, g.summary());
            assertTrue(g.dissection() >= .2, g.summary());
            assertTrue(g.medianSlope() >= 24 && g.medianSlope() <= 38, g.summary());
            assertTrue(g.gentleShare() <= .22, g.summary());
            assertTrue(g.crestWidth() <= 40, g.summary());
            // Plan cost (best of five fresh builds): the drainage relief at most doubles the RH6 river plan's cost.
            var continent = (RollingHighlandsContinent) context.heightmap.continent();
            long plan = Long.MAX_VALUE, relief = Long.MAX_VALUE;
            for (int rep = 0; rep < 5; rep++) {
                var index = continent.rebuildAt(HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z);
                plan = Math.min(plan, index.plan().buildNanos());
                relief = Math.min(relief, index.relief.buildNanos);
            }
            String cost = String.format(java.util.Locale.ROOT, "RHRelief cost plan=%.0fms relief=%.0fms", plan / 1e6, relief / 1e6);
            System.out.println(cost);
            assertTrue(relief <= plan, cost);
            on = columnTime(context);
        }
        var flat = HighlandPlanformProbe.userPreset();
        flat.terrain().rollingHighlands = flat.terrain().rollingHighlands.withRanges(0, 6144);
        try (var context = WorldgenFixture.context(flat, HighlandPlanformProbe.USER_SEED)) {
            off = columnTime(context);
        }
        String time = String.format(java.util.Locale.ROOT, "RHRange time ranges=%.2f none=%.2f us/column", on, off);
        System.out.println(time);
        assertTrue(on <= 1.15 * off, time);
    }

    /** RH7 M user world in the Rolling Highlands tall frame (top y 735, depth 128) with the given Range Height. */
    static etcodehome.freeterraforged.data.worldgen.preset.settings.Preset tallPreset(int rangeHeight) throws Exception {
        var preset = HighlandPlanformProbe.userPreset();
        preset.world().properties.worldHeight = 736;
        preset.world().properties.worldDepth = 128;
        preset.terrain().rollingHighlands = preset.terrain().rollingHighlands.withRanges(rangeHeight, 6144);
        return preset;
    }

    /** RH7 M: at Range Height 640 in the tall frame, no column reaches the world top (soft compression below it). */
    @Test void tallRangesStayBelowWorldTop() throws Exception {
        try (var context = WorldgenFixture.context(tallPreset(640), HighlandPlanformProbe.USER_SEED)) {
            Cell c = new Cell();
            double top = 0;
            int x0 = 2656, z0 = 7396, size = 8192;
            for (int z = 0; z < size; z += 8) for (int x = 0; x < size; x += 8) {
                context.heightmap.apply(c.reset(), x0 + x, z0 + z, false);
                top = Math.max(top, c.height * context.levels.terrainScaleFactor);
            }
            String message = "highest column y=" + top + " world top y=" + (context.levels.worldHeight - 1);
            System.out.println("RHCeiling " + message);
            assertTrue(top <= context.levels.worldHeight - 1, message);
            assertTrue(top >= 500, "640 ranges should stand well above the 320 frame: " + message);
        }
    }

    /**
     * RH7 M: G0 morphology at Range Height 400 in the tall frame, where SPEC-RH7's targets are judged (PLAN-RH7 §4).
     * Gates lock in the measured state; the targets themselves are tracked in the plan.
     */
    @Test void tallRangesAreDissected() throws Exception {
        try (var context = WorldgenFixture.context(tallPreset(400), HighlandPlanformProbe.USER_SEED)) {
            var g = HighlandMountainProbe.measure(context, HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z);
            System.out.println("RH400 " + g.summary());
            var m = HighlandRangeProbe.measure(context, HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z);
            System.out.println("RH400 " + m.summary());
            assertTrue(g.medianSlope() >= 30 && g.medianSlope() <= 38, g.summary());
            assertTrue(g.gentleShare() <= .13, g.summary());
            assertTrue(g.crestWidth() <= 36, g.summary());
            assertTrue(g.dissection() >= .19, g.summary());
            assertTrue(m.crestHeight() - m.footHeight() >= 300, m.summary());
            System.out.println("RH400 " + HighlandMountainProbe.ridges(context, HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z).summary());
        }
    }

    /**
     * RH8 D-b: one ridge per range reproduces the model-7 ranges exactly (rendered heights, water and range fields of the
     * user world at Range Height 400 in the tall frame). Capture with -Dftf.captureBaseline=true.
     */
    @Test void singleRidgeRangesAreFrozen() throws Exception {
        var hash = java.security.MessageDigest.getInstance("SHA-256");
        try (var context = WorldgenFixture.context(tallPreset(400), HighlandPlanformProbe.USER_SEED)) {
            Cell c = new Cell();
            int x0 = 2656, z0 = 7396;
            for (int z = 0; z < 8192; z += 256) for (int x = 0; x < 8192; x += 256) {
                context.heightmap.apply(c.reset(), x0 + x + 37, z0 + z + 91, false);
                String row = Float.floatToIntBits(c.height) + "," + c.terrain.getName() + "," + c.hasRiverWater + ","
                    + Float.floatToIntBits(c.riverMask) + "," + Float.floatToIntBits(c.riverWaterLevel) + "," + Float.floatToIntBits(c.highlandRange);
                hash.update(row.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        String actual = java.util.HexFormat.of().formatHex(hash.digest()) + "\n";
        var fixture = java.nio.file.Path.of("src/test/resources/rolling-highlands/ranges-model7.sha256");
        if (Boolean.getBoolean("ftf.captureBaseline")) java.nio.file.Files.writeString(fixture, actual);
        assertEquals(java.nio.file.Files.readString(fixture), actual);
    }

    /**
     * RH8 T: three-ridge belts at Range Height 400 in the tall frame (the ROCKIES setup): several crests across the
     * belt with deep saddles between them, staggered ridges whose tallest changes, and the RH7 morphology, glacial and
     * water gates still holding.
     */
    @Test void ridgeBeltsHaveParallelCrests() throws Exception {
        double on, off;
        var preset = tallPreset(400);
        preset.terrain().rollingHighlands = preset.terrain().rollingHighlands.withRangeRidges(3);
        try (var context = WorldgenFixture.context(preset, HighlandPlanformProbe.USER_SEED)) {
            int ux = HighlandPlanformProbe.USER_X, uz = HighlandPlanformProbe.USER_Z;
            var r = HighlandMountainProbe.ridges(context, ux, uz);
            var g = HighlandMountainProbe.measure(context, ux, uz);
            var m = HighlandRangeProbe.measure(context, ux, uz);
            var p = HighlandPlanformProbe.measure("user-ridges", context, ux, uz);
            var continent = (RollingHighlandsContinent) context.heightmap.continent();
            long plan = Long.MAX_VALUE, relief = Long.MAX_VALUE;
            HighlandPlanIndex index = null;
            for (int rep = 0; rep < 3; rep++) {
                index = continent.rebuildAt(ux, uz);
                plan = Math.min(plan, index.plan().buildNanos());
                relief = Math.min(relief, index.relief.buildNanos);
            }
            int[] spill = tarnContainment(context, index.relief);
            String summary = String.format(java.util.Locale.ROOT, "RH8 %s | %s | %s | crossings=%d | cost plan=%.0fms relief=%.0fms | cirques=%d (%.1f per 10 km) tarns=%d spills=%d walls=%d",
                r.summary(), g.summary(), m.summary(), p.crossings(), plan / 1e6, relief / 1e6, index.relief.cirques,
                index.relief.cirques * 1e4 / m.totalLength(), index.relief.tarnCount(), spill[1], spill[2]);
            System.out.println(summary);
            assertTrue(r.multiShare() >= .6 && r.meanCrests() >= 1.8, summary);
            assertTrue(r.saddleDepth() >= .25, summary);
            assertTrue(r.spacingCv() <= .45, summary);
            assertTrue(r.highestModeShare() <= .7, summary);
            assertEquals(0, p.crossings(), summary);
            assertTrue(index.relief.tarnCount() >= 10, summary);
            assertEquals(0, spill[1], summary);
            assertEquals(0, spill[2], summary);
            assertTrue(relief <= 1.3 * plan, summary);
            on = columnTime(context);
        }
        try (var context = WorldgenFixture.context(tallPreset(400), HighlandPlanformProbe.USER_SEED)) {
            off = columnTime(context);
        }
        String time = String.format(java.util.Locale.ROOT, "RH8 time ridges3=%.2f ridges1=%.2f us/column", on, off);
        System.out.println(time);
        assertTrue(on <= 1.15 * off, time);
        var water = withRanges();
        water.terrain().rollingHighlands = water.terrain().rollingHighlands.withRangeRidges(3);
        var q = HighlandQualityProbe.measure(water, 0, false);
        System.out.println("RH8 ridges " + q.summary());
        assertEquals(0, q.waterSteps(), q.summary());
        assertEquals(0, q.dryJumps(), q.summary());
        assertEquals(0, q.shoreCliffs(), q.summary());
        assertEquals(0, q.belowSea(), q.summary());
        assertTrue(q.flowCoverage() >= .999, q.summary());
        if (Boolean.getBoolean("ftf.render")) {
            for (int ridges : new int[] {1, 3}) {
                var shown = tallPreset(400);
                shown.terrain().rollingHighlands = shown.terrain().rollingHighlands.withRangeRidges(ridges);
                try (var context = WorldgenFixture.context(shown, HighlandPlanformProbe.USER_SEED)) {
                    hillshade(context, 2656, 7396, 12288, 12, java.nio.file.Path.of("build/rolling-highlands/ridges-" + ridges + ".png"));
                    hillshade(context, 6616, 14116, 4096, 4,
                        java.nio.file.Path.of("build/rolling-highlands/ridges-" + ridges + "-zoom.png"));
                }
            }
        }
    }

    /** Diagnostic hillshade (sun from the north-west, water blue) of a square, {@code res} blocks per pixel. */
    private static void hillshade(etcodehome.freeterraforged.world.worldgen.GeneratorContext context, int x0, int z0, int size, int res, java.nio.file.Path out) throws Exception {
        int n = size / res;
        double[] h = new double[n * n];
        boolean[] wet = new boolean[n * n];
        Cell c = new Cell();
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            context.heightmap.apply(c.reset(), x0 + x * res, z0 + z * res, false);
            h[z * n + x] = (c.height - context.levels.water) * context.levels.terrainScaleFactor;
            wet[z * n + x] = c.hasRiverWater || h[z * n + x] < 0;
        }
        var image = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
            int i = z * n + x;
            if (wet[i]) { image.setRGB(x, z, 0x287dcc); continue; }
            double gx = (h[i + 1] - h[i - 1]) / (2 * res), gz = (h[i + n] - h[i - n]) / (2 * res);
            double shade = HighlandMath.clamp((1 + (gx + gz) * .7071) / Math.sqrt(1 + gx * gx + gz * gz), 0, 1.4) / 1.4;
            double tint = HighlandMath.clamp(h[i] / 500, 0, 1);
            int r = (int) (255 * shade * (.45 + .55 * tint)), g = (int) (255 * shade * (.6 + .3 * tint)), b = (int) (255 * shade * (.35 + .6 * tint));
            image.setRGB(x, z, (r << 16) | (g << 8) | b);
        }
        java.nio.file.Files.createDirectories(out.getParent());
        javax.imageio.ImageIO.write(image, "png", out.toFile());
    }

    /**
     * RH7 G: glacial overprint at Range Height 400 (default snowline) against the same world without glaciation:
     * cirques per 10 km of crest, and the flank and valley morphology both ways.
     */
    @Test void tallRangesAreGlaciated() throws Exception {
        String[] lines = new String[2];
        double[] cirquesPer10km = new double[2];
        int[] tarns = new int[2];
        for (int run = 0; run < 2; run++) {
            var preset = tallPreset(400);
            if (run == 1) preset.terrain().rollingHighlands = preset.terrain().rollingHighlands.withSnowline(100);
            try (var context = WorldgenFixture.context(preset, HighlandPlanformProbe.USER_SEED)) {
                var g = HighlandMountainProbe.measure(context, HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z);
                var m = HighlandRangeProbe.measure(context, HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z);
                var index = ((RollingHighlandsContinent) context.heightmap.continent()).rebuildAt(HighlandPlanformProbe.USER_X, HighlandPlanformProbe.USER_Z);
                cirquesPer10km[run] = index.relief.cirques * 1e4 / m.totalLength();
                int[] spill = tarnContainment(context, index.relief);
                tarns[run] = index.relief.tarnCount();
                lines[run] = String.format(java.util.Locale.ROOT, "RHGlacial snowline=%s cirques=%d (%.1f per 10 km crest) tarns=%d wetTarns=%d tarnColumns=%d spills=%d walls=%d maxRaise=%d glacialChannels=%d %s crest=%.0f foot=%.0f",
                    run == 0 ? "60" : "off", index.relief.cirques, cirquesPer10km[run], tarns[run], spill[4], spill[0], spill[1], spill[2], spill[3], index.relief.glacialChannels, g.summary(), m.crestHeight(), m.footHeight());
                assertEquals(0, spill[1], lines[run]);
                assertEquals(0, spill[2], lines[run]);
                System.out.println(lines[run]);
            }
        }
        assertEquals(0, cirquesPer10km[1], lines[1]);
        assertTrue(cirquesPer10km[0] >= 30, lines[0]);
        assertEquals(0, tarns[1], lines[1]);
        assertTrue(tarns[0] >= 10, lines[0]);
    }

    /**
     * Every tarn water column's eight neighbours are water at the same level or dry ground at least at the water
     * surface, and dry neighbours are the ground's own: tarn shaping raises none by more than WALL_RAISE blocks (a
     * raised rim holding perched water is a wall along the footprint grid). Returns {water columns, spills, walls,
     * largest raise, tarns holding water}.
     */
    private static int[] tarnContainment(etcodehome.freeterraforged.world.worldgen.GeneratorContext context, HighlandRangeRelief relief) {
        var levels = context.levels;
        var continent = (RollingHighlandsContinent) context.heightmap.continent();
        Cell c = new Cell(), e = new Cell(), scratch = new Cell();
        int columns = 0, spills = 0, walls = 0, wet = 0;
        double maxRaise = 0;
        for (int t = 0; t < relief.tarnCount(); t++) {
            var centre = relief.tarnCentre(t);
            boolean holds = false;
            for (int dz = -160; dz <= 160; dz += 2) for (int dx = -160; dx <= 160; dx += 2) {
                float x = (float) (centre[0] + dx), z = (float) (centre[1] + dz);
                context.heightmap.apply(c.reset(), x, z, false);
                if (!c.hasRiverWater || c.terrain != etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType.RIVER) continue;
                columns++;
                var own = relief.tarn(x, z);
                if (own != null && own.id() == t) holds = true;
                int waterY = levels.scale(c.riverWaterLevel);
                for (int k = 0; k < 8; k++) {
                    int ox = k < 3 ? -1 : k < 5 ? 0 : 1, oz = k == 3 ? -1 : k == 4 ? 1 : (k % 3) - 1;
                    context.heightmap.apply(e.reset(), x + ox, z + oz, false);
                    boolean ok = e.hasRiverWater ? levels.scale(e.riverWaterLevel) == waterY : levels.scale(e.height) >= waterY;
                    if (!ok) spills++;
                    if (e.hasRiverWater) continue;
                    double raise = (e.height - levels.water) * levels.terrainScaleFactor - continent.groundBeforeTarns(scratch, x + ox, z + oz);
                    maxRaise = Math.max(maxRaise, raise);
                    if (raise > WALL_RAISE) walls++;
                }
            }
            if (holds) wet++;
        }
        return new int[] {columns, spills, walls, (int) Math.ceil(maxRaise), wet};
    }
    /** Largest rim raise (blocks) tarn shaping may add beside its water (rim sampling between RIM_STRIDE samples). */
    static final double WALL_RAISE = 2;

    /**
     * RH7 S: at Range Height 400, range columns are classed by the same slope/snowline rule the surface feature uses.
     * Mountain biome parameters sit above the treeline, snow only on gentle ground, rock on the steepest ground.
     */
    @Test void tallRangeSurfacesFollowSlopeAndSnowline() throws Exception {
        try (var context = WorldgenFixture.context(tallPreset(400), HighlandPlanformProbe.USER_SEED)) {
            var levels = context.levels;
            Cell c = new Cell(), e = new Cell();
            int x0 = 2656, z0 = 7396, size = 8192;
            int[] count = new int[HighlandSurface.Cover.values().length];
            double[] slopeSum = new double[count.length];
            long mountains = 0, mountainsAbove = 0, range = 0, snowSteep = 0;
            java.util.function.BiFunction<Double, Double, Double> h = (x, z) -> {
                context.heightmap.apply(e.reset(), x.floatValue(), z.floatValue(), false);
                return (double) (e.height - levels.water) * levels.terrainScaleFactor;
            };
            for (int z = 0; z < size; z += 16) for (int x = 0; x < size; x += 16) {
                double wx = x0 + x, wz = z0 + z;
                context.heightmap.apply(c.reset(), (float) wx, (float) wz, false);
                if (!c.rollingHighlands || c.hasRiverWater || c.highlandRange < HighlandSurface.MIN_RANGE) continue;
                range++;
                double y = (c.height - levels.water) * levels.terrainScaleFactor;
                double dx = (h.apply(wx + 2, wz) - h.apply(wx - 2, wz)) / 4, dz = (h.apply(wx, wz + 2) - h.apply(wx, wz - 2)) / 4;
                double slope = Math.toDegrees(Math.atan(Math.hypot(dx, dz)));
                double snow = (c.highlandSnowline - levels.water) * levels.terrainScaleFactor, tree = (c.highlandTreeline - levels.water) * levels.terrainScaleFactor;
                var cover = HighlandSurface.choose(slope, y, snow, tree, c.highlandRange, 0);
                count[cover.ordinal()]++;
                slopeSum[cover.ordinal()] += slope;
                if (cover == HighlandSurface.Cover.SNOW && slope > 45) snowSteep++;
                if (c.terrain == etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType.MOUNTAIN_CHAIN) {
                    mountains++;
                    if (y >= tree - 2 * RollingHighlandsContinent.BAND_BLEND) mountainsAbove++;
                }
            }
            var text = new StringBuilder("RHSurface range=" + range);
            for (var cover : HighlandSurface.Cover.values()) {
                int k = cover.ordinal();
                text.append(String.format(java.util.Locale.ROOT, " %s=%.0f%%(%.0fdeg)", cover, 100.0 * count[k] / range, count[k] == 0 ? 0 : slopeSum[k] / count[k]));
            }
            text.append(String.format(java.util.Locale.ROOT, " mountainAboveTreeline=%.1f%% (n=%d)", 100.0 * mountainsAbove / Math.max(1, mountains), mountains));
            System.out.println(text);
            assertEquals(0, snowSteep, text.toString());
            assertTrue(mountains > 0 && mountainsAbove >= .95 * mountains, text.toString());
            int cliff = HighlandSurface.Cover.CLIFF.ordinal(), scree = HighlandSurface.Cover.SCREE.ordinal(), meadow = HighlandSurface.Cover.MEADOW.ordinal(), snow = HighlandSurface.Cover.SNOW.ordinal();
            assertTrue(count[snow] > 0 && count[meadow] > 0 && count[cliff] > 0 && count[scree] > 0, text.toString());
            assertTrue(slopeSum[cliff] / count[cliff] > slopeSum[scree] / count[scree] && slopeSum[scree] / count[scree] > slopeSum[meadow] / count[meadow], text.toString());
        }
    }

    @Test void surfaceRuleBands() {
        assertEquals(HighlandSurface.Cover.BIOME, HighlandSurface.choose(10, 500, 300, 200, .1, 0));
        assertEquals(HighlandSurface.Cover.SNOW, HighlandSurface.choose(10, 320, 300, 200, 1, 0));
        assertEquals(HighlandSurface.Cover.MEADOW, HighlandSurface.choose(10, 250, 300, 200, 1, 0));
        assertEquals(HighlandSurface.Cover.BIOME, HighlandSurface.choose(10, 150, 300, 200, 1, 0));
        assertEquals(HighlandSurface.Cover.SCREE, HighlandSurface.choose(35, 320, 300, 200, 1, 0));
        assertEquals(HighlandSurface.Cover.CLIFF, HighlandSurface.choose(50, 320, 300, 200, 1, 0));
        assertEquals(HighlandSurface.Cover.CLIFF, HighlandSurface.choose(55, 150, 300, 200, 1, 0));
        assertEquals(HighlandSurface.Cover.BIOME, HighlandSurface.choose(47, 150, 300, 200, 1, 0));
        for (double slope = 0; slope <= 90; slope += 1) for (double y = 0; y < 600; y += 7)
            assertTrue(slope <= 45 || HighlandSurface.choose(slope, y, 300, 200, 1, 1) != HighlandSurface.Cover.SNOW);
    }

    /** Best of three heightmap passes (µs per column) over a 4 km square of the user continent, after warming its plans. */
    private static double columnTime(etcodehome.freeterraforged.world.worldgen.GeneratorContext context) {
        Cell c = new Cell();
        int x0 = 2656, z0 = 7396, size = 4096;
        for (int z = 0; z < size; z += 64) for (int x = 0; x < size; x += 64) context.heightmap.apply(c.reset(), x0 + x, z0 + z, false);
        double best = Double.MAX_VALUE;
        for (int rep = 0; rep < 3; rep++) {
            long t = System.nanoTime();
            for (int z = 0; z < size; z += 16) for (int x = 0; x < size; x += 16) context.heightmap.apply(c.reset(), x0 + x, z0 + z, false);
            best = Math.min(best, (System.nanoTime() - t) / 1e3 / ((size / 16) * (size / 16)));
        }
        return best;
    }
}
