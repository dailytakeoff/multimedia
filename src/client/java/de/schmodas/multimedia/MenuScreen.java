package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The main MultiMedia menu (hotkey M or /mm menu): share your screen, webcam on/off and which camera,
 * floating screens, remote-control requests, invites and settings – everything without typing commands.
 */
public class MenuScreen extends Screen {
	private record Label(String text, int x, int y, int color) {
	}

	private final List<Label> labels = new ArrayList<>();
	private static List<String> cams = List.of();
	private static volatile boolean camsLoading;
	private String state = "";

	public MenuScreen() {
		super(Component.literal("MultiMedia"));
		if (cams.isEmpty() && !camsLoading) {
			loadCams();
		}
	}

	private void loadCams() {
		camsLoading = true;
		Tools.ensure(MultiMediaClient::info).thenAccept(paths -> {
			List<String> found = TvSender.camDevices(paths);
			Minecraft.getInstance().execute(() -> {
				cams = found;
				camsLoading = false;
				if (Minecraft.getInstance().gui.screen() == this) {
					rebuildWidgets();
				}
			});
		}).exceptionally(e -> {
			camsLoading = false;
			return null;
		});
	}

	/** Changes when something shown in the menu changes (requests, invites, sending …). */
	private static String state() {
		return TvSender.isSending() + "|" + TvSender.isCamOn() + "|" + RemoteControl.pending() + "|" + RemoteControl.allowed()
				+ "|" + TvInvite.pending().keySet() + "|" + FloatingScreens.own().size() + "|" + cams;
	}

	private void label(String text, int y, int color) {
		labels.add(new Label(text, this.width / 2, y, color));
	}

