package de.schmodas.multimedia;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Absprache für die direkte Verbindung über den Minecraft-Chat (Flüstern, für Spieler unsichtbar):
 * Der Zuschauer schickt dem Sender "ich will zuschauen, so erreichst du mich", der Sender antwortet
 * "so erreichst du mich" (oder "sende gerade nicht"). Danach bohren beide das Loch (siehe P2p).
 */
final class TvSignal {
	// "mmtv…" = screen share, "mmcm…" = webcam (same protocol, second sender)
	private static final Pattern REQUEST = Pattern.compile("mm(tv|cm)q1:([A-Za-z0-9_-]{10,})");
	private static final Pattern ANSWER = Pattern.compile("mm(tv|cm)a1:([A-Za-z0-9_-]{10,})");
	private static final Pattern ANY = Pattern.compile("mm(tv|cm)[qa]1:");
	/** Nur für den Selbsttest: Nachrichten nicht verschicken, sondern direkt hier zustellen. */
	static final boolean LOOPBACK = System.getProperty("multimedia.p2ploop") != null;

	record Answer(String sender, String status, List<InetSocketAddress> candidates) {
	}

	private static final Map<Long, CompletableFuture<Answer>> PENDING = new ConcurrentHashMap<>();

	private TvSignal() {
	}

	static void register() {
		// unsichtbar machen (auch die eigene "Du flüsterst …"-Bestätigung)
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) -> {
			String text = message.getString();
			if (ANY.matcher(text).find()) {
				boolean own = sender != null && Minecraft.getInstance().player != null
						&& sender.id().equals(Minecraft.getInstance().player.getUUID());
				if (!own) {
					handle(text, sender == null ? null : sender.name());
				}
				return false;
			}
			return true;
		});
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			String text = message.getString();
			if (!overlay && ANY.matcher(text).find()) {
				if (!text.contains("→") && !text.toLowerCase(java.util.Locale.ROOT).startsWith("you whisper")
						&& !text.toLowerCase(java.util.Locale.ROOT).startsWith("du flüsterst")) {
					handle(text, null);
				}
				return false;
			}
			return true;
		});
	}

	// ------------------------------------------------------------------ Zuschauer

	/** Anfrage an den Sender schicken; die Antwort kommt über das Future. */
	static CompletableFuture<Answer> request(String sender, String token, long session, List<InetSocketAddress> candidates) {
		return request(sender, token, session, candidates, false);
	}

	static CompletableFuture<Answer> request(String sender, String token, long session, List<InetSocketAddress> candidates, boolean cam) {
		CompletableFuture<Answer> f = new CompletableFuture<>();
		PENDING.put(session, f);
		f.whenComplete((a, e) -> PENDING.remove(session));
		String me = Minecraft.getInstance().getUser().getName();
		send(sender, (cam ? "mmcmq1:" : "mmtvq1:") + b64(me + "|" + token + "|" + Long.toHexString(session) + "|" + join(candidates)));
		return f;
	}

	// ------------------------------------------------------------------ Sender

	/** Antwort an einen Zuschauer (status: ok, off, bad; session 0 + "on" = "ich sende jetzt"). */
	static void answer(String viewer, long session, String status, List<InetSocketAddress> candidates) {
		answer(viewer, session, status, candidates, false);
	}

	static void answer(String viewer, long session, String status, List<InetSocketAddress> candidates, boolean cam) {
		String me = Minecraft.getInstance().getUser().getName();
		send(viewer, (cam ? "mmcma1:" : "mmtva1:") + b64(me + "|" + Long.toHexString(session) + "|" + status + "|" + join(candidates)));
	}

	// ------------------------------------------------------------------ Empfang

	private static void handle(String text, String chatSender) {
		Matcher q = REQUEST.matcher(text);
		if (q.find()) {
			boolean cam = q.group(1).equals("cm");
			String[] p = unb64(q.group(2));
			if (p == null || p.length < 3 || !p[0].matches("[A-Za-z0-9_]{3,16}") || !p[1].matches("[0-9a-f]{16,64}")) {
				return;
			}
			if (chatSender != null && !chatSender.equalsIgnoreCase(p[0])) {
				return; // Name passt nicht zum Absender
			}
			long session;
			try {
				session = Long.parseUnsignedLong(p[2], 16);
			} catch (NumberFormatException e) {
				return;
			}
			List<InetSocketAddress> cands = candidates(p.length > 3 ? p[3] : "");
			String viewer = p[0];
			Thread t = new Thread(() -> TvSender.handleRequest(viewer, p[1], session, cands, cam), "MultiMedia-TvRequest");
			t.setDaemon(true);
			t.start();
			return;
		}
		Matcher a = ANSWER.matcher(text);
		if (a.find()) {
			boolean cam = a.group(1).equals("cm");
			String[] p = unb64(a.group(2));
			if (p == null || p.length < 3 || !p[0].matches("[A-Za-z0-9_]{3,16}")) {
				return;
			}
			if (chatSender != null && !chatSender.equalsIgnoreCase(p[0])) {
				return;
			}
			long session;
			try {
				session = Long.parseUnsignedLong(p[1], 16);
			} catch (NumberFormatException e) {
				return;
			}
			Answer ans = new Answer(p[0], p[2], candidates(p.length > 3 ? p[3] : ""));
			if (session == 0 && ans.status().equals("on")) {
				LiveFeed.wake(p[0], cam);
				return;
			}
			CompletableFuture<Answer> f = PENDING.get(session);
			if (f != null) {
				f.complete(ans);
			}
		}
	}

	private static void send(String to, String code) {
		if (LOOPBACK) {
			// Selbsttest: wie vom anderen Spieler empfangen
			Thread t = new Thread(() -> {
				P2p.sleep(300);
				handle(code, null);
			}, "MultiMedia-SignalLoop");
			t.setDaemon(true);
			t.start();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			var conn = mc.getConnection();
			if (conn != null) {
				conn.sendCommand("msg " + to + " [MultiMedia] " + code);
			}
		});
	}

	/** Ist der Spieler gerade auf dem Server? (Sonst gar nicht erst anfragen.) */
	static boolean isOnline(String name) {
		return onlineName(name) != null;
	}

	/** Richtig geschriebener Name, wenn der Spieler online ist (Groß-/Kleinschreibung egal), sonst null. */
	static String onlineName(String name) {
		if (LOOPBACK) {
			return name;
		}
		var conn = Minecraft.getInstance().getConnection();
		if (conn == null) {
			return null;
		}
		for (var info : conn.getOnlinePlayers()) {
			if (info.getProfile().name().equalsIgnoreCase(name)) {
				return info.getProfile().name();
			}
		}
		return null;
	}

	private static String join(List<InetSocketAddress> cands) {
		List<String> s = new ArrayList<>();
		for (InetSocketAddress c : cands) {
			s.add(P2p.format(c));
		}
		return String.join(";", s);
	}

	private static List<InetSocketAddress> candidates(String s) {
		List<InetSocketAddress> out = new ArrayList<>();
		for (String part : s.split(";")) {
			InetSocketAddress a = P2p.parse(part.trim());
			if (a != null && out.size() < 4) {
				out.add(a);
			}
		}
		return out;
	}

	private static String b64(String s) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	private static String[] unb64(String s) {
		try {
			return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8).split("\\|", -1);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
