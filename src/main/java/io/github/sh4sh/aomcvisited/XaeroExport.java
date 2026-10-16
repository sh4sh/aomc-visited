package io.github.sh4sh.aomcvisited;

import com.mojang.blaze3d.Blaze3D;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.ChunkPos;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.file.export.PNGExportResult;
import xaero.map.file.export.PNGExportResultType;
import xaero.map.gui.ExportScreen;
import xaero.map.gui.MapTileSelection;
import xaero.map.world.MapDimension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code /visited export}: runs Xaero's World Map export by itself, one compact area at a time
 * ({@link ExportAreas}), cleans each result ({@link TileMasker}) and gathers everything into one folder.
 * Everything that touches Xaero is here and in the mixins; only load this class
 * when {@code xaeroworldmap} is installed.
 */
public final class XaeroExport {
	/** Xaero names each export folder after the time it was made, e.g. {@code 2026-10-15_23.52.26}. */
	private static final Pattern EXPORT_FOLDER = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}");
	/** A single-image export: the timestamp, the corner, and {@code _<n>} if Xaero found the name taken. */
	private static final Pattern SINGLE_IMAGE =
			Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}_x(-?\\d+)_z(-?\\d+)(?:_\\d+)?\\.png");
	private static final DateTimeFormatter FOLDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

	/** True while our export runs; read through {@link #active()}. */
	private static volatile boolean running;
	/** When {@link #running} was set. If Xaero's exporter throws, it never reports back; see {@link #start}. */
	private static volatile long startedAt;
	/** Longer than any real export (each area freezes the game for seconds, not minutes). */
	private static final long STUCK_MS = 5 * 60 * 1000;

	private XaeroExport() {
	}

	/**
	 * Forgets a run when leaving the world. Normally a no-op; it matters if Xaero's exporter threw, since
	 * Xaero then never reports back and {@link #running} would keep forcing our options on Xaero's own export.
	 */
	static void reset() {
		running = false;
	}

	/**
	 * Whether our export is running. Meanwhile {@code XaeroPNGExporterMixin} hands Xaero's exporter our
	 * options (the player's own settings are never changed) and {@code XaeroExportTaskMixin} stops Xaero
	 * opening folders, since we open the combined one at the end. A run older than {@link #STUCK_MS} counts as
	 * dead, so a crashed run can't keep affecting Xaero's own export.
	 */
	public static boolean active() {
		return running && System.currentTimeMillis() - startedAt < STUCK_MS;
	}

	/**
	 * Starts exporting what Xaero wrote this session, for the dimension its map is showing (that's what it
	 * exports), with cave mode off. Returns an error for the player, or null if it started.
	 */
	static Component start(Minecraft client) {
		if (active()) {
			return red("An export is already running.");
		}
		WorldMapSession session = WorldMapSession.getCurrentSession();
		MapProcessor processor = session == null ? null : session.getMapProcessor();
		MapDimension shown = processor == null || !processor.isMapWorldUsable() ? null
				: processor.getMapWorld().getCurrentDimension();
		if (shown == null) {
			return red("Xaero's World Map isn't ready yet. Open the world map once, then try again.");
		}
		// The webmap's main maps (including the Nether, mapped on the roof) are made with cave mode off; cave
		// mode is a separate webmap map that we don't support yet.
		if (processor.getCurrentCaveLayer() != VisitedChunks.SURFACE_LAYER) {
			return red("Xaero's World Map is in cave mode. Turn cave mode off on the world map, then run"
					+ " /visited export again.");
		}
		String key = VisitedChunks.recordKey(shown.getDimId(), VisitedChunks.SURFACE_LAYER);
		LongOpenHashSet chunks = AomcVisitedClient.VISITED.snapshot(key);
		if (chunks.isEmpty()) {
			return red("Nothing recorded for " + key + " yet. If Xaero has been mapping and this stays empty,"
					+ " this version of Xaero's World Map may not be supported.");
		}
		List<int[]> list = new ArrayList<>(chunks.size());
		for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
			long pos = it.nextLong();
			list.add(new int[] {ChunkPos.getX(pos), ChunkPos.getZ(pos)});
		}
		running = true;
		startedAt = System.currentTimeMillis();
		Component error = new Run(client, processor, key, ExportAreas.plan(list)).next();
		if (error != null) {
			running = false;
		}
		return error;
	}

	/** One {@code /visited export}: exports its areas one after another. */
	private static final class Run {
		private final Minecraft client;
		private final MapProcessor processor;
		private final String key;
		private final List<ExportAreas.Box> areas;
		/** Xaero's write count when we started: chunks written after it are kept by "mark as uploaded". */
		private final long writesAtStart = AomcVisitedClient.VISITED.writes();
		private final LongOpenHashSet exported = new LongOpenHashSet();
		private int index;
		private Path folder;
		private TileMasker.Result total = new TileMasker.Result(0, 0, 0, 0);

		Run(Minecraft client, MapProcessor processor, String key, List<ExportAreas.Box> areas) {
			this.client = client;
			this.processor = processor;
			this.key = key;
			this.areas = areas;
		}

		/** Exports the next area, or finishes. Returns an error for the player, or null. */
		Component next() {
			if (index == areas.size()) {
				return finish();
			}
			ExportAreas.Box area = areas.get(index);
			MapTileSelection selection = new MapTileSelection(area.minX(), area.minZ());
			selection.setEnd(area.maxX(), area.maxZ());
			// Xaero only needs the screen to report the result; it shows it afterwards, and we close it.
			ExportScreen screen = new ExportScreen(null, null, processor, selection) {
				@Override
				public void onExportDone(PNGExportResult result) {
					super.onExportDone(result);
					done(this, area, result);
				}
			};
			if (!processor.getMapSaveLoad().exportPNG(screen, selection)) {
				return red("Xaero's World Map is already exporting. Try again when it's done.");
			}
			// Xaero runs the export a moment later, so these get drawn before the game freezes.
			if (index == 0) {
				tell(Component.literal("Exporting " + (areas.size() == 1 ? "1 area" : areas.size() + " areas") + " of "
						+ key + ". The game will freeze for a few seconds" + (areas.size() == 1 ? "." : " per area.")));
			}
			if (client.player != null) {
				client.player.sendOverlayMessage(Component.literal("Exporting area " + (index + 1) + " of "
						+ areas.size() + "…"));
			}
			return null;
		}

		/**
		 * Runs on the main thread inside Xaero's export task. Afterwards Xaero clears its "exporting" flag and
		 * shows {@code screen}, so the next step is queued to run after that.
		 */
		private void done(ExportScreen screen, ExportAreas.Box area, PNGExportResult result) {
			Component problem;
			if (result.getType() == PNGExportResultType.EMPTY) {
				// Nothing Xaero could draw there (e.g. all of it black): nothing to clean, go on.
				problem = null;
			} else if (result.getType().isSuccess() && result.getFolderToOpen() != null) {
				problem = clean(result.getFolderToOpen(), area);
			} else {
				problem = red("Xaero couldn't export area " + (index + 1) + " of " + areas.size() + ": ")
						.append(result.getMessage());
			}
			client.execute(() -> {
				if (client.gui.screen() == screen) {
					client.gui.setScreen(null);
				}
				index++;
				Component message = problem != null ? fail(problem) : next();
				if (message != null) {
					tell(message);
				}
			});
		}

		/**
		 * Cleans what Xaero just wrote for one area and moves it into this run's folder. With Multiple
		 * Unscaled Images on (we force it), that's a timestamped folder of tiles, except when the area fits in
		 * one tile: then it's a single unscaled image straight in {@code map exports}, which Xaero reports as
		 * the folder. Returns an error, or null.
		 */
		private Component clean(Path written, ExportAreas.Box area) {
			// Take the record now, not when the command ran: Xaero keeps mapping meanwhile, and its writer
			// runs on this thread, so this is exactly what the export just rendered.
			LongOpenHashSet chunks = AomcVisitedClient.VISITED.snapshot(key);
			for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
				long pos = it.nextLong();
				int x = ChunkPos.getX(pos), z = ChunkPos.getZ(pos);
				if (x >= area.minX() && x <= area.maxX() && z >= area.minZ() && z <= area.maxZ()) {
					exported.add(pos);
				}
			}
			TileMasker.Visited visited = (x, z) -> chunks.contains(ChunkPos.pack(x, z));
			boolean tiles = EXPORT_FOLDER.matcher(written.getFileName().toString()).matches();
			Path image = null;
			try {
				if (folder == null) {
					Path mapExports = tiles ? written.getParent() : written;
					folder = mapExports.resolve("aomc-visited-" + LocalDateTime.now().format(FOLDER_TIME));
					Files.createDirectories(folder);
				}
				if (tiles) {
					total = total.plus(TileMasker.mask(written, visited));
					moveImages(written);
					Files.deleteIfExists(written);
				} else {
					int blockX = area.minX() * 16, blockZ = area.minZ() * 16;
					image = findSingleImage(written, blockX, blockZ);
					if (image == null) {
						return red("Couldn't find the exported image in " + written + ", so it wasn't cleaned."
								+ " Don't upload it.");
					}
					BufferedImage read = ImageIO.read(image.toFile());
					int width = (area.maxX() - area.minX() + 1) * 16, height = (area.maxZ() - area.minZ() + 1) * 16;
					if (read == null || read.getWidth() != width || read.getHeight() != height) {
						return red("Xaero scaled the export down, so it can't be cleaned. Don't upload " + image + ".");
					}
					TileMasker.Result result = TileMasker.maskImage(image, blockX, blockZ, visited);
					total = total.plus(result);
					if (result.tilesDeleted() == 0) {
						Files.move(image, folder.resolve(image.getFileName()));
					}
				}
				return null;
			} catch (IOException | RuntimeException e) {
				Path broken = image != null ? image : written;
				AomcVisitedClient.LOGGER.error("Could not clean the export in {}", broken, e);
				return red("Could not clean the export (" + e.getMessage() + "). Don't upload it: it may contain"
						+ " older map data. " + markNotCleaned(broken));
			}
		}

		private void moveImages(Path tileFolder) throws IOException {
			try (Stream<Path> files = Files.list(tileFolder)) {
				for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".png"))::iterator) {
					Files.move(file, folder.resolve(file.getFileName()));
				}
			}
		}

		private Component finish() {
			running = false;
			int left = total.tiles() - total.tilesDeleted();
			if (left == 0) {
				deleteEmptyFolder();
				return red("Nothing to upload: none of the exported ground had map data.");
			}
			int changed = 0;
			for (LongIterator it = exported.iterator(); it.hasNext(); ) {
				long pos = it.nextLong();
				if (AomcVisitedClient.VISITED.isChanged(key, ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
					changed++;
				}
			}
			AomcVisitedClient.VISITED.save();
			int number = AomcVisitedClient.VISITED.rememberExport(key, exported, writesAtStart);
			Blaze3D.openPath(folder);
			Component button = Component.literal("[Mark as uploaded]").withStyle(style -> style
					.withColor(ChatFormatting.AQUA)
					.withUnderlined(true)
					.withClickEvent(new ClickEvent.RunCommand("/visited uploaded " + number))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click once the upload has gone"
							+ " through: forgets what this export contained, so the next one only has ground mapped"
							+ " since."))));
			return Component.literal("Export cleaned and ready to upload: " + folder + "\nKept "
					+ total.chunksKept() + " chunks you visited (" + changed + " new or changed), blacked out "
					+ total.chunksBlackedOut() + " chunks of older map; " + left + " images from " + areas.size()
					+ (areas.size() == 1 ? " area. " : " areas. ")).withStyle(ChatFormatting.GREEN).append(button);
		}

		private Component fail(Component problem) {
			running = false;
			if (folder == null || total.tiles() == total.tilesDeleted()) {
				deleteEmptyFolder();
				return problem;
			}
			return problem.copy().append(Component.literal(" The " + (index - 1) + " areas before it are cleaned and"
					+ " in " + folder + ", but this export is incomplete."));
		}

		private void deleteEmptyFolder() {
			if (folder != null) {
				try {
					Files.deleteIfExists(folder);
				} catch (IOException e) {
					// Not empty, or gone: leave it.
				}
			}
		}

		private void tell(Component message) {
			AomcVisitedClient.LOGGER.info(message.getString());
			if (client.player != null) {
				client.player.sendSystemMessage(message);
			}
		}
	}

	/** The newest single-image export in {@code folder} whose name ends in this corner. */
	private static Path findSingleImage(Path folder, int blockX, int blockZ) throws IOException {
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> {
				Matcher m = SINGLE_IMAGE.matcher(p.getFileName().toString());
				return m.matches() && Integer.parseInt(m.group(1)) == blockX && Integer.parseInt(m.group(2)) == blockZ;
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

	private static MutableComponent red(String text) {
		return Component.literal(text).withStyle(ChatFormatting.RED);
	}
}
