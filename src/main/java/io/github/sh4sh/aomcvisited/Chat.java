package io.github.sh4sh.aomcvisited;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

/**
 * The look of the mod's chat messages, kept in one place: every message starts with the mod's name, and
 * its colour says what kind it is (white for information, green for done, gold for a warning, red for a
 * problem). Extra lines are grey and indented; clickable buttons go on a line of their own, centred.
 */
public final class Chat {
	private static final String NAME = "AOMC Visited";
	private static final String BUTTON_GAP = "   ";

	private Chat() {
	}

	public static MutableComponent info(String text) {
		return message(text, ChatFormatting.WHITE);
	}

	public static MutableComponent success(String text) {
		return message(text, ChatFormatting.GREEN);
	}

	public static MutableComponent warning(String text) {
		return message(text, ChatFormatting.GOLD);
	}

	public static MutableComponent error(String text) {
		return message(text, ChatFormatting.RED);
	}

	/** The mod's name, then {@code text} in {@code colour}. Append more with {@link #line} and {@link #buttons}. */
	private static MutableComponent message(String text, ChatFormatting colour) {
		return Component.literal("[" + NAME + "] ").withStyle(ChatFormatting.DARK_AQUA)
				.append(Component.literal(text).withStyle(colour));
	}

	/** An indented grey line to append to a message (starts with a line break). */
	public static MutableComponent line(Component text) {
		return Component.literal("\n  ").withStyle(ChatFormatting.GRAY).append(text);
	}

	public static MutableComponent line(String text) {
		return line(Component.literal(text));
	}

	/** A clickable button that runs a {@code /visited} command. */
	public static MutableComponent button(String label, ChatFormatting colour, String command, String hover) {
		return Component.literal("[" + label + "]").withStyle(style -> style
				.withColor(colour)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/** A clickable button that opens a file or folder. */
	public static MutableComponent openButton(String label, java.nio.file.Path path) {
		return Component.literal("[" + label + "]").withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.OpenFile(path))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(path.toString()))));
	}

	/**
	 * A line of buttons to append to a message (starts with a line break), centred in the chat as it's
	 * set up right now: Minecraft chat can't centre text itself, so this pads with spaces. If the chat is
	 * resized later, the buttons are simply off-centre.
	 */
	public static MutableComponent buttons(Component... buttons) {
		MutableComponent row = Component.empty();
		for (int i = 0; i < buttons.length; i++) {
			if (i > 0) {
				row.append(BUTTON_GAP);
			}
			row.append(buttons[i]);
		}
		return Component.literal("\n" + " ".repeat(centringSpaces(row))).append(row);
	}

	private static int centringSpaces(Component row) {
		Minecraft client = Minecraft.getInstance();
		// The chat wraps lines at this many (unscaled) pixels; see ChatComponent.
		double scale = client.options.chatScale().get();
		int chatWidth = scale <= 0 ? 0 : Mth.floor(ChatComponent.getWidth(client.options.chatWidth().get()) / scale);
		int space = Math.max(1, client.font.width(" "));
		return Math.max(0, (chatWidth - client.font.width(row)) / 2 / space);
	}

	/** Shows a message to the player (only them: it isn't sent to the server), and logs it. */
	public static void send(Minecraft client, Component message) {
		AomcVisitedClient.LOGGER.info(message.getString());
		if (client.player != null) {
			client.player.sendSystemMessage(message);
		}
	}

	/** The text above the hotbar, for progress. Shown for about 3 seconds unless sent again. */
	public static void status(Minecraft client, String text, ChatFormatting colour) {
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.literal(NAME + ": ").withStyle(ChatFormatting.DARK_AQUA)
					.append(Component.literal(text).withStyle(colour)));
		}
	}
}
