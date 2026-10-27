package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Touch screen: opens when you right-click an in-world screen. Shows the screen big, with
 * - a YouTube video: tap = pause/play, plus buttons for previous/next track and restart (for everyone, like the sign);
 * - a friend's screen share: [Request control]; once they allow it, your clicks, scrolling and typing on the
 *   picture go to their PC. Esc closes; the [Esc] button sends Escape to their PC.
 */
public class TouchScreen extends Screen {
	private static final int TEX_W = VideoPlayer.WIDTH;
	private static final int TEX_H = VideoPlayer.HEIGHT;

	private final BlockPos signPos;
	private final String spec;
	private final ScreenSource source;
	/** YouTube video on this screen's own sign (controllable), or null. */
	private final Source video;
	private final LiveFeed feed;
	private long lastMove;
	private double lastU = -1;
	private double lastV = -1;
	private String lastControl = "";
	private Button requestButton;

	TouchScreen(BlockPos signPos, String spec, ScreenSource source, Source video, double tapU, double tapV) {
		super(Component.literal("Touch screen"));
		this.signPos = signPos;
		this.spec = spec;
		this.source = source;
		this.video = video != null && !video.isLive() ? video : null;
		this.feed = source instanceof LivePlayer lp ? lp.feed() : null;
	}

	private boolean remote() {
		return feed != null && !feed.cam;
	}

	private boolean controlling() {
		return remote() && feed.controlState().equals("granted") && feed.canSendInput();
	}

	// ------------------------------------------------------------------ Layout

	/** Picture area: as big as fits above the buttons, 16:9. */
	private int[] area() {
		int maxW = this.width - 20;
		int maxH = this.height - 64;
		int w = maxW;
		int h = w * 9 / 16;
		if (h > maxH) {
			h = maxH;
			w = h * 16 / 9;
		}
		int x = (this.width - w) / 2;
		int y = 24;
		return new int[]{x, y, w, h};
	}

	/** Mouse position -> 0..1 on the picture, or null outside it. */
	private double[] uv(double mx, double my) {
		int[] a = area();
		double u = (mx - a[0]) / a[2];
		double v = (my - a[1]) / a[3];
		if (u < 0 || u > 1 || v < 0 || v > 1) {
			return null;
		}
		return new double[]{u, v};
	}

	@Override
	protected void init() {
		int y = this.height - 30;
		int cx = this.width / 2;
		if (video != null) {
			boolean paused = video.isPaused();
			if (video.playlist()) {
				this.addRenderableWidget(Button.builder(Component.literal("◀◀"), b -> action("prev"))
						.bounds(cx - 154, y, 44, 20).tooltip(Tooltip.create(Component.literal("Previous track – for everyone"))).build());
				this.addRenderableWidget(Button.builder(Component.literal("▶▶"), b -> action("next"))
						.bounds(cx + 110, y, 44, 20).tooltip(Tooltip.create(Component.literal("Next track – for everyone"))).build());
			}
			this.addRenderableWidget(Button.builder(Component.literal(paused ? "▶ Play" : "⏸ Pause"), b -> action(paused ? "resume" : "pause"))
					.bounds(cx - 104, y, 70, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("⟲ Restart"), b -> action("restart"))
					.bounds(cx - 30, y, 70, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
					.bounds(cx + 44, y, 62, 20).build());
			return;
		}
		if (remote()) {
			requestButton = Button.builder(Component.literal("✋ Request control"), b -> {
				feed.sendInput("req");
				rebuildWidgets();
			}).bounds(cx - 154, y, 120, 20).tooltip(Tooltip.create(Component.literal(
					"Asks " + feed.sender + " to let you use their mouse and keyboard on this screen. They have to allow it."))).build();
			requestButton.active = feed.canSendInput() && !controlling();
			this.addRenderableWidget(requestButton);
			Button esc = Button.builder(Component.literal("Esc"), b -> feed.sendInput("key escape"))
					.bounds(cx - 30, y, 40, 20).tooltip(Tooltip.create(Component.literal("Sends Escape to their PC"))).build();
			esc.active = controlling();
			this.addRenderableWidget(esc);
			Button ctrlAltTab = Button.builder(Component.literal("Alt+Tab"), b -> feed.sendInput("key alt+tab"))
					.bounds(cx + 14, y, 60, 20).build();
			ctrlAltTab.active = controlling();
			this.addRenderableWidget(ctrlAltTab);
		}
		this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
				.bounds(cx + 92, y, 62, 20).build());
	}

	private void action(String action) {
		Minecraft mc = Minecraft.getInstance();
		onClose();
		mc.execute(() -> LeanView.signActionAt(mc, signPos, action));
	}

	@Override
	public void tick() {
		super.tick();
		if (remote()) {
			String c = feed.controlState() + feed.canSendInput();
			if (!c.equals(lastControl)) {
				lastControl = c;
				rebuildWidgets();
			}
		}
	}

