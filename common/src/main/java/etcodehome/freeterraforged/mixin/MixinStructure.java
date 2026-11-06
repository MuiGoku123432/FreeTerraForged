package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.structure.rule.StructureRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationStub;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.structure.StructureGroundFit;

@Mixin(Structure.class)
public class MixinStructure {

	@Inject(
		at = @At("HEAD"), 
		method = "isValidBiome",
		cancellable = true
	)
	private static void isValidBiome(GenerationStub generationStub, GenerationContext generationContext, CallbackInfoReturnable<Boolean> callback) {
		if (!(generationContext.chunkGenerator() instanceof TerraForgedChunkGenerator generator)) {
			return;
		}
		for (var rule : generator.activeStructurePlan().rules()) {
			if (!rule.value().test(generationContext.randomState(), generationStub.position())) {
				callback.setReturnValue(false);
				return;
			}
		}
	}

	/** A chunk only sees structures whose box reaches it, so the box must cover everywhere terrain is graded to the structure. */
	@Inject(method = "adjustBoundingBox", at = @At("HEAD"), cancellable = true)
	private void freeterraforged$referenceGradedTerrain(BoundingBox box, CallbackInfoReturnable<BoundingBox> cir) {
		if (StructureGroundFit.grades((Structure) (Object) this)) {
			cir.setReturnValue(box.inflatedBy(StructureGroundFit.GRADE_REACH));
		}
	}
}
