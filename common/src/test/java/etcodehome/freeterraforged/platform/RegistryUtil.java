package etcodehome.freeterraforged.platform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Test-only replacement for Architectury's untransformed binding, never packaged into the mod. */
public final class RegistryUtil {
    public static <T> Registry<T> createRegistry(ResourceKey<Registry<T>> key) {
        return new MappedRegistry<>(key, Lifecycle.stable());
    }
    public static <T> void register(Registry<T> registry, String name, T value) {
        Registry.register(registry, ResourceLocation.fromNamespaceAndPath("freeterraforged", name), value);
    }
    public static <T> void createDataRegistry(ResourceKey<Registry<T>> key, Codec<T> codec, boolean synced) {
        throw new UnsupportedOperationException("No world registry loading in standalone climate tests");
    }
}
