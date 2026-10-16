package io.github.sh4sh.aomcvisited;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
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

	private int ticksSinceSave;

	@Override
	public void onInitializeClient() {
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) ->
				VISITED.open(VisitedChunks.serverKey(client, listener)));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> VISITED.close());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> VISITED.close());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			VISITED.tick();
			if (++ticksSinceSave >= AUTOSAVE_TICKS) {
				ticksSinceSave = 0;
				VISITED.save();
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(ClientCommands.literal("visited").executes(context -> {
					String server = VISITED.server();
					if (server == null) {
						context.getSource().sendError(Component.literal("Not connected to a world."));
						return 0;
					}
					Map<String, Integer> counts = VISITED.counts();
					context.getSource().sendFeedback(Component.literal("Visited chunks on " + server + ":"));
					if (counts.isEmpty()) {
						context.getSource().sendFeedback(Component.literal("  none yet"));
					}
					counts.forEach((dim, count) ->
							context.getSource().sendFeedback(Component.literal("  " + dim + ": " + count)));
					return 1;
				})));

		LOGGER.info("AOMC Visited loaded");
	}
}
