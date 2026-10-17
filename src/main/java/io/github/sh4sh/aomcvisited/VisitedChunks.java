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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Records are keyed {@code minecraft:overworld}, or {@code minecraft:overworld/cave_<layer>} for a cave-mode
 * layer. Synchronized because the disconnect event may arrive off the main thread.
 */
public final class VisitedChunks {
	private static final String EXPORTS_HEADER = "# AOMC Visited: exports not yet marked as uploaded."
			+ " 'next <n>' is the next export's number; then one export per line: its number, its record, and the"
			+ " chunks (x,z) it contained whose map hasn't changed since";
	private static final int MAX_EXPORTS = 20;
	private static final String HEADER = "# AOMC Visited: chunks Xaero's World Map wrote, as chunk x,z"
			+ " (multiply by 16 for block coordinates); x,z,c = new or changed since the player's map had it";
	/** Xaero's layer number for the normal (not cave-mode) map. */
	public static final int SURFACE_LAYER = Integer.MAX_VALUE;

	private final Path root;
	private String server;
	private final Map<String, LongOpenHashSet> records = new TreeMap<>();
	private final Map<String, LongOpenHashSet> changed = new HashMap<>();
	private final Map<String, Long2IntOpenHashMap> regionCounts = new HashMap<>();
	/** Xaero reloads a region whenever its hash changes, so this depends only on what's drawn there. */
	private final Map<String, Long2IntOpenHashMap> regionHashes = new HashMap<>();
	private final Map<String, LongOpenHashSet> changedRegions = new HashMap<>();
	private final Set<String> dirty = new HashSet<>();

	/** Only writes that change the map count: Xaero keeps rewriting unchanged chunks around the player. */
	private long writes;
	private final Map<String, Long2LongOpenHashMap> lastWrite = new HashMap<>();
	private final Map<Integer, Export> exports = new HashMap<>();
	private int nextExport = 1;
	private boolean exportsDirty;
	private List<Earlier> earlier = List.of();
	private List<Earlier> expired = List.of();
	private long lastHere;

	/** Time away, by the newest record file: {@link #save} stamps them all, even unchanged ones. */
	public static final long FORGET_AFTER_MS = 60 * 60 * 1000L;
	private static final String FORGOTTEN = "forgotten";
	public static final long KEEP_FORGOTTEN_MS = 24 * 60 * 60 * 1000L;
	/** Exists while on the server, so finding it on joining means the game crashed. */
	private static final String PLAYING_MARKER = "playing";
	private boolean crashedLastTime;

	public record Earlier(String key, int chunks) {
	}

	private record Export(String key, LongOpenHashSet chunks, long writes) {
	}

	public VisitedChunks(Path root) {
		this.root = root;
	}

	private static final Set<String> WEBMAP_DIMENSIONS =
			Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

	private static boolean onWebmap(String key) {
		int slash = key.indexOf('/');
		return WEBMAP_DIMENSIONS.contains(slash < 0 ? key : key.substring(0, slash));
	}

	public static String recordKey(ResourceKey<Level> dimension, int layer) {
		String id = dimension.identifier().toString();
		return layer == SURFACE_LAYER ? id : id + "/cave_" + layer;
	}

	public synchronized boolean mapped(ResourceKey<Level> dimension, int layer, int x, int z, boolean differs) {
		if (server == null) {
			Minecraft client = Minecraft.getInstance();
			if (client.getConnection() == null) {
				return false;
			}
			open(serverKey(client));
		}
		String key = recordKey(dimension, layer);
		if (!onWebmap(key)) {
			return false;
		}
		long pos = ChunkPos.pack(x, z);
		if (differs) {
			lastWrite.computeIfAbsent(key, k -> new Long2LongOpenHashMap()).put(pos, ++writes);
		}
		boolean added = records.computeIfAbsent(key, k -> new LongOpenHashSet()).add(pos);
		// Once changed, a chunk stays changed: later writes compare against this session's own data.
		boolean nowChanged = differs && changed.computeIfAbsent(key, k -> new LongOpenHashSet()).add(pos);
		if (!added && !nowChanged) {
			return false;
		}
		if (added) {
			regionCounts.computeIfAbsent(key, k -> new Long2IntOpenHashMap()).addTo(ChunkPos.pack(x >> 5, z >> 5), 1);
			toggleHash(key, pos, false);
		}
		if (nowChanged) {
			toggleHash(key, pos, true);
		}
		dirty.add(key);
		return true;
	}

