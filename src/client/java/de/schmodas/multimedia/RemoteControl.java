package de.schmodas.multimedia;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Remote control of a shared screen (the streamer's side).
 *
 * A viewer looking at your screen share can ask to control it ("req"). You get a chat message with [Allow] /
 * [Deny]; nothing happens until you allow that player yourself. Allowed players' mouse and keyboard events are
 * replayed on the monitor you're sharing. Permission lasts until you revoke it (/mm control revoke <player>,
 * /mm control off) or stop sharing – it's never saved. The HUD shows who can control your PC while it's active.
 *
 * Events (viewer -> sender, UTF-8 text in a P2p.INPUT packet), coordinates are 0..1 of the sent 16:9 picture:
 *   req | move u v | down l|r|m u v | up l|r|m u v | wheel n | key combo (e.g. "ctrl+c", "enter") | text base64
 * Injection: Windows via the bundled helper mminput.exe (SendInput), Linux via ydotool (Wayland) or xdotool (X11).
 */
public final class RemoteControl {
	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
	/** Players allowed to control (lower case). */
	private static final Set<String> ALLOWED = ConcurrentHashMap.newKeySet();
	/** Players who asked and are waiting for an answer (lower case -> time asked). */
	private static final Map<String, Long> ASKED = new ConcurrentHashMap<>();
	/** How to answer each viewer (their current connection). */
	private static final Map<String, Consumer<String>> REPLY = new ConcurrentHashMap<>();
	/** Correct spelling of names for messages. */
	private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
	private static volatile Injector injector;
	private static volatile boolean warnedNoTool;

	private RemoteControl() {
	}

	// ------------------------------------------------------------------ Permissions

	static void onEvent(TvSender s, String viewer, String event, Consumer<String> reply) {
		String key = viewer.toLowerCase(Locale.ROOT);
		REPLY.put(key, reply);
		NAMES.put(key, viewer);
		event = event.trim();
		if (event.equals("req")) {
			if (ALLOWED.contains(key)) {
				reply.accept("granted");
				return;
			}
			Long last = ASKED.get(key);
			long now = System.currentTimeMillis();
			reply.accept("pending");
			if (last != null && now - last < 20_000) {
				return; // already asked a moment ago – don't spam the chat
			}
			ASKED.put(key, now);
			prompt(viewer);
			return;
		}
		if (!ALLOWED.contains(key)) {
			return; // not allowed: ignore silently
		}
		Injector inj = injector();
		if (inj != null) {
			inj.handle(s, event);
		}
	}

