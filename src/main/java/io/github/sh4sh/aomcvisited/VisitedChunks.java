package io.github.sh4sh.aomcvisited;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The chunks Xaero's World Map wrote this session, per server, dimension and map layer: exactly the ground
 * that is fresh in the player's Xaero map. {@code XaeroMapWriterMixin} reports
 * each write.
 *
 * <p>Records are keyed like {@code minecraft:overworld} for the normal map, or
 * {@code minecraft:overworld/cave_<layer>} for one of Xaero's cave-mode layers, since an export covers one
 * layer. Stored as text under {@code <game dir>/aomc_visited/<server>/<namespace>/<path>.txt}, one
 * {@code x,z} chunk coordinate per line. Methods are synchronized because the disconnect event may arrive
 * off the main thread.
 */
public final class VisitedChunks {
	private static final String HEADER = "# AOMC Visited: chunks Xaero's World Map wrote, as chunk x,z"
			+ " (multiply by 16 for block coordinates)";
	/** Xaero's layer number for the normal (not cave-mode) map. */
	public static final int SURFACE_LAYER = Integer.MAX_VALUE;

	private final Path root;
	/** Folder name of the current server, or null when not connected. */
	private String server;
	/** Record key (see the class comment) to packed chunk positions. */
	private final Map<String, LongOpenHashSet> records = new TreeMap<>();
	private final Set<String> dirty = new HashSet<>();

	public VisitedChunks(Path root) {
		this.root = root;
	}

	/** The record key for a dimension and Xaero map layer. */
	public static String recordKey(ResourceKey<Level> dimension, int layer) {
		String id = dimension.identifier().toString();
		return layer == SURFACE_LAYER ? id : id + "/cave_" + layer;
	}

	/** Called after Xaero writes fresh map data for a chunk. */
	public synchronized void mapped(ResourceKey<Level> dimension, int layer, int x, int z) {
		if (server == null) {
			Minecraft client = Minecraft.getInstance();
			if (client.getConnection() == null) {
				return;
			}
			open(serverKey(client));
		}
		String key = recordKey(dimension, layer);
		if (records.computeIfAbsent(key, k -> new LongOpenHashSet()).add(ChunkPos.pack(x, z))) {
			dirty.add(key);
		}
	}

	/** Switches to the given server's record, loading it from disk. */
	public synchronized void open(String serverKey) {
		if (serverKey.equals(server)) {
			return;
		}
		close();
		server = serverKey;
		Path dir = root.resolve(server);
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".txt"))::iterator) {
				Path rel = dir.relativize(file);
				if (rel.getNameCount() < 2) {
					continue;
				}
				String path = rel.subpath(1, rel.getNameCount()).toString().replace('\\', '/');
				String key = rel.getName(0) + ":" + path.substring(0, path.length() - ".txt".length());
				records.put(key, load(file));
			}
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not read visited chunks from {}", dir, e);
		}
	}

	/** Saves and forgets the current server's record. */
	public synchronized void close() {
		save();
		server = null;
		records.clear();
		dirty.clear();
	}

	/** Writes the records that changed since the last save. */
	public synchronized void save() {
		if (server == null) {
			return;
		}
		for (String key : dirty) {
			Path file = fileFor(key);
			try {
				write(file, records.get(key));
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not save visited chunks to {}", file, e);
			}
		}
		dirty.clear();
	}

	public synchronized String server() {
		return server;
	}

	/** A copy of one record (empty if there is none). */
	public synchronized LongOpenHashSet snapshot(String key) {
		LongOpenHashSet chunks = records.get(key);
		return chunks == null ? new LongOpenHashSet() : new LongOpenHashSet(chunks);
	}

	/** Chunk counts per record for the current server. */
	public synchronized Map<String, Integer> counts() {
		Map<String, Integer> counts = new TreeMap<>();
		records.forEach((key, chunks) -> counts.put(key, chunks.size()));
		return counts;
	}

	/**
	 * A folder name for the server: its address for multiplayer, the world's folder for single-player.
	 */
	public static String serverKey(Minecraft client) {
		if (client.isLocalServer() && client.getSingleplayerServer() != null) {
			Path world = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			return "singleplayer/" + sanitize(world.getFileName().toString());
		}
		ServerData data = client.getConnection() == null ? null : client.getConnection().getServerData();
		return "server/" + sanitize(data != null ? data.ip : "unknown");
	}

	private static String sanitize(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
	}

	/** {@code minecraft:overworld/cave_5} is stored as {@code minecraft/overworld/cave_5.txt}. */
	private Path fileFor(String key) {
		int colon = key.indexOf(':');
		return root.resolve(server).resolve(key.substring(0, colon)).resolve(key.substring(colon + 1) + ".txt");
	}

	private static LongOpenHashSet load(Path file) throws IOException {
		LongOpenHashSet chunks = new LongOpenHashSet();
		for (String line : Files.readAllLines(file)) {
			line = line.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int comma = line.indexOf(',');
			try {
				chunks.add(ChunkPos.pack(Integer.parseInt(line.substring(0, comma)),
						Integer.parseInt(line.substring(comma + 1))));
			} catch (RuntimeException e) {
				AomcVisitedClient.LOGGER.warn("Skipping bad line in {}: {}", file, line);
			}
		}
		return chunks;
	}

	/** Writes to a temporary file first, so a crash mid-write can't destroy the old record. */
	private static void write(Path file, LongOpenHashSet chunks) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (BufferedWriter out = Files.newBufferedWriter(tmp)) {
			out.write(HEADER);
			out.newLine();
			for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
				long packed = it.nextLong();
				out.write(ChunkPos.getX(packed) + "," + ChunkPos.getZ(packed));
				out.newLine();
			}
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