	public synchronized boolean isChanged(String key, int x, int z) {
		LongOpenHashSet chunks = changed.get(key);
		return chunks != null && chunks.contains(ChunkPos.pack(x, z));
	}

	public synchronized int regionHash(String key, int regionX, int regionZ) {
		Long2IntOpenHashMap hashes = regionHashes.get(key);
		return hashes == null ? 0 : hashes.get(ChunkPos.pack(regionX, regionZ));
	}

	public synchronized Map<String, LongOpenHashSet> takeChangedRegions() {
		Map<String, LongOpenHashSet> taken = new HashMap<>(changedRegions);
		changedRegions.clear();
		return taken;
	}

	/** Adds or removes (XOR) a chunk; also in neighbouring regions, whose outline depends on it. */
	private void toggleHash(String key, long pos, boolean changedMark) {
		Long2IntOpenHashMap hashes = regionHashes.computeIfAbsent(key, k -> new Long2IntOpenHashMap());
		LongOpenHashSet regions = changedRegions.computeIfAbsent(key, k -> new LongOpenHashSet());
		int x = ChunkPos.getX(pos), z = ChunkPos.getZ(pos);
		long own = ChunkPos.pack(x >> 5, z >> 5);
		hashes.put(own, hashes.get(own) ^ mix(pos, changedMark ? 1 : 0));
		regions.add(own);
		if (changedMark) {
			return;
		}
		for (long neighbour : new long[] {ChunkPos.pack((x - 1) >> 5, z >> 5), ChunkPos.pack((x + 1) >> 5, z >> 5),
				ChunkPos.pack(x >> 5, (z - 1) >> 5), ChunkPos.pack(x >> 5, (z + 1) >> 5)}) {
			if (neighbour != own) {
				hashes.put(neighbour, hashes.get(neighbour) ^ mix(pos, 2));
				regions.add(neighbour);
			}
		}
	}

	/** A well-spread hash of a chunk position and a small salt (SplitMix64's finaliser). */
	private static int mix(long pos, int salt) {
		long h = pos * 0x9E3779B97F4A7C15L + (salt + 1) * 0xC2B2AE3D27D4EB4FL;
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		h ^= h >>> 31;
		return (int) h ^ (int) (h >>> 32);
	}

	public synchronized boolean contains(String key, int x, int z) {
		LongOpenHashSet chunks = records.get(key);
		return chunks != null && chunks.contains(ChunkPos.pack(x, z));
	}

	public synchronized int regionCount(String key, int regionX, int regionZ) {
		Long2IntOpenHashMap counts = regionCounts.get(key);
		return counts == null ? 0 : counts.get(ChunkPos.pack(regionX, regionZ));
	}

