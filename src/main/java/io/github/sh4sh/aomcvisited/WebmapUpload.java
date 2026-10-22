package io.github.sh4sh.aomcvisited;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

final class WebmapUpload {
	private static final URI WEBMAP = URI.create("https://map.diorite.xyz/");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	private static final String USER_AGENT = "AOMC Visited/" + FabricLoader.getInstance()
			.getModContainer(AomcVisitedClient.MOD_ID).map(mod -> mod.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");

	/** This session's exports, by number, for their [Upload] buttons. */
	private static final Map<Integer, Dimension> EXPORTS = new HashMap<>();
	private static volatile boolean running;

	/** One dimension of an export: its record key, its export number, and its cleaned images. */
	record Dimension(String key, int export, List<Path> images) {
	}

	private WebmapUpload() {
	}

	static void remember(Dimension dimension) {
		EXPORTS.put(dimension.export(), dimension);
	}

	static Dimension export(int number) {
		return EXPORTS.get(number);
	}

	static boolean running() {
		return running;
	}

	/** The webmap's name for a dimension, or null if it has no map for it. */
	static String webmapName(String key) {
		return switch (key) {
			case "minecraft:overworld" -> "overworld";
			case "minecraft:the_nether" -> "nether";
			case "minecraft:the_end" -> "the_end";
			default -> null;
		};
	}

	/**
	 * Uploads each dimension's images one after another, has the webmap process them, then marks each
	 * dimension whose images were all accepted and processed as uploaded. Runs in the background.
	 */
	static void start(Minecraft client, List<Dimension> dimensions) {
		running = true;
		Thread.ofVirtual().name("AOMC Visited upload").start(() -> {
			try {
				Map<Dimension, String> problems = upload(client, dimensions);
				client.execute(() -> finish(client, dimensions, problems));
			} catch (RuntimeException e) {
				AomcVisitedClient.LOGGER.error("Upload failed", e);
				client.execute(() -> Chat.send(client, Chat.error("The upload failed: " + e.getMessage()
						+ ". Nothing was marked as uploaded.")));
			} finally {
				running = false;
			}
		});
	}

	/** Returns a problem for each dimension that didn't fully succeed. */
	private static Map<Dimension, String> upload(Minecraft client, List<Dimension> dimensions) {
		Map<Dimension, String> problems = new LinkedHashMap<>();
		int total = dimensions.stream().mapToInt(dimension -> dimension.images().size()).sum();
		int done = 0;
		boolean anyUploaded = false;
		for (Dimension dimension : dimensions) {
			for (Path image : dimension.images()) {
				status(client, "uploading " + (++done) + " of " + total);
				String problem = uploadImage(webmapName(dimension.key()), image);
				if (problem != null) {
					problems.put(dimension, image.getFileName() + ": " + problem);
					break;
				}
				anyUploaded = true;
			}
		}
		if (!anyUploaded) {
			return problems;
		}
		status(client, "the webmap is processing the upload");
		try {
			HttpRequest request = HttpRequest.newBuilder(WEBMAP.resolve("api/map/process"))
					.header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(15))
					.POST(HttpRequest.BodyPublishers.noBody()).build();
			HttpResponse<Stream<String>> response = HTTP.send(request, HttpResponse.BodyHandlers.ofLines());
			boolean complete = false;
			if (response.statusCode() == 200) {
				for (String line : (Iterable<String>) response.body()::iterator) {
					AomcVisitedClient.LOGGER.info("[AOMC Visited] webmap: {}", line);
					status(client, "webmap: " + line.strip());
					complete |= line.startsWith("Processing complete");
					if (line.contains("Error processing ")) {
						for (Dimension dimension : dimensions) {
							if (dimension.images().stream().anyMatch(image -> line.contains(image.getFileName().toString()))) {
								problems.putIfAbsent(dimension, line.strip());
							}
						}
					}
				}
			}
			if (!complete) {
				String problem = "the webmap didn't finish processing (HTTP " + response.statusCode() + ")";
				dimensions.forEach(dimension -> problems.putIfAbsent(dimension, problem));
			}
		} catch (IOException | InterruptedException e) {
			AomcVisitedClient.LOGGER.error("Webmap processing failed", e);
			dimensions.forEach(dimension -> problems.putIfAbsent(dimension, "processing failed: " + e.getMessage()));
		}
		return problems;
	}

	/** Uploads one image. Returns a problem, or null if the webmap accepted it. */
	private static String uploadImage(String dimension, Path image) {
		String boundary = "aomcvisited" + UUID.randomUUID().toString().replace("-", "");
		byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
				+ image.getFileName() + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
		byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		try {
			HttpRequest request = HttpRequest.newBuilder(WEBMAP.resolve("api/map/upload/" + dimension))
					.header("User-Agent", USER_AGENT).header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.timeout(Duration.ofMinutes(2))
					.POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(head),
							HttpRequest.BodyPublishers.ofFile(image), HttpRequest.BodyPublishers.ofByteArray(tail)))
					.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			AomcVisitedClient.LOGGER.info("[AOMC Visited] upload: {} -> HTTP {} {}", image.getFileName(),
					response.statusCode(), response.body());
			if (response.statusCode() != 200) {
				return "the webmap answered HTTP " + response.statusCode();
			}
			if (!response.body().replace(" ", "").contains("\"files_saved\":1")) {
				return "the webmap didn't save it";
			}
			return null;
		} catch (IOException | InterruptedException e) {
			AomcVisitedClient.LOGGER.error("Could not upload {}", image, e);
			return "couldn't reach the webmap (" + e.getMessage() + ")";
		}
	}

	private static void finish(Minecraft client, List<Dimension> dimensions, Map<Dimension, String> problems) {
		MutableComponent report = problems.isEmpty()
				? Chat.success("Uploaded to map.diorite.xyz and marked as uploaded.")
				: Chat.warning("The upload didn't fully succeed. Only what's listed as marked was marked as uploaded.");
		for (Dimension dimension : dimensions) {
			String name = VisitedChunks.shortName(dimension.key());
			String problem = problems.get(dimension);
			if (problem != null) {
				report.append(Chat.line(Component.literal(name + ": not marked as uploaded, " + problem)
						.withStyle(ChatFormatting.RED)));
				continue;
			}
			int forgotten = AomcVisitedClient.VISITED.markUploaded(dimension.export());
			report.append(Chat.line(forgotten < 0
					? name + ": uploaded, but couldn't be marked (that export was replaced or cleared)"
					: name + ": " + dimension.images().size() + " images uploaded, marked as uploaded"));
		}
		VisitedHighlighter.refreshAll();
		Chat.status(client, problems.isEmpty() ? "upload done, see chat." : "upload had problems, see chat.",
				problems.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.GOLD);
		Chat.send(client, report);
	}

	private static void status(Minecraft client, String text) {
		client.execute(() -> Chat.status(client, text, ChatFormatting.YELLOW));
	}
}
