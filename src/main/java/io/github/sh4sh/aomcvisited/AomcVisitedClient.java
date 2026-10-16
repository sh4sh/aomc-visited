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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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

	/** Save every 5 minutes while playing, so a crash loses little. */
	private static final int AUTOSAVE_TICKS = 5 * 60 * 20;

	public static final VisitedChunks VISITED =
			new VisitedChunks(FabricLoader.getInstance().getGameDir().resolve(MOD_ID));
	public static final Settings SETTINGS =
			new Settings(FabricLoader.getInstance().getGameDir().resolve(MOD_ID).resolve("settings.properties"));

	private int ticksSinceSave;
	/** The export starts a tick after the command, once the chat screen has closed. */
	private boolean exportNextTick;

	@Override
	public void onInitializeClient() {
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> VISITED.open(VisitedChunks.serverKey(client)));
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
						.then(ClientCommands.literal("export")
								.executes(context -> requestExport(context.getSource(), false))
								.then(ClientCommands.literal("anyway").executes(context -> requestExport(context.getSource(), true)))
								.then(ClientCommands.literal("without-packs").executes(context -> exportWithoutPacks(context.getSource()))))
						.then(ClientCommands.literal("clear")
								.executes(context -> clear(context.getSource(), false))
								.then(ClientCommands.literal("confirm").executes(context -> clear(context.getSource(), true))))
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
		}
		if (exportNextTick) {
			exportNextTick = false;
			Component error = XaeroExport.start(client, null);
			if (error != null && client.player != null) {
				client.player.sendSystemMessage(error);
			}
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
			source.sendError(Component.literal("Not connected to a world."));
			return 0;
		}
		Map<String, Integer> counts = VISITED.counts();
		source.sendFeedback(Component.literal("Visited chunks on " + server + ":"));
		if (counts.isEmpty()) {
			source.sendFeedback(Component.literal("  none yet"));
		}
		counts.forEach((key, count) -> source.sendFeedback(Component.literal("  " + key + ": " + count)));
		return 1;
	}

	/**
	 * {@code /visited clear [confirm]}: forgets the record for the player's dimension. Without
	 * {@code confirm} it only says what would be cleared, with a [Confirm] button, since a record can't be
	 * rebuilt. (Fabric runs client commands clicked in chat locally, like typed ones.)
	 */
	private static int clear(FabricClientCommandSource source, boolean confirm) {
		if (VISITED.server() == null || source.getClient().level == null) {
			source.sendError(Component.literal("Not connected to a world."));
			return 0;
		}
		ResourceKey<Level> dimension = source.getClient().level.dimension();
		String id = dimension.identifier().toString();
		if (!confirm) {
			Component button = Component.literal("[Confirm]").withStyle(style -> style
					.withColor(ChatFormatting.RED)
					.withUnderlined(true)
					.withClickEvent(new ClickEvent.RunCommand("/visited clear confirm"))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Forget the record for " + id))));
			source.sendFeedback(Component.literal("This will forget the " + VISITED.count(dimension) + " chunks"
					+ " recorded in " + id + ", so the next export starts from scratch. ").append(button));
			return 1;
		}
		int forgotten = VISITED.clear(dimension);
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendFeedback(Component.literal("Forgot " + forgotten + " chunks recorded in " + id + ". Zoomed-out"
				+ " world map views may show the old tint until you zoom in on them once."));
		return 1;
	}

	/**
	 * {@code /visited uploaded [<export>]}: the [Mark as uploaded] button runs it with the export's number;
	 * typed without one, it marks the latest export.
	 */
	private static int markUploaded(FabricClientCommandSource source, int export) {
		if (export < 0) {
			source.sendError(Component.literal("There's no export from this session to mark as uploaded. To start"
					+ " over, use /visited clear."));
			return 0;
		}
		int forgotten = VISITED.markUploaded(export);
		if (forgotten < 0) {
			source.sendError(Component.literal("That export was already marked as uploaded, or is from before the"
					+ " game was restarted. To start over, use /visited clear."));
			return 0;
		}
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendFeedback(Component.literal("Marked as uploaded: forgot " + forgotten + " chunks. Anything"
				+ " mapped since that export is kept for the next one."));
		return 1;
	}

	/** {@code /visited show [on|off]}: the highlight on Xaero's World Map; without on/off it switches. */
	private static int setShow(FabricClientCommandSource source, boolean show) {
		SETTINGS.setShowOnMap(show);
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendFeedback(Component.literal(show
				? "Showing what the next export will keep on Xaero's World Map. Hide it with /visited show off."
				: "No longer showing the record on Xaero's World Map. Show it again with /visited show on."));
		return 1;
	}

	/**
	 * {@code /visited export [anyway]}: starts the export on the next tick (see {@link #exportNextTick}).
	 * Xaero colours its map from block textures, so a resource pack changes the exported colours; unless
	 * {@code anyway}, refuse while one of the player's own packs is on.
	 */
	private int requestExport(FabricClientCommandSource source, boolean anyway) {
		if (!xaeroInstalled()) {
			source.sendError(Component.literal("Exporting needs Xaero's World Map."));
			return 0;
		}
		// The player's own packs have ids like "file/<name>"; built-in and mod packs don't. Pack names can
		// contain colour codes (a tester's turned the rest of the message black), so strip them.
		List<String> packs = source.getClient().getResourcePackRepository().getSelectedIds().stream()
				.filter(id -> id.startsWith("file/"))
				.map(id -> ChatFormatting.stripFormatting(id.substring("file/".length()))).toList();
		if (!packs.isEmpty() && !anyway) {
			Component turnOff = Component.literal("[Turn packs off for the export]").withStyle(style -> style
					.withColor(ChatFormatting.GREEN)
					.withUnderlined(true)
					.withClickEvent(new ClickEvent.RunCommand("/visited export without-packs"))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Turns your resource packs off, exports,"
							+ " then turns them back on in the same order. Reloads resources twice."))));
			Component anywayButton = Component.literal("[Export anyway]").withStyle(style -> style
					.withColor(ChatFormatting.RED)
					.withUnderlined(true)
					.withClickEvent(new ClickEvent.RunCommand("/visited export anyway"))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Export with the resource packs on"))));
			source.sendFeedback(Component.literal("Resource packs change the colours of the exported map ("
					+ String.join(", ", packs) + "). Turn them off in Options > Resource Packs, or: ")
					.withStyle(ChatFormatting.GOLD).append(turnOff).append(" ").append(anywayButton));
			return 0;
		}
		exportNextTick = true;
		return 1;
	}

	/**
	 * {@code /visited export without-packs}: turns the player's own resource packs off, reloads, exports, then
	 * turns them back on in the same order and reloads again. Minecraft's settings aren't saved meanwhile,
	 * so if the game stops mid-way the packs are simply on again next launch. Xaero recalculates its block
	 * colours after a reload (it clears its colour cache when the textures are re-stitched).
	 */
	private static int exportWithoutPacks(FabricClientCommandSource source) {
		if (!xaeroInstalled()) {
			source.sendError(Component.literal("Exporting needs Xaero's World Map."));
			return 0;
		}
		if (XaeroExport.active()) {
			source.sendError(Component.literal("An export is already running."));
			return 0;
		}
		Minecraft client = source.getClient();
		PackRepository repository = client.getResourcePackRepository();
		// In order: the list's order is the packs' priority, and Minecraft keeps the order it's given.
		List<String> original = List.copyOf(repository.getSelectedIds());
		repository.setSelected(original.stream().filter(id -> !id.startsWith("file/")).toList());
		source.sendFeedback(Component.literal("Turning your resource packs off for the export. They'll come back"
				+ " on afterwards, in the same order."));
		client.reloadResourcePacks().thenRun(() -> client.execute(() -> {
			Component error = XaeroExport.start(client, () -> restorePacks(client, original));
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
			if (client.player != null) {
				client.player.sendSystemMessage(sameOrder
						? Component.literal("Your resource packs are back on.")
						: Component.literal("Your resource packs are back on, but their order may have changed:"
								+ " please check Options > Resource Packs.").withStyle(ChatFormatting.GOLD));
			}
		}));
	}

	/** Classes that use Xaero ({@link XaeroExport}, {@link VisitedHighlighter}) may only load when this is true. */
	private static boolean xaeroInstalled() {
		return FabricLoader.getInstance().isModLoaded("xaeroworldmap");
	}
}
