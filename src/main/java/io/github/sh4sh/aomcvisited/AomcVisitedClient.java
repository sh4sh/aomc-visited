package io.github.sh4sh.aomcvisited;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class AomcVisitedClient implements ClientModInitializer {
	public static final String MOD_ID = "aomc_visited";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Save every minute while playing, so a crash loses little (only records that changed are written). */
	private static final int AUTOSAVE_TICKS = 60 * 20;

	public static final VisitedChunks VISITED =
			new VisitedChunks(FabricLoader.getInstance().getGameDir().resolve(MOD_ID));
	public static final Settings SETTINGS =
			new Settings(FabricLoader.getInstance().getGameDir().resolve(MOD_ID).resolve("settings.properties"));

	private int ticksSinceSave;
	/** The export starts a tick after the command, once the chat screen has closed. */
	private boolean exportNextTick;
	/** The dimension to export then, or null for every dimension with recorded ground. */
	private String exportOnly;
	/** Ticks until the reminder about earlier ground, after joining; 0 when there's none to show. */
	private int reminderTicks;

	/** Wait a few seconds after joining, so the reminder isn't lost among the server's join messages. */
	private static final int REMINDER_DELAY_TICKS = 5 * 20;

	@Override
	public void onInitializeClient() {
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
			VISITED.open(VisitedChunks.serverKey(client));
			reminderTicks = REMINDER_DELAY_TICKS;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
			VISITED.close();
			if (xaeroInstalled()) {
				XaeroExport.reset();
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> VISITED.close());
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("visited")
						.executes(context -> showCounts(context.getSource()))
						.then(exportCommand(ClientCommands.literal("export"), null)
								.then(exportCommand(ClientCommands.literal("overworld"), "minecraft:overworld"))
								.then(exportCommand(ClientCommands.literal("the_nether"), "minecraft:the_nether"))
								.then(exportCommand(ClientCommands.literal("the_end"), "minecraft:the_end")))
						.then(ClientCommands.literal("clear")
								.executes(context -> clear(context.getSource(), false))
								.then(ClientCommands.literal("confirm").executes(context -> clear(context.getSource(), true)))
								.then(ClientCommands.literal("all")
										.executes(context -> clearAll(context.getSource(), false))
										.then(ClientCommands.literal("confirm").executes(context -> clearAll(context.getSource(), true)))))
						.then(ClientCommands.literal("restore").executes(context -> restore(context.getSource())))
						.then(ClientCommands.literal("uploaded")
								.executes(context -> markUploaded(context.getSource(), VISITED.latestExport()))
								.then(ClientCommands.argument("export", IntegerArgumentType.integer(1)).executes(context ->
										markUploaded(context.getSource(), IntegerArgumentType.getInteger(context, "export")))))
						.then(ClientCommands.literal("show")
								.executes(context -> setShow(context.getSource(), !SETTINGS.showOnMap()))
								.then(ClientCommands.literal("on").executes(context -> setShow(context.getSource(), true)))
								.then(ClientCommands.literal("off").executes(context -> setShow(context.getSource(), false))))));
		if (xaeroInstalled()) {
			VisitedHighlighter.registerEvents();
		}

		LOGGER.info("AOMC Visited loaded");
	}

	private void tick(Minecraft client) {
		if (xaeroInstalled()) {
			XaeroExport.tick();
			if (ticksSinceSave % 20 == 0) {
				VisitedHighlighter.refreshChanged();
			}
		}
		if (exportNextTick) {
			exportNextTick = false;
			Component error = XaeroExport.start(client, exportOnly, null);
			if (error != null && client.player != null) {
				client.player.sendSystemMessage(error);
			}
		}
		if (reminderTicks > 0 && --reminderTicks == 0) {
			remindOfEarlierGround(client);
		}
		if (++ticksSinceSave >= AUTOSAVE_TICKS) {
			ticksSinceSave = 0;
			VISITED.save();
		}
	}

	/** {@code /visited}: how many chunks each record holds. */
	private static int showCounts(FabricClientCommandSource source) {
		String server = VISITED.server();
		if (server == null) {
			source.sendFeedback(Chat.error("Not connected to a world."));
			return 0;
		}
		Map<String, Integer> counts = new java.util.TreeMap<>(VISITED.counts());
		counts.values().removeIf(count -> count == 0);
		MutableComponent message = Chat.info("Chunks recorded on " + server.substring(server.indexOf('/') + 1) + ":");
		if (counts.isEmpty()) {
			message.append(Chat.line("none yet"));
		}
		counts.forEach((key, count) -> message.append(Chat.line(shortName(key) + ": " + count)));
		source.sendFeedback(message);
		return 1;
	}

	/**
	 * {@code /visited clear [confirm]}: forgets the record for the player's dimension. Without
	 * {@code confirm} it only says what would be cleared, with a [Confirm] button, since a record can't be
	 * rebuilt. (Fabric runs client commands clicked in chat locally, like typed ones.)
	 */
	private static int clear(FabricClientCommandSource source, boolean confirm) {
		if (VISITED.server() == null || source.getClient().level == null) {
			source.sendFeedback(Chat.error("Not connected to a world."));
			return 0;
		}
		ResourceKey<Level> dimension = source.getClient().level.dimension();
		String id = dimension.identifier().toString();
		if (!confirm) {
			source.sendFeedback(Chat.warning("This will forget the " + VISITED.count(dimension) + " chunks"
					+ " recorded in " + id + ", so the next export starts from scratch.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.RED, "/visited clear confirm",
							"Forget the record for " + id))));
			return 1;
		}
		int forgotten = VISITED.clear(dimension);
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendFeedback(Chat.success("Forgot " + forgotten + " chunks recorded in " + id + ".")
				.append(Chat.line("Zoomed-out world map views may show the old tint until you zoom in on them once.")));
		return 1;
	}

	/**
	 * Shortly after joining: say if ground from an earlier session was forgotten for being too old (see
	 * {@link VisitedChunks#FORGET_AFTER_MS}), and if more recent ground is still recorded (the game crashed,
	 * the player left before uploading, or just what Xaero mapped after the last export), with how old it is
	 * and buttons to export it or start over.
	 */
	private static void remindOfEarlierGround(Minecraft client) {
		if (client.player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		List<VisitedChunks.Earlier> expired = VISITED.expired();
		if (!expired.isEmpty()) {
			MutableComponent message = Chat.info("Forgot your map data from " + ago(now - lastHere(expired))
					+ ", since it may be out of date:");
			for (VisitedChunks.Earlier record : expired) {
				message.append(Chat.line(shortName(record.key()) + ": " + record.chunks() + " chunks"));
			}
			Chat.send(client, message.append(Chat.buttons(Chat.button("Restore", ChatFormatting.GOLD,
					"/visited restore", "May be out of date: only restore it if nobody else has mapped there since."
							+ " Can be restored until a day after you were last here."))));
		}
		List<VisitedChunks.Earlier> earlier = VISITED.earlier();
		if (earlier.isEmpty()) {
			return;
		}
		String when = ago(now - lastHere(earlier));
		MutableComponent message = Chat.info(VISITED.crashedLastTime()
				? "Your game crashed " + when + ", so your map data was kept:"
				: "Map data from " + when + " isn't uploaded yet:");
		for (VisitedChunks.Earlier record : earlier) {
			message.append(Chat.line(shortName(record.key()) + ": " + record.chunks() + " chunks"));
		}
		message.append(Chat.line("Keep playing: your next export includes it. It's only forgotten after an hour offline."));
		Chat.send(client, message.append(Chat.buttons(
				Chat.button("Export it", ChatFormatting.GREEN, "/visited export",
						"Export it now, with anything you map from here on"),
				Chat.button("Start over", ChatFormatting.RED, "/visited clear all",
						"Forget it in every dimension (asks to confirm)"))));
	}

	/** When the player was last here, going by the records' save times. */
	private static long lastHere(List<VisitedChunks.Earlier> records) {
		return records.stream().mapToLong(VisitedChunks.Earlier::savedMillis).max().orElse(0);
	}

	private static String shortName(String key) {
		return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
	}

	private static String ago(long ms) {
		long minutes = Math.max(1, ms / 60_000);
		if (minutes < 60) {
			return minutes == 1 ? "a minute ago" : minutes + " minutes ago";
		}
		long hours = minutes / 60;
		if (hours < 24) {
			return hours == 1 ? "an hour ago" : hours + " hours ago";
		}
		long days = hours / 24;
		return days == 1 ? "a day ago" : days + " days ago";
	}

	/** {@code /visited restore}: puts back the ground forgotten on joining for being old. */
	private static int restore(FabricClientCommandSource source) {
		int restored = VISITED.restoreForgotten();
		if (restored < 0) {
			source.sendFeedback(Chat.error("Not connected to a world."));
			return 0;
		}
		if (restored == 0) {
			source.sendFeedback(Chat.info("Nothing left to restore."));
			return 1;
		}
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendFeedback(Chat.success("Restored " + restored + " chunks. Your next export includes them."));
		return 1;
	}

	/** {@code /visited clear all [confirm]}: like {@link #clear}, for every dimension on this server. */
	private static int clearAll(FabricClientCommandSource source, boolean confirm) {
		if (VISITED.server() == null) {
			source.sendFeedback(Chat.error("Not connected to a world."));
			return 0;
		}
		if (!confirm) {
			int total = VISITED.counts().values().stream().mapToInt(Integer::intValue).sum();
			source.sendFeedback(Chat.warning("This will forget all " + total + " chunks recorded on this server,"
					+ " in every dimension, so the next export starts from scratch.")
					.append(Chat.buttons(Chat.button("Confirm", ChatFormatting.RED, "/visited clear all confirm",
							"Forget the record in every dimension"))));
			return 1;
		}
		int forgotten = VISITED.clearAll();
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendFeedback(Chat.success("Forgot " + forgotten + " chunks in every dimension.")
				.append(Chat.line("Zoomed-out world map views may show the old tint until you zoom in on them once.")));
		return 1;
	}

	/**
	 * {@code /visited uploaded [<export>]}: the [Mark as uploaded] button runs it with the export's number;
	 * typed without one, it marks the latest export.
	 */
	private static int markUploaded(FabricClientCommandSource source, int export) {
		if (export < 0) {
			source.sendFeedback(Chat.error("There's no export to mark as uploaded. To start over, use /visited"
					+ " clear."));
			return 0;
		}
		int forgotten = VISITED.markUploaded(export);
		if (forgotten < 0) {
			source.sendFeedback(Chat.error("That export was already marked as uploaded, or its record was cleared"
					+ " since. To start over, use /visited clear."));
			return 0;
		}
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendFeedback(Chat.success("Marked as uploaded: forgot " + forgotten + " chunks.")
				.append(Chat.line("Anything that changed since that export is kept for the next one.")));
		return 1;
	}

	/** {@code /visited show [on|off]}: the highlight on Xaero's World Map; without on/off it switches. */
	private static int setShow(FabricClientCommandSource source, boolean show) {
		SETTINGS.setShowOnMap(show);
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendFeedback(Chat.info(show
				? "Showing what the next export will keep on Xaero's World Map. Hide it with /visited show off."
				: "No longer showing the record on Xaero's World Map. Show it again with /visited show on."));
		return 1;
	}

	/**
	 * {@code /visited export [<dimension>] [anyway|without-packs]}: {@code only} is the dimension's id, or null
	 * for every dimension with recorded ground.
	 */
	private <S extends com.mojang.brigadier.builder.ArgumentBuilder<FabricClientCommandSource, S>> S exportCommand(
			S command, String only) {
		return command.executes(context -> requestExport(context.getSource(), only, false))
				.then(ClientCommands.literal("anyway").executes(context -> requestExport(context.getSource(), only, true)))
				.then(ClientCommands.literal("without-packs").executes(context -> exportWithoutPacks(context.getSource(), only)));
	}

	/**
	 * {@code /visited export [anyway]}: starts the export on the next tick (see {@link #exportNextTick}).
	 * Xaero colours its map from block textures, so a resource pack changes the exported colours; unless
	 * {@code anyway}, refuse while one of the player's own packs is on.
	 */
	private int requestExport(FabricClientCommandSource source, String only, boolean anyway) {
		if (!xaeroInstalled()) {
			source.sendFeedback(Chat.error("Exporting needs Xaero's World Map."));
			return 0;
		}
		// The player's own packs have ids like "file/<name>"; built-in and mod packs don't. Pack names can
		// contain colour codes (a tester's turned the rest of the message black), so strip them.
		List<String> packs = source.getClient().getResourcePackRepository().getSelectedIds().stream()
				.filter(id -> id.startsWith("file/"))
				.map(id -> ChatFormatting.stripFormatting(id.substring("file/".length()))).toList();
		String command = "/visited export" + (only == null ? "" : " " + only.substring(only.indexOf(':') + 1));
		if (!packs.isEmpty() && !anyway) {
			source.sendFeedback(Chat.warning("Resource packs change the colours of the exported map. These are on:")
					.append(Chat.line(String.join(", ", packs)))
					.append(Chat.buttons(
							Chat.button("Turn packs off for the export", ChatFormatting.GREEN, command + " without-packs",
									"Turns your resource packs off, exports, then turns them back on in the same"
											+ " order. Reloads resources twice."),
							Chat.button("Export anyway", ChatFormatting.RED, command + " anyway",
									"Export with the resource packs on: the colours on the webmap will be off"))));
			return 0;
		}
		exportNextTick = true;
		exportOnly = only;
		return 1;
	}

	/**
	 * {@code /visited export without-packs}: turns the player's own resource packs off, reloads, exports, then
	 * turns them back on in the same order and reloads again. Minecraft's settings aren't saved meanwhile,
	 * so if the game stops mid-way the packs are simply on again next launch. Xaero recalculates its block
	 * colours after a reload (it clears its colour cache when the textures are re-stitched).
	 */
	private static int exportWithoutPacks(FabricClientCommandSource source, String only) {
		if (!xaeroInstalled()) {
			source.sendFeedback(Chat.error("Exporting needs Xaero's World Map."));
			return 0;
		}
		if (XaeroExport.active()) {
			source.sendFeedback(Chat.error("An export is already running."));
			return 0;
		}
		Minecraft client = source.getClient();
		PackRepository repository = client.getResourcePackRepository();
		// In order: the list's order is the packs' priority, and Minecraft keeps the order it's given.
		List<String> original = List.copyOf(repository.getSelectedIds());
		repository.setSelected(original.stream().filter(id -> !id.startsWith("file/")).toList());
		source.sendFeedback(Chat.info("Turning your resource packs off for the export. They'll come back on"
				+ " afterwards, in the same order."));
		client.reloadResourcePacks().thenRun(() -> client.execute(() -> {
			Component error = XaeroExport.start(client, only, () -> restorePacks(client, original));
			if (error != null) {
				if (client.player != null) {
					client.player.sendSystemMessage(error);
				}
				restorePacks(client, original);
			}
		}));
		return 1;
	}

	private static void restorePacks(Minecraft client, List<String> original) {
		PackRepository repository = client.getResourcePackRepository();
		repository.setSelected(original);
		client.reloadResourcePacks().thenRun(() -> client.execute(() -> {
			boolean sameOrder = List.copyOf(repository.getSelectedIds()).equals(original);
			if (!sameOrder) {
				LOGGER.warn("Resource packs restored in a different order: {} instead of {}",
						repository.getSelectedIds(), original);
			}
			Chat.send(client, sameOrder
					? Chat.success("Your resource packs are back on.")
					: Chat.warning("Your resource packs are back on, but their order may have changed: please check"
							+ " Options > Resource Packs."));
		}));
	}

	/** Classes that use Xaero ({@link XaeroExport}, {@link VisitedHighlighter}) may only load when this is true. */
	private static boolean xaeroInstalled() {
		return FabricLoader.getInstance().isModLoaded("xaeroworldmap");
	}
}
