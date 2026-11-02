package etcodehome.freeterraforged.world.worldgen.cell.rivermap;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;

/** Single height contract for water consumers, preserving the legacy offset expression exactly. */
public final class RiverWater {
    private RiverWater() {}
    public static float level(Cell cell, float oceanLevel) {
        return cell.rollingHighlands ? cell.riverWaterLevel : ContinentalHydrology.getComplexWaterHeight(
            cell.waterTable, cell.globalContinentScale, cell.continentSizeModifier) + oceanLevel;
    }
    public static boolean isWater(Cell cell) {
        return cell.rollingHighlands ? cell.hasRiverWater : cell.terrain.isWetland()
            || ((cell.terrain.isRiver() || cell.terrain.isLake()) && cell.riverWaterLevel > 0);
    }
}
