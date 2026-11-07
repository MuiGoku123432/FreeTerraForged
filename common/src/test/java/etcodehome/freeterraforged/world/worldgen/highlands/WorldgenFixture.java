package etcodehome.freeterraforged.world.worldgen.highlands;

import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseData;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.BuiltInRegistries;

final class WorldgenFixture {
    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    static void initialize() {}

    /** The FTF default preset with the continent settings RH is calibrated for (type left as the default). */
    static Preset preset() {
        Preset preset = etcodehome.freeterraforged.data.worldgen.preset.settings.Presets.makeFTFDefault();
        etcodehome.freeterraforged.data.worldgen.preset.settings.RollingHighlandsSettings.applyContinentBaseline(preset.world().continent);
        return preset;
    }

    /** Shared contexts kept per test JVM, least recently used first; evicted ones are closed. */
    private static final int SHARED_MAX = 2;
    private static final java.util.LinkedHashMap<String, GeneratorContext> SHARED = new java.util.LinkedHashMap<>(4, .75F, true);

    /**
     * A context shared by every caller in this test JVM with an equal preset and seed, so its continent plans (about
     * 10 s each) are built once. Callers must not close it, mutate it, or rely on a cold plan cache; use
     * {@link #context} for tests that compare fresh builds or time cold work.
     */
    static synchronized GeneratorContext shared(Preset preset, long seed) {
        String key = seed + ":" + Preset.DIRECT_CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, preset).getOrThrow();
        var context = SHARED.get(key);
        if (context != null) return context;
        if (SHARED.size() >= SHARED_MAX) {
            var eldest = SHARED.entrySet().iterator().next();
            SHARED.remove(eldest.getKey());
            eldest.getValue().close();
        }
        context = context(preset, seed);
        SHARED.put(key, context);
        return context;
    }

    static GeneratorContext context(Preset preset, long seed) {
        var registries = new RegistrySetBuilder()
            .add(FTFRegistries.NOISE, ctx -> PresetNoiseData.bootstrap(preset, ctx))
            .build(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        return GeneratorContext.make(preset, registries.lookupOrThrow(FTFRegistries.NOISE), seed);
    }
}