	public synchronized void open(String serverKey) {
		if (serverKey.equals(server)) {
			return;
		}
		close();
		server = serverKey;
		Path dir = root.resolve(server);
		Path marker = dir.resolve(PLAYING_MARKER);
		crashedLastTime = Files.exists(marker);
		try {
			Files.createDirectories(dir);
			Files.writeString(marker, "AOMC Visited: the player is on this server, or the game didn't close normally.\n");
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not write {}", marker, e);
		}
		long forgetAfter = crashedLastTime ? KEEP_FORGOTTEN_MS : FORGET_AFTER_MS;
		loadExports(dir.resolve("exports.txt"));
		List<Earlier> found = new ArrayList<>(), tooOld = new ArrayList<>();
		long now = System.currentTimeMillis();
		List<Path> files = new ArrayList<>();
		for (Path file : recordFiles(dir)) {
			String key = keyFor(dir, file);
			if (key == null || key.startsWith(FORGOTTEN + ":")) {
				continue;
			}
			if (onWebmap(key)) {
				files.add(file);
			} else {
				deleteQuietly(file);
			}
		}
		lastHere = 0;
		for (Path file : files) {
			try {
				lastHere = Math.max(lastHere, Files.getLastModifiedTime(file).toMillis());
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not read the time of {}", file, e);
			}
		}
		for (Path file : files) {
			String key = keyFor(dir, file);
			try {
				LongOpenHashSet chunks = new LongOpenHashSet(), changedChunks = new LongOpenHashSet();
				load(file, chunks, changedChunks);
				Earlier record = new Earlier(key, chunks.size());
				if (now - lastHere > forgetAfter) {
					Path kept = forgottenFile(key);
					Files.createDirectories(kept.getParent());
					Files.move(file, kept, StandardCopyOption.REPLACE_EXISTING);
					Files.setLastModifiedTime(kept, FileTime.fromMillis(lastHere));
					if (!key.contains("/") && !chunks.isEmpty()) {
						tooOld.add(record);
					}
					continue;
				}
				if (!key.contains("/") && !chunks.isEmpty()) {
					found.add(record);
				}
				addRecord(key, chunks, changedChunks);
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not read visited chunks from {}", file, e);
			}
		}
		deleteForgotten(key -> true, now - KEEP_FORGOTTEN_MS);
		found.sort(Comparator.comparing(Earlier::key));
		tooOld.sort(Comparator.comparing(Earlier::key));
		earlier = List.copyOf(found);
		expired = List.copyOf(tooOld);
		if (exports.values().removeIf(export -> !records.containsKey(export.key()))) {
			exportsDirty = true;
			saveExports();
		}
	}

	public synchronized int restoreForgotten() {
		if (server == null) {
			return -1;
		}
		Path dir = root.resolve(server).resolve(FORGOTTEN);
		long now = System.currentTimeMillis();
		int restored = 0;
		for (Path file : recordFiles(dir)) {
			String key = keyFor(dir, file);
			if (key == null) {
				continue;
			}
			try {
				if (now - Files.getLastModifiedTime(file).toMillis() <= KEEP_FORGOTTEN_MS) {
					LongOpenHashSet chunks = new LongOpenHashSet(), changedChunks = new LongOpenHashSet();
					load(file, chunks, changedChunks);
					int before = records.containsKey(key) ? records.get(key).size() : 0;
					addRecord(key, chunks, changedChunks);
					restored += records.get(key).size() - before;
					dirty.add(key);
				}
				Files.delete(file);
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not restore visited chunks from {}", file, e);
			}
		}
		expired = List.of();
		save();
		return restored;
	}

	public synchronized boolean crashedLastTime() {
		return crashedLastTime;
	}

	public synchronized long lastHere() {
		return lastHere;
	}

	public synchronized List<Earlier> expired() {
		return expired;
	}

	public synchronized List<Earlier> earlier() {
		return earlier.stream().filter(record -> records.containsKey(record.key())).toList();
	}

