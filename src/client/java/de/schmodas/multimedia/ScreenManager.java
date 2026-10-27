package de.schmodas.multimedia;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Leinwände: Wand aus gleichen Blöcken + Schild "bildschirm" darunter. Steht ein Link drauf, läuft
 * dieses Video; steht nur ein Kanal drauf, das Video der Haupt-Jukebox/-Leinwand dieses Kanals.
 * Der Ton kommt (falls gewünscht) aus den Jukeboxen auf demselben Kanal.
 */
public final class ScreenManager {
	/** Bis hier wird das Video abgespielt (dekodiert). */
	private static final double PLAY_DISTANCE = 80;
	/** Bis hier wird gezeichnet. */
	private static final double DRAW_DISTANCE = 128;
	private static final int SCAN_INTERVAL = 10;
	private static final long CLOSE_DELAY_MS = 3000;

	/** note: Hinweis, wenn auf dieser Leinwand nichts laufen kann (z.B. keine Einladung). */
	private record Active(ScreenGeometry geometry, String spec, String note) {
	}

	private static final Map<BlockPos, Active> SCREENS = new HashMap<>();
	private static final Map<String, ScreenSource> PLAYERS = new HashMap<>();
	private static final Map<String, Long> UNUSED_SINCE = new HashMap<>();
	private static int tick;
	private static ClientLevel lastLevel;

