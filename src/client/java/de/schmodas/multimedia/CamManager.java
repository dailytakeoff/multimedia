package de.schmodas.multimedia;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Webcams above players' heads (like StreamCraft): for every player nearby who has invited you (the same
 * invite as for their screen), their webcam stream is connected and drawn as a small screen floating above
 * their name tag, always facing you and full-bright so it's visible at night. Nothing is drawn while their
 * webcam is off. Your own webcam is shown above your own head only in third person.
 */
public final class CamManager {
	/** Connect to webcams of players this close (blocks). */
	private static final double RANGE = 40;
	private static final long CLOSE_DELAY_MS = 4000;
	private static final float W = 1.2F;
	private static final float H = W * 9 / 16;

	/** Lower-case player name -> webcam player. */
	private static final Map<String, LivePlayer> CAMS = new HashMap<>();
	private static final Map<String, Long> UNUSED_SINCE = new HashMap<>();
	private static int tick;
	private static ClientLevel lastLevel;

	private CamManager() {
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != lastLevel) {
			closeAll();
			lastLevel = level;
		}
		if (level == null || mc.player == null || Settings.muted() || !Settings.screens()) {
			if (!CAMS.isEmpty()) {
				closeAll();
			}
			return;
		}
		if (++tick % 20 != 0) {
			return;
		}
		String me = mc.getUser().getName();
		Set<String> wanted = new HashSet<>();
		for (Player p : level.players()) {
			String name = p.getGameProfile().name();
			boolean self = name.equalsIgnoreCase(me);
			if (self && !TvSender.isCamOn()) {
				continue;
			}
			if (!self && p.distanceTo(mc.player) > RANGE) {
				continue;
			}
			Settings.TvSource target = LiveFeed.targetFor(name, me);
			if (target == null) {
				continue; // no invite from this player -> their webcam stays private
			}
			String key = name.toLowerCase(Locale.ROOT);
			wanted.add(key);
			if (!CAMS.containsKey(key)) {
				CAMS.put(key, new LivePlayer(name, target, true));
			}
		}
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<String, LivePlayer>> it = CAMS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, LivePlayer> e = it.next();
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
		CAMS.values().forEach(LivePlayer::close);
		CAMS.clear();
		UNUSED_SINCE.clear();
	}

	public static void render(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (CAMS.isEmpty() || mc.level == null || mc.player == null) {
			return;
		}
		Vec3 cam = ctx.gameRenderer().mainCamera().position();
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		String me = mc.getUser().getName();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		PoseStack poseStack = ctx.poseStack();
		for (Player p : mc.level.players()) {
			String name = p.getGameProfile().name();
			LivePlayer player = CAMS.get(name.toLowerCase(Locale.ROOT));
			if (player == null) {
				continue;
			}
			if (name.equalsIgnoreCase(me) && firstPerson) {
				continue;
			}
			player.upload();
			if (!player.hasFrame() || p.isInvisible()) {
				continue;
			}
			Vec3 pos = p.getPosition(partial);
			double y = pos.y + p.getBbHeight() + 0.75 + H / 2;
			double dx = cam.x - pos.x;
			double dz = cam.z - pos.z;
			float yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
			poseStack.pushPose();
			poseStack.translate(pos.x - cam.x, y - cam.y, pos.z - cam.z);
			poseStack.rotateDegrees(com.mojang.math.Axis.YP, yaw);
			ctx.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.text(player.textureId()),
					(pose, buffer) -> billboard(pose.pose(), buffer));
			poseStack.popPose();
		}
	}

	/** Rectangle facing +Z (towards the camera after the rotation), texture on it, full-bright. */
	private static void billboard(Matrix4f m, VertexConsumer buffer) {
		int light = LightCoordsUtil.FULL_BRIGHT;
		int color = 0xFFFFFFFF;
		float x = W / 2;
		float y = H / 2;
		buffer.addVertex(m, -x, -y, 0).setColor(color).setUv(0F, 1F).setLight(light);
		buffer.addVertex(m, x, -y, 0).setColor(color).setUv(1F, 1F).setLight(light);
		buffer.addVertex(m, x, y, 0).setColor(color).setUv(1F, 0F).setLight(light);
		buffer.addVertex(m, -x, y, 0).setColor(color).setUv(0F, 0F).setLight(light);
	}
}
