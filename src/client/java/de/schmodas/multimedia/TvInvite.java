package de.schmodas.multimedia;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Einladungen zum Bildschirm: Wer sendet, flüstert ausgewählten Spielern einen Code mit Adresse, Port und
 * geheimem Schlüssel. Deren Mod zeigt [Annehmen]; erst dann verbindet sie sich. Ohne Schlüssel lässt der
 * Sender niemanden zuschauen. (Die Adresse steht so nur bei denen, die man einlädt – nicht öffentlich im Chat.)
 */
public final class TvInvite {
	private static final Pattern CODE = Pattern.compile("mmtv[12]:([A-Za-z0-9_-]{20,})");

	public record Invite(String name, String host, int port, String token) {
	}

	/** Invites that arrived and haven't been accepted yet: player name -> code (for the menu). */
	private static final java.util.Map<String, String> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

	public static java.util.Map<String, String> pending() {
		return new java.util.TreeMap<>(PENDING);
	}

	private TvInvite() {
	}

	/** Einladung: Name + geheimer Schlüssel. Adressen werden erst beim Zuschauen über den Chat ausgetauscht (siehe TvSignal). */
	public static String encode(String name, String token) {
		String raw = name + "|" + token;
		return "mmtv2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static Invite decode(String code) {
		Matcher m = CODE.matcher(code);
		if (!m.find()) {
			return null;
		}
		try {
			String[] p = new String(Base64.getUrlDecoder().decode(m.group(1)), StandardCharsets.UTF_8).split("\\|");
			String token = p.length == 2 ? p[1] : p.length == 4 ? p[3] : "";
			if (!p[0].matches("[A-Za-z0-9_]{3,16}") || !token.matches("[0-9a-f]{16,64}")) {
				return null;
			}
			return new Invite(p[0], "", 0, token); // verbunden wird direkt (Loch bohren), nicht über eine feste Adresse
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Lädt Spieler per Flüstern ein. */
	public static void send(Collection<String> players) {
		var conn = Minecraft.getInstance().getConnection();
		if (conn == null || players.isEmpty()) {
			return;
		}
		String me = Minecraft.getInstance().getUser().getName();
		String code = encode(me, Settings.tvToken());
		for (String p : players) {
			ChatQueue.normal("msg " + p + " [MultiMedia] Bildschirm-Einladung " + code, "invite:" + p.toLowerCase(java.util.Locale.ROOT));
		}
		MultiMediaClient.status(T.t("Einladung geschickt an: ") + String.join(", ", players));
	}

	public static void register() {
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
			Minecraft mc = Minecraft.getInstance();
			boolean own = sender != null && mc.player != null && sender.id().equals(mc.player.getUUID());
			if (!own) {
				onMessage(message.getString());
			}
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				onMessage(message.getString());
			}
		});
	}

	private static void onMessage(String text) {
		Matcher m = CODE.matcher(text);
		if (!m.find()) {
			return;
		}
		Invite inv = decode(m.group());
		Minecraft mc = Minecraft.getInstance();
		if (inv == null || inv.name().equalsIgnoreCase(mc.getUser().getName())) {
			return; // kaputt oder die eigene
		}
		ModPeers.mark(inv.name());
		Settings.TvSource known = Settings.tvSource(inv.name());
		boolean same = known != null && known.token().equals(inv.token());
		if (same) {
			return; // schon angenommen
		}
		PENDING.put(inv.name(), m.group());
		Component button = Component.literal(T.t("[Annehmen]")).withStyle(s -> s
				.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.SuggestCommand((T.german() ? "/mm tv annehmen " : "/mm tv accept ") + m.group()))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(T.t("Klicken, dann Enter drücken. Danach siehst du ")
						+ inv.name() + T.t("s Bildschirm auf Leinwänden mit \"tv:") + inv.name() + "\"."))));
		mc.execute(() -> StreamWatcher.later(50, () -> {
			if (mc.player != null) {
				mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[MM] ").withStyle(ChatFormatting.LIGHT_PURPLE)
						.append(Component.literal(inv.name() + T.t(" lädt dich zu seinem Bildschirm ein ")).withStyle(ChatFormatting.GRAY))
						.append(button));
			}
		}));
	}

	/** Einladung annehmen (Befehl /mm tv annehmen <code>). */
	public static boolean accept(String code) {
		Invite inv = decode(code);
		if (inv == null) {
			return false;
		}
		Settings.setTvSource(inv.name(), new Settings.TvSource(inv.host(), inv.port(), inv.token()));
		PENDING.remove(inv.name());
		return true;
	}

	/** Für Anzeigen: Name, aber gekürzt. */
	static String nameOf(String code) {
		Invite inv = decode(code);
		return inv == null ? "?" : inv.name();
	}
}