	private ScreenManager() {
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != lastLevel) {
			closeAll();
			lastLevel = level;
		}
		if (level == null || mc.player == null) {
			return;
		}
		if (Settings.muted() || !Settings.screens()) {
			if (!SCREENS.isEmpty() || !PLAYERS.isEmpty()) {
				closeAll();
			}
			return;
		}
		if (++tick % SCAN_INTERVAL != 0) {
			return;
		}
		Vec3 me = mc.player.position();
		SCREENS.clear();
		Set<String> wanted = new HashSet<>();
		for (RadioManager.ScreenSign sign : RadioManager.screens()) {
			ScreenGeometry geo = ScreenGeometry.detect(level, sign.pos());
			if (geo == null) {
				continue;
			}
			double dist = Math.sqrt(geo.center().distanceToSqr(me));
			if (dist > DRAW_DISTANCE) {
				continue;
			}
			// Live-Bildschirm eines Spielers ("tv:Name" auf dem Schild) hat Vorrang
			String tv = sign.info().tv();
			if (tv != null) {
				Settings.TvSource target = LiveFeed.targetFor(tv, mc.getUser().getName());
				if (target == null) {
					SCREENS.put(sign.pos(), new Active(geo, null, T.t("Keine Einladung von ") + tv));
					continue;
				}
				String key = LiveFeed.keyFor(tv, target);
				SCREENS.put(sign.pos(), new Active(geo, key, null));
				if (dist <= PLAY_DISTANCE) {
					wanted.add(key);
					if (!PLAYERS.containsKey(key)) {
						MultiMediaClient.LOGGER.info("Leinwand an bei {} ({}x{}): live von {}", sign.pos(), geo.width(), geo.height(), tv);
						PLAYERS.put(key, new LivePlayer(tv, target));
					}
				}
				continue;
			}
			Source source = sign.info().source() != null ? sign.info().source() : RadioManager.channelSource(sign.info().channels(), sign.pos());
			if (source == null) {
				continue;
			}
			if (source.isLive()) {
				// Kanal zeigt einen Live-Bildschirm (Haupt-Leinwand mit tv:Name)
				String name = source.liveName();
				Settings.TvSource target = LiveFeed.targetFor(name, mc.getUser().getName());
				if (target == null) {
					continue;
				}
				String key = LiveFeed.keyFor(name, target);
				SCREENS.put(sign.pos(), new Active(geo, key, null));
				if (dist <= PLAY_DISTANCE) {
					wanted.add(key);
					PLAYERS.computeIfAbsent(key, k -> new LivePlayer(name, target));
				}
				continue;
			}
			// Spieler nach Link (ohne Start/Pause): Pausieren/Weiterspielen ändert nur seinen Zustand, lädt nicht neu
			String key = source.key();
			SCREENS.put(sign.pos(), new Active(geo, key, null));
			if (dist <= PLAY_DISTANCE) {
				wanted.add(key);
				ScreenSource existing = PLAYERS.get(key);
				if (!(existing instanceof VideoPlayer vp)) {
					MultiMediaClient.LOGGER.info("Leinwand an bei {} ({}x{}): {}", sign.pos(), geo.width(), geo.height(), source.spec());
					PLAYERS.put(key, new VideoPlayer(source, level.getGameTime() / 20.0));
				} else {
					vp.update(source, level.getGameTime() / 20.0);
				}
			}
		}
		// Videos, die keiner mehr braucht, nach kurzer Zeit beenden
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<String, ScreenSource>> it = PLAYERS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, ScreenSource> e = it.next();
			if (wanted.contains(e.getKey())) {
				UNUSED_SINCE.remove(e.getKey());
				continue;
			}
			long since = UNUSED_SINCE.computeIfAbsent(e.getKey(), k -> now);
			if (now - since > CLOSE_DELAY_MS) {
				e.getValue().close();
				it.remove();
				UNUSED_SINCE.remove(e.getKey());
			}
		}
	}

	public static void closeAll() {
		PLAYERS.values().forEach(ScreenSource::close);
		PLAYERS.clear();
		SCREENS.clear();
		UNUSED_SINCE.clear();
	}

	/** Anzahl Leinwände und Videos (für /disc check und Tests). */
	public static String debugInfo() {
		return SCREENS.size() + T.t(" Leinwand/Leinwände, ") + PLAYERS.size() + T.t(" Video(s)");
	}

	public static List<String> playingSpecs() {
		return new ArrayList<>(PLAYERS.keySet());
	}

	// ------------------------------------------------------------------ Touch screen

	/**
	 * Right-click on a screen's front: opens the touch screen for it (video controls, or remote control of a
	 * friend's shared screen). Returns true if the click was on a screen and is handled.
	 */
	public static boolean touch(net.minecraft.world.phys.BlockHitResult hit) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || SCREENS.isEmpty()) {
			return false;
		}
		Vec3 at = hit.getLocation();
		for (Map.Entry<BlockPos, Active> e : SCREENS.entrySet()) {
			ScreenGeometry g = e.getValue().geometry();
			if (hit.getDirection() != g.facing()) {
				continue;
			}
			double dx = at.x - g.center().x;
			double dy = at.y - g.center().y;
			double dz = at.z - g.center().z;
			double u = dx * g.right().getStepX() + dz * g.right().getStepZ();
			double front = dx * g.facing().getStepX() + dz * g.facing().getStepZ();
			if (Math.abs(u) > g.width() / 2.0 + 0.01 || Math.abs(dy) > g.height() / 2.0 + 0.01 || front < -1.2 || front > 0.3) {
				continue;
			}
			Active screen = e.getValue();
			ScreenSource source = screen.spec() == null ? null : PLAYERS.get(screen.spec());
			Source video = null;
			for (RadioManager.ScreenSign sign : RadioManager.screens()) {
				if (sign.pos().equals(e.getKey())) {
					video = sign.info().source();
				}
			}
			// where on the picture (0..1, top left = 0,0), letterboxed to 16:9 like drawScreen
			float aspect = VideoPlayer.WIDTH / (float) VideoPlayer.HEIGHT;
			double vw = g.width();
			double vh = vw / aspect;
			if (vh > g.height()) {
				vh = g.height();
				vw = vh * aspect;
			}
			double pu = (u + vw / 2) / vw;
			double pv = 1 - (dy + vh / 2) / vh;
			mc.gui.setScreen(new TouchScreen(e.getKey(), screen.spec(), source, video, pu, pv));
			return true;
		}
		return false;
	}

	/** The player behind a screen key (for the touch screen), or null. */
	static ScreenSource player(String spec) {
		return spec == null ? null : PLAYERS.get(spec);
	}

	// ------------------------------------------------------------------ Zeichnen

	public static void render(LevelRenderContext ctx) {
		if (SCREENS.isEmpty()) {
			return;
		}
		Vec3 cam = ctx.gameRenderer().mainCamera().position();
		PoseStack poseStack = ctx.poseStack();
		Set<ScreenSource> uploaded = new HashSet<>();
		for (Active screen : SCREENS.values()) {
			ScreenSource player = screen.spec() == null ? null : PLAYERS.get(screen.spec());
			String note = screen.note() != null ? screen.note() : player != null ? player.status() : null;
			if (player == null && note == null) {
				continue;
			}
			if (player != null && uploaded.add(player)) {
				player.upload();
			}
			ScreenGeometry g = screen.geometry();
			poseStack.pushPose();
			poseStack.translate(g.center().x - cam.x, g.center().y - cam.y, g.center().z - cam.z);
			boolean frame = player != null && player.hasFrame();
			ctx.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.text(player != null ? player.textureId() : blackTexture()),
					(pose, buffer) -> drawScreen(pose.pose(), buffer, g, frame));
			if (note != null && !frame) {
				drawNote(ctx, poseStack, g, note);
			}
			poseStack.popPose();
		}
	}

	private static net.minecraft.resources.Identifier black;

	/** Schwarze 1x1-Textur für Leinwände ohne Video. */
	private static net.minecraft.resources.Identifier blackTexture() {
		if (black == null) {
			black = net.minecraft.resources.Identifier.fromNamespaceAndPath(MultiMediaClient.MOD_ID, "black");
			net.minecraft.client.renderer.texture.DynamicTexture t = new net.minecraft.client.renderer.texture.DynamicTexture(black::toString, 1, 1, false);
			t.getPixels().setPixel(0, 0, 0xFF000000);
			t.upload();
			Minecraft.getInstance().getTextureManager().register(black, t);
		}
		return black;
	}

	/** Hinweis mittig auf die Leinwand schreiben (z.B. "Kein Signal"). */
	private static void drawNote(LevelRenderContext ctx, PoseStack poseStack, ScreenGeometry g, String note) {
		var font = Minecraft.getInstance().font;
		var text = net.minecraft.network.chat.Component.literal(note).getVisualOrderText();
		int textWidth = Math.max(1, font.width(text));
		float scale = Math.min(0.7F * g.width() / textWidth, 0.12F * g.height() / font.lineHeight);
		poseStack.pushPose();
		poseStack.rotateDegrees(com.mojang.math.Axis.YP, -g.facing().toYRot());
		poseStack.translate(0, 0, 0.01);
		poseStack.scale(scale, -scale, scale);
		ctx.submitNodeCollector().submitText(poseStack, -textWidth / 2F, -font.lineHeight / 2F, text, false,
				net.minecraft.client.gui.Font.DisplayMode.NORMAL, LightCoordsUtil.FULL_BRIGHT, 0xFFDDDDDD, 0, 0);
		poseStack.popPose();
	}

	/** Schwarze Fläche über die ganze Wand, darauf das Bild im 16:9-Format (mittig, mit Rändern). */
	private static void drawScreen(Matrix4f m, VertexConsumer buffer, ScreenGeometry g, boolean hasFrame) {
		float w = g.width();
		float h = g.height();
		quad(m, buffer, g, -w / 2, -h / 2, w / 2, h / 2, -0.001F, 0xFF000000);
		if (!hasFrame) {
			return;
		}
		float aspect = VideoPlayer.WIDTH / (float) VideoPlayer.HEIGHT;
		float vw = w;
		float vh = w / aspect;
		if (vh > h) {
			vh = h;
			vw = h * aspect;
		}
		// Helligkeit aus den Einstellungen (gegen Überbelichtung durch Shader): Farbe abdunkeln
		int v = Math.round(255 * Settings.screenBrightness() / 100F);
		quad(m, buffer, g, -vw / 2, -vh / 2, vw / 2, vh / 2, 0F, 0xFF000000 | (v << 16) | (v << 8) | v);
	}

	/** Rechteck in der Leinwand-Ebene (u nach rechts, v nach oben, d nach vorne), Textur ganz drauf. */
	private static void quad(Matrix4f m, VertexConsumer buffer, ScreenGeometry g, float u0, float v0, float u1, float v1,
			float d, int color) {
		float rx = g.right().getStepX();
		float rz = g.right().getStepZ();
		float fx = g.facing().getStepX();
		float fz = g.facing().getStepZ();
		int light = LightCoordsUtil.FULL_BRIGHT;
		// unten links, unten rechts, oben rechts, oben links (von vorne gesehen gegen den Uhrzeigersinn)
		buffer.addVertex(m, rx * u0 + fx * d, v0, rz * u0 + fz * d).setColor(color).setUv(0F, 1F).setLight(light);
		buffer.addVertex(m, rx * u1 + fx * d, v0, rz * u1 + fz * d).setColor(color).setUv(1F, 1F).setLight(light);
		buffer.addVertex(m, rx * u1 + fx * d, v1, rz * u1 + fz * d).setColor(color).setUv(1F, 0F).setLight(light);
		buffer.addVertex(m, rx * u0 + fx * d, v1, rz * u0 + fz * d).setColor(color).setUv(0F, 0F).setLight(light);
	}
}
