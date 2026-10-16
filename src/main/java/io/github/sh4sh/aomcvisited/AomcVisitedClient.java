package io.github.sh4sh.aomcvisited;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AomcVisitedClient implements ClientModInitializer {
	public static final String MOD_ID = "aomc_visited";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		LOGGER.info("AOMC Visited loaded");
	}
}
