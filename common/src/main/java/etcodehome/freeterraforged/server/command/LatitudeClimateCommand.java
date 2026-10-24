package etcodehome.freeterraforged.server.command;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.serialization.JsonOps;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.LatitudeSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.platform.ModLoaderUtil;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.climate.LatitudeDiagnosticPoints;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/** Operator-only bounded diagnostic. Samples existing worldgen APIs; never loads/generates chunks. */
public final class LatitudeClimateCommand {
    private LatitudeClimateCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var count = Commands.argument("count", IntegerArgumentType.integer(0, LatitudeDiagnosticPoints.MAX_POINTS))
            .executes(ctx -> export(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z"),
                IntegerArgumentType.getInteger(ctx, "stepX"), IntegerArgumentType.getInteger(ctx, "stepZ"), IntegerArgumentType.getInteger(ctx, "count")));
        var stepZ = Commands.argument("stepZ", IntegerArgumentType.integer()).then(count);
        var stepX = Commands.argument("stepX", IntegerArgumentType.integer()).then(stepZ);
        var z = Commands.argument("z", IntegerArgumentType.integer(-LatitudeSettings.LIMIT, LatitudeSettings.LIMIT)).then(stepX);
        var x = Commands.argument("x", IntegerArgumentType.integer(-LatitudeSettings.LIMIT, LatitudeSettings.LIMIT)).then(z);
        dispatcher.register(Commands.literal("ftf").requires(source -> source.hasPermission(2))
            .then(Commands.literal("climate").then(Commands.literal("transect").then(x))));
    }

    private static int export(CommandSourceStack source, int x, int z, int stepX, int stepZ, int count) {
        var level = source.getLevel();
        var random = level.getChunkSource().randomState();
        if (level.dimension() != Level.OVERWORLD || !((Object) random instanceof FTFRandomState state)
            || state.generatorContext() == null || state.plan() == null) {
            source.sendFailure(Component.literal("Climate diagnostics require an owned FreeTerraForged Overworld."));
            return 0;
        }
        try {
            var points = LatitudeDiagnosticPoints.transect(x, z, stepX, stepZ, count);
            var context = state.generatorContext();
            var heightmap = context.lookup.getHeightmap();
            var profile = heightmap.climate().biomeNoise().latitude().settings();
            var json = new GsonBuilder().setPrettyPrinting().create();
            JsonObject root = new JsonObject();
            root.addProperty("format", "ftf:latitude_diagnostic_v1");
            root.addProperty("build", ModLoaderUtil.version(FTFCommon.MOD_ID).orElse("unknown-development-build"));
            try (var bytes = etcodehome.freeterraforged.world.worldgen.cell.climate.LatitudeClimate.class
                    .getResourceAsStream("LatitudeClimate.class")) {
                if (bytes == null) throw new IllegalStateException("Cannot identify loaded latitude evaluator");
                root.addProperty("evaluatorClassSha256", java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
            }
            root.addProperty("model", profile.model());
            root.addProperty("generationStable", false);
            root.addProperty("seed", state.seed());
            root.addProperty("dimension", level.dimension().location().toString());
            root.addProperty("sampleCount", count);
            root.addProperty("coordinatePolicy", "requested block positions snapped down to quart origins; surface Y clamped to world height");
            root.addProperty("comparisonPolicy", "diagnostic shaping uses sampled cell height; erosion/caches and biome policies can differ; inspect independent actual sampler and loaded stored biome");
            root.add("profile", LatitudeSettings.CODEC.encodeStart(JsonOps.INSTANCE, profile).getOrThrow());
            root.add("preset", Preset.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, context.preset).getOrThrow());
            JsonArray records = new JsonArray();
            for (var point : points) {
                int qx = QuartPos.fromBlock(point.x()), qz = QuartPos.fromBlock(point.z());
                int bx = QuartPos.toBlock(qx), bz = QuartPos.toBlock(qz);
                Cell cell = new Cell();
                context.lookup.applyCell(cell, bx, bz, false);
                int surfaceY = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, heightmap.levels().scale(cell.height)));
                int qy = QuartPos.fromBlock(surfaceY);
                var stages = heightmap.climate().diagnose(cell, bx, bz);
                // Independent query through the actual running sampler, not a recomputed latitude equation.
                var actual = random.sampler().sample(qx, qy, qz);
                var biome = level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(qx, qy, qz, random.sampler());
                JsonObject row = new JsonObject();
                row.addProperty("requestedX", point.x()); row.addProperty("requestedZ", point.z());
                row.addProperty("blockX", bx); row.addProperty("blockZ", bz); row.addProperty("surfaceY", surfaceY);
                row.addProperty("quartX", qx); row.addProperty("quartY", qy); row.addProperty("quartZ", qz);
                row.addProperty("cellHeight", cell.height);
                row.add("stages01", json.toJsonTree(stages));
                row.addProperty("cellTemperatureSigned", cell.temperature); row.addProperty("cellMoistureSigned", cell.moisture);
                JsonObject sampled = new JsonObject();
                sampled.addProperty("temperature", actual.temperature()); sampled.addProperty("moisture", actual.humidity());
                sampled.addProperty("continentalness", actual.continentalness()); sampled.addProperty("erosion", actual.erosion());
                sampled.addProperty("depth", actual.depth()); sampled.addProperty("weirdness", actual.weirdness());
                row.add("actualSamplerQuantized", sampled);
                row.addProperty("sampledBiome", biome.unwrapKey().map(key -> key.location().toString()).orElse("unregistered"));
                var chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
                if (chunk == null) row.add("storedBiome", com.google.gson.JsonNull.INSTANCE);
                else row.addProperty("storedBiome", chunk.getNoiseBiome(qx, qy, qz).unwrapKey().map(key -> key.location().toString()).orElse("unregistered"));
                records.add(row);
            }
            root.add("samples", records);
            var directory = source.getServer().getWorldPath(LevelResource.ROOT).resolve("debug/ftf-latitude");
            Files.createDirectories(directory);
            var file = directory.resolve("transect-" + UUID.randomUUID() + ".json");
            Files.writeString(file, json.toJson(root) + "\n", StandardOpenOption.CREATE_NEW);
            source.sendSuccess(() -> Component.literal("Exported " + count + " climate samples to " + file), false);
            return count;
        } catch (Exception failure) {
            FTFCommon.LOGGER.error("Latitude diagnostic failed", failure);
            source.sendFailure(Component.literal("Climate diagnostic failed: " + failure.getMessage()));
            return 0;
        }
    }
}
