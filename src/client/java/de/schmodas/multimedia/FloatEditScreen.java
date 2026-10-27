package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edit one floating screen: what it shows (YouTube link, your screen share, your webcam, a friend's screen),
 * size, rotation, tilt, position and who can see it. Changes show live; Save sends them to the others.
 */
public class FloatEditScreen extends Screen {
	private final Screen parent;
	private final FloatingScreens.FloatData screen;
	private final FloatingScreens.FloatData backup;
	private final boolean isNew;
	private EditBox content;
	private String error;
	private final List<String> players = new ArrayList<>();

	FloatEditScreen(Screen parent, FloatingScreens.FloatData screen, boolean isNew) {
		super(Component.literal(isNew ? "New floating screen" : "Edit floating screen"));
		this.parent = parent;
		this.screen = screen;
		this.backup = screen.copy();
		this.isNew = isNew;
		Minecraft mc = Minecraft.getInstance();
		var conn = mc.getConnection();
		if (conn != null) {
			for (PlayerInfo p : conn.getOnlinePlayers()) {
				String name = p.getProfile().name();
				if (!name.equalsIgnoreCase(mc.getUser().getName())) {
					players.add(name);
				}
			}
		}
		players.sort(String.CASE_INSENSITIVE_ORDER);
		for (String f : screen.friends) {
			if (players.stream().noneMatch(f::equalsIgnoreCase)) {
				players.add(f); // offline friends stay in the list
			}
		}
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int left = cx - 155;
		int y = 30;
		String me = Minecraft.getInstance().getUser().getName();

		// ---- What it shows
		String old = content == null ? displayContent() : content.getValue();
		content = new EditBox(this.font, left, y, 310, 20, Component.literal("YouTube link"));
		content.setMaxLength(300);
		content.setValue(old);
		content.setHint(Component.literal("Paste a YouTube link (Ctrl+V), or tv:Name / cam:Name"));
		this.addRenderableWidget(content);
		y += 24;
		this.addRenderableWidget(Button.builder(Component.literal("My screen share"), b -> content.setValue("tv:" + me))
				.bounds(left, y, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("My webcam"), b -> content.setValue("cam:" + me))
				.bounds(left + 105, y, 100, 20).build());
		List<String> invitedBy = new ArrayList<>();
		for (String p : players) {
			if (Settings.tvSource(p) != null) {
				invitedBy.add(p);
			}
		}
		Button friend = Button.builder(Component.literal(invitedBy.isEmpty() ? "Friend's screen" : "Friend's screen ▸"), b -> {
			String cur = content.getValue();
			int i = -1;
			for (int k = 0; k < invitedBy.size(); k++) {
				if (cur.equalsIgnoreCase("tv:" + invitedBy.get(k))) {
					i = k;
				}
			}
			content.setValue("tv:" + invitedBy.get((i + 1) % invitedBy.size()));
		}).bounds(left + 210, y, 100, 20).tooltip(Tooltip.create(Component.literal(invitedBy.isEmpty()
				? "Accept a friend's invite first (menu → Invites)"
				: "Cycles through friends whose invite you accepted"))).build();
		friend.active = !invitedBy.isEmpty();
		this.addRenderableWidget(friend);
		y += 34;

		// ---- Size / rotation / tilt
		y = stepper(y, "Size", () -> String.format(Locale.ROOT, "%.1f blocks wide", screen.width),
				() -> screen.width = Math.max(0.5F, screen.width - 0.5F),
				() -> screen.width = Math.min(FloatingScreens.MAX_WIDTH, screen.width + 0.5F));
		y = stepper(y, "Turn", () -> Math.round(((screen.yaw % 360) + 360) % 360) + "°",
				() -> screen.yaw -= 15, () -> screen.yaw += 15);
		y = stepper(y, "Tilt", () -> Math.round(screen.tilt) + "°",
				() -> screen.tilt = Math.max(-90, screen.tilt - 5), () -> screen.tilt = Math.min(90, screen.tilt + 5));
		this.addRenderableWidget(Button.builder(Component.literal("Move to where I'm looking"), b -> FloatingScreens.placeInFront(screen))
				.bounds(left, y, 152, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Turn to face me"), b -> FloatingScreens.faceMe(screen))
				.bounds(cx + 3, y, 152, 20).build());
		y += 30;

		// ---- Who sees it
		this.addRenderableWidget(Button.builder(Component.literal("Visible to: " + visibilityLabel()), b -> {
			screen.visibility = switch (screen.visibility) {
				case "me" -> "friends";
				case "friends" -> "all";
				default -> "me";
			};
			rebuildWidgets();
		}).bounds(left, y, 310, 20).tooltip(Tooltip.create(Component.literal(
				"Only you · friends you tick below · everyone with the mod on this server"))).build());
		y += 24;
		if (screen.visibility.equals("friends")) {
			int col = 0;
			for (String p : players.subList(0, Math.min(players.size(), 9))) {
				String name = p;
				this.addRenderableWidget(Button.builder(friendLabel(name), b -> {
					if (!screen.friends.removeIf(name::equalsIgnoreCase)) {
						screen.friends.add(name);
					}
					b.setMessage(friendLabel(name));
				}).bounds(left + col * 104, y, 100, 20).build());
				if (++col == 3) {
					col = 0;
					y += 24;
				}
			}
			if (col != 0) {
				y += 24;
			}
			if (players.isEmpty()) {
				y += 12;
			}
		}

		// ---- Save / cancel
		int bottom = Math.max(y + 8, this.height - 28);
		this.addRenderableWidget(Button.builder(Component.literal("✔ Save"), b -> save()).bounds(left, bottom, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(cx - 50, bottom, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("✖ Delete"), b -> {
			FloatingScreens.remove(screen);
			Minecraft.getInstance().gui.setScreen(parent);
		}).bounds(cx + 55, bottom, 100, 20).build());
	}

	/** "Label   [-]  value  [+]" row; returns the next y. */
	private int stepper(int y, String name, java.util.function.Supplier<String> value, Runnable minus, Runnable plus) {
		int cx = this.width / 2;
		int left = cx - 155;
		Button v = Button.builder(Component.literal(name + ": " + value.get()), b -> {
		}).bounds(left + 28, y, 254, 20).build();
		v.active = false;
		this.addRenderableWidget(Button.builder(Component.literal("−"), b -> {
			minus.run();
			v.setMessage(Component.literal(name + ": " + value.get()));
		}).bounds(left, y, 24, 20).build());
		this.addRenderableWidget(v);
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			plus.run();
			v.setMessage(Component.literal(name + ": " + value.get()));
		}).bounds(left + 286, y, 24, 20).build());
		return y + 24;
	}

	private String visibilityLabel() {
		return switch (screen.visibility) {
			case "friends" -> "friends I pick";
			case "all" -> "everyone with the mod";
			default -> "only me";
		};
	}

	private Component friendLabel(String name) {
		boolean on = screen.friends.stream().anyMatch(name::equalsIgnoreCase);
		return Component.literal((on ? "✔ " : "") + name);
	}

	/** Content as the user would type it (a full YouTube link instead of the stored spec). */
	private String displayContent() {
		if (screen.content.isBlank() || screen.content.startsWith("tv:") || screen.content.startsWith("cam:")) {
			return screen.content;
		}
		Source s = Source.parse(screen.content);
		return s == null ? "" : s.url();
	}

	private void save() {
		String text = content.getValue().trim();
		String spec;
		if (text.isEmpty()) {
			spec = "";
		} else if (text.regionMatches(true, 0, "tv:", 0, 3) || text.regionMatches(true, 0, "cam:", 0, 4)) {
			spec = text.substring(0, text.indexOf(':')).toLowerCase(Locale.ROOT) + text.substring(text.indexOf(':'));
		} else {
			Source s = Source.parse(text);
			if (s == null || s.isLive()) {
				error = Source.looksLikeOtherLink(text) ? "Only YouTube links work (for safety)." : "That's not a YouTube link.";
				return;
			}
			// keep the position if it's the same video, otherwise start from the beginning for everyone
			Source before = Source.parse(screen.content);
			if (before != null && before.key().equals(s.key())) {
				spec = screen.content;
			} else {
				long now = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime() / 20;
				spec = new Source(s.url(), s.playlist(), s.shuffle(), s.loop(), now, Double.NaN, 0).spec();
			}
		}
		if (!FloatingScreens.safeContent(spec)) {
			error = "Use tv:Name or cam:Name with a real player name.";
			return;
		}
		screen.content = spec;
		FloatingScreens.saved(screen);
		Minecraft.getInstance().gui.setScreen(parent);
	}

	@Override
	public void onClose() {
		if (isNew) {
			FloatingScreens.remove(screen);
		} else {
			// undo live changes
			screen.x = backup.x;
			screen.y = backup.y;
			screen.z = backup.z;
			screen.yaw = backup.yaw;
			screen.tilt = backup.tilt;
			screen.width = backup.width;
			screen.visibility = backup.visibility;
			screen.friends = new ArrayList<>(backup.friends);
			screen.dim = backup.dim;
		}
		Minecraft.getInstance().gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		if (error != null) {
			g.centeredText(this.font, Component.literal(error), this.width / 2, 20, 0xFFFF5555);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
