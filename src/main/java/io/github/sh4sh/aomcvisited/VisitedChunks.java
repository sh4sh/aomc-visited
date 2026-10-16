package io.github.sh4sh.aomcvisited;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
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
 * The chunks Xaero's World Map could map this session, per server and per dimension.
 *
 * <p>Xaero only maps a chunk while it and all 8 of its neighbours are loaded, so the outer ring of what the
 * server sends is never mapped and may still hold old map data. We track the chunks loaded right now (chunk
 * data packets add, forget packets remove) and record a chunk once its whole 3x3 neighbourhood has stayed
 * loaded for {@link #DWELL_MS}. Without the wait, about 1 in 12 chunks on the edge of an elytra flight
 * unloaded before Xaero mapped it (AOMC test, October 2026).
 *
 * <p>Stored as text under {@code <game dir>/aomc_visited/<server>/<namespace>/<dimension>.txt}, one
 * {@code x,z} chunk coordinate per line. Methods are synchronized because the disconnect event may
 * arrive off the main thread.
 */
public final class VisitedChunks {
	private static final String HEADER = "# AOMC Visited: chunks Xaero could map (loaded with all 8 neighbours),"
			+ " as chunk x,z (multiply by 16 for block coordinates)";
	/** How long a chunk's neighbourhood must stay loaded before we count it as mapped. */
	private static final long DWELL_MS = 3000;

	private final Path root;
	/** Folder name of the current server, or null when not connected. */
	private String server;
	/** Dimension id (e.g. "minecraft:overworld") to packed chunk positions. */
	private final Map<String, LongOpenHashSet> byDimension = new TreeMap<>();
	private final Set<String> dirty = new HashSet<>();

	/** Chunks loaded in the client right now, in {@link #loadedLevel}. */
	private final LongOpenHashSet loaded = new LongOpenHashSet();
	/** The level {@link #loaded} belongs to; a new level (dimension change, respawn) starts empty. */
	private ClientLevel loadedLevel;
	/** Chunks whose neighbourhood is loaded but not yet for {@link #DWELL_MS}, to when it became loaded. */
	private final Long2LongOpenHashMap pending = new Long2LongOpenHashMap();

	public VisitedChunks(Path root) {
		this.root = root;
	}

	/** Called for every chunk data packet, on the main thread. */
	public synchronized void chunkLoaded(ClientPacketListener listener, ClientLevel level, int x, int z) {
		if (server == null) {
			open(serverKey(Minecraft.getInstance(), listener));
		}
		if (level != loadedLevel) {
			loaded.clear();
			pending.clear();
			loadedLevel = level;
		}
		loaded.add(ChunkPos.pack(x, z));
		// The new chunk can complete its own neighbourhood or any of its 8 neighbours'.
		LongOpenHashSet chunks = currentChunks();
		long now = Util.getMillis();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				long pos = ChunkPos.pack(x + dx, z + dz);
				if (!chunks.contains(pos) && !pending.containsKey(pos) && neighbourhoodLoaded(x + dx, z + dz)) {
					pending.put(pos, now);
				}
			}
		}
	}

	/** Called for every forget-chunk packet, on the main thread. */
	public synchronized void chunkUnloaded(ClientLevel level, int x, int z) {
		if (level != loadedLevel) {
			return;
		}
		loaded.remove(ChunkPos.pack(x, z));
		// The neighbourhoods this chunk belonged to are broken; they start over if it comes back.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				pending.remove(ChunkPos.pack(x + dx, z + dz));
			}
		}
	}

	/** Records the pending chunks whose neighbourhood has stayed loaded long enough. Call regularly. */
	public synchronized void tick() {
		if (pending.isEmpty() || loadedLevel == null) {
			return;
		}
		LongOpenHashSet chunks = currentChunks();
		long now = Util.getMillis();
		for (ObjectIterator<Long2LongMap.Entry> it = pending.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
			Long2LongMap.Entry entry = it.next();
			if (now - entry.getLongValue() >= DWELL_MS) {
				chunks.add(entry.getLongKey());
				it.remove();
				dirty.add(loadedLevel.dimension().identifier().toString());
			}
		}
	}

	private LongOpenHashSet currentChunks() {
		return byDimension.computeIfAbsent(loadedLevel.dimension().identifier().toString(), d -> new LongOpenHashSet());
	}

	private boolean neighbourhoodLoaded(int x, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!loaded.contains(ChunkPos.pack(x + dx, z + dz))) {
					return false;
				}
			}
		}
		return true;
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
		loaded.clear();
		pending.clear();
		loadedLevel = null;
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
