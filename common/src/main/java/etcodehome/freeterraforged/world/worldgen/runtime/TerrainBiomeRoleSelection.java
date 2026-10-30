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
	/** Vanilla's snow line: below this temperature precipitation falls as snow and water freezes. */
	static final float FREEZING_TEMPERATURE = 0.15F;
	/**
	 * Vanilla's height-adjusted temperature (Biome.getHeightAdjustedTemperature, 1.21.1): above y COOLING_START the
	 * temperature falls by COOLING per block, shifted by a noise of ±NOISE_BLOCKS blocks.
	 */
	static final int COOLING_START = 80;
	static final float COOLING = 0.05F / 40.0F, NOISE_BLOCKS = 8;
	private final Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers;
	/** Rolling Highlands river candidates by base temperature (RH5 W, RH6 I). */
	private final Map<Climate.ParameterList<Holder<Biome>>, RiverTemperatures> riverTemperatures;
	/** Vanilla river and frozen river, used when no candidate is unambiguous at the water's height (may be null). */
	private final Climate.ParameterList<Holder<Biome>> openFallback, frozenFallback;

	TerrainBiomeRoleSelection(WorldgenPlans.ProviderSelection providers) {
		this(providers, null, null);
	}

	TerrainBiomeRoleSelection(WorldgenPlans.ProviderSelection providers, Holder<Biome> river, Holder<Biome> frozenRiver) {
		IdentityHashMap<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers = new IdentityHashMap<>();
		IdentityHashMap<Climate.ParameterList<Holder<Biome>>, RiverTemperatures> temperatures = new IdentityHashMap<>();
		for (WorldgenPlans.ProviderDomain provider : providers.providers()) {
			addRiverCandidates(rivers, temperatures, provider.candidates());
		}
		providers.fallback().ifPresent(table -> addRiverCandidates(rivers, temperatures, table));
		this.rivers = rivers;
		this.riverTemperatures = temperatures;
		this.openFallback = river == null ? null : anywhere(river);
		this.frozenFallback = frozenRiver == null ? null : anywhere(frozenRiver);
	}

	private static Climate.ParameterList<Holder<Biome>> anywhere(Holder<Biome> biome) {
		Climate.Parameter all = Climate.Parameter.span(-1, 1);
		return new Climate.ParameterList<>(List.of(Pair.of(Climate.parameters(all, all, all, all, Climate.Parameter.point(0), all, 0), biome)));
	}

	static boolean freezes(Holder<Biome> biome) {
		return biome.value().getBaseTemperature() < FREEZING_TEMPERATURE;
	}

	/** Coldest height-adjusted temperature of a biome at block height y (most negative temperature noise). */
	static float coldest(float temperature, int y) {
		return y > COOLING_START ? temperature - (NOISE_BLOCKS + y - COOLING_START) * COOLING : temperature;
	}

	/** Warmest height-adjusted temperature of a biome at block height y (most positive temperature noise). */
	static float warmest(float temperature, int y) {
		return y > COOLING_START ? temperature - (-NOISE_BLOCKS + y - COOLING_START) * COOLING : temperature;
	}

	/** Whether water at height y never freezes in a biome of this base temperature, whatever the temperature noise. */
	static boolean alwaysOpen(float temperature, int y) { return coldest(temperature, y) >= FREEZING_TEMPERATURE; }

	/** Whether water at height y always freezes in a biome of this base temperature, whatever the temperature noise. */
	static boolean alwaysFrozen(float temperature, int y) { return warmest(temperature, y) < FREEZING_TEMPERATURE; }

	/** Whether the land biome around water at height y is freezing (its mean height-adjusted temperature). */
	static boolean landFreezes(float temperature, int y) {
		return (y > COOLING_START ? temperature - (y - COOLING_START) * COOLING : temperature) < FREEZING_TEMPERATURE;
	}

	/**
	 * River candidates of one candidate table grouped by base temperature: the candidates whose water never freezes at
	 * a height (base temperature at least a threshold) and those whose water always freezes (below a threshold).
	 */
	static final class RiverTemperatures {
		final float[] temperatures;
		/** {@code warmer[k]}: candidates at temperatures[k] and above; {@code colder[k]}: below temperatures[k]. */
		final Climate.ParameterList<Holder<Biome>>[] warmer, colder;

		@SuppressWarnings("unchecked")
		RiverTemperatures(List<Pair<Climate.ParameterPoint, Holder<Biome>>> candidates) {
			this.temperatures = sortedTemperatures(candidates);
			int m = temperatures.length;
			this.warmer = new Climate.ParameterList[m + 1];
			this.colder = new Climate.ParameterList[m + 1];
			for (int k = 0; k <= m; k++) {
				float threshold = k < m ? temperatures[k] : Float.POSITIVE_INFINITY;
				List<Pair<Climate.ParameterPoint, Holder<Biome>>> up = candidates.stream().filter(e -> e.getSecond().value().getBaseTemperature() >= threshold).toList();
				List<Pair<Climate.ParameterPoint, Holder<Biome>>> down = candidates.stream().filter(e -> e.getSecond().value().getBaseTemperature() < threshold).toList();
				warmer[k] = up.isEmpty() ? null : new Climate.ParameterList<>(up);
				colder[k] = down.isEmpty() ? null : new Climate.ParameterList<>(down);
			}
		}

		private static float[] sortedTemperatures(List<Pair<Climate.ParameterPoint, Holder<Biome>>> candidates) {
			double[] values = candidates.stream().mapToDouble(e -> e.getSecond().value().getBaseTemperature()).distinct().sorted().toArray();
			float[] out = new float[values.length];
			for (int i = 0; i < values.length; i++) out[i] = (float) values[i];
			return out;
		}

		/** Candidates whose water never freezes at height y, or null. */
		Climate.ParameterList<Holder<Biome>> open(int y) {
			for (int k = 0; k < temperatures.length; k++) if (alwaysOpen(temperatures[k], y)) return warmer[k];
			return null;
		}

		/** Candidates whose water always freezes at height y, or null. */
		Climate.ParameterList<Holder<Biome>> frozen(int y) {
			int k = 0;
			while (k < temperatures.length && alwaysFrozen(temperatures[k], y)) k++;
			return colder[k];
		}
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
			// biome around it is itself freezing (frozen_river beside non-snowy land read as speckled ice). RH6 I: both
			// judged at the water's height with vanilla's height cooling; a river biome whose water would freeze on some
			// columns and not others at that height (its temperature noise straddles the snow line) is never chosen.
			boolean corner = cell.terrain.isRiver();
			boolean east = CellSampler.getCachedOrLookup(context.lookup, blockX + 4, blockZ, false).terrain.isRiver();
			boolean south = CellSampler.getCachedOrLookup(context.lookup, blockX, blockZ + 4, false).terrain.isRiver();
			boolean far = CellSampler.getCachedOrLookup(context.lookup, blockX + 4, blockZ + 4, false).terrain.isRiver();
			if (!mostlyWater(corner, east, south, far)) {
				return selected;
			}
			int y = waterY(cell, context);
			boolean freezing = landFreezes(selected.value().getBaseTemperature(), y);
			RiverTemperatures byTemperature = this.riverTemperatures.get(result.candidates());
			Climate.ParameterList<Holder<Biome>> water = byTemperature == null ? null
				: freezing ? byTemperature.frozen(y) : byTemperature.open(y);
			if (water == null) water = freezing ? this.frozenFallback : this.openFallback;
			if (water == null) water = riverCandidates;
			return water.findValue(result.target());
		} finally {
			if (ts != null) {
				ts.currentCaller = prevCaller;
			}
		}
	}

	/** Block height of the cell's water surface (its top water block). */
	private static int waterY(Cell cell, GeneratorContext context) {
		return context.levels.waterLevel + (int) Math.floor((cell.riverWaterLevel - context.levels.water) * context.levels.terrainScaleFactor);
	}

	private static void addRiverCandidates(
		Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers,
		Map<Climate.ParameterList<Holder<Biome>>, RiverTemperatures> temperatures,
		Climate.ParameterList<Holder<Biome>> source
	) {
		Climate.ParameterList<Holder<Biome>> candidates = taggedSurfaceCandidates(
			source, holder -> holder.is(BiomeTags.IS_RIVER)
		);
		if (candidates != null) {
			rivers.put(source, candidates);
			temperatures.put(source, new RiverTemperatures(candidates.values()));
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
