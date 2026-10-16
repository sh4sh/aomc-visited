package io.github.sh4sh.aomcvisited;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	/** The export screen opens a tick after the command, since the chat screen closes after running it. */
	private boolean exportNextTick;

	@Override
	public void onInitializeClient() {
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> VISITED.open(VisitedChunks.serverKey(client)));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> VISITED.close());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> VISITED.close());
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("visited")
						.executes(context -> showCounts(context.getSource()))
						.then(ClientCommands.literal("export").executes(context -> requestExport(context.getSource())))
						.then(ClientCommands.literal("show")
								.then(ClientCommands.literal("on").executes(context -> setShow(context.getSource(), true)))
								.then(ClientCommands.literal("off").executes(context -> setShow(context.getSource(), false))))));
		if (xaeroInstalled()) {
			VisitedHighlighter.registerEvents();
		}

		LOGGER.info("AOMC Visited loaded");
	}

	private void tick(Minecraft client) {
		if (exportNextTick) {
			exportNextTick = false;
			Component error = XaeroExport.open(client);
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

	/** {@code /visited show on|off}: the highlight on Xaero's World Map. */
	private static int setShow(FabricClientCommandSource source, boolean show) {
		SETTINGS.setShowOnMap(show);
		if (xaeroInstalled()) {
			VisitedHighlighter.refreshAll();
		}
		source.sendFeedback(Component.literal(show
				? "Showing what the next export will keep on Xaero's World Map."
				: "No longer showing the record on Xaero's World Map."));
		return 1;
	}

	/** {@code /visited export}: opens Xaero's export screen on the next tick (see {@link #exportNextTick}). */
	private int requestExport(FabricClientCommandSource source) {
		if (!xaeroInstalled()) {
			source.sendError(Component.literal("Exporting needs Xaero's World Map."));
			return 0;
		}
		exportNextTick = true;
		return 1;
	}

	/** Classes that use Xaero ({@link XaeroExport}, {@link VisitedHighlighter}) may only load when this is true. */
	private static boolean xaeroInstalled() {
		return FabricLoader.getInstance().isModLoaded("xaeroworldmap");
	}
}