	// ------------------------------------------------------------------ Drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int[] r = area();
		g.fill(r[0] - 2, r[1] - 2, r[0] + r[2] + 2, r[1] + r[3] + 2, controlling() ? 0xFF55FF55 : 0xFF333333);
		g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF000000);
		if (source != null) {
			source.upload();
			if (source.hasFrame()) {
				g.blit(RenderPipelines.GUI_TEXTURED, source.textureId(), r[0], r[1], 0F, 0F, r[2], r[3], TEX_W, TEX_H, TEX_W, TEX_H);
			} else if (source.status() != null) {
				g.centeredText(this.font, Component.literal(source.status()), this.width / 2, r[1] + r[3] / 2, 0xFFDDDDDD);
			}
		}
		g.centeredText(this.font, Component.literal(headline()), this.width / 2, 8, 0xFFFFFFFF);
	}

	private String headline() {
		if (video != null) {
			return "Tap the picture to " + (video.isPaused() ? "play" : "pause") + " – controls work for everyone";
		}
		if (!remote()) {
			return source == null ? "Nothing playing on this screen" : "Live screen";
		}
		if (!feed.canSendInput()) {
			return feed.sender + "'s screen – remote control works once connected directly";
		}
		return switch (feed.controlState()) {
			case "granted" -> "You're controlling " + feed.sender + "'s PC – click and type on the picture";
			case "pending" -> "Waiting for " + feed.sender + " to allow control …";
			case "denied" -> feed.sender + " said no";
			case "revoked" -> feed.sender + " took back control";
			default -> feed.sender + "'s screen";
		};
	}

	// ------------------------------------------------------------------ Input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		double[] p = uv(event.x(), event.y());
		if (p == null) {
			return false;
		}
		if (video != null) {
			action(video.isPaused() ? "resume" : "pause");
			return true;
		}
		if (controlling()) {
			String b = button(event.button());
			if (b != null) {
				feed.sendInput(String.format(java.util.Locale.ROOT, "down %s %.4f %.4f", b, p[0], p[1]));
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (controlling()) {
			String b = button(event.button());
			double[] p = uv(event.x(), event.y());
			if (b != null) {
				feed.sendInput(p == null ? "up " + b : String.format(java.util.Locale.ROOT, "up %s %.4f %.4f", b, p[0], p[1]));
				return true;
			}
		}
		return super.mouseReleased(event);
	}

	@Override
	public void mouseMoved(double mx, double my) {
		super.mouseMoved(mx, my);
		if (!controlling()) {
			return;
		}
		double[] p = uv(mx, my);
		long now = System.currentTimeMillis();
		if (p == null || now - lastMove < 33 || (Math.abs(p[0] - lastU) < 0.0005 && Math.abs(p[1] - lastV) < 0.0005)) {
			return; // at most ~30 moves per second
		}
		lastMove = now;
		lastU = p[0];
		lastV = p[1];
		feed.sendInput(String.format(java.util.Locale.ROOT, "move %.4f %.4f", p[0], p[1]));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		mouseMoved(event.x(), event.y());
		return controlling() || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (controlling() && uv(mx, my) != null && scrollY != 0) {
			int n = (int) Math.signum(scrollY) * Math.max(1, (int) Math.round(Math.abs(scrollY)));
			feed.sendInput("wheel " + n);
			return true;
		}
		return super.mouseScrolled(mx, my, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_ESCAPE || !controlling()) {
			return super.keyPressed(event);
		}
		String name = keyName(event.key());
		boolean ctrl = event.hasControlDown();
		boolean alt = event.hasAltDown();
		boolean shift = event.hasShiftDown();
		if (name == null) {
			return true;
		}
		boolean printable = name.length() == 1 || name.equals("space");
		if (printable && !ctrl && !alt) {
			return true; // comes as text via charTyped (keeps the keyboard layout right)
		}
		StringBuilder combo = new StringBuilder();
		if (ctrl) {
			combo.append("ctrl+");
		}
		if (shift && !printable) {
			combo.append("shift+");
		}
		if (alt) {
			combo.append("alt+");
		}
		combo.append(name);
		feed.sendInput("key " + combo);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (!controlling()) {
			return super.charTyped(event);
		}
		String s = new String(Character.toChars(event.codepoint()));
		feed.sendInput("text " + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)));
		return true;
	}

	private static String button(int b) {
		if (b == InputConstants.MOUSE_BUTTON_LEFT) {
			return "l";
		}
		if (b == InputConstants.MOUSE_BUTTON_RIGHT) {
			return "r";
		}
		if (b == InputConstants.MOUSE_BUTTON_MIDDLE) {
			return "m";
		}
		return null;
	}

	private static String keyName(int key) {
		if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) {
			return String.valueOf((char) ('a' + key - InputConstants.KEY_A));
		}
		if (key >= InputConstants.KEY_0 && key <= InputConstants.KEY_9) {
			return String.valueOf((char) ('0' + key - InputConstants.KEY_0));
		}
		if (key >= InputConstants.KEY_F1 && key <= InputConstants.KEY_F12) {
			return "f" + (key - InputConstants.KEY_F1 + 1);
		}
		int[] codes = {InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_BACKSPACE,
				InputConstants.KEY_TAB, InputConstants.KEY_SPACE, InputConstants.KEY_DELETE, InputConstants.KEY_INSERT,
				InputConstants.KEY_HOME, InputConstants.KEY_END, InputConstants.KEY_PAGEUP, InputConstants.KEY_PAGEDOWN,
				InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_UP, InputConstants.KEY_DOWN};
		String[] names = {"enter", "enter", "backspace", "tab", "space", "delete", "insert", "home", "end", "pageup",
				"pagedown", "left", "right", "up", "down"};
		for (int i = 0; i < codes.length; i++) {
			if (codes[i] == key) {
				return names[i];
			}
		}
		return null;
	}

	@Override
	public void removed() {
		if (controlling()) {
			// let go of anything still held on their PC
			feed.sendInput("up l");
			feed.sendInput("up r");
			feed.sendInput("up m");
		}
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
