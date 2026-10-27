package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Your floating screens on this server: edit, move here, delete, or add a new one. */
public class FloatListScreen extends Screen {
	private final Screen parent;

	FloatListScreen(Screen parent) {
		super(Component.literal("Your floating screens"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int left = cx - 155;
		int y = 34;
		List<FloatingScreens.FloatData> own = FloatingScreens.own();
		for (FloatingScreens.FloatData d : own) {
			FloatingScreens.FloatData screen = d;
			String vis = switch (d.visibility) {
				case "all" -> "everyone";
				case "friends" -> d.friends.size() + " friend" + (d.friends.size() == 1 ? "" : "s");
				default -> "only you";
			};
			this.addRenderableWidget(Button.builder(Component.literal(trim(d.describe(), 22) + " · " + vis), b ->
					Minecraft.getInstance().gui.setScreen(new FloatEditScreen(this, screen, false))).bounds(left, y, 200, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Move here"), b -> {
				FloatingScreens.placeInFront(screen);
				FloatingScreens.saved(screen);
			}).bounds(left + 204, y, 64, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("✖"), b -> {
				FloatingScreens.remove(screen);
				rebuildWidgets();
			}).bounds(left + 272, y, 38, 20).build());
			y += 24;
		}
		Button add = Button.builder(Component.literal("+ New screen where I'm looking"), b -> {
			FloatingScreens.FloatData d = FloatingScreens.create();
			if (d != null) {
				Minecraft.getInstance().gui.setScreen(new FloatEditScreen(this, d, true));
			}
		}).bounds(left, y + 6, 310, 20).build();
		add.active = own.size() < FloatingScreens.MAX_OWN;
		this.addRenderableWidget(add);
		this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(cx - 50, this.height - 28, 100, 20).build());
	}

	private static String trim(String s, int n) {
		return s.length() > n ? s.substring(0, n - 1) + "…" : s;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		String hint = FloatingScreens.own().isEmpty() ? "No screens yet – add one where you're looking."
				: "Click a screen to edit it. Right-click it in the world for the touch screen.";
		g.centeredText(this.font, Component.literal(hint), this.width / 2, 22, 0xFF888888);
		int others = FloatingScreens.othersCount();
		if (others > 0) {
			g.centeredText(this.font, Component.literal(others + " screen" + (others == 1 ? "" : "s") + " from friends nearby"),
					this.width / 2, this.height - 42, 0xFF888888);
		}
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
