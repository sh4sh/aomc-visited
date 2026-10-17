package io.github.sh4sh.aomcvisited;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

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

	private static MutableComponent message(String text, ChatFormatting colour) {
		return Component.literal("[" + NAME + "] ").withStyle(ChatFormatting.DARK_AQUA)
				.append(Component.literal(text).withStyle(colour));
	}

	public static MutableComponent line(Component text) {
		return Component.literal("\n  ").withStyle(ChatFormatting.GRAY).append(text);
	}

	public static MutableComponent line(String text) {
		return line(Component.literal(text));
	}

	public static MutableComponent button(String label, ChatFormatting colour, String command, String hover) {
		return Component.literal("[" + label + "]").withStyle(style -> style
				.withColor(colour)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	public static MutableComponent openButton(String label, java.nio.file.Path path) {
		return Component.literal("[" + label + "]").withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.OpenFile(path))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(path.toString()))));
	}

	/** Minecraft chat can't centre text, so this pads with spaces for the chat's current width. */
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
		double scale = client.options.chatScale().get();
		int chatWidth = scale <= 0 ? 0 : Mth.floor(ChatComponent.getWidth(client.options.chatWidth().get()) / scale);
		int space = Math.max(1, client.font.width(" "));
		return Math.max(0, (chatWidth - client.font.width(row)) / 2 / space);
	}

	public static void send(Minecraft client, Component message) {
		AomcVisitedClient.LOGGER.info(message.getString());
		if (client.player != null) {
			client.player.sendSystemMessage(message);
		}
	}

	public static void status(Minecraft client, String text, ChatFormatting colour) {
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.literal(NAME + ": ").withStyle(ChatFormatting.DARK_AQUA)
					.append(Component.literal(text).withStyle(colour)));
		}
	}
}
