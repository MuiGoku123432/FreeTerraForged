package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.concurrent.Resource;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeSurfaceProtection;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;

final class TerrainBiomeRoleSelection {
	/** Vanilla's snow line: below this base temperature precipitation falls as snow and water freezes. */
	static final float FREEZING_TEMPERATURE = 0.15F;
	private final Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers;
	/** Rolling Highlands water roles split by whether the river biome freezes (RH5 W). */
	private final Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> openRivers, frozenRivers;

	TerrainBiomeRoleSelection(WorldgenPlans.ProviderSelection providers) {
		IdentityHashMap<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers =
			new IdentityHashMap<>(), open = new IdentityHashMap<>(), frozen = new IdentityHashMap<>();
		for (WorldgenPlans.ProviderDomain provider : providers.providers()) {
			addRiverCandidates(rivers, open, frozen, provider.candidates());
		}
		providers.fallback().ifPresent(table -> addRiverCandidates(rivers, open, frozen, table));
		this.rivers = rivers;
		this.openRivers = open;
		this.frozenRivers = frozen;
	}

	static boolean freezes(Holder<Biome> biome) {
		return biome.value().getBaseTemperature() < FREEZING_TEMPERATURE;
	}

	/**
	 * Rolling Highlands water role of a 4x4 biome patch (RH5 W): water when at least two of the patch's four quart
	 * corners are water, so the decision follows the patch rather than one corner (no alternating patches along banks,
	 * bars and confluence wedges).
	 */
	static boolean mostlyWater(boolean a, boolean b, boolean c, boolean d) {
		return (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0) >= 2;
	}

	Holder<Biome> resolve(
		WorldgenPlans.ProviderResult result,
		Climate.TargetPoint target,
		Holder<Biome> selected,
		int quartX,
		int quartY,
		int quartZ,
		Climate.Sampler sampler,
		GeneratorContext context
	) {
		Climate.ParameterList<Holder<Biome>> riverCandidates = this.rivers.get(result.candidates());
		if (riverCandidates == null || !result.biome().equals(result.baseBiome())
			|| selected.is(BiomeTags.IS_RIVER)
			|| UndergroundBiomeSurfaceProtection.coverageFactor(
				sampler, target, quartX, quartY, quartZ
			) > 0.0F) {
			return selected;
		}
		boolean perfActive = WorldgenPerfTracker.isActive();
		WorldgenPerfTracker.ThreadStats ts = perfActive ? WorldgenPerfTracker.threadStats() : null;
		WorldgenPerfTracker.ApplyCellCaller prevCaller = null;
		if (ts != null) {
			prevCaller = ts.currentCaller;
			ts.currentCaller = WorldgenPerfTracker.ApplyCellCaller.TERRAIN_ROLE_RIVER;
		}
		try {
			int blockX = QuartPos.toBlock(quartX), blockZ = QuartPos.toBlock(quartZ);
			Cell cell = CellSampler.getCachedOrLookup(context.lookup, blockX, blockZ, false);
			if (!cell.rollingHighlands) {
				return cell.terrain.isRiver() ? riverCandidates.findValue(result.target()) : selected;
			}
			// Rolling Highlands: the water role follows the whole patch, and its water freezes only where the land
			// biome around it is itself freezing (frozen_river beside non-snowy land read as speckled ice).
			boolean corner = cell.terrain.isRiver();
			boolean east = CellSampler.getCachedOrLookup(context.lookup, blockX + 4, blockZ, false).terrain.isRiver();
			boolean south = CellSampler.getCachedOrLookup(context.lookup, blockX, blockZ + 4, false).terrain.isRiver();
			boolean far = CellSampler.getCachedOrLookup(context.lookup, blockX + 4, blockZ + 4, false).terrain.isRiver();
			if (!mostlyWater(corner, east, south, far)) {
				return selected;
			}
			Climate.ParameterList<Holder<Biome>> water = freezes(selected)
				? this.frozenRivers.getOrDefault(result.candidates(), riverCandidates)
				: this.openRivers.getOrDefault(result.candidates(), riverCandidates);
			return water.findValue(result.target());
		} finally {
			if (ts != null) {
				ts.currentCaller = prevCaller;
			}
		}
	}

	private static void addRiverCandidates(
		Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers,
		Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> open,
		Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> frozen,
		Climate.ParameterList<Holder<Biome>> source
	) {
		Climate.ParameterList<Holder<Biome>> candidates = taggedSurfaceCandidates(
			source, holder -> holder.is(BiomeTags.IS_RIVER)
		);
		if (candidates != null) {
			rivers.put(source, candidates);
			// Missing either kind (e.g. a pack without frozen rivers) falls back to every river candidate.
			Climate.ParameterList<Holder<Biome>> openOnly = taggedSurfaceCandidates(
				source, holder -> holder.is(BiomeTags.IS_RIVER) && !freezes(holder)
			);
			Climate.ParameterList<Holder<Biome>> frozenOnly = taggedSurfaceCandidates(
				source, holder -> holder.is(BiomeTags.IS_RIVER) && freezes(holder)
			);
			if (openOnly != null) {
				open.put(source, openOnly);
			}
			if (frozenOnly != null) {
				frozen.put(source, frozenOnly);
			}
		}
	}

	static <T> Climate.ParameterList<T> taggedSurfaceCandidates(
		Climate.ParameterList<T> source,
		Predicate<T> tag
	) {
		List<Pair<Climate.ParameterPoint, T>> candidates = source.values().stream()
			.filter(entry -> tag.test(entry.getSecond())
				&& entry.getFirst().depth().min() <= 0
				&& entry.getFirst().depth().max() >= 0)
			.toList();
		return candidates.isEmpty() ? null : new Climate.ParameterList<>(candidates);
	}
}
