package io.github.sh4sh.aomcvisited;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
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
 * The chunks the server has sent this player, per server and per dimension.
 *
 * <p>Stored as text under {@code <game dir>/aomc_visited/<server>/<namespace>/<dimension>.txt}, one
 * {@code x,z} chunk coordinate per line. Methods are synchronized because the disconnect event may
 * arrive off the main thread.
 */
public final class VisitedChunks {
	private static final String HEADER = "# AOMC Visited: chunks received from the server, as chunk x,z"
			+ " (multiply by 16 for block coordinates)";

	private final Path root;
	/** Folder name of the current server, or null when not connected. */
	private String server;
	/** Dimension id (e.g. "minecraft:overworld") to packed chunk positions. */
	private final Map<String, LongOpenHashSet> byDimension = new TreeMap<>();
	private final Set<String> dirty = new HashSet<>();

	public VisitedChunks(Path root) {
		this.root = root;
	}

	/** Called for every chunk data packet, on the main thread. */
	public synchronized void record(ClientPacketListener listener, ResourceKey<Level> dimension, int x, int z) {
		if (server == null) {
			open(serverKey(Minecraft.getInstance(), listener));
		}
		String dim = dimension.identifier().toString();
		if (byDimension.computeIfAbsent(dim, d -> new LongOpenHashSet()).add(ChunkPos.pack(x, z))) {
			dirty.add(dim);
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
				String dim = rel.getName(0) + ":" + path.substring(0, path.length() - ".txt".length());
				byDimension.put(dim, load(file));
			}
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not read visited chunks from {}", dir, e);
		}
	}

	/** Saves and forgets the current server's record. */
	public synchronized void close() {
		save();
		server = null;
		byDimension.clear();
		dirty.clear();
	}

	/** Writes the dimensions that changed since the last save. */
	public synchronized void save() {
		if (server == null) {
			return;
		}
		for (String dim : dirty) {
			Path file = fileFor(dim);
			try {
				write(file, byDimension.get(dim));
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not save visited chunks to {}", file, e);
			}
		}
		dirty.clear();
	}

	public synchronized String server() {
		return server;
	}

	/** Chunk counts per dimension for the current server. */
	public synchronized Map<String, Integer> counts() {
		Map<String, Integer> counts = new TreeMap<>();
		byDimension.forEach((dim, chunks) -> counts.put(dim, chunks.size()));
		return counts;
	}

	/**
	 * A folder name for the server: its address for multiplayer, the world's folder for single-player.
	 */
	public static String serverKey(Minecraft client, ClientPacketListener listener) {
		if (client.isLocalServer() && client.getSingleplayerServer() != null) {
			Path world = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			return "singleplayer/" + sanitize(world.getFileName().toString());
		}
		ServerData data = listener.getServerData();
		return "server/" + sanitize(data != null ? data.ip : "unknown");
	}

	private static String sanitize(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
	}

	private Path fileFor(String dim) {
		int colon = dim.indexOf(':');
		return root.resolve(server).resolve(dim.substring(0, colon)).resolve(dim.substring(colon + 1) + ".txt");
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