	@Override
	protected void init() {
		labels.clear();
		state = state();
		int cx = this.width / 2;
		int left = cx - 155;
		int y = 28;

		// ---- Share screen
		label("Share your screen", y, 0xFFFFFFFF);
		y += 12;
		if (TvSender.isSending()) {
			TvSender s = TvSender.current();
			int n = s == null ? 0 : s.viewerCount();
			this.addRenderableWidget(Button.builder(Component.literal("⚙ Change / invite"), b ->
					Minecraft.getInstance().gui.setScreen(new SendScreen(true))).bounds(left, y, 150, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("■ Stop sharing (" + n + " watching)"), b -> {
				TvSender.stopScreen();
				rebuildWidgets();
			}).bounds(cx + 5, y, 150, 20).build());
		} else {
			this.addRenderableWidget(Button.builder(Component.literal("▶ Share screen …"), b ->
					Minecraft.getInstance().gui.setScreen(new SendScreen())).bounds(left, y, 310, 20)
					.tooltip(Tooltip.create(Component.literal("Pick a monitor and the friends who may watch. Show it on a floating screen "
							+ "(below) or a wall screen with a \"screen tv:" + Minecraft.getInstance().getUser().getName() + "\" sign.")))
					.build());
		}
		y += 30;

		// ---- Webcam
		label("Webcam", y, 0xFFFFFFFF);
		y += 12;
		boolean on = TvSender.isCamOn();
		this.addRenderableWidget(Button.builder(Component.literal(on ? "● Webcam on – turn off" : "○ Webcam off – turn on"), b -> {
			if (TvSender.isCamOn()) {
				TvSender.stopCam();
				MultiMediaClient.status("Webcam off.");
			} else {
				MultiMediaClient.camOn();
			}
			rebuildWidgets();
		}).bounds(left, y, 150, 20).tooltip(Tooltip.create(Component.literal(
				"Shows your webcam above your head for friends you've invited (within 40 blocks)."))).build());
		String current = Settings.camDevice();
		String camLabel = camsLoading ? "Camera: looking …" : cams.isEmpty() ? "Camera: none found"
				: "Camera: " + shorten(current != null ? current : cams.get(0) + " (auto)");
		Button cam = Button.builder(Component.literal(camLabel), b -> {
			if (cams.isEmpty()) {
				loadCams();
				return;
			}
			int i = current == null ? -1 : cams.indexOf(current);
			String next = cams.get((i + 1) % cams.size());
			Settings.setCamDevice(next);
			if (TvSender.isCamOn()) {
				TvSender.startCam(next, MultiMediaClient::status, () -> MultiMediaClient.status("Webcam switched to " + next + "."));
			}
			rebuildWidgets();
		}).bounds(cx + 5, y, 150, 20).tooltip(Tooltip.create(Component.literal("Click to switch camera"))).build();
		cam.active = !camsLoading;
		this.addRenderableWidget(cam);
		y += 30;

		// ---- Floating screens
		label("Floating screens", y, 0xFFFFFFFF);
		y += 12;
		int count = FloatingScreens.own().size();
		this.addRenderableWidget(Button.builder(Component.literal("▣ Your floating screens (" + count + ") …"), b ->
				Minecraft.getInstance().gui.setScreen(new FloatListScreen(this))).bounds(left, y, 150, 20).build());
		Button place = Button.builder(Component.literal("+ New screen here"), b -> {
			FloatingScreens.FloatData d = FloatingScreens.create();
			if (d != null) {
				Minecraft.getInstance().gui.setScreen(new FloatEditScreen(this, d, true));
			}
		}).bounds(cx + 5, y, 150, 20).tooltip(Tooltip.create(Component.literal("Places a screen where you're looking"))).build();
		place.active = count < FloatingScreens.MAX_OWN;
		this.addRenderableWidget(place);
		y += 30;

		// ---- Remote control
		List<String> pending = RemoteControl.pending();
		List<String> allowed = RemoteControl.allowed();
		label("Remote control of your shared screen", y, 0xFFFFFFFF);
		y += 12;
		if (pending.isEmpty() && allowed.isEmpty()) {
			label("Nobody is asking. Friends ask by right-clicking your screen.", y + 4, 0xFF888888);
			y += 18;
		}
		for (String p : pending.subList(0, Math.min(3, pending.size()))) {
			String name = p;
			label("", y, 0);
			this.addRenderableWidget(Button.builder(Component.literal("✔ Let " + name + " control"), b -> {
				RemoteControl.allow(name);
				rebuildWidgets();
			}).bounds(left, y, 150, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("✖ Deny " + name), b -> {
				RemoteControl.deny(name);
				rebuildWidgets();
			}).bounds(cx + 5, y, 150, 20).build());
			y += 24;
		}
		for (String p : allowed.subList(0, Math.min(3, allowed.size()))) {
			String name = p;
			this.addRenderableWidget(Button.builder(Component.literal("⚠ " + name + " can control – revoke"), b -> {
				RemoteControl.revoke(name);
				rebuildWidgets();
			}).bounds(left, y, 310, 20).build());
			y += 24;
		}
		if (!allowed.isEmpty()) {
			this.addRenderableWidget(Button.builder(Component.literal("Turn remote control off for everyone"), b -> {
				RemoteControl.stopAll();
				rebuildWidgets();
			}).bounds(left, y, 310, 20).build());
			y += 24;
		}
		y += 6;

		// ---- Invites
		Map<String, String> invites = TvInvite.pending();
		if (!invites.isEmpty()) {
			label("Invites", y, 0xFFFFFFFF);
			y += 12;
			int col = 0;
			for (Map.Entry<String, String> e : invites.entrySet()) {
				String code = e.getValue();
				String name = e.getKey();
				this.addRenderableWidget(Button.builder(Component.literal("✔ Accept " + name), b -> {
					if (TvInvite.accept(code)) {
						MultiMediaClient.status("Invite from " + name + " accepted – you can now see their screen and webcam.");
					}
					rebuildWidgets();
				}).bounds(col == 0 ? left : cx + 5, y, 150, 20).build());
				col++;
				if (col == 2) {
					col = 0;
					y += 24;
				}
				if (y > this.height - 60) {
					break;
				}
			}
			if (col != 0) {
				y += 24;
			}
		}

		// ---- Quality
		int q = Settings.videoQuality();
		String[] names = {"Normal (480p)", "High (720p)", "Very high (1080p)"};
		this.addRenderableWidget(Button.builder(Component.literal("Quality: " + names[q] + (q != VideoPlayer.QUALITY_AT_START ? " – restart" : "")), b -> {
			Settings.setVideoQuality((Settings.videoQuality() + 1) % 3);
			rebuildWidgets();
		}).bounds(left, y, 310, 20).tooltip(Tooltip.create(Component.literal(
				"Sharper videos and screen shares, but more upload, CPU and graphics memory. Videos on screens change after "
						+ "restarting Minecraft; your screen share uses it the next time you start sharing."))).build());
		y += 24;

		// ---- Bottom
		int bottom = Math.max(y + 6, this.height - 28);
		this.addRenderableWidget(Button.builder(Component.literal("Settings …"), b ->
				Minecraft.getInstance().gui.setScreen(new SettingsScreen(this))).bounds(left, bottom, 150, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
				.bounds(cx + 5, bottom, 150, 20).build());
	}

	private static String shorten(String s) {
		return s.length() > 18 ? s.substring(0, 17) + "…" : s;
	}

	@Override
	public void tick() {
		super.tick();
		if (!state().equals(state)) {
			rebuildWidgets();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		for (Label l : labels) {
			if (!l.text().isEmpty()) {
				g.centeredText(this.font, Component.literal(l.text()), l.x(), l.y(), l.color());
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
