package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Immutable saved profile. The draft model must not be advertised as generation-stable. */
public record LatitudeSettings(boolean enabled, int schemaVersion, String model, int equatorZ,
                               int poleDistance, float temperatureStrength, float moistureStrength) {
    public static final int LIMIT = 30_000_000;
    public static final String MODEL = "ftf:latitude_v1_draft";
    public static final LatitudeSettings DEFAULT = new LatitudeSettings(false, 1, MODEL, 0, 50_000, 0.75F, 0.5F);

    // Codec.INT silently truncates fractional numbers; geographic fields require exact integers.
    private static final Codec<Integer> INTEGER = Codec.PASSTHROUGH.comapFlatMap(dynamic -> {
        return dynamic.asNumber().flatMap(number -> {
            try {
                return DataResult.success(new java.math.BigDecimal(number.toString()).intValueExact());
            } catch (NumberFormatException | ArithmeticException invalid) {
                return DataResult.error(() -> "Latitude setting must be an exact integer");
            }
        });
    }, value -> new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JsonOps.INSTANCE,
        new com.google.gson.JsonPrimitive(value)));
    private static final Codec<Float> STRENGTH = Codec.FLOAT.validate(value ->
        Float.isFinite(value) && value >= 0.0F && value <= 1.0F
            ? DataResult.success(value) : DataResult.error(() -> "Latitude strength must be finite and in [0, 1]"));

    public static final Codec<LatitudeSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.BOOL.fieldOf("enabled").forGetter(LatitudeSettings::enabled),
        INTEGER.validate(v -> v == 1 ? DataResult.success(v) : DataResult.error(() -> "Unsupported latitude schemaVersion: " + v))
            .fieldOf("schemaVersion").forGetter(LatitudeSettings::schemaVersion),
        Codec.STRING.validate(v -> MODEL.equals(v) ? DataResult.success(v) : DataResult.error(() -> "Unsupported latitude model: " + v))
            .fieldOf("model").forGetter(LatitudeSettings::model),
        INTEGER.validate(v -> v >= -LIMIT && v <= LIMIT ? DataResult.success(v) : DataResult.error(() -> "Latitude equatorZ outside world bounds"))
            .fieldOf("equatorZ").forGetter(LatitudeSettings::equatorZ),
        INTEGER.validate(v -> v >= 1 && v <= LIMIT ? DataResult.success(v) : DataResult.error(() -> "Latitude poleDistance must be 1..30000000 blocks"))
            .fieldOf("poleDistance").forGetter(LatitudeSettings::poleDistance),
        STRENGTH.fieldOf("temperatureStrength").forGetter(LatitudeSettings::temperatureStrength),
        STRENGTH.fieldOf("moistureStrength").forGetter(LatitudeSettings::moistureStrength)
    ).apply(instance, LatitudeSettings::new));

    /** JsonOps maps explicit null to a missing get(); inspect entries to distinguish old presets from corruption. */
    public static final com.mojang.serialization.MapCodec<LatitudeSettings> OPTIONAL = new com.mojang.serialization.MapCodec<>() {
        @Override
        public <T> DataResult<LatitudeSettings> decode(com.mojang.serialization.DynamicOps<T> ops, com.mojang.serialization.MapLike<T> input) {
            var entry = input.entries().filter(pair -> ops.getStringValue(pair.getFirst()).result()
                .map("latitude"::equals).orElse(false)).findFirst();
            return entry.isEmpty() ? DataResult.success(DEFAULT) : CODEC.parse(ops, entry.get().getSecond());
        }
        @Override
        public <T> com.mojang.serialization.RecordBuilder<T> encode(LatitudeSettings value,
                com.mojang.serialization.DynamicOps<T> ops, com.mojang.serialization.RecordBuilder<T> prefix) {
            return DEFAULT.equals(value) ? prefix : prefix.add("latitude", CODEC.encodeStart(ops, value));
        }
        @Override
        public <T> java.util.stream.Stream<T> keys(com.mojang.serialization.DynamicOps<T> ops) {
            return java.util.stream.Stream.of(ops.createString("latitude"));
        }
    };

    public LatitudeSettings {
        if (schemaVersion != 1 || !MODEL.equals(model) || equatorZ < -LIMIT || equatorZ > LIMIT
            || poleDistance < 1 || poleDistance > LIMIT || !validStrength(temperatureStrength) || !validStrength(moistureStrength)) {
            throw new IllegalArgumentException("Invalid or unsupported native latitude profile");
        }
    }

    private static boolean validStrength(float value) {
        return Float.isFinite(value) && value >= 0.0F && value <= 1.0F;
    }

    public boolean active() { return enabled && (temperatureStrength != 0.0F || moistureStrength != 0.0F); }

    public LatitudeSettings withEnabled(boolean value) {
        return new LatitudeSettings(value, schemaVersion, model, equatorZ, poleDistance, temperatureStrength, moistureStrength);
    }

    public LatitudeSettings withGeometry(int equator, int distance) {
        return new LatitudeSettings(enabled, schemaVersion, model, equator, distance, temperatureStrength, moistureStrength);
    }

    public LatitudeSettings withStrengths(float temperature, float moisture) {
        return new LatitudeSettings(enabled, schemaVersion, model, equatorZ, poleDistance, temperature, moisture);
    }
}
