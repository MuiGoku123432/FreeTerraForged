package etcodehome.freeterraforged.world.worldgen.noise.module;

import java.util.Arrays;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.StringRepresentable;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil.Vec2f;

public record Erosion(Noise input, int seed, int octaves, float strength, float gridSize, float amplitude, float lacunarity, float distanceFallOff, BlendMode blendMode, ThreadLocal<float[]> cache) implements Noise {
	private static final int HEIGHT_CACHE_SIZE = 5 * 5;
	// Nine (ax, ay, bx, by) segments, followed by pix, piy, grid-size bits and validity.
	private static final int OCTAVE_CACHE_SIZE = 9 * 4 + 4;

	public static final MapCodec<Erosion> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		HOLDER_HELPER_CODEC.fieldOf("input").forGetter(Erosion::input),
		Codec.INT.fieldOf("seed").forGetter(Erosion::seed),
		Codec.INT.fieldOf("octaves").forGetter(Erosion::octaves),
		Codec.FLOAT.fieldOf("strength").forGetter(Erosion::strength),
		Codec.FLOAT.fieldOf("grid_size").forGetter(Erosion::gridSize),
		Codec.FLOAT.fieldOf("amplitude").forGetter(Erosion::amplitude),
		Codec.FLOAT.fieldOf("lacunarity").forGetter(Erosion::lacunarity),
		Codec.FLOAT.fieldOf("distance_falloff").forGetter(Erosion::distanceFallOff),
		BlendMode.CODEC.fieldOf("blend_mode").forGetter(Erosion::blendMode)
	).apply(instance, Erosion::new));

	public Erosion(Noise input, int seed, int octaves, float strength, float gridSize, float amplitude, float lacunarity, float distanceFallOff, BlendMode blendMode) {
		this(input, seed, octaves, strength, gridSize, amplitude, lacunarity, distanceFallOff, blendMode, ThreadLocal.withInitial(() -> new float[HEIGHT_CACHE_SIZE + OCTAVE_CACHE_SIZE * Math.max(0, octaves)]));
	}

	@Override
	public float compute(float x, float z, int seed) {
		float value = this.input.compute(x, z, seed);
		float[] scratch = this.cache.get();
		int required = HEIGHT_CACHE_SIZE + OCTAVE_CACHE_SIZE * Math.max(0, this.octaves);
		if (scratch.length < required) {
			// Preserve callers of the canonical constructor that supply the old 25-float scratch.
			scratch = new float[required];
			this.cache.set(scratch);
		}
		float erosion = this.getErosionValue(x, z, scratch);
		return NoiseUtil.lerp(erosion, value, this.blendMode.blend(value, erosion, this.strength));
	}

	@Override
	public float minValue() {
		return 0.0F;
	}

	@Override
	public float maxValue() {
		return 1.0F;
	}

	@Override
	public Noise mapAll(Visitor visitor) {
		return new Erosion(this.input.mapAll(visitor), this.seed, this.octaves, this.strength, this.gridSize, this.amplitude, this.lacunarity, this.distanceFallOff, this.blendMode);
	}

	@Override
	public MapCodec<Erosion> codec() {
		return CODEC;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Erosion other && other.input.equals(this.input) && other.seed == this.seed && other.octaves == this.octaves && other.strength == this.strength && other.gridSize == this.gridSize && other.amplitude == this.amplitude && other.lacunarity == this.lacunarity && other.distanceFallOff == this.distanceFallOff && other.blendMode.equals(this.blendMode);
	}
	
	private float getErosionValue(float x, float y, float[] cache) {
		float sum = 0.0F;
		float max = 0.0F;
		float gain = 1.0F;
		float distance = this.gridSize;
		for (int i = 0; i < this.octaves; ++i) {
			float value = this.getSingleErosionValue(x, y, distance, cache, HEIGHT_CACHE_SIZE + i * OCTAVE_CACHE_SIZE);
			value *= gain;
			sum += value;
			max += gain;
			gain *= this.amplitude;
			distance *= this.distanceFallOff;
			x *= this.lacunarity;
			y *= this.lacunarity;
		}
		return sum / max;
	}

	private float getSingleErosionValue(float x, float y, float gridSize, float[] cache, int offset) {
		int pix = NoiseUtil.floor(x / gridSize);
		int piy = NoiseUtil.floor(y / gridSize);
		int key = offset + 9 * 4;
		// The drainage segments depend on the lattice cell and octave scale, not the
		// sample's position inside that cell. Retain them in this instance's existing
		// per-thread scratch; keep the point-to-segment arithmetic and tie order intact.
		// Compare integer coordinates as doubles: rounded large float keys must miss.
		if (cache[key + 3] == 0.0F
				|| (double) cache[key] != pix
				|| (double) cache[key + 1] != piy
				|| Float.floatToRawIntBits(cache[key + 2]) != Float.floatToRawIntBits(gridSize)) {
			cache[key + 3] = 0.0F;
			Arrays.fill(cache, 0, HEIGHT_CACHE_SIZE, -1.0F);
			int segment = offset;
			for (int dy1 = -1; dy1 <= 1; ++dy1) {
				for (int dx1 = -1; dx1 <= 1; ++dx1) {
					int pax = pix + dx1;
					int pay = piy + dy1;
					Vec2f vec1 = NoiseUtil.cell(this.seed, pax, pay);
					float ax = (pax + vec1.x()) * gridSize;
					float ay = (pay + vec1.y()) * gridSize;
					float bx = ax;
					float by = ay;
					float lowestNeighbour = Float.MAX_VALUE;
					for (int dy2 = -1; dy2 <= 1; ++dy2) {
						for (int dx2 = -1; dx2 <= 1; ++dx2) {
							int pbx = pax + dx2;
							int pby = pay + dy2;
							Vec2f vec2 = (pbx == pax && pby == pay) ? vec1 : NoiseUtil.cell(this.seed, pbx, pby);
							float candidateX = (pbx + vec2.x()) * gridSize;
							float candidateY = (pby + vec2.y()) * gridSize;
							float height = getNoiseValue(dx1 + dx2, dy1 + dy2, candidateX, candidateY, this.input, cache);
							if (height < lowestNeighbour) {
								lowestNeighbour = height;
								bx = candidateX;
								by = candidateY;
							}
						}
					}
					cache[segment++] = ax;
					cache[segment++] = ay;
					cache[segment++] = bx;
					cache[segment++] = by;
				}
			}
			cache[key] = pix;
			cache[key + 1] = piy;
			cache[key + 2] = gridSize;
			cache[key + 3] = 1.0F;
		}
		float minHeight2 = Float.MAX_VALUE;
		for (int segment = offset; segment < key; segment += 4) {
			float height2 = sd(x, y, cache[segment], cache[segment + 1], cache[segment + 2], cache[segment + 3]);
			if (height2 < minHeight2) {
				minHeight2 = height2;
			}
		}
		return NoiseUtil.clamp(sqrt(minHeight2) / gridSize, 0.0F, 1.0F);
	}

	private static float getNoiseValue(int dx, int dy, float px, float py, Noise module, float[] cache) {
		int index = (dy + 2) * 5 + (dx + 2);
		float value = cache[index];
		if (value == -1.0F) {
			value = module.compute(px, py, 0);
			cache[index] = value;
		}
		return value;
	}

	private static float sd(float px, float py, float ax, float ay, float bx, float by) {
		float padx = px - ax;
		float pady = py - ay;
		float badx = bx - ax;
		float bady = by - ay;
		float paba = padx * badx + pady * bady;
		float baba = badx * badx + bady * bady;
		
		// avoid dividing by 0
		float h = paba == 0.0F && baba == 0.0F ? 0.0F : NoiseUtil.clamp(paba / baba, 0.0F, 1.0F);
		return len2(padx, pady, badx * h, bady * h);
	}

	private static float len2(float x1, float y1, float x2, float y2) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		return dx * dx + dy * dy;
	}

	private static float sqrt(float value) {
		return (float) Math.sqrt(value);
	}

	public enum BlendMode implements StringRepresentable {
		CONSTANT("constant") {
			@Override
			public float blend(float value, float erosion, float strength) {
				return 1.0F - strength;
			}
		},
		INPUT_LINEAR("input_linear") {
			@Override
			public float blend(float value, float erosion, float strength) {
				return 1.0F - strength * value;
			}
		},
		OUTPUT_LINEAR("output_linear") {
			@Override
			public float blend(float value, float erosion, float strength) {
				return 1.0F - strength * erosion;
			}
		};
		
		public static final Codec<BlendMode> CODEC = StringRepresentable.fromEnum(BlendMode::values);
		
		private String name;
		
		private BlendMode(String name) {
			this.name = name;
		}
		
		@Override
		public String getSerializedName() {
			return this.name;
		}

		public abstract float blend(float value, float erosion, float strength);
	}
}
