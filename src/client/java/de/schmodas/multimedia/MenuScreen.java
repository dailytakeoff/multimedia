package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The MultiMedia menu (hotkey M or /mm menu): a dark panel with pages on the left –
 * Live (camera + screen share), Screens (floating screens), Friends (who can watch, invites, remote control),
 * Settings and How it works. Everything is drawn here (flat buttons, dropdowns, switches, sliders).
 */
public class MenuScreen extends Screen {
	// ---- colours
	private static final int BG = 0xF2141416;
	private static final int SIDEBAR = 0xFF19191C;
	private static final int HEADER = 0xFF1D1D21;
	private static final int ACTIVE_ROW = 0xFF232328;
	private static final int LINE = 0xFF34343B;
	private static final int TEXT = 0xFFE8E8EC;
	private static final int DIM = 0xFF9C9CA6;
	private static final int ACCENT = 0xFF9B6BFF;
	private static final int BUTTON = 0xFF46396A;
	private static final int BUTTON_HOVER = 0xFF574683;
	private static final int FIELD = 0xFF2A2A30;
	private static final int FIELD_HOVER = 0xFF33333A;
	private static final int DANGER = 0xFF6A2F36;
	private static final int DANGER_HOVER = 0xFF853B44;

	private enum Page {
		LIVE("Live"), SCREENS("Screens"), FRIENDS("Friends"), SETTINGS("Settings"), HELP("How it works");

		final String title;

		Page(String title) {
			this.title = title;
		}
	}

	private static Page page = Page.LIVE;

	/** Something you can click: a rectangle and what happens. */
	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** An open dropdown: its options under the field at (x, y, w). */
	private record Dropdown(int x, int y, int w, List<String> options, IntConsumer pick) {
	}

	/** A slider being dragged. */
	private record Slider(int x, int y, int w, IntConsumer set) {
	}

	private final List<Hit> hits = new ArrayList<>();
	private final List<Slider> sliders = new ArrayList<>();
	private Dropdown dropdown;
	private Slider dragging;
	private int scroll;
	private int mouseX;
	private int mouseY;

	// ---- data loaded in the background
	private static List<String> cams = List.of();
	private static volatile boolean camsLoading;
	private static int monitors = 1;
	private static List<String> apps = List.of();
	private static List<String> soundApps = List.of();
	private static volatile boolean sourcesLoading;
	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

	/** Screen-share choices (kept while the menu is closed). */
	private static String source; // "monitor:<i>", "app:<title>", "app:*"
	private static String sound; // "all", "off", "only:<program>"

	public MenuScreen() {
		super(Component.literal("MultiMedia"));
		if (source == null) {
			String w = Settings.tvWindow();
			source = w == null ? "monitor:" + Settings.tvMonitor() : "app:" + w;
			sound = Settings.tvSound().startsWith("except:") ? "all" : Settings.tvSound();
		}
		if (cams.isEmpty() && !camsLoading) {
			loadCams();
		}
		if (!sourcesLoading) {
			loadSources();
		}
	}

	private static void loadCams() {
		camsLoading = true;
		Tools.ensure(MultiMediaClient::info).thenAccept(paths -> {
			List<String> found = TvSender.camDevices(paths);
			Minecraft.getInstance().execute(() -> {
				cams = found;
				camsLoading = false;
			});
		}).exceptionally(e -> {
			camsLoading = false;
			return null;
		});
	}

	private static void loadSources() {
		sourcesLoading = true;
		Tools.ensure(MultiMediaClient::info).thenAccept(paths -> {
			int n = Math.max(1, TvSender.monitorPreviews(paths).size());
			List<String> a = TvSender.appWindows();
			List<String> s = TvSender.audioPrograms();
			Minecraft.getInstance().execute(() -> {
				monitors = n;
				apps = a;
				soundApps = s;
				sourcesLoading = false;
			});
		}).exceptionally(e -> {
			sourcesLoading = false;
			return null;
		});
	}

	// ------------------------------------------------------------------ Layout

	private int panelW() {
		return Math.min(this.width - 16, 440);
	}

