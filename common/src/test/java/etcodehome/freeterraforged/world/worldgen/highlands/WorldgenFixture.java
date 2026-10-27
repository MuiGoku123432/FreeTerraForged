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

    static GeneratorContext context(Preset preset, long seed) {
        var registries = new RegistrySetBuilder()
            .add(FTFRegistries.NOISE, ctx -> PresetNoiseData.bootstrap(preset, ctx))
            .build(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        return GeneratorContext.make(preset, registries.lookupOrThrow(FTFRegistries.NOISE), seed);
    }
}
