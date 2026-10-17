package io.github.sh4sh.aomcvisited;

import com.mojang.blaze3d.Blaze3D;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.file.export.PNGExportResult;
import xaero.map.file.export.PNGExportResultType;
import xaero.map.gui.ExportScreen;
import xaero.map.gui.MapTileSelection;
import xaero.map.world.MapWorld;

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

public final class XaeroExport {
	private static final Pattern EXPORT_FOLDER = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}");
	private static final Pattern SINGLE_IMAGE =
			Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}_x(-?\\d+)_z(-?\\d+)(?:_\\d+)?\\.png");
	private static final DateTimeFormatter FOLDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

	private static final long STUCK_MS = 5 * 60 * 1000;
	/**
	 * Xaero's task polls until its "exporting" flag clears; starting the next area before it returns sets the
	 * flag again and both wait forever. Used only if {@code XaeroExportTaskMixin} can't report the return.
	 */
	private static final long TASK_END_FALLBACK_MS = 1000;
	private static final long SWITCH_TIMEOUT_MS = 30_000;
	private static final long SETTLE_MS = 1000;
	private static final int NOT_PREPARED_RETRIES = 20;

	private static volatile Job job;
	private static volatile long lastProgress;
	private static volatile boolean taskEnded;

	private XaeroExport() {
	}

	/** If Xaero's exporter throws it never reports back, so a stuck job stops counting. */
	public static boolean active() {
		return job != null && System.currentTimeMillis() - lastProgress < STUCK_MS;
	}

	static void reset() {
		Job current = job;
		job = null;
		if (current != null && current.whenDone != null) {
			current.whenDone.run();
		}
	}

	public static void taskEnded() {
		taskEnded = true;
	}

	static void tick() {
		Job current = job;
		if (current != null) {
			current.tick();
		}
	}

	static Component start(Minecraft client, String only, Runnable whenDone) {
		if (!AomcVisitedClient.SETTINGS.enabled()) {
			return Chat.error("AOMC Visited is off. Turn it on with /visited on.");
		}
		if (active()) {
			return Chat.error("An export is already running.");
		}
		WorldMapSession session = WorldMapSession.getCurrentSession();
		MapProcessor processor = session == null ? null : session.getMapProcessor();
		if (processor == null || !processor.isMapWorldUsable() || processor.getMapWorld() == null) {
			return Chat.error("Xaero's World Map isn't ready yet. Open the world map once, then try again.");
		}
		List<String> keys = AomcVisitedClient.VISITED.dimensions();
		if (only != null) {
			if (!keys.contains(only)) {
				return Chat.error("Nothing recorded in " + VisitedChunks.shortName(only) + " yet.");
			}
			keys = List.of(only);
		}
		if (keys.isEmpty()) {
			return Chat.error("Nothing recorded yet. If Xaero has been mapping and this stays empty, this version of"
					+ " Xaero's World Map may not be supported.");
		}
		List<String> names = keys.stream().map(VisitedChunks::shortName).toList();
		ResourceKey<Level> showing = processor.getMapWorld().getCurrentDimensionId();
		boolean switches = keys.size() > 1 || showing == null || !keys.getFirst().equals(showing.identifier().toString());
		Chat.send(client, Chat.info("Exporting " + String.join(", ", names) + ". Progress shows above your hotbar."
				+ (switches ? " Xaero's map switches dimension while it works, and the" : " The")
				+ " game may freeze for a few seconds."));
		job = new Job(client, processor, keys, whenDone);
		lastProgress = System.currentTimeMillis();
		job.startDimension();
		return null;
	}

	private static final class Job {
		private enum Stage { SWITCHING, SETTLING, EXPORTING, AREA_DONE, RETRY }

		private final Minecraft client;
		private final MapProcessor processor;
		private final List<String> keys;
		private final Runnable whenDone;
		private final ResourceKey<Level> previousCustom;
		private final long writesAtStart = AomcVisitedClient.VISITED.writes();
		private final MutableComponent report = Component.empty();
		private final List<Component> buttons = new ArrayList<>();
		private int ticks;
		private int dimension;
		private Path root;
		private boolean anyImages;
		private Stage stage;
		private long stageSince;
		private ResourceKey<Level> target;
		private Run run;

		Job(Minecraft client, MapProcessor processor, List<String> keys, Runnable whenDone) {
			this.client = client;
			this.processor = processor;
			this.keys = keys;
			this.whenDone = whenDone;
			this.previousCustom = processor.getMapWorld().getCustomDimensionId();
		}

		private void stage(Stage next) {
			stage = next;
			stageSince = System.currentTimeMillis();
			lastProgress = stageSince;
		}

		private long inStage() {
			return System.currentTimeMillis() - stageSince;
		}

		void startDimension() {
			if (dimension == keys.size()) {
				finish();
				return;
			}
			target = ResourceKey.create(Registries.DIMENSION, Identifier.parse(keys.get(dimension)));
			processor.getMapWorld().setCustomDimensionId(target);
			processor.checkForWorldUpdate();
			stage(Stage.SWITCHING);
		}

		private void nextDimension() {
			dimension++;
			startDimension();
		}

		void tick() {
			if (++ticks % 20 == 1) {
				showProgress();
			}
			switch (stage) {
				case SWITCHING -> {
					MapWorld world = processor.getMapWorld();
					if (!processor.isWaitingForWorldUpdate() && processor.isMapWorldUsable() && world != null
							&& target.equals(world.getCurrentDimensionId())) {
						stage(Stage.SETTLING);
					} else if (inStage() > SWITCH_TIMEOUT_MS) {
						line(red(VisitedChunks.shortName(keys.get(dimension)) + ": Xaero didn't switch its map to it in time,"
								+ " so it was skipped. Try again."));
						nextDimension();
					}
				}
				case SETTLING -> {
					if (inStage() > SETTLE_MS) {
						beginDimensionExport();
					}
				}
				case AREA_DONE -> {
					if (taskEnded || inStage() > TASK_END_FALLBACK_MS) {
						run.continueAfterArea();
					}
				}
				case RETRY -> {
					if (inStage() > SETTLE_MS && (taskEnded || inStage() > SETTLE_MS + TASK_END_FALLBACK_MS)) {
						run.exportArea();
					}
				}
				case EXPORTING -> {
					// Waiting for Xaero to call onExportDone.
				}
			}
		}

		private void showProgress() {
			String name = VisitedChunks.shortName(keys.get(dimension));
			String of = keys.size() > 1 ? " (" + (dimension + 1) + " of " + keys.size() + ")" : "";
			String what = stage == Stage.SWITCHING || stage == Stage.SETTLING || run == null
					? "switching Xaero's map to " + name + of
					: "exporting " + name + of + ", area " + Math.min(run.index + 1, run.areas.size()) + " of "
							+ run.areas.size();
			Chat.status(client, what + ".".repeat(1 + ticks / 20 % 3), ChatFormatting.YELLOW);
		}

		private void beginDimensionExport() {
			// The webmap's main maps are made with cave mode off.
			if (processor.getCurrentCaveLayer() != VisitedChunks.SURFACE_LAYER) {
				line(red(VisitedChunks.shortName(keys.get(dimension)) + ": Xaero's map is in cave mode there, so it was skipped."
						+ " Turn cave mode off and export again."));
				nextDimension();
				return;
			}
			String key = keys.get(dimension);
			LongOpenHashSet chunks = AomcVisitedClient.VISITED.snapshot(key);
			List<int[]> list = new ArrayList<>(chunks.size());
			for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
				long pos = it.nextLong();
				list.add(new int[] {ChunkPos.getX(pos), ChunkPos.getZ(pos)});
			}
			run = new Run(this, key, ExportAreas.plan(list));
			showProgress();
			run.exportArea();
		}

		void line(Component text) {
			report.append(Chat.line(text));
		}

		private void finish() {
			MapWorld world = processor.getMapWorld();
			if (world != null) {
				world.setCustomDimensionId(previousCustom);
				processor.checkForWorldUpdate();
			}
			job = null;
			AomcVisitedClient.VISITED.save();
			MutableComponent message;
			if (anyImages) {
				Blaze3D.openPath(root);
				message = Chat.success("Export cleaned and ready to upload: map exports/" + root.getFileName()
						+ (keys.size() == 1 ? ". Upload it to the " + VisitedChunks.shortName(keys.getFirst()) + " map."
								: ". Upload each dimension's folder to its own map."));
				buttons.addFirst(Chat.openButton("Open folder", root));
				message.append(report).append(Chat.buttons(buttons.toArray(Component[]::new)));
				Chat.status(client, "export done, see chat.", ChatFormatting.GREEN);
			} else {
				deleteIfEmpty(root);
				message = Chat.warning("Nothing to upload.").append(report);
				Chat.status(client, "nothing to upload.", ChatFormatting.GOLD);
			}
			Chat.send(client, message);
			if (whenDone != null) {
				whenDone.run();
			}
		}
	}

	private static final class Run {
		private final Job job;
		private final String key;
		private final List<ExportAreas.Box> areas;
		private final LongOpenHashSet exported = new LongOpenHashSet();
		private int index;
		private int retries;
		private Path folder;
		private TileMasker.Result total = new TileMasker.Result(0, 0, 0, 0);
		private ExportScreen doneScreen;
		private Component doneProblem;

		Run(Job job, String key, List<ExportAreas.Box> areas) {
			this.job = job;
			this.key = key;
			this.areas = areas;
		}

		void exportArea() {
			if (index == areas.size()) {
				finishDimension();
				return;
			}
			ExportAreas.Box area = areas.get(index);
			MapTileSelection selection = new MapTileSelection(area.minX(), area.minZ());
			selection.setEnd(area.maxX(), area.maxZ());
			// Xaero only needs the screen to report the result; it shows it afterwards, and we close it.
			ExportScreen screen = new ExportScreen(null, null, job.processor, selection) {
				@Override
				public void onExportDone(PNGExportResult result) {
					super.onExportDone(result);
					done(this, area, result);
				}
			};
			taskEnded = false;
			if (!job.processor.getMapSaveLoad().exportPNG(screen, selection)) {
				fail(red("Xaero's World Map is already exporting."));
				return;
			}
			job.stage(Job.Stage.EXPORTING);
			// Xaero runs the export a moment later, so this gets drawn before the game freezes.
			job.showProgress();
		}

		/** Runs inside Xaero's task, which shows {@code screen} afterwards (see {@link #TASK_END_FALLBACK_MS}). */
		private void done(ExportScreen screen, ExportAreas.Box area, PNGExportResult result) {
			doneScreen = screen;
			PNGExportResultType type = result.getType();
			if (type == PNGExportResultType.NOT_PREPARED && retries < NOT_PREPARED_RETRIES) {
				retries++;
				doneProblem = null;
				job.stage(Job.Stage.RETRY);
				closeScreenLater();
				return;
			}
			if (type == PNGExportResultType.EMPTY) {
				doneProblem = null;
			} else if (type.isSuccess() && result.getFolderToOpen() != null) {
				doneProblem = clean(result.getFolderToOpen(), area);
			} else {
				doneProblem = red("Xaero couldn't export area " + (index + 1) + " of " + areas.size() + ": ")
						.append(result.getMessage());
			}
			job.stage(Job.Stage.AREA_DONE);
		}

		/** For a retry: Xaero shows the screen after this task, so close it once the task is over. */
		private void closeScreenLater() {
			ExportScreen screen = doneScreen;
			job.client.execute(() -> {
				if (job.client.gui.screen() == screen) {
					job.client.gui.setScreen(null);
				}
			});
		}

		void continueAfterArea() {
			if (job.client.gui.screen() == doneScreen) {
				job.client.gui.setScreen(null);
			}
			if (doneProblem != null) {
				fail(doneProblem);
				return;
			}
			index++;
			retries = 0;
			exportArea();
		}

		/**
		 * {@code written} is a timestamped folder of tiles, or, when the area fits in one tile, {@code map
		 * exports} itself with a single image in it. Returns an error, or null.
		 */
		private Component clean(Path written, ExportAreas.Box area) {
			// Xaero's writer runs on this thread, so this is exactly what the export rendered.
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
				if (job.root == null) {
					Path mapExports = tiles ? written.getParent() : written;
					String dimension = job.keys.size() == 1 ? folderName(key) + "-" : "";
					job.root = mapExports.resolve("aomc-visited-" + dimension + LocalDateTime.now().format(FOLDER_TIME));
				}
				if (folder == null) {
					folder = job.keys.size() == 1 ? job.root : job.root.resolve(folderName(key));
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

		private void finishDimension() {
			int left = total.tiles() - total.tilesDeleted();
			if (left == 0) {
				deleteIfEmpty(folder);
				job.line(Component.literal(VisitedChunks.shortName(key) + ": nothing to upload."));
				job.nextDimension();
				return;
			}
			job.anyImages = true;
			int changed = 0;
			for (LongIterator it = exported.iterator(); it.hasNext(); ) {
				long pos = it.nextLong();
				if (AomcVisitedClient.VISITED.isChanged(key, ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
					changed++;
				}
			}
			int number = AomcVisitedClient.VISITED.rememberExport(key, exported, job.writesAtStart);
			job.buttons.add(Chat.button("Mark " + VisitedChunks.shortName(key) + " as uploaded", ChatFormatting.GREEN,
					"/visited uploaded " + number, "Click once " + where() + " has been uploaded: forgets"
							+ " what it contained, so the next export only has areas mapped since. Works after a"
							+ " restart too."));
			String name = job.keys.size() == 1 ? VisitedChunks.shortName(key) : folderName(key) + "/";
			job.line(Component.literal(name + ": " + total.chunksKept() + " chunks (" + changed + " new or changed), "
					+ left + (left == 1 ? " image" : " images")
					+ (total.chunksBlackedOut() > 0 ? ", " + total.chunksBlackedOut() + " older chunks blacked out" : "")
					+ "."));
			job.nextDimension();
		}

		private String where() {
			return job.keys.size() == 1 ? "the export" : folderName(key) + "/";
		}

		private void fail(Component problem) {
			MutableComponent text = Component.literal(VisitedChunks.shortName(key) + ": ").append(problem);
			if (total.tiles() > total.tilesDeleted()) {
				text.append(" The areas before it are cleaned, but " + where() + " is incomplete.");
				job.anyImages = true;
			} else {
				deleteIfEmpty(folder);
			}
			job.line(text.withStyle(ChatFormatting.RED));
			job.nextDimension();
		}
	}

	private static String folderName(String key) {
		return VisitedChunks.shortName(key).replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static void deleteIfEmpty(Path folder) {
		if (folder != null) {
			try {
				Files.deleteIfExists(folder);
			} catch (IOException e) {
				// Not empty, or gone: leave it.
			}
		}
	}

	private static Path findSingleImage(Path folder, int blockX, int blockZ) throws IOException {
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> {
				Matcher m = SINGLE_IMAGE.matcher(p.getFileName().toString());
				return m.matches() && Integer.parseInt(m.group(1)) == blockX && Integer.parseInt(m.group(2)) == blockZ;
			}).max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
		}
	}

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
