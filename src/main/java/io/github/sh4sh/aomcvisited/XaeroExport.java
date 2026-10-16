package io.github.sh4sh.aomcvisited;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.level.ChunkPos;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.file.export.PNGExportResult;
import xaero.map.gui.ExportScreen;
import xaero.map.gui.MapTileSelection;
import xaero.map.world.MapDimension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Everything that touches Xaero's World Map. Only load this class when
 * {@code xaeroworldmap} is installed.
 */
final class XaeroExport {
	/** Xaero names each export folder after the time it was made, e.g. {@code 2026-10-15_23.52.26}. */
	private static final Pattern EXPORT_FOLDER = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}");
	/** A single-image export: the timestamp, the corner, and {@code _<n>} if Xaero found the name taken. */
	private static final Pattern SINGLE_IMAGE =
			Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}_x(-?\\d+)_z(-?\\d+)(?:_\\d+)?\\.png");

	private XaeroExport() {
	}

	/**
	 * Opens Xaero's export screen with the bounding box of the chunks Xaero wrote this session selected, for
	 * the dimension its map is showing (that's what it exports), with cave mode off. Once the export
	 * succeeds, blacks out everything else. Returns an error for the player, or null.
	 */
	static Component open(Minecraft client) {
		WorldMapSession session = WorldMapSession.getCurrentSession();
		MapProcessor processor = session == null ? null : session.getMapProcessor();
		MapDimension shown = processor == null || !processor.isMapWorldUsable() ? null
				: processor.getMapWorld().getCurrentDimension();
		if (shown == null) {
			return Component.literal("Xaero's World Map isn't ready yet. Open the world map once, then try again.");
		}
		// The webmap's main maps (including the Nether, mapped on the roof) are made with cave mode off; cave
		// mode is a separate webmap map that we don't support yet.
		if (processor.getCurrentCaveLayer() != VisitedChunks.SURFACE_LAYER) {
			return Component.literal("Xaero's World Map is in cave mode. Turn cave mode off on the world map,"
					+ " then run /visited export again.");
		}
		String key = VisitedChunks.recordKey(shown.getDimId(), VisitedChunks.SURFACE_LAYER);
		LongOpenHashSet chunks = AomcVisitedClient.VISITED.snapshot(key);
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
		Area area = new Area(minX * 16, minZ * 16, (maxX - minX + 1) * 16, (maxZ - minZ + 1) * 16);

		client.gui.setScreen(new ExportScreen(null, null, processor, selection) {
			@Override
			public void onExportDone(PNGExportResult result) {
				super.onExportDone(result);
				// Runs on the main thread before Xaero opens the folder, so the player only ever sees the
				// cleaned export. The game is already frozen for the export; this adds about a second.
				if (result.getType().isSuccess() && result.getFolderToOpen() != null) {
					// Take the record now, not when the command ran: Xaero keeps mapping meanwhile (it was
					// still catching up after a flight in the 0.1.1 test), and its writer runs on this thread,
					// so this is exactly what the export just rendered. Save it so the file matches too.
					LongOpenHashSet exported = AomcVisitedClient.VISITED.snapshot(key);
					int changedCount = AomcVisitedClient.VISITED.changedCount(key);
					AomcVisitedClient.VISITED.save();
					Component message = clean(result.getFolderToOpen(), area, key, exported, changedCount);
					AomcVisitedClient.LOGGER.info(message.getString());
					if (client.player != null) {
						client.player.sendSystemMessage(message);
					}
				}
			}
		});
		return null;
	}

	/** The exported rectangle in blocks: its top-left corner and size. */
	private record Area(int blockX, int blockZ, int width, int height) {
	}

	/**
	 * Cleans what Xaero just wrote. With Multiple Unscaled Images on, that's a timestamped folder of tiles,
	 * except when the whole area fits in one tile: then Xaero writes a single unscaled image straight into
	 * {@code map exports}, named {@code <timestamp>_x<X>_z<Z>.png} (the folder it reports is then
	 * {@code map exports} itself). With the option off and a bigger area, the single image is scaled down and
	 * can't be cleaned chunk by chunk.
	 */
	private static Component clean(Path folder, Area area, String key, LongOpenHashSet chunks, int changedCount) {
		TileMasker.Visited visited = (x, z) -> chunks.contains(ChunkPos.pack(x, z));
		Path written = folder;
		TileMasker.Result result;
		try {
			if (EXPORT_FOLDER.matcher(folder.getFileName().toString()).matches()) {
				result = TileMasker.mask(folder, visited);
			} else {
				written = findSingleImage(folder, area);
				if (written == null) {
					return Component.literal("Couldn't find the exported image in " + folder + ", so it wasn't"
							+ " cleaned. Don't upload it.").withStyle(ChatFormatting.RED);
				}
				BufferedImage image = ImageIO.read(written.toFile());
				if (image == null || image.getWidth() != area.width() || image.getHeight() != area.height()) {
					return Component.literal("That export was scaled down to fit one image, so it can't be cleaned."
							+ " Don't upload it: turn on Multiple Unscaled Images and run /visited export again.")
							.withStyle(ChatFormatting.RED);
				}
				result = TileMasker.maskImage(written, area.blockX(), area.blockZ(), visited);
			}
		} catch (IOException | RuntimeException e) {
			AomcVisitedClient.LOGGER.error("Could not clean the export in {}", written, e);
			return Component.literal("Could not clean the export (" + e.getMessage() + "). Don't upload it: it may"
					+ " contain older map data. " + markNotCleaned(written)).withStyle(ChatFormatting.RED);
		}
		if (result.tiles() == 0) {
			return Component.literal("Found no exported images in " + folder + ", so nothing was cleaned. Don't"
					+ " upload anything from this export.").withStyle(ChatFormatting.RED);
		}
		Component message = Component.literal("Export cleaned and ready to upload: " + written + "\nKept "
				+ result.chunksKept() + " chunks you visited (" + changedCount + " new or changed), blacked out "
				+ result.chunksBlackedOut() + " chunks of older map, " + (result.tiles() - result.tilesDeleted())
				+ " of " + result.tiles() + " images left. ").withStyle(ChatFormatting.GREEN);
		if (result.tiles() == result.tilesDeleted()) {
			return message;
		}
		int number = AomcVisitedClient.VISITED.rememberExport(key, chunks);
		Component button = Component.literal("[Mark as uploaded]").withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand("/visited uploaded " + number))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click once the upload has gone through:"
						+ " forgets what this export contained, so the next one only has ground mapped since."))));
		return message.copy().append(button);
	}

	/** The newest single-image export in {@code folder} whose name ends in this area's top-left corner. */
	private static Path findSingleImage(Path folder, Area area) throws IOException {
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> {
				Matcher m = SINGLE_IMAGE.matcher(p.getFileName().toString());
				return m.matches() && Integer.parseInt(m.group(1)) == area.blockX()
						&& Integer.parseInt(m.group(2)) == area.blockZ();
			}).max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
		}
	}

	/**
	 * Renames a half-cleaned export so nobody uploads it by mistake: a timestamped tile folder gets
	 * {@code -NOT-CLEANED}, a single image gets {@code .NOT-CLEANED} after {@code .png} (so it no longer
	 * looks like an image). Never touches {@code map exports} itself. Returns where it is now, for the player.
	 */
	private static String markNotCleaned(Path written) {
		String name = written.getFileName().toString();
		Path marked;
		if (EXPORT_FOLDER.matcher(name).matches()) {
			marked = written.resolveSibling(name + "-NOT-CLEANED");
		} else if (SINGLE_IMAGE.matcher(name).matches()) {
			marked = written.resolveSibling(name + ".NOT-CLEANED");
		} else {
			return "It's in " + written + ".";
		}
		try {
			Files.move(written, marked);
			return "It's been renamed to " + marked + ".";
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not rename {}", written, e);
			return "It's in " + written + ".";
		}
	}
}
