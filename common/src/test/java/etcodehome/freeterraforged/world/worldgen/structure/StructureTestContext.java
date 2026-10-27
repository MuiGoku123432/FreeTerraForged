package etcodehome.freeterraforged.world.worldgen.structure;

import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseData;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.BuiltInRegistries;

final class StructureTestContext {
    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    static GeneratorContext create(long seed) {
        Preset preset = Presets.makeFTFDefault();
        var registries = new RegistrySetBuilder()
            .add(FTFRegistries.NOISE, ctx -> PresetNoiseData.bootstrap(preset, ctx))
            .build(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        return GeneratorContext.make(preset, registries.lookupOrThrow(FTFRegistries.NOISE), seed);
    }
}
