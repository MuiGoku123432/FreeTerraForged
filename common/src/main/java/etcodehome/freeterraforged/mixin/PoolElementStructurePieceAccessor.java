package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

@Mixin(PoolElementStructurePiece.class)
public interface PoolElementStructurePieceAccessor {
	@Accessor("structureTemplateManager")
	StructureTemplateManager ftf$getTemplates();
}
