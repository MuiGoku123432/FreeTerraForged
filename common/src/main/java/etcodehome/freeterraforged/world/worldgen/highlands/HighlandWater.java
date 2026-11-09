package etcodehome.freeterraforged.world.worldgen.highlands;

/**
 * RH10 drowned-water tags. A Rolling Highlands water cell is FJORD or BAY when it is sea only because the drowned coast
 * flooded it: the standalone (RH9) coast there is land, and the drowned edge is at or below the coast point. Open sea in
 * front of a drowned region stays untagged. FJORD when the fjord-region weight is at least the ria-region weight (fjord
 * arms and their sounds), BAY otherwise (ria bays and harbors).
 *
 * <p>Tags are read-only metadata on {@code Cell.highlandWater}: they are written after the cell's terrain, height and
 * climate inputs and nothing in generation reads them, so tagging never changes the generated world.
 */
public final class HighlandWater {
    public static final byte NONE = 0, FJORD = 1, BAY = 2;

    private HighlandWater() {}

    static byte classify(HighlandCoast coast, HighlandCoast.Sample location, double x, double z, double coastEdge) {
        if (location.drowned() <= 0 || location.edge() > coastEdge) return NONE;
        if (!(coast.undrowned(x, z).edge() > coastEdge)) return NONE; // sea on the RH9 coast too: open water, not drowned land
        var drowned = coast.drowned();
        double fjord = drowned.fjord(x, z);
        return fjord > 0 && fjord >= drowned.ria(x, z) ? FJORD : BAY;
    }
}