	private int panelH() {
		return Math.min(this.height - 16, 270);
	}

	private int panelX() {
		return (this.width - panelW()) / 2;
	}

	private int panelY() {
		return (this.height - panelH()) / 2;
	}

	private static final int SIDE_W = 104;
	private static final int HEAD_H = 24;

	// ------------------------------------------------------------------ Drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		super.extractRenderState(g, mx, my, a);
		this.mouseX = mx;
		this.mouseY = my;
		hits.clear();
		sliders.clear();
		int px = panelX();
		int py = panelY();
		int pw = panelW();
		int ph = panelH();
		// panel
		g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFF0B0B0C);
		g.fill(px, py, px + pw, py + ph, BG);
		g.fill(px, py, px + SIDE_W, py + ph, SIDEBAR);
		g.fill(px + SIDE_W, py, px + pw, py + HEAD_H, HEADER);
		g.fill(px, py, px + SIDE_W, py + HEAD_H, HEADER);
		g.text(this.font, "MultiMedia", px + 10, py + 8, TEXT, false);
		g.text(this.font, page.title, px + SIDE_W + 12, py + 8, TEXT, false);
		// status + close
		boolean live = TvSender.isSending() || TvSender.isCamOn();
		String status = live ? "● Live" + viewersText() : "● Idle";
		int sw = this.font.width(status);
		int closeX = px + pw - 22;
		g.text(this.font, status, closeX - sw - 8, py + 8, live ? 0xFFFF5B5B : DIM, false);
		boolean closeHover = hover(closeX, py + 4, 16, 16);
		g.fill(closeX, py + 4, closeX + 16, py + 20, closeHover ? FIELD_HOVER : FIELD);
		g.text(this.font, "×", closeX + 5, py + 8, TEXT, false);
		hits.add(new Hit(closeX, py + 4, 16, 16, this::onClose));
		// sidebar
		int y = py + HEAD_H + 6;
		for (Page p : Page.values()) {
			if (p == Page.HELP) {
				g.fill(px + 8, y + 2, px + SIDE_W - 8, y + 3, LINE);
				y += 8;
			}
			boolean on = p == page;
			boolean h = hover(px, y, SIDE_W, 18);
			if (on || h) {
				g.fill(px, y, px + SIDE_W, y + 18, on ? ACTIVE_ROW : 0xFF1F1F23);
			}
			if (on) {
				g.fill(px + 2, y + 2, px + 4, y + 16, ACCENT);
			}
			g.text(this.font, p.title, px + 10, y + 5, on ? ACCENT : TEXT, false);
			Page target = p;
			hits.add(new Hit(px, y, SIDE_W, 18, () -> {
				page = target;
				scroll = 0;
				dropdown = null;
			}));
			y += 20;
		}
		// content
		int cx = px + SIDE_W + 12;
		int cw = pw - SIDE_W - 24;
		int top = py + HEAD_H + 8;
		g.enableScissor(px + SIDE_W, py + HEAD_H, px + pw, py + ph);
		int firstContentHit = hits.size();
		int cy = top - scroll;
		switch (page) {
			case LIVE -> cy = drawLive(g, cx, cy, cw);
			case SCREENS -> cy = drawScreens(g, cx, cy, cw);
			case FRIENDS -> cy = drawFriends(g, cx, cy, cw);
			case SETTINGS -> cy = drawSettings(g, cx, cy, cw);
			case HELP -> cy = drawHelp(g, cx, cy, cw);
		}
		g.disableScissor();
		contentHeight = cy + scroll - top;
		// content scrolled out of view can't be clicked
		List<Hit> content = new ArrayList<>(hits.subList(firstContentHit, hits.size()));
		hits.subList(firstContentHit, hits.size()).clear();
		for (Hit hit : content) {
			if (hit.y() + hit.h() > py + HEAD_H && hit.y() < py + ph) {
				hits.add(hit);
			}
		}
		// dropdown on top
		if (dropdown != null) {
			drawDropdown(g);
		}
	}

	private int contentHeight;

	private String viewersText() {
		TvSender s = TvSender.current();
		int n = s != null && TvSender.isSending() ? s.viewerCount() : 0;
		return n > 0 ? " · " + n + " watching" : "";
	}

	private boolean hover(int x, int y, int w, int h) {
		return dropdown == null && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	private String fit(String s, int width) {
		if (this.font.width(s) <= width) {
			return s;
		}
		while (s.length() > 1 && this.font.width(s + "…") > width) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "…";
	}

	/** "——— Title ———" */
	private int section(GuiGraphicsExtractor g, int x, int y, int w, String title) {
		int tw = this.font.width(title);
		int mid = x + w / 2;
		g.fill(x, y + 4, mid - tw / 2 - 6, y + 5, LINE);
		g.fill(mid + tw / 2 + 6, y + 4, x + w, y + 5, LINE);
		g.text(this.font, title, mid - tw / 2, y, DIM, false);
		return y + 16;
	}

	private void label(GuiGraphicsExtractor g, int x, int y, String text) {
		g.text(this.font, text, x, y + 5, TEXT, false);
	}

	/** Big purple (or red) button over the whole width. */
	private int bigButton(GuiGraphicsExtractor g, int x, int y, int w, String text, boolean danger, boolean active, Runnable action) {
		boolean h = active && hover(x, y, w, 20);
		int fill = !active ? FIELD : danger ? (h ? DANGER_HOVER : DANGER) : (h ? BUTTON_HOVER : BUTTON);
		g.fill(x, y, x + w, y + 20, active ? (danger ? 0xFFC0505C : ACCENT) : LINE);
		g.fill(x + 1, y + 1, x + w - 1, y + 19, fill);
		int tw = this.font.width(text);
		g.text(this.font, text, x + (w - tw) / 2, y + 6, active ? TEXT : DIM, false);
		if (active) {
			hits.add(new Hit(x, y, w, 20, action));
		}
		return y + 26;
	}

	/** Small flat button; returns its width. */
	private int smallButton(GuiGraphicsExtractor g, int x, int y, String text, boolean danger, Runnable action) {
		int w = this.font.width(text) + 12;
		boolean h = hover(x, y, w, 16);
		g.fill(x, y, x + w, y + 16, danger ? (h ? DANGER_HOVER : DANGER) : (h ? FIELD_HOVER : FIELD));
		g.text(this.font, text, x + 6, y + 4, TEXT, false);
		hits.add(new Hit(x, y, w, 16, action));
		return w;
	}

	/** Dropdown field on the right half of the row. */
	private void field(GuiGraphicsExtractor g, int x, int y, int w, String value, List<String> options, IntConsumer pick) {
		boolean h = hover(x, y, w, 18);
		g.fill(x, y, x + w, y + 18, LINE);
		g.fill(x + 1, y + 1, x + w - 1, y + 17, h ? FIELD_HOVER : FIELD);
		g.text(this.font, fit(value, w - 22), x + 6, y + 5, TEXT, false);
		g.text(this.font, "▾", x + w - 12, y + 5, DIM, false);
		hits.add(new Hit(x, y, w, 18, () -> {
			if (!options.isEmpty()) {
				dropdown = new Dropdown(x, y + 18, w, options, pick);
			}
		}));
	}

	private void toggle(GuiGraphicsExtractor g, int x, int y, boolean on, Runnable flip) {
		g.fill(x, y + 2, x + 26, y + 16, on ? ACCENT : LINE);
		g.fill(x + 1, y + 3, x + 25, y + 15, on ? BUTTON : FIELD);
		int knob = on ? x + 14 : x + 2;
		g.fill(knob, y + 4, knob + 10, y + 14, on ? TEXT : 0xFF8A8A92);
		hits.add(new Hit(x, y, 26, 18, flip));
	}

	private void slider(GuiGraphicsExtractor g, int x, int y, int w, int percent, IntConsumer set) {
		g.fill(x, y + 7, x + w, y + 10, LINE);
		int filled = x + (int) Math.round(w * percent / 100.0);
		g.fill(x, y + 7, filled, y + 10, ACCENT);
		g.fill(filled - 3, y + 3, filled + 3, y + 14, TEXT);
		String t = percent + "%";
		g.text(this.font, t, x + w + 6, y + 4, DIM, false);
		Slider s = new Slider(x, y, w, set);
		sliders.add(s);
		hits.add(new Hit(x - 3, y, w + 6, 18, () -> {
			dragging = s;
			applySlider(s, mouseX);
		}));
	}

	private void applySlider(Slider s, double mx) {
		int v = (int) Math.round(Math.max(0, Math.min(1, (mx - s.x()) / s.w())) * 100);
		s.set().accept(v);
	}

	private void drawDropdown(GuiGraphicsExtractor g) {
		Dropdown d = dropdown;
		int rowH = 14;
		int max = Math.min(d.options().size(), Math.max(3, (this.height - d.y() - 4) / rowH));
		g.fill(d.x(), d.y(), d.x() + d.w(), d.y() + max * rowH + 2, LINE);
		g.fill(d.x() + 1, d.y(), d.x() + d.w() - 1, d.y() + max * rowH + 1, 0xFF202025);
		for (int i = 0; i < max; i++) {
			int ry = d.y() + 1 + i * rowH;
			boolean h = mouseX >= d.x() && mouseX < d.x() + d.w() && mouseY >= ry && mouseY < ry + rowH;
			if (h) {
				g.fill(d.x() + 1, ry, d.x() + d.w() - 1, ry + rowH, FIELD_HOVER);
			}
			g.text(this.font, fit(d.options().get(i), d.w() - 10), d.x() + 6, ry + 3, TEXT, false);
		}
	}

	// ------------------------------------------------------------------ Live

	private int drawLive(GuiGraphicsExtractor g, int x, int y, int w) {
		int fieldX = x + w / 2 - 10;
		int fieldW = w - (w / 2 - 10);
		// ---- Camera
		y = section(g, x, y, w, "Camera");
		label(g, x, y, "Device");
		String current = Settings.camDevice();
		String camValue = camsLoading ? "Looking …" : cams.isEmpty() ? "No webcam found" : (current != null ? current : cams.get(0));
		List<String> camOptions = new ArrayList<>(cams);
		field(g, fieldX, y, fieldW, "● " + camValue, camOptions, i -> {
			String dev = camOptions.get(i);
			Settings.setCamDevice(dev);
			if (TvSender.isCamOn()) {
				TvSender.startCam(dev, MultiMediaClient::status, () -> MultiMediaClient.status("Webcam switched to " + dev + "."));
			}
		});
		y += 24;
		boolean camOn = TvSender.isCamOn();
		y = bigButton(g, x, y, w, camOn ? "Turn off camera" : "Turn on camera", camOn, !cams.isEmpty() || camOn, () -> {
			if (TvSender.isCamOn()) {
				TvSender.stopCam();
				MultiMediaClient.status("Webcam off.");
			} else {
				MultiMediaClient.camOn();
			}
		});
		y += 4;

		// ---- Screen share
		y = section(g, x, y, w, "Screen share");
		label(g, x, y, "Source");
		List<String> srcKeys = new ArrayList<>();
		List<String> srcNames = new ArrayList<>();
		for (int i = 0; i < monitors; i++) {
			srcKeys.add("monitor:" + i);
			srcNames.add(WINDOWS ? "Monitor " + (i + 1) : monitors == 1 ? "Whole screen" : "Monitor " + (i + 1));
		}
		if (WINDOWS) {
			for (String app : apps) {
				srcKeys.add("app:" + app);
				srcNames.add("App: " + app);
			}
		} else {
			srcKeys.add("app:*");
			srcNames.add("One app (pick in the popup)");
		}
		int si = srcKeys.indexOf(source);
		String srcValue = sourcesLoading && si < 0 ? "Looking …" : si >= 0 ? srcNames.get(si)
				: source.startsWith("app:") ? "App: " + source.substring(4) : "Monitor 1";
		field(g, fieldX, y, fieldW, "▣ " + srcValue, srcNames, i -> {
			source = srcKeys.get(i);
			// sharing one app on Windows: send just its sound
			if (WINDOWS && source.startsWith("app:")) {
				String exe = TvSender.appExe(source.substring(4));
				if (exe != null && !"off".equals(sound)) {
					sound = "only:" + exe;
				}
			}
		});
		y += 22;
		label(g, x, y, "Audio");
		List<String> sndKeys = new ArrayList<>(List.of("all", "off"));
		List<String> sndNames = new ArrayList<>(List.of("Whole PC", "No sound"));
		for (String p : soundApps) {
			sndKeys.add("only:" + p);
			sndNames.add(p);
		}
		if (sound.startsWith("only:") && !sndKeys.contains(sound)) {
			sndKeys.add(sound);
			sndNames.add(sound.substring(5));
		}
		int ai = Math.max(0, sndKeys.indexOf(sound));
		field(g, fieldX, y, fieldW, "♪ " + sndNames.get(ai), sndNames, i -> sound = sndKeys.get(i));
		y += 22;
		label(g, x, y, "Who can watch");
		Set<String> invited = Settings.tvInvited();
		smallButton(g, fieldX, y + 1, invited.isEmpty() ? "Pick friends …" : invited.size() + " friend" + (invited.size() == 1 ? "" : "s") + " – change",
				false, () -> {
					page = Page.FRIENDS;
					scroll = 0;
				});
		y += 24;
		boolean sending = TvSender.isSending();
		y = bigButton(g, x, y, w, sending ? "Stop sharing" : "Share screen", sending, true, () -> {
			if (TvSender.isSending()) {
				TvSender.stopScreen();
				MultiMediaClient.status(T.t("Senden beendet."));
			} else {
				startSharing();
			}
		});
		if (sending) {
			y = bigButton(g, x, y, w, "Apply changes (viewers stay connected)", false, true, this::applySharing);
		}
		return y;
	}

	private void saveShareChoices() {
		int monitor = source.startsWith("monitor:") ? Integer.parseInt(source.substring(8)) : Settings.tvMonitor();
		Settings.setTvMonitor(monitor);
		Settings.setTvWindow(source.startsWith("app:") ? source.substring(4) : null);
		Settings.setTvSound(sound);
	}

	private void startSharing() {
		saveShareChoices();
		int monitor = Settings.tvMonitor();
		String snd = Settings.tvSound();
		MultiMediaClient.status("Starting screen share …");
		TvSender.start(monitor, snd, MultiMediaClient::status, () -> {
			TvSender s = TvSender.current();
			MultiMediaClient.status("You're sharing your screen (" + (s != null ? s.encoderName() + ", " + s.soundInfo() : "?") + ").");
			if (s != null && !s.networkInfo().isEmpty()) {
				MultiMediaClient.error(s.networkInfo());
			}
			Minecraft.getInstance().execute(() -> {
				List<String> online = onlinePlayers();
				List<String> to = new ArrayList<>();
				for (String p : Settings.tvInvited()) {
					if (online.stream().anyMatch(p::equalsIgnoreCase)) {
						to.add(p);
					}
				}
				if (!to.isEmpty()) {
					TvInvite.send(to);
				}
			});
		});
	}

	private void applySharing() {
		saveShareChoices();
		TvSender.change(Settings.tvMonitor(), Settings.tvSound(), MultiMediaClient::status);
	}

	// ------------------------------------------------------------------ Screens

	private int drawScreens(GuiGraphicsExtractor g, int x, int y, int w) {
		y = section(g, x, y, w, "Floating screens");
		List<FloatingScreens.FloatData> own = FloatingScreens.own();
		if (own.isEmpty()) {
			g.text(this.font, "No screens yet. Place one where you're looking:", x, y + 2, DIM, false);
			y += 16;
		}
		for (FloatingScreens.FloatData d : new ArrayList<>(own)) {
			String vis = switch (d.visibility) {
				case "all" -> "everyone";
				case "friends" -> d.friends.size() + " friend" + (d.friends.size() == 1 ? "" : "s");
				default -> "only you";
			};
			g.fill(x, y, x + w, y + 20, FIELD);
			g.text(this.font, fit(d.describe(), w - 150), x + 6, y + 6, TEXT, false);
			g.text(this.font, vis, x + w - 146, y + 6, DIM, false);
			int bx = x + w - 92;
			bx += smallButton(g, bx, y + 2, "Edit", false, () ->
					Minecraft.getInstance().gui.setScreen(new FloatEditScreen(this, d, false))) + 2;
			bx += smallButton(g, bx, y + 2, "Here", false, () -> {
				FloatingScreens.placeInFront(d);
				FloatingScreens.saved(d);
			}) + 2;
			smallButton(g, bx, y + 2, "×", true, () -> FloatingScreens.remove(d));
			y += 24;
		}
		y += 2;
		boolean room = own.size() < FloatingScreens.MAX_OWN;
		y = bigButton(g, x, y, w, room ? "New screen where I'm looking" : "Screen limit reached", false, room, () -> {
			FloatingScreens.FloatData d = FloatingScreens.create();
			if (d != null) {
				Minecraft.getInstance().gui.setScreen(new FloatEditScreen(this, d, true));
			}
		});
		int others = FloatingScreens.othersCount();
		if (others > 0) {
			g.text(this.font, others + " screen" + (others == 1 ? "" : "s") + " from friends nearby", x, y + 2, DIM, false);
			y += 14;
		}
		g.text(this.font, "Tip: right-click a screen in the world to control it.", x, y + 2, DIM, false);
		return y + 14;
	}

	// ------------------------------------------------------------------ Friends

	private int drawFriends(GuiGraphicsExtractor g, int x, int y, int w) {
		// ---- requests to control your PC
		List<String> pending = RemoteControl.pending();
		List<String> allowed = RemoteControl.allowed();
		if (!pending.isEmpty() || !allowed.isEmpty()) {
			y = section(g, x, y, w, "Remote control");
			for (String p : pending) {
				label(g, x, y, fit(p + " wants to control your PC", w - 110));
				int bx = x + w - 100;
				bx += smallButton(g, bx, y + 1, "Allow", false, () -> RemoteControl.allow(p)) + 4;
				smallButton(g, bx, y + 1, "Deny", true, () -> RemoteControl.deny(p));
				y += 22;
			}
			for (String p : allowed) {
				label(g, x, y, fit("⚠ " + p + " can control your PC", w - 70));
				smallButton(g, x + w - 56, y + 1, "Revoke", true, () -> RemoteControl.revoke(p));
				y += 22;
			}
			if (!allowed.isEmpty()) {
				y = bigButton(g, x, y, w, "Turn remote control off for everyone", true, true, RemoteControl::stopAll);
			}
		}
		// ---- invites you got
		Map<String, String> invites = TvInvite.pending();
		if (!invites.isEmpty()) {
			y = section(g, x, y, w, "Invites");
			for (Map.Entry<String, String> e : invites.entrySet()) {
				String name = e.getKey();
				String code = e.getValue();
				label(g, x, y, fit(name + " invites you to their screen", w - 70));
				smallButton(g, x + w - 56, y + 1, "Accept", false, () -> {
					if (TvInvite.accept(code)) {
						MultiMediaClient.status("Invite from " + name + " accepted – you can now see their screen and webcam.");
					}
				});
				y += 22;
			}
		}
		// ---- who can watch you
		y = section(g, x, y, w, "Who can watch your screen and webcam");
		List<String> players = onlinePlayers();
		Set<String> invited = new LinkedHashSet<>(Settings.tvInvited());
		if (players.isEmpty()) {
			g.text(this.font, "Nobody else is online.", x, y + 2, DIM, false);
			y += 16;
		}
		for (String p : players) {
			boolean on = invited.stream().anyMatch(p::equalsIgnoreCase);
			label(g, x, y, fit(p, w - 40) + (ModPeers.has(p) ? "" : "  (mod?)"));
			toggle(g, x + w - 26, y, on, () -> {
				Set<String> next = new LinkedHashSet<>(Settings.tvInvited());
				if (on) {
					next.removeIf(p::equalsIgnoreCase);
				} else {
					next.add(p);
					TvInvite.send(List.of(p)); // they get an [Accept] once
				}
				Settings.setTvInvited(next);
			});
			y += 20;
		}
		g.text(this.font, "Turning someone on sends them an invite to accept.", x, y + 4, DIM, false);
		return y + 16;
	}

	private static List<String> onlinePlayers() {
		Minecraft mc = Minecraft.getInstance();
		List<String> out = new ArrayList<>();
		var conn = mc.getConnection();
		if (conn != null) {
			for (PlayerInfo p : conn.getOnlinePlayers()) {
				String name = p.getProfile().name();
				if (!name.equalsIgnoreCase(mc.getUser().getName())) {
					out.add(name);
				}
			}
		}
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	// ------------------------------------------------------------------ Settings

	private int drawSettings(GuiGraphicsExtractor g, int x, int y, int w) {
		int fieldX = x + w / 2 - 10;
		int fieldW = w - (w / 2 - 10);
		y = section(g, x, y, w, "Sound");
		label(g, x, y, "Volume");
		slider(g, fieldX, y, fieldW - 30, Settings.masterVolume(), Settings::setMasterVolume);
		y += 24;
		y = section(g, x, y, w, "Picture");
		label(g, x, y, "Quality");
		List<String> q = List.of("Normal (480p)", "High (720p)", "Very high (1080p)");
		field(g, fieldX, y, fieldW, q.get(Settings.videoQuality()), q, Settings::setVideoQuality);
		y += 20;
		if (Settings.videoQuality() != VideoPlayer.QUALITY_AT_START) {
			g.text(this.font, "Restart Minecraft for screens to use the new quality.", x, y + 2, 0xFFFFC060, false);
			y += 14;
		}
		y += 6;
		y = section(g, x, y, w, "More");
		y = bigButton(g, x, y, w, "All MultiMedia settings …", false, true, () ->
				Minecraft.getInstance().gui.setScreen(new SettingsScreen(this)));
		g.text(this.font, "Menu key: M (change it under Options → Controls).", x, y + 2, DIM, false);
		return y + 14;
	}

	// ------------------------------------------------------------------ Help

	private int drawHelp(GuiGraphicsExtractor g, int x, int y, int w) {
		String[] lines = {
				"Screens: build a wall from one block type with a sign",
				"saying \"screen\" under it, then paste a YouTube link (Ctrl+V).",
				"Or place a floating screen on the Screens page.",
				"",
				"Your screen: Live → Share screen. Friends you turned on",
				"(Friends page) get an invite and see it on screens with",
				"\"tv:" + Minecraft.getInstance().getUser().getName() + "\" or a floating screen showing it.",
				"",
				"Webcam: Live → Turn on camera. It floats above your head.",
				"",
				"Right-click any screen to pause, change volume, or ask",
				"to control a friend's PC (they have to allow it).",
				"",
				"Everyone needs this mod; the server doesn't.",
		};
		for (String l : lines) {
			g.text(this.font, fit(l, w), x, y, l.isEmpty() ? DIM : TEXT, false);
			y += 11;
		}
		return y;
	}

	// ------------------------------------------------------------------ Input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (dropdown != null) {
			Dropdown d = dropdown;
			dropdown = null;
			int rowH = 14;
			int max = Math.min(d.options().size(), Math.max(3, (this.height - d.y() - 4) / rowH));
			if (mx >= d.x() && mx < d.x() + d.w() && my >= d.y() && my < d.y() + max * rowH + 1) {
				int i = (int) ((my - d.y() - 1) / rowH);
				if (i >= 0 && i < max) {
					d.pick().accept(i);
				}
			}
			return true;
		}
		for (Hit h : new ArrayList<>(hits)) {
			if (h.contains(mx, my)) {
				h.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging != null) {
			applySlider(dragging, event.x());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = null;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		int visible = panelH() - HEAD_H - 12;
		int maxScroll = Math.max(0, contentHeight - visible);
		scroll = (int) Math.max(0, Math.min(maxScroll, scroll - scrollY * 16));
		dropdown = null;
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
