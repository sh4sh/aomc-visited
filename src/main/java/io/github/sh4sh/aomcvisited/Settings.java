package io.github.sh4sh.aomcvisited;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Settings {
	private final Path file;
	private boolean enabled = true;
	private boolean showOnMap = true;

	public Settings(Path file) {
		this.file = file;
		if (Files.isRegularFile(file)) {
			Properties properties = new Properties();
			try (Reader in = Files.newBufferedReader(file)) {
				properties.load(in);
				enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
				showOnMap = Boolean.parseBoolean(properties.getProperty("showOnMap", "true"));
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not read {}", file, e);
			}
		}
	}

	public synchronized boolean enabled() {
		return enabled;
	}

	public synchronized boolean showOnMap() {
		return showOnMap;
	}

	public synchronized void setEnabled(boolean enabled) {
		this.enabled = enabled;
		save();
	}

	public synchronized void setShowOnMap(boolean show) {
		showOnMap = show;
		save();
	}

	private void save() {
		Properties properties = new Properties();
		properties.setProperty("enabled", Boolean.toString(enabled));
		properties.setProperty("showOnMap", Boolean.toString(showOnMap));
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
