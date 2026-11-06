package etcodehome.freeterraforged.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

@Mixin(JigsawStructure.class)
public interface JigsawStructureAccessor {
	@Accessor("projectStartToHeightmap")
	Optional<Heightmap.Types> ftf$getProjection();
}
