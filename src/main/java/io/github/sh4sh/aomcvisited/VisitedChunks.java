package io.github.sh4sh.aomcvisited;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The chunks Xaero's World Map has written since the record was last cleared or marked as uploaded, per
 * server, dimension and map layer: the ground the player's Xaero map has fresh data for. {@code XaeroMapWriterMixin} reports each write. The record is kept across sessions;
 * "mark as uploaded" bookkeeping ({@link #writes}, exports) only lasts the session.
 *
 * <p>Records are keyed like {@code minecraft:overworld} for the normal map, or
 * {@code minecraft:overworld/cave_<layer>} for one of Xaero's cave-mode layers, since an export covers one
 * layer. Chunks whose map data differed from what the player's map had before (new ground, or something
 * changed) are marked "changed". Stored as text under
 * {@code <game dir>/aomc_visited/<server>/<namespace>/<path>.txt}, one {@code x,z} chunk coordinate per
 * line, {@code x,z,c} for changed chunks. Methods are synchronized because the disconnect event may arrive
 * off the main thread.
 */
public final class VisitedChunks {
	private static final String HEADER = "# AOMC Visited: chunks Xaero's World Map wrote, as chunk x,z"
			+ " (multiply by 16 for block coordinates); x,z,c = new or changed since the player's map had it";
	/** Xaero's layer number for the normal (not cave-mode) map. */
	public static final int SURFACE_LAYER = Integer.MAX_VALUE;

	private final Path root;
	/** Folder name of the current server, or null when not connected. */
	private String server;
	/** Record key (see the class comment) to packed chunk positions. */
	private final Map<String, LongOpenHashSet> records = new TreeMap<>();
	/** Record key to the recorded chunks that were new or changed. */
	private final Map<String, LongOpenHashSet> changed = new HashMap<>();
	/** Record key to chunk counts per 512-block region (32 x 32 chunks), for the map highlight. */
	private final Map<String, Long2IntOpenHashMap> regionCounts = new HashMap<>();
	/** Record key to a per-region counter bumped on every change, so the map highlight knows to redraw. */
	private final Map<String, Long2IntOpenHashMap> regionVersions = new HashMap<>();
	private final Set<String> dirty = new HashSet<>();

	/** Counts Xaero's writes this session, so we can tell what was written after an export. */
	private long writes;
	/** Record key to each chunk's latest write this session ({@link #writes} at the time). */
	private final Map<String, Long2LongOpenHashMap> lastWrite = new HashMap<>();
	/** Exports made this session that can still be marked as uploaded, by number. */
	private final Map<Integer, Export> exports = new HashMap<>();
	private int nextExport = 1;

	/** What an export contained: a record's chunks, and {@link #writes} when it was made. */
	private record Export(String key, LongOpenHashSet chunks, long writes) {
	}

	public VisitedChunks(Path root) {
		this.root = root;
	}

	/** The record key for a dimension and Xaero map layer. */
	public static String recordKey(ResourceKey<Level> dimension, int layer) {
		String id = dimension.identifier().toString();
		return layer == SURFACE_LAYER ? id : id + "/cave_" + layer;
	}

	/**
	 * Called after Xaero writes fresh map data for a chunk; {@code differs} says whether it differs from what
	 * the map had there. Returns whether the record changed (new chunk, or newly marked changed).
	 */
	public synchronized boolean mapped(ResourceKey<Level> dimension, int layer, int x, int z, boolean differs) {
		if (server == null) {
			Minecraft client = Minecraft.getInstance();
			if (client.getConnection() == null) {
				return false;
			}
			open(serverKey(client));
		}
		String key = recordKey(dimension, layer);
		long pos = ChunkPos.pack(x, z);
		long region = ChunkPos.pack(x >> 5, z >> 5);
		lastWrite.computeIfAbsent(key, k -> new Long2LongOpenHashMap()).put(pos, ++writes);
		boolean added = records.computeIfAbsent(key, k -> new LongOpenHashSet()).add(pos);
		// Once changed, a chunk stays changed: later writes compare against this session's own data.
		boolean nowChanged = differs && changed.computeIfAbsent(key, k -> new LongOpenHashSet()).add(pos);
		if (!added && !nowChanged) {
			return false;
		}
		if (added) {
			regionCounts.computeIfAbsent(key, k -> new Long2IntOpenHashMap()).addTo(region, 1);
		}
		regionVersions.computeIfAbsent(key, k -> new Long2IntOpenHashMap()).addTo(region, 1);
		dirty.add(key);
		return true;
	}

	public synchronized boolean isChanged(String key, int x, int z) {
		LongOpenHashSet chunks = changed.get(key);
		return chunks != null && chunks.contains(ChunkPos.pack(x, z));
	}

	/** How many of a record's chunks are new or changed. */
	public synchronized int changedCount(String key) {
		LongOpenHashSet chunks = changed.get(key);
		return chunks == null ? 0 : chunks.size();
	}

	/** Bumped whenever a region's part of the record changes. */
	public synchronized int regionVersion(String key, int regionX, int regionZ) {
		Long2IntOpenHashMap versions = regionVersions.get(key);
		return versions == null ? 0 : versions.get(ChunkPos.pack(regionX, regionZ));
	}

	public synchronized boolean contains(String key, int x, int z) {
		LongOpenHashSet chunks = records.get(key);
		return chunks != null && chunks.contains(ChunkPos.pack(x, z));
	}

	/** Recorded chunks in a 512-block region (region coordinates are chunk coordinates {@code >> 5}). */
	public synchronized int regionCount(String key, int regionX, int regionZ) {
		Long2IntOpenHashMap counts = regionCounts.get(key);
		return counts == null ? 0 : counts.get(ChunkPos.pack(regionX, regionZ));
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
				LongOpenHashSet chunks = new LongOpenHashSet(), changedChunks = new LongOpenHashSet();
				load(file, chunks, changedChunks);
				records.put(key, chunks);
				changed.put(key, changedChunks);
				Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
				for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
					long pos = it.nextLong();
					counts.addTo(ChunkPos.pack(ChunkPos.getX(pos) >> 5, ChunkPos.getZ(pos) >> 5), 1);
				}
				regionCounts.put(key, counts);
				regionVersions.put(key, new Long2IntOpenHashMap(counts));
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
		changed.clear();
		regionCounts.clear();
		regionVersions.clear();
		lastWrite.clear();
		exports.clear();
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
				write(file, records.get(key), changed.getOrDefault(key, new LongOpenHashSet()));
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not save visited chunks to {}", file, e);
			}
		}
		dirty.clear();
	}

	public synchronized String server() {
		return server;
	}

	/**
	 * Forgets a dimension's records (the normal map and any cave layers), in memory and on disk. Returns how
	 * many chunks were forgotten.
	 */
	public synchronized int clear(ResourceKey<Level> dimension) {
		String id = dimension.identifier().toString();
		int forgotten = 0;
		for (String key : records.keySet().toArray(new String[0])) {
			if (!key.equals(id) && !key.startsWith(id + "/")) {
				continue;
			}
			forgotten += records.remove(key).size();
			changed.remove(key);
			lastWrite.remove(key);
			dirty.remove(key);
			// Bump rather than reset the region versions, so the map highlight's hash for every affected
			// region differs from anything Xaero has cached and it redraws them.
			Long2IntOpenHashMap counts = regionCounts.remove(key);
			if (counts != null) {
				Long2IntOpenHashMap versions = regionVersions.computeIfAbsent(key, k -> new Long2IntOpenHashMap());
				counts.keySet().forEach(region -> versions.addTo(region, 1));
			}
			if (server != null) {
				try {
					Files.deleteIfExists(fileFor(key));
				} catch (IOException e) {
					AomcVisitedClient.LOGGER.error("Could not delete {}", fileFor(key), e);
				}
			}
		}
		return forgotten;
	}

	/** How many writes Xaero has made this session; pass to {@link #rememberExport}. */
	public synchronized long writes() {
		return writes;
	}

	/**
	 * Remembers what an export contained, so it can be marked as uploaded later this session; chunks written
	 * after {@code writesAtStart} are newer than the export and won't be forgotten. Returns its number for
	 * {@link #markUploaded}.
	 */
	public synchronized int rememberExport(String key, LongOpenHashSet chunks, long writesAtStart) {
		int number = nextExport++;
		exports.put(number, new Export(key, chunks, writesAtStart));
		return number;
	}

	/** The latest export that can still be marked as uploaded, or -1. */
	public synchronized int latestExport() {
		return exports.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
	}

	/**
	 * Forgets the chunks an export contained, except those Xaero has written again since (they hold newer
	 * data than what was uploaded). Returns how many were forgotten, or -1 if there's no such export this
	 * session (unknown number, already marked, or the game was restarted).
	 */
	public synchronized int markUploaded(int number) {
		Export export = exports.remove(number);
		if (export == null || server == null) {
			return -1;
		}
		LongOpenHashSet chunks = records.get(export.key());
		if (chunks == null) {
			return 0;
		}
		LongOpenHashSet changedChunks = changed.get(export.key());
		Long2LongOpenHashMap written = lastWrite.getOrDefault(export.key(), new Long2LongOpenHashMap());
		Long2IntOpenHashMap counts = regionCounts.computeIfAbsent(export.key(), k -> new Long2IntOpenHashMap());
		Long2IntOpenHashMap versions = regionVersions.computeIfAbsent(export.key(), k -> new Long2IntOpenHashMap());
		int forgotten = 0;
		for (LongIterator it = export.chunks().iterator(); it.hasNext(); ) {
			long pos = it.nextLong();
			if (written.get(pos) > export.writes() || !chunks.remove(pos)) {
				continue;
			}
			if (changedChunks != null) {
				changedChunks.remove(pos);
			}
			written.remove(pos);
			long region = ChunkPos.pack(ChunkPos.getX(pos) >> 5, ChunkPos.getZ(pos) >> 5);
			counts.addTo(region, -1);
			versions.addTo(region, 1);
			forgotten++;
		}
		dirty.add(export.key());
		save();
		return forgotten;
	}

	/** A copy of one record (empty if there is none). */
	public synchronized LongOpenHashSet snapshot(String key) {
		LongOpenHashSet chunks = records.get(key);
		return chunks == null ? new LongOpenHashSet() : new LongOpenHashSet(chunks);
	}

	/** How many chunks a dimension's records hold (the normal map and any cave layers). */
	public synchronized int count(ResourceKey<Level> dimension) {
		String id = dimension.identifier().toString();
		int total = 0;
		for (Map.Entry<String, LongOpenHashSet> record : records.entrySet()) {
			if (record.getKey().equals(id) || record.getKey().startsWith(id + "/")) {
				total += record.getValue().size();
			}
		}
		return total;
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

	private static void load(Path file, LongOpenHashSet chunks, LongOpenHashSet changedChunks) throws IOException {
		for (String line : Files.readAllLines(file)) {
			line = line.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] fields = line.split(",");
			try {
				long pos = ChunkPos.pack(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]));
				chunks.add(pos);
				if (fields.length > 2 && fields[2].equals("c")) {
					changedChunks.add(pos);
				}
			} catch (RuntimeException e) {
				AomcVisitedClient.LOGGER.warn("Skipping bad line in {}: {}", file, line);
			}
		}
	}

	/** Writes to a temporary file first, so a crash mid-write can't destroy the old record. */
	private static void write(Path file, LongOpenHashSet chunks, LongOpenHashSet changedChunks) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (BufferedWriter out = Files.newBufferedWriter(tmp)) {
			out.write(HEADER);
			out.newLine();
			for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
				long packed = it.nextLong();
				out.write(ChunkPos.getX(packed) + "," + ChunkPos.getZ(packed) + (changedChunks.contains(packed) ? ",c" : ""));
				out.newLine();
			}
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
