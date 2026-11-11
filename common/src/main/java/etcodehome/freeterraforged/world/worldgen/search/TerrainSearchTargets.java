package etcodehome.freeterraforged.world.worldgen.search;

import java.util.List;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.highlands.HighlandWater;

/** The canonical terrain targets supported by FTF locate and preview searches. */
public final class TerrainSearchTargets {
	/** Island beaches report as beaches, so the internal island-beach terrain is intentionally omitted. */
	private static final List<Terrain> VALUES = List.of(
		TerrainType.DEEP_OCEAN,
		TerrainType.SHALLOW_OCEAN,
		TerrainType.COAST,
		TerrainType.BEACH,
		TerrainType.RIVER,
		TerrainType.WETLAND,
		TerrainType.FLATS,
		TerrainType.BADLANDS,
		TerrainType.PLATEAU,
		TerrainType.HILLS,
		TerrainType.MOUNTAINS_1,
		TerrainType.MOUNTAINS_2,
		TerrainType.MOUNTAINS_3,
		TerrainType.MOUNTAIN_CHAIN,
		TerrainType.VOLCANO,
		TerrainType.VOLCANO_PIPE,
		TerrainType.ISLAND,
		TerrainType.ISLAND_MOUNTAINS,
		TerrainType.FJORD,
		TerrainType.BAY
	);

	private TerrainSearchTargets() {
	}

	public static Stream<String> names() {
		return VALUES.stream().map(Terrain::getName);
	}

	/**
	 * Whether the sampled cell is the target. Fjord and bay are Rolling Highlands drowned-water tags carried on
	 * {@link Cell#highlandWater}, not terrain identities; every other target matches the cell's terrain.
	 */
	public static boolean matches(Terrain target, Cell cell) {
		if (target == TerrainType.FJORD) return cell.highlandWater == HighlandWater.FJORD;
		if (target == TerrainType.BAY) return cell.highlandWater == HighlandWater.BAY;
		return cell.terrain == target;
	}

	public static boolean contains(Terrain terrain) {
		return VALUES.contains(terrain);
	}

	@Nullable
	public static Terrain get(String name) {
		Terrain terrain = TerrainType.get(name);
		return terrain != null && contains(terrain) ? terrain : null;
	}
}