	public synchronized void close() {
		save();
		if (server != null) {
			try {
				Files.deleteIfExists(root.resolve(server).resolve(PLAYING_MARKER));
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not delete the {} marker", PLAYING_MARKER, e);
			}
		}
		crashedLastTime = false;
		server = null;
		records.clear();
		changed.clear();
		regionCounts.clear();
		regionHashes.clear();
		changedRegions.clear();
		lastWrite.clear();
		exports.clear();
		nextExport = 1;
		exportsDirty = false;
		earlier = List.of();
		expired = List.of();
		dirty.clear();
	}

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
		FileTime now = FileTime.fromMillis(System.currentTimeMillis());
		for (String key : records.keySet()) {
			Path file = fileFor(key);
			try {
				if (Files.exists(file)) {
					Files.setLastModifiedTime(file, now);
				}
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not stamp {}", file, e);
			}
		}
		if (exportsDirty || !exports.isEmpty()) {
			saveExports();
		}
	}

	public synchronized String server() {
		return server;
	}

	public synchronized int clear(ResourceKey<Level> dimension) {
		return clear(dimension.identifier().toString());
	}

	public synchronized int clearAll() {
		Set<String> dimensions = new HashSet<>();
		records.keySet().forEach(key -> dimensions.add(key.split("/", 2)[0]));
		int forgotten = 0;
		for (String id : dimensions) {
			forgotten += clear(id);
		}
		return forgotten;
	}

	private int clear(String id) {
		int forgotten = 0;
		if (server != null) {
			deleteForgotten(key -> key.equals(id) || key.startsWith(id + "/"), Long.MAX_VALUE);
		}
		if (exports.values().removeIf(export -> export.key().equals(id) || export.key().startsWith(id + "/"))) {
			exportsDirty = true;
		}
		for (String key : records.keySet().toArray(new String[0])) {
			if (!key.equals(id) && !key.startsWith(id + "/")) {
				continue;
			}
			forgotten += records.remove(key).size();
			changed.remove(key);
			lastWrite.remove(key);
			dirty.remove(key);
			// With no record, every region's highlight hash is 0 again: callers refresh the whole map.
			regionCounts.remove(key);
			regionHashes.remove(key);
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

	public synchronized long writes() {
		return writes;
	}

	public synchronized int rememberExport(String key, LongOpenHashSet chunks, long writesAtStart) {
		int number = nextExport++;
		exports.put(number, new Export(key, chunks, writesAtStart));
		exportsDirty = true;
		save();
		return number;
	}

	public synchronized int latestExport() {
		return exports.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
	}

	public synchronized int markUploaded(int number) {
		Export export = exports.remove(number);
		if (export == null || server == null) {
			return -1;
		}
		exportsDirty = true;
		LongOpenHashSet chunks = records.get(export.key());
		if (chunks == null) {
			return 0;
		}
		LongOpenHashSet changedChunks = changed.get(export.key());
		Long2LongOpenHashMap written = lastWrite.getOrDefault(export.key(), new Long2LongOpenHashMap());
		Long2IntOpenHashMap counts = regionCounts.computeIfAbsent(export.key(), k -> new Long2IntOpenHashMap());
		int forgotten = 0;
		for (LongIterator it = export.chunks().iterator(); it.hasNext(); ) {
			long pos = it.nextLong();
			if (written.get(pos) > export.writes() || !chunks.remove(pos)) {
				continue;
			}
			toggleHash(export.key(), pos, false);
			if (changedChunks != null && changedChunks.remove(pos)) {
				toggleHash(export.key(), pos, true);
			}
			written.remove(pos);
			counts.addTo(ChunkPos.pack(ChunkPos.getX(pos) >> 5, ChunkPos.getZ(pos) >> 5), -1);
			forgotten++;
		}
		dirty.add(export.key());
		save();
		return forgotten;
	}

	public synchronized List<String> surfaceKeys() {
		return records.entrySet().stream()
				.filter(record -> !record.getKey().contains("/") && !record.getValue().isEmpty())
				.map(Map.Entry::getKey).toList();
	}

	public synchronized LongOpenHashSet snapshot(String key) {
		LongOpenHashSet chunks = records.get(key);
		return chunks == null ? new LongOpenHashSet() : new LongOpenHashSet(chunks);
	}

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

	public synchronized Map<String, Integer> counts() {
		Map<String, Integer> counts = new TreeMap<>();
		records.forEach((key, chunks) -> counts.put(key, chunks.size()));
		return counts;
	}

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

	/** Write counts restart with the game, so chunks changed since each export are left out instead. */
	private void saveExports() {
		Path file = root.resolve(server).resolve("exports.txt");
		try {
			if (exports.isEmpty()) {
				Files.deleteIfExists(file);
				exportsDirty = false;
				return;
			}
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("exports.txt.tmp");
			List<Integer> numbers = exports.keySet().stream().sorted(Comparator.reverseOrder()).toList();
			try (BufferedWriter out = Files.newBufferedWriter(tmp)) {
				out.write(EXPORTS_HEADER);
				out.newLine();
				out.write("next " + nextExport);
				out.newLine();
				for (int number : numbers.subList(0, Math.min(MAX_EXPORTS, numbers.size()))) {
					Export export = exports.get(number);
					LongOpenHashSet recorded = records.getOrDefault(export.key(), new LongOpenHashSet());
					Long2LongOpenHashMap written = lastWrite.getOrDefault(export.key(), new Long2LongOpenHashMap());
					StringBuilder line = new StringBuilder().append(number).append(' ').append(export.key());
					for (LongIterator it = export.chunks().iterator(); it.hasNext(); ) {
						long pos = it.nextLong();
						if (recorded.contains(pos) && written.get(pos) <= export.writes()) {
							line.append(' ').append(ChunkPos.getX(pos)).append(',').append(ChunkPos.getZ(pos));
						}
					}
					out.write(line.toString());
					out.newLine();
				}
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			exportsDirty = false;
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not save the exports to {}", file, e);
		}
	}

	private void loadExports(Path file) {
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(file)) {
				String[] fields = line.strip().split(" ");
				try {
					if (fields[0].isEmpty() || fields[0].startsWith("#")) {
						continue;
					}
					if (fields[0].equals("next")) {
						nextExport = Math.max(nextExport, Integer.parseInt(fields[1]));
						continue;
					}
					int number = Integer.parseInt(fields[0]);
					LongOpenHashSet chunks = new LongOpenHashSet();
					for (int i = 2; i < fields.length; i++) {
						String[] xz = fields[i].split(",");
						chunks.add(ChunkPos.pack(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])));
					}
					exports.put(number, new Export(fields[1], chunks, 0));
					nextExport = Math.max(nextExport, number + 1);
				} catch (RuntimeException e) {
					AomcVisitedClient.LOGGER.warn("Skipping bad line in {}: {}", file, line);
				}
			}
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not read the exports from {}", file, e);
		}
	}

	private void addRecord(String key, LongOpenHashSet chunks, LongOpenHashSet changedChunks) {
		LongOpenHashSet recorded = records.computeIfAbsent(key, k -> new LongOpenHashSet());
		LongOpenHashSet recordedChanged = changed.computeIfAbsent(key, k -> new LongOpenHashSet());
		Long2IntOpenHashMap counts = regionCounts.computeIfAbsent(key, k -> new Long2IntOpenHashMap());
		for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
			long pos = it.nextLong();
			if (recorded.add(pos)) {
				counts.addTo(ChunkPos.pack(ChunkPos.getX(pos) >> 5, ChunkPos.getZ(pos) >> 5), 1);
				toggleHash(key, pos, false);
			}
			if (changedChunks.contains(pos) && recordedChanged.add(pos)) {
				toggleHash(key, pos, true);
			}
		}
	}

	/** Every record file under {@code dir}, listed up front so files can be moved meanwhile. */
	private static List<Path> recordFiles(Path dir) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(p -> p.toString().endsWith(".txt")).toList();
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not list visited chunks in {}", dir, e);
			return List.of();
		}
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			AomcVisitedClient.LOGGER.error("Could not delete {}", file, e);
		}
	}

	public static String shortName(String key) {
		return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
	}

	private static String keyFor(Path dir, Path file) {
		Path rel = dir.relativize(file);
		if (rel.getNameCount() < 2) {
			return null;
		}
		String path = rel.subpath(1, rel.getNameCount()).toString().replace('\\', '/');
		return rel.getName(0) + ":" + path.substring(0, path.length() - ".txt".length());
	}

	private void deleteForgotten(Predicate<String> matches, long before) {
		Path dir = root.resolve(server).resolve(FORGOTTEN);
		for (Path file : recordFiles(dir)) {
			String key = keyFor(dir, file);
			try {
				if (key != null && matches.test(key) && Files.getLastModifiedTime(file).toMillis() < before) {
					Files.delete(file);
				}
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not delete {}", file, e);
			}
		}
	}

	private Path forgottenFile(String key) {
		int colon = key.indexOf(':');
		return root.resolve(server).resolve(FORGOTTEN).resolve(key.substring(0, colon))
				.resolve(key.substring(colon + 1) + ".txt");
	}

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