	private static void prompt(String viewer) {
		Minecraft mc = Minecraft.getInstance();
		Component allow = Component.literal("[Allow]").withStyle(st -> st.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.SuggestCommand("/mm control allow " + viewer))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(
						"Click, then press Enter. " + viewer + " can then use your mouse and keyboard on the monitor you're sharing."))));
		Component deny = Component.literal("[Deny]").withStyle(st -> st.withColor(ChatFormatting.RED)
				.withClickEvent(new ClickEvent.SuggestCommand("/mm control deny " + viewer)));
		mc.execute(() -> {
			if (mc.player != null) {
				mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[MM] ").withStyle(ChatFormatting.LIGHT_PURPLE)
						.append(Component.literal(viewer + " wants to control your shared screen. ").withStyle(ChatFormatting.GOLD))
						.append(allow).append(Component.literal(" ")).append(deny));
			}
		});
	}

	/** /mm control allow <player> */
	public static boolean allow(String name) {
		if (!TvSender.isSending()) {
			MultiMediaClient.error("You're not sharing your screen.");
			return false;
		}
		String key = name.toLowerCase(Locale.ROOT);
		ALLOWED.add(key);
		ASKED.remove(key);
		NAMES.putIfAbsent(key, name);
		Consumer<String> r = REPLY.get(key);
		if (r != null) {
			r.accept("granted");
		}
		Injector inj = injector();
		MultiMediaClient.status(NAMES.get(key) + " can now control your shared screen. Stop with /mm control revoke "
				+ NAMES.get(key) + " or /mm control off.");
		if (inj == null) {
			MultiMediaClient.error(noToolMessage());
		}
		return true;
	}

	/** /mm control deny <player> */
	public static void deny(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		ASKED.remove(key);
		Consumer<String> r = REPLY.get(key);
		if (r != null && !ALLOWED.contains(key)) {
			r.accept("denied");
		}
		MultiMediaClient.status("Denied.");
	}

	/** /mm control revoke <player> */
	public static void revoke(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		if (ALLOWED.remove(key)) {
			Consumer<String> r = REPLY.get(key);
			if (r != null) {
				r.accept("revoked");
			}
			releaseButtons();
			MultiMediaClient.status(NAMES.getOrDefault(key, name) + " can't control your PC anymore.");
		} else {
			MultiMediaClient.status(name + " couldn't control your PC anyway.");
		}
	}

	/** Revokes everyone (also when sharing stops). */
	public static void stopAll() {
		for (String key : ALLOWED) {
			Consumer<String> r = REPLY.get(key);
			if (r != null) {
				r.accept("revoked");
			}
		}
		boolean had = !ALLOWED.isEmpty();
		ALLOWED.clear();
		ASKED.clear();
		REPLY.clear();
		if (had) {
			releaseButtons();
		}
		Injector inj = injector;
		injector = null;
		if (inj != null) {
			inj.close();
		}
	}

	/** "Alex, Sam" while someone may control this PC, else null (for the HUD). */
	public static String activeSummary() {
		if (ALLOWED.isEmpty()) {
			return null;
		}
		Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (String key : ALLOWED) {
			names.add(NAMES.getOrDefault(key, key));
		}
		return String.join(", ", names);
	}

	private static void releaseButtons() {
		Injector inj = injector;
		if (inj != null) {
			inj.releaseAll();
		}
	}

	private static String noToolMessage() {
		if (WINDOWS) {
			return "Remote control helper (mminput.exe) is missing from this build.";
		}
		return "Remote control needs ydotool (Wayland, with ydotoold running) or xdotool (X11). On Arch/CachyOS: "
				+ "sudo pacman -S ydotool && systemctl --user enable --now ydotool";
	}

	private static synchronized Injector injector() {
		if (injector != null) {
			return injector;
		}
		Injector inj = null;
		if (WINDOWS) {
			java.nio.file.Path helper = Tools.inputHelper();
			if (helper != null) {
				inj = new WindowsInjector(helper);
			}
		} else {
			boolean wayland = System.getenv("WAYLAND_DISPLAY") != null
					|| "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"));
			if (wayland && TvSender.onPath("ydotool")) {
				inj = new LinuxInjector(true);
			} else if (System.getenv("DISPLAY") != null && TvSender.onPath("xdotool")) {
				inj = new LinuxInjector(false);
			}
		}
		if (inj == null && !warnedNoTool) {
			warnedNoTool = true;
			MultiMediaClient.error(noToolMessage());
		}
		injector = inj;
		return inj;
	}

	// ------------------------------------------------------------------ Coordinates

	/** 0..1 in the sent picture (16:9, letterboxed) -> 0..1 on the captured monitor, or null if on the black bars. */
	static double[] unpad(double u, double v, int srcW, int srcH) {
		if (srcW <= 0 || srcH <= 0) {
			return new double[]{clamp(u), clamp(v)};
		}
		double k = Math.min(1280.0 / srcW, 720.0 / srcH);
		double cw = srcW * k / 1280.0;
		double ch = srcH * k / 720.0;
		double x = (u - (1 - cw) / 2) / cw;
		double y = (v - (1 - ch) / 2) / ch;
		return new double[]{clamp(x), clamp(y)};
	}

	private static double clamp(double d) {
		return Double.isNaN(d) ? 0.5 : Math.max(0, Math.min(1, d));
	}

	/** Parses "u v" (two numbers) from parts[from], parts[from+1]. */
	static double[] uv(String[] parts, int from) {
		if (parts.length < from + 2) {
			return null;
		}
		try {
			return new double[]{Double.parseDouble(parts[from]), Double.parseDouble(parts[from + 1])};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Only these key names (optionally with ctrl+/shift+/alt+/super+) are accepted. */
	static final Set<String> KEYS = Set.of("enter", "backspace", "tab", "escape", "space", "delete", "insert", "home", "end",
			"pageup", "pagedown", "left", "right", "up", "down", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10",
			"f11", "f12", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t",
			"u", "v", "w", "x", "y", "z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
	static final Set<String> MODS = Set.of("ctrl", "shift", "alt", "super");

	/** "ctrl+shift+t" -> ["ctrl","shift","t"], or null if anything is unknown. */
	static List<String> combo(String s) {
		List<String> out = new ArrayList<>();
		String[] p = s.toLowerCase(Locale.ROOT).split("\\+");
		for (int i = 0; i < p.length; i++) {
			boolean last = i == p.length - 1;
			if (last ? !KEYS.contains(p[i]) : !MODS.contains(p[i])) {
				return null;
			}
			out.add(p[i]);
		}
		return out.isEmpty() ? null : out;
	}

	// ------------------------------------------------------------------ Injectors

	private abstract static class Injector {
		/** Last pointer position on the monitor (pixels), for button events without coordinates. */
		volatile int lastX = -1;
		volatile int lastY = -1;

		abstract int[] monitorSize(TvSender s);

		abstract void move(int x, int y);

		abstract void button(char b, boolean down);

		abstract void wheel(int n);

		abstract void key(List<String> combo);

		abstract void text(String s);

		abstract void releaseAll();

		abstract void close();

		void handle(TvSender s, String event) {
			String[] parts = event.split(" ", 2);
			String cmd = parts[0];
			String rest = parts.length > 1 ? parts[1] : "";
			String[] args = rest.split(" ");
			switch (cmd) {
				case "move" -> {
					int[] p = point(s, args, 0);
					if (p != null) {
						move(p[0], p[1]);
					}
				}
				case "down", "up" -> {
					if (args.length < 1 || args[0].length() != 1 || "lrm".indexOf(args[0].charAt(0)) < 0) {
						return;
					}
					int[] p = point(s, args, 1);
					if (p != null) {
						move(p[0], p[1]);
					}
					button(args[0].charAt(0), cmd.equals("down"));
				}
				case "wheel" -> {
					try {
						int n = Integer.parseInt(rest.trim());
						if (n != 0 && Math.abs(n) <= 20) {
							wheel(n);
						}
					} catch (NumberFormatException ignored) {
					}
				}
				case "key" -> {
					List<String> c = combo(rest.trim());
					if (c != null) {
						key(c);
					}
				}
				case "text" -> {
					try {
						String t = new String(Base64.getDecoder().decode(rest.trim()), StandardCharsets.UTF_8);
						t = t.replaceAll("[\\p{Cntrl}]", "");
						if (!t.isEmpty() && t.length() <= 200) {
							text(t);
						}
					} catch (IllegalArgumentException ignored) {
					}
				}
				default -> {
				}
			}
		}

		private int[] point(TvSender s, String[] args, int from) {
			double[] uv = uv(args, from);
			if (uv == null) {
				return null;
			}
			int[] size = monitorSize(s);
			if (size == null) {
				return null;
			}
			double[] m = unpad(uv[0], uv[1], size[0], size[1]);
			int x = (int) Math.round(m[0] * (size[0] - 1));
			int y = (int) Math.round(m[1] * (size[1] - 1));
			lastX = x;
			lastY = y;
			return new int[]{x, y};
		}
	}

	/** Windows: the bundled helper replays events with SendInput on the shared monitor (same order as ddagrab). */
	private static final class WindowsInjector extends Injector {
		private final Process process;
		private final OutputStream out;
		private volatile int width;
		private volatile int height;
		private volatile int monitor = -1;

		WindowsInjector(java.nio.file.Path helper) {
			Process p = null;
			try {
				p = new ProcessBuilder(helper.toString()).redirectErrorStream(true).start();
			} catch (IOException e) {
				MultiMediaClient.LOGGER.warn("Fernsteuerung: Helfer startet nicht: {}", e.toString());
			}
			process = p;
			out = p == null ? null : p.getOutputStream();
			if (p != null) {
				Process proc = p;
				Thread t = new Thread(() -> {
					try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
						String line;
						while ((line = r.readLine()) != null) {
							String[] parts = line.trim().split(" ");
							if (parts.length == 3 && parts[0].equals("size")) {
								try {
									width = Integer.parseInt(parts[1]);
									height = Integer.parseInt(parts[2]);
								} catch (NumberFormatException ignored) {
								}
							} else if (!line.isBlank()) {
								MultiMediaClient.LOGGER.info("[fernsteuerung] {}", line.trim());
							}
						}
					} catch (IOException ignored) {
					}
				}, "MultiMedia-InputHelper");
				t.setDaemon(true);
				t.start();
			}
		}

		private synchronized void send(String line) {
			if (out == null) {
				return;
			}
			try {
				out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
			} catch (IOException e) {
				MultiMediaClient.LOGGER.info("Fernsteuerung: Helfer weg: {}", e.toString());
			}
		}

		@Override
		int[] monitorSize(TvSender s) {
			int m = s.monitor();
			if (m != monitor) {
				// ask the helper for this monitor; until its size arrives (a few ms) events are skipped
				monitor = m;
				width = 0;
				send("mon " + m);
			}
			return width > 0 ? new int[]{width, height} : null;
		}

		@Override
		void move(int x, int y) {
			send("move " + x + " " + y);
		}

		@Override
		void button(char b, boolean down) {
			send((down ? "down " : "up ") + b);
		}

		@Override
		void wheel(int n) {
			send("wheel " + n);
		}

		@Override
		void key(List<String> combo) {
			send("key " + String.join("+", combo));
		}

		@Override
		void text(String s) {
			send("text " + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)));
		}

		@Override
		void releaseAll() {
			send("release");
		}

		@Override
		void close() {
			if (process != null) {
				send("release");
				process.destroy();
			}
		}
	}

	/** Linux: one short ydotool / xdotool call per event, in order; mouse moves are merged when they pile up. */
	private static final class LinuxInjector extends Injector {
		private final boolean ydotool;
		private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "MultiMedia-Input");
			t.setDaemon(true);
			return t;
		});
		private final AtomicReference<int[]> pendingMove = new AtomicReference<>();
		private final Set<Character> held = ConcurrentHashMap.newKeySet();
		private volatile boolean failedOnce;

		LinuxInjector(boolean ydotool) {
			this.ydotool = ydotool;
		}

		private void run(List<String> cmd) {
			try {
				Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
				String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
				int code = p.waitFor();
				if (code != 0 && !failedOnce) {
					failedOnce = true;
					MultiMediaClient.LOGGER.info("Fernsteuerung: {} -> {} {}", cmd, code, text);
					MultiMediaClient.error("Remote control: " + cmd.get(0) + " failed (" + (text.isEmpty() ? "exit " + code : text)
							+ ")" + (ydotool ? " – is ydotoold running? systemctl --user enable --now ydotool" : ""));
				}
			} catch (IOException e) {
				if (!failedOnce) {
					failedOnce = true;
					MultiMediaClient.error("Remote control: " + e.getMessage());
				}
			} catch (InterruptedException ignored) {
			}
		}

		private void submit(List<String> cmd) {
			queue.submit(() -> run(cmd));
		}

		@Override
		int[] monitorSize(TvSender s) {
			if (s.sourceWidth > 0) {
				return new int[]{s.sourceWidth, s.sourceHeight};
			}
			return null;
		}

		@Override
		void move(int x, int y) {
			if (pendingMove.getAndSet(new int[]{x, y}) != null) {
				return; // a move is already queued – it'll use the newest position
			}
			queue.submit(() -> {
				int[] p = pendingMove.getAndSet(null);
				if (p == null) {
					return;
				}
				if (ydotool) {
					run(List.of("ydotool", "mousemove", "--absolute", "-x", Integer.toString(p[0]), "-y", Integer.toString(p[1])));
				} else {
					run(List.of("xdotool", "mousemove", "--", Integer.toString(p[0]), Integer.toString(p[1])));
				}
			});
		}

		@Override
		void button(char b, boolean down) {
			if (down) {
				held.add(b);
			} else {
				held.remove(b);
			}
			if (ydotool) {
				int base = b == 'l' ? 0 : b == 'r' ? 1 : 2;
				submit(List.of("ydotool", "click", String.format("0x%02X", (down ? 0x40 : 0x80) | base)));
			} else {
				String n = b == 'l' ? "1" : b == 'm' ? "2" : "3";
				submit(List.of("xdotool", down ? "mousedown" : "mouseup", n));
			}
		}

		@Override
		void wheel(int n) {
			if (ydotool) {
				submit(List.of("ydotool", "mousemove", "--wheel", "-x", "0", "-y", Integer.toString(n)));
			} else {
				submit(List.of("xdotool", "click", "--repeat", Integer.toString(Math.abs(n)), n > 0 ? "4" : "5"));
			}
		}

		@Override
		void key(List<String> combo) {
			if (!ydotool) {
				List<String> names = new ArrayList<>();
				for (String k : combo) {
					names.add(xName(k));
				}
				submit(List.of("xdotool", "key", "--", String.join("+", names)));
				return;
			}
			List<String> cmd = new ArrayList<>(List.of("ydotool", "key"));
			for (String k : combo) {
				cmd.add(EV.get(k) + ":1");
			}
			for (int i = combo.size() - 1; i >= 0; i--) {
				cmd.add(EV.get(combo.get(i)) + ":0");
			}
			submit(cmd);
		}

		@Override
		void text(String s) {
			submit(ydotool ? List.of("ydotool", "type", "--", s) : List.of("xdotool", "type", "--", s));
		}

		@Override
		void releaseAll() {
			for (char b : new ArrayList<>(held)) {
				button(b, false);
			}
		}

		@Override
		void close() {
			releaseAll();
			queue.shutdown();
		}

		private static String xName(String k) {
			return switch (k) {
				case "enter" -> "Return";
				case "backspace" -> "BackSpace";
				case "tab" -> "Tab";
				case "escape" -> "Escape";
				case "delete" -> "Delete";
				case "insert" -> "Insert";
				case "home" -> "Home";
				case "end" -> "End";
				case "pageup" -> "Prior";
				case "pagedown" -> "Next";
				case "left" -> "Left";
				case "right" -> "Right";
				case "up" -> "Up";
				case "down" -> "Down";
				case "ctrl" -> "ctrl";
				case "shift" -> "shift";
				case "alt" -> "alt";
				case "super" -> "super";
				default -> k.startsWith("f") && k.length() > 1 ? "F" + k.substring(1) : k;
			};
		}

		/** Linux input event codes (linux/input-event-codes.h) for ydotool. */
		private static final Map<String, Integer> EV = Map.ofEntries(
				Map.entry("escape", 1), Map.entry("1", 2), Map.entry("2", 3), Map.entry("3", 4), Map.entry("4", 5),
				Map.entry("5", 6), Map.entry("6", 7), Map.entry("7", 8), Map.entry("8", 9), Map.entry("9", 10),
				Map.entry("0", 11), Map.entry("backspace", 14), Map.entry("tab", 15), Map.entry("q", 16), Map.entry("w", 17),
				Map.entry("e", 18), Map.entry("r", 19), Map.entry("t", 20), Map.entry("y", 21), Map.entry("u", 22),
				Map.entry("i", 23), Map.entry("o", 24), Map.entry("p", 25), Map.entry("enter", 28), Map.entry("ctrl", 29),
				Map.entry("a", 30), Map.entry("s", 31), Map.entry("d", 32), Map.entry("f", 33), Map.entry("g", 34),
				Map.entry("h", 35), Map.entry("j", 36), Map.entry("k", 37), Map.entry("l", 38), Map.entry("shift", 42),
				Map.entry("z", 44), Map.entry("x", 45), Map.entry("c", 46), Map.entry("v", 47), Map.entry("b", 48),
				Map.entry("n", 49), Map.entry("m", 50), Map.entry("alt", 56), Map.entry("space", 57), Map.entry("f1", 59),
				Map.entry("f2", 60), Map.entry("f3", 61), Map.entry("f4", 62), Map.entry("f5", 63), Map.entry("f6", 64),
				Map.entry("f7", 65), Map.entry("f8", 66), Map.entry("f9", 67), Map.entry("f10", 68), Map.entry("f11", 87),
				Map.entry("f12", 88), Map.entry("home", 102), Map.entry("up", 103), Map.entry("pageup", 104),
				Map.entry("left", 105), Map.entry("right", 106), Map.entry("end", 107), Map.entry("down", 108),
				Map.entry("pagedown", 109), Map.entry("insert", 110), Map.entry("delete", 111), Map.entry("super", 125));
	}
}
