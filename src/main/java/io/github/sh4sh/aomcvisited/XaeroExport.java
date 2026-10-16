package io.github.sh4sh.aomcvisited;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.file.export.PNGExportResult;
import xaero.map.gui.ExportScreen;
import xaero.map.gui.MapTileSelection;
import xaero.map.world.MapDimension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Everything that touches Xaero's World Map. Only load this class when
 * {@code xaeroworldmap} is installed.
 */
final class XaeroExport {
	/** Xaero names each export folder after the time it was made, e.g. {@code 2026-10-15_23.52.26}. */
	private static final Pattern EXPORT_FOLDER = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}");

	private XaeroExport() {
	}

	/**
	 * Opens Xaero's export screen with the bounding box of the chunks Xaero wrote this session selected, for
	 * the dimension and layer its map is showing (that's what it exports). Once the export succeeds, blacks
	 * out everything else. Returns an error for the player, or null.
	 */
	static Component open(Minecraft client) {
		WorldMapSession session = WorldMapSession.getCurrentSession();
		MapProcessor processor = session == null ? null : session.getMapProcessor();
		MapDimension shown = processor == null || !processor.isMapWorldUsable() ? null
				: processor.getMapWorld().getCurrentDimension();
		if (shown == null) {
			return Component.literal("Xaero's World Map isn't ready yet. Open the world map once, then try again.");
		}
		String key = VisitedChunks.recordKey(shown.getDimId(), processor.getCurrentCaveLayer());
		LongOpenHashSet chunks = AomcVisitedClient.VISITED.snapshot(key);
		// So the record on disk matches this export (handy when checking an export by hand).
		AomcVisitedClient.VISITED.save();
		if (chunks.isEmpty()) {
			return Component.literal("Nothing recorded for " + key + " yet. If Xaero has been mapping and this"
					+ " stays empty, this version of Xaero's World Map may not be supported.");
		}

		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
			long pos = it.nextLong();
			minX = Math.min(minX, ChunkPos.getX(pos));
			maxX = Math.max(maxX, ChunkPos.getX(pos));
			minZ = Math.min(minZ, ChunkPos.getZ(pos));
			maxZ = Math.max(maxZ, ChunkPos.getZ(pos));
		}
		MapTileSelection selection = new MapTileSelection(minX, minZ);
		selection.setEnd(maxX, maxZ);

		client.gui.setScreen(new ExportScreen(null, null, processor, selection) {
			@Override
			public void onExportDone(PNGExportResult result) {
				super.onExportDone(result);
				// Runs on the main thread before Xaero opens the folder, so the player only ever sees the
				// cleaned export. The game is already frozen for the export; this adds about a second.
				if (result.getType().isSuccess() && result.getFolderToOpen() != null) {
					Component message = clean(result.getFolderToOpen(), chunks);
					AomcVisitedClient.LOGGER.info(message.getString());
					if (client.player != null) {
						client.player.sendSystemMessage(message);
					}
				}
			}
		});
		return null;
	}

	private static Component clean(Path folder, LongOpenHashSet chunks) {
		TileMasker.Result result;
		try {
			result = TileMasker.mask(folder, (x, z) -> chunks.contains(ChunkPos.pack(x, z)));
		} catch (IOException | RuntimeException e) {
			AomcVisitedClient.LOGGER.error("Could not clean the export in {}", folder, e);
			return Component.literal("Could not clean the export (" + e.getMessage() + "). Don't upload it: it may"
					+ " contain older map data. " + markNotCleaned(folder));
		}
		if (result.tiles() == 0) {
			// With Multiple Unscaled Images off, Xaero writes one image straight into "map exports".
			return Component.literal("That export wasn't made with Multiple Unscaled Images, so it wasn't"
					+ " cleaned. Don't upload it: turn the option on and run /visited export again.");
		}
		return Component.literal("Export cleaned and ready to upload: " + folder + "\nKept " + result.chunksKept()
				+ " chunks you visited, blacked out " + result.chunksBlackedOut() + " chunks of older map, "
				+ (result.tiles() - result.tilesDeleted()) + " of " + result.tiles() + " tiles left.");
	}

	/**
	 * Renames a half-cleaned export folder so nobody uploads it by mistake. Only Xaero's timestamped tile
	 * folders, never {@code map exports} itself. Returns where the folder is now, for the player.
	 */
	private static String markNotCleaned(Path folder) {
		if (!EXPORT_FOLDER.matcher(folder.getFileName().toString()).matches()) {
			return "It's in " + folder + ".";
		}
		Path marked = folder.resolveSibling(folder.getFileName() + "-NOT-CLEANED");
		try {
			Files.move(folder, marked);
			return "It's been renamed to " + marked + ".";
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not rename {}", folder, e);
			return "It's in " + folder + ".";
		}
	}
}
