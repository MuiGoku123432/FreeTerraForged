package etcodehome.freeterraforged.world.worldgen.structure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.world.worldgen.noise.module.Erosion;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import org.junit.jupiter.api.Test;

class ErosionNoiseCacheTest {
    @Test
    void matchesOriginalFloatBitsAcrossLatticeBoundariesAndSettings() throws Exception {
        try (var context = StructureTestContext.create(42)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var bits = ByteBuffer.allocate(4);
            float[] coordinates = {-1000000, -96, -64.001F, -64, -63.999F, -37.5F,
                -32, -31.999F, -0.001F, -0.0F, 0, 0.001F, 31.999F, 32, 32.001F,
                63.999F, 64, 64.001F, 1000000};
            Noise terrain = Noises.warpPerlin(Noises.perlin(17, 60, 3), 23, 80, 2, 30);
            for (Noise input : new Noise[]{terrain, Noises.constant(0.5F)}) {
                for (var blend : Erosion.BlendMode.values()) for (int seed : new int[]{42, -7}) {
                    for (int octaves : new int[]{1, 3}) for (float grid : new float[]{32, 37.5F}) {
                        var erosion = new Erosion(input, seed, octaves, 0.7F, grid, 0.5F, 2, 0.5F, blend);
                        for (float z : coordinates) for (float x : coordinates) {
                            bits.clear();
                            bits.putInt(Float.floatToRawIntBits(erosion.compute(x, z, 8675309)));
                            digest.update(bits.array());
                        }
                    }
                }
            }
            // Captured from f91b17f7 before retaining drainage segments: 17,328 samples.
            assertEquals("0f5e65a8053df9b274a99ce666c6499b9538ce0001ace1b0e4b07ae31d9dd2c7",
                HexFormat.of().formatHex(digest.digest()));
        }
    }
    @Test
    void neighboringSamplesReuseDrainageSegmentsAndRecoverAfterFailure() {
        try (var context = StructureTestContext.create(42)) {
            var input = new CountingNoise(Noises.perlin(17, 60, 3));
            var erosion = new Erosion(input, 42, 3, 0.7F, 32, 0.5F, 2, 0.5F, Erosion.BlendMode.CONSTANT);
            erosion.compute(0.1F, 0.1F, 7);
            int coldCalls = input.calls.get();
            for (int i = 1; i < 16; i++) erosion.compute(0.1F + i * 0.01F, 0.1F, 7);
            assertEquals(coldCalls + 15, input.calls.get(), "Only each new point's input should be reevaluated");

            input.failAt = input.calls.get() + 4;
            assertThrows(IllegalStateException.class, () -> erosion.compute(500, -500, 7));
            input.failAt = -1;
            var fresh = new Erosion(input.delegate, 42, 3, 0.7F, 32, 0.5F, 2, 0.5F, Erosion.BlendMode.CONSTANT);
            assertEquals(Float.floatToRawIntBits(fresh.compute(500, -500, 7)),
                Float.floatToRawIntBits(erosion.compute(500, -500, 7)));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    void reorderedConcurrentCallsKeepInstanceAndLargeCoordinateOwnership() throws Exception {
        try (var context = StructureTestContext.create(42); var workers = Executors.newFixedThreadPool(4)) {
            Noise input = Noises.warpPerlin(Noises.perlin(17, 60, 3), 23, 80, 2, 30);
            var first = new Erosion(input, 42, 3, 0.7F, 0.75F, 0.5F, 2, 0.5F, Erosion.BlendMode.INPUT_LINEAR);
            var second = new Erosion(Noises.constant(-1), -7, 2, 0.3F, 37.5F, 0.6F, 1.7F, 0.4F, Erosion.BlendMode.OUTPUT_LINEAR);
            var instances = new Erosion[]{first, second};
            float[] points = {-29999984, -16777214, -1000000, -64.001F, -32, -0.001F, 0, 0.001F,
                31.999F, 32, 32.001F, 1000000, 16777214, 29999984};
            int[][] expected = new int[instances.length][points.length];
            for (int owner = 0; owner < instances.length; owner++) {
                var sample = instances[owner];
                for (int i = 0; i < points.length; i++) {
                    // A fresh legacy-sized scratch forces a cold computation for this point.
                    var cold = new Erosion(sample.input(), sample.seed(), sample.octaves(), sample.strength(),
                        sample.gridSize(), sample.amplitude(), sample.lacunarity(), sample.distanceFallOff(),
                        sample.blendMode(), ThreadLocal.withInitial(() -> new float[25]));
                    expected[owner][i] = Float.floatToRawIntBits(cold.compute(points[i], points[points.length - 1 - i], i));
                }
            }
            var tasks = new ArrayList<Callable<Void>>();
            for (int task = 0; task < 8; task++) {
                int offset = task;
                tasks.add(() -> {
                    for (int round = 0; round < 4; round++) for (int owner = 0; owner < instances.length; owner++) {
                        for (int n = 0; n < points.length; n++) {
                            int i = Math.floorMod(offset - n - round, points.length);
                            assertEquals(expected[owner][i], Float.floatToRawIntBits(instances[owner].compute(
                                points[i], points[points.length - 1 - i], i)));
                        }
                    }
                    return null;
                });
            }
            for (var result : workers.invokeAll(tasks)) result.get();
        }
    }

    private static final class CountingNoise implements Noise {
        private final Noise delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private int failAt = -1;

        private CountingNoise(Noise delegate) { this.delegate = delegate; }
        public float compute(float x, float z, int seed) {
            if (this.calls.incrementAndGet() == this.failAt) throw new IllegalStateException("injected input failure");
            return this.delegate.compute(x, z, seed);
        }
        public float minValue() { return this.delegate.minValue(); }
        public float maxValue() { return this.delegate.maxValue(); }
        public Noise mapAll(Visitor visitor) { return visitor.apply(this); }
        public MapCodec<? extends Noise> codec() { return this.delegate.codec(); }
    }

}
