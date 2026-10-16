package io.github.sh4sh.aomcvisited;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The player's settings, in {@code <game dir>/aomc_visited/settings.properties}. */
public final class Settings {
	private final Path file;
	private boolean showOnMap = true;

	public Settings(Path file) {
		this.file = file;
		if (Files.isRegularFile(file)) {
			Properties properties = new Properties();
			try (Reader in = Files.newBufferedReader(file)) {
				properties.load(in);
				showOnMap = Boolean.parseBoolean(properties.getProperty("showOnMap", "true"));
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not read {}", file, e);
			}
		}
	}

	/** Whether the record is highlighted on Xaero's World Map. */
	public synchronized boolean showOnMap() {
		return showOnMap;
	}

	public synchronized void setShowOnMap(boolean show) {
		showOnMap = show;
		Properties properties = new Properties();
		properties.setProperty("showOnMap", Boolean.toString(show));
		try {
			Files.createDirectories(file.getParent());
			try (Writer out = Files.newBufferedWriter(file)) {
				properties.store(out, "AOMC Visited settings");
			}
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not save {}", file, e);
		}
	}
}
