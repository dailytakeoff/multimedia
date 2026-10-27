package de.schmodas.multimedia;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Floating screens: placed in the air from the menu, no wall or sign needed. Each one shows a YouTube link,
 * a player's screen share ("tv:Name") or a player's webcam ("cam:Name"), with its own size, rotation and tilt.
 * YouTube screens play their sound from where the screen hangs.
 *
 * Your own screens are saved per server. Who else sees them is chosen per screen: only you, friends you pick,
 * or everyone with the mod. Others learn about them through hidden whispers ("mmfs1:" = screen, "mmfd1:" =
 * removed), the same way invites travel – so the server still needs no mod. A friend who joins later gets them
 * a few seconds after they show up in the player list.
 */
public final class FloatingScreens {
	/** One screen. Public fields so Gson can save it. yaw: rotation around the vertical axis, tilt: forwards/back. */
	public static final class FloatData {
		public String id;
		public String owner;
		public String dim;
		public double x;
		public double y;
		public double z;
		public float yaw;
		public float tilt;
		public float width = 3;
		/** YouTube link spec (Source.spec()), "tv:Name" or "cam:Name". */
		public String content = "";
		/** "me", "friends" or "all". */
		public String visibility = "me";
		public List<String> friends = new ArrayList<>();

		FloatData copy() {
			FloatData c = new FloatData();
			c.id = id;
			c.owner = owner;
			c.dim = dim;
			c.x = x;
			c.y = y;
			c.z = z;
			c.yaw = yaw;
			c.tilt = tilt;
			c.width = width;
			c.content = content;
			c.visibility = visibility;
			c.friends = new ArrayList<>(friends);
			return c;
		}

		float height() {
			return width * 9 / 16;
		}

		Vec3 center() {
			return new Vec3(x, y, z);
		}

		/** What this screen shows, as a short text for lists. */
		public String describe() {
			if (content.startsWith("tv:")) {
				return content.substring(3) + "'s screen";
			}
			if (content.startsWith("cam:")) {
				return content.substring(4) + "'s webcam";
			}
			Source s = Source.parse(content);
			return s == null ? "(nothing)" : s.compact();
		}
	}

	public static final int MAX_OWN = 8;
	private static final int MAX_FROM_OTHERS = 48;
	public static final float MAX_WIDTH = 16;
	private static final double PLAY_DISTANCE = 80;
	private static final double DRAW_DISTANCE = 128;
	private static final long CLOSE_DELAY_MS = 3000;

	/** Own screens (this server), in order. */
	private static final List<FloatData> OWN = new ArrayList<>();
	/** Screens other players shared with you: "owner/id" -> screen (not saved). */
	private static final Map<String, FloatData> OTHERS = new LinkedHashMap<>();
	/** Players / video for each visible screen ("owner/id" -> source). */
	private static final Map<String, ScreenSource> PLAYERS = new HashMap<>();
	private static final Map<String, String> PLAYER_SPEC = new HashMap<>();
	private static final Map<String, StreamSoundInstance> SOUNDS = new HashMap<>();
	private static final Map<String, Long> UNUSED_SINCE = new HashMap<>();
	/** Who got our screens already this session (lower case); cleared when they leave. */
	private static final Set<String> SENT_TO = new HashSet<>();
	private static final Map<String, Long> SEEN_ONLINE_AT = new HashMap<>();
	private static String loadedFor;
	private static ClientLevel lastLevel;
	private static int tick;

	private static final Pattern SCREEN = Pattern.compile("mmfs1:([A-Za-z0-9_-]{10,})");
	private static final Pattern DELETE = Pattern.compile("mmfd1:([A-Za-z0-9_-]{4,})");
	/** "Hello, I have the mod" – sent back once to someone whose screen we got, so they know us too. */
	private static final Pattern HELLO = Pattern.compile("mmfh1:([A-Za-z0-9_-]{4,})");
	private static final Pattern ANY = Pattern.compile("mmf[sdh]1:");
	/** Players invited from floating screens this session (lower case). */
	private static final Set<String> INVITED = new HashSet<>();
	/** Players we've said hello to this session (lower case). */
	private static final Set<String> GREETED = new HashSet<>();
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

	private FloatingScreens() {
	}

	// ------------------------------------------------------------------ Own screens

	public static List<FloatData> own() {
		return OWN;
	}

	public static int othersCount() {
		return OTHERS.size();
	}

	private static String me() {
		return Minecraft.getInstance().getUser().getName();
	}

	private static String dimension() {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? "?" : Integer.toHexString(level.dimension().toString().hashCode());
	}

	private static String serverKey() {
		Minecraft mc = Minecraft.getInstance();
		var server = mc.getCurrentServer();
		return server == null ? "singleplayer" : server.ip.toLowerCase(Locale.ROOT);
	}

	/** A new screen 3 blocks in front of you, facing you, showing nothing yet. Null if you have too many. */
	public static FloatData create() {
		if (OWN.size() >= MAX_OWN) {
			return null;
		}
		FloatData d = new FloatData();
		d.id = Long.toString(System.nanoTime() & 0xFFFFFFFFFFL, 36);
		d.owner = me();
		d.visibility = "all"; // new screens are shared with players who have the mod (change in the editor)
		placeInFront(d);
		OWN.add(d);
		return d;
	}

	/** Moves the screen to 3 blocks in front of you (or onto the block you look at) and turns it towards you. */
	public static void placeInFront(FloatData d) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getLookAngle();
		double dist = 3;
		if (mc.hitResult != null && mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
			dist = Math.max(1.0, Math.min(dist, mc.hitResult.getLocation().distanceTo(eye) - 0.1));
		}
		Vec3 c = eye.add(look.scale(dist));
		d.x = Math.round(c.x * 100) / 100.0;
		d.y = Math.round(c.y * 100) / 100.0;
		d.z = Math.round(c.z * 100) / 100.0;
		d.yaw = 180 - mc.player.getYRot();
		d.dim = dimension();
	}

	/** Turns the screen so its front faces you, without moving it. */
	public static void faceMe(FloatData d) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		double dx = mc.player.getX() - d.x;
		double dz = mc.player.getZ() - d.z;
		d.yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
	}

	/** Saves and tells the right players (call after editing). */
	public static void saved(FloatData d) {
		save();
		closePlayer(key(d));
		broadcast(d);
		// for your own screen share / webcam, make sure the people who see it can actually connect
		String mine = me();
		if (d.content.equalsIgnoreCase("tv:" + mine) || d.content.equalsIgnoreCase("cam:" + mine)) {
			List<String> to = new ArrayList<>();
			for (String p : recipients(d)) {
				if (INVITED.add(p.toLowerCase(Locale.ROOT))) {
					to.add(p); // invite each player at most once per session
				}
			}
			if (!to.isEmpty()) {
				TvInvite.send(to);
			}
		}
	}

	public static void remove(FloatData d) {
		OWN.remove(d);
		save();
		closePlayer(key(d));
		if (d.visibility.equals("me")) {
			return; // nobody else knew about it
		}
		for (String p : onlineNames()) {
			if (!p.equalsIgnoreCase(me()) && (ModPeers.has(p) || containsIgnoreCase(d.friends, p))) {
				whisper(p, "mmfd1:" + b64(d.owner + "|" + d.id), d.id);
			}
		}
	}

	/** Pause / resume / restart a YouTube screen of yours (for everyone who sees it). */
	public static void action(FloatData d, String action) {
		Minecraft mc = Minecraft.getInstance();
		Source src = Source.parse(d.content);
		if (src == null || mc.level == null || src.isLive()) {
			return;
		}
		double now = mc.level.getGameTime() / 20.0;
		double pos = src.positionAt(now);
		Source next = switch (action) {
			case "pause" -> new Source(src.url(), src.playlist(), src.shuffle(), src.loop(), src.start(), pos, src.first());
			case "resume" -> new Source(src.url(), src.playlist(), src.shuffle(), src.loop(), Math.round(now - pos), Double.NaN, src.first());
			case "restart" -> new Source(src.url(), src.playlist(), src.shuffle(), src.loop(), (long) Math.floor(now), Double.NaN, 0);
			default -> src;
		};
		d.content = next.spec();
		saved(d);
	}

	private static void save() {
		List<FloatData> copy = new ArrayList<>();
		for (FloatData d : OWN) {
			copy.add(d.copy());
		}
		Settings.setFloating(serverKey(), copy);
	}

	private static void load() {
		OWN.clear();
		for (FloatData d : Settings.floating(serverKey())) {
			if (OWN.size() < MAX_OWN && d.id != null) {
				d.owner = me();
				if (d.friends == null) {
					d.friends = new ArrayList<>();
				}
				OWN.add(d);
			}
		}
	}

	// ------------------------------------------------------------------ Sync (hidden whispers)

	public static void register() {
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) -> {
			String text = message.getString();
			if (!ANY.matcher(text).find()) {
				return true;
			}
			Minecraft mc = Minecraft.getInstance();
			boolean own = sender != null && mc.player != null && sender.id().equals(mc.player.getUUID());
			if (!own) {
				String from = sender == null ? null : sender.name();
				mc.execute(() -> handle(text, from));
			}
			return false;
		});
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			String text = message.getString();
			if (overlay || !ANY.matcher(text).find()) {
				return true;
			}
			String low = text.toLowerCase(Locale.ROOT);
			if (!text.contains("→") && !low.startsWith("you whisper") && !low.startsWith("du flüsterst")) {
				Minecraft.getInstance().execute(() -> handle(text, null));
			}
			return false;
		});
	}

	private static List<String> recipients(FloatData d) {
		List<String> out = new ArrayList<>();
		String mine = me();
		for (String p : onlineNames()) {
			if (p.equalsIgnoreCase(mine)) {
				continue;
			}
			if (sees(d, p)) {
				out.add(p);
			}
		}
		return out;
	}

	private static void broadcast(FloatData d) {
		String code = encode(d);
		String mine = me();
		for (String p : onlineNames()) {
			if (p.equalsIgnoreCase(mine)) {
				continue;
			}
			if (sees(d, p)) {
				whisper(p, code, d.id);
			} else if (ModPeers.has(p)) {
				whisper(p, "mmfd1:" + b64(d.owner + "|" + d.id), d.id); // no longer sees it: remove it for them
			}
		}
	}

	private static String encode(FloatData d) {
		String raw = String.join("|", d.owner, d.id, d.dim == null ? "?" : d.dim,
				String.format(Locale.ROOT, "%.2f", d.x), String.format(Locale.ROOT, "%.2f", d.y), String.format(Locale.ROOT, "%.2f", d.z),
				String.format(Locale.ROOT, "%.0f", ((d.yaw % 360) + 360) % 360), String.format(Locale.ROOT, "%.0f", d.tilt),
				String.format(Locale.ROOT, "%.1f", d.width), shortContent(d.content));
		return "mmfs1:" + b64(raw);
	}

	/** YouTube content in its short form ("yt:ID" / "list=ID" + flags), so the whisper stays under the chat limit. */
	private static String shortContent(String content) {
		Source s = Source.parse(content);
		if (s == null || s.isLive() || content.startsWith("tv:") || content.startsWith("cam:")) {
			return content;
		}
		String url = s.url();
		String base = s.playlist() ? "list=" + url.substring(url.indexOf("list=") + 5)
				: "yt:" + url.substring(url.indexOf("watch?v=") + 8);
		return base + (s.shuffle() ? " mix" : "") + (s.loop() ? " loop" : "") + (s.first() > 0 ? " ▶" + (s.first() + 1) : "")
				+ (s.start() >= 0 ? " @" + s.start() : "")
				+ (s.isPaused() ? " ⏸" + String.format(Locale.ROOT, "%.1f", s.paused()) : "");
	}

	private static void handle(String text, String chatSender) {
		Matcher m = SCREEN.matcher(text);
		if (m.find()) {
			String[] p = unb64(m.group(1));
			if (p == null || p.length < 10 || !NAME.matcher(p[0]).matches() || !p[1].matches("[a-z0-9]{1,13}")) {
				return;
			}
			if (chatSender != null && !chatSender.equalsIgnoreCase(p[0])) {
				return; // name doesn't match who sent it
			}
			if (p[0].equalsIgnoreCase(me())) {
				return;
			}
			try {
				FloatData d = new FloatData();
				d.owner = p[0];
				d.id = p[1];
				d.dim = p[2];
				d.x = Double.parseDouble(p[3]);
				d.y = Double.parseDouble(p[4]);
				d.z = Double.parseDouble(p[5]);
				d.yaw = Float.parseFloat(p[6]);
				d.tilt = Math.max(-90, Math.min(90, Float.parseFloat(p[7])));
				d.width = Math.max(0.5F, Math.min(MAX_WIDTH, Float.parseFloat(p[8])));
				// the content may itself contain "|"? No – specs never do, but join the rest to be safe
				d.content = String.join("|", java.util.Arrays.copyOfRange(p, 9, p.length));
				if (!safeContent(d.content) || !Double.isFinite(d.x) || !Double.isFinite(d.y) || !Double.isFinite(d.z)) {
					return;
				}
				String key = key(d);
				FloatData old = OTHERS.get(key);
				if (old == null && OTHERS.size() >= MAX_FROM_OTHERS) {
					return;
				}
				OTHERS.put(key, d);
				ModPeers.mark(d.owner);
				greet(d.owner);
				if (old == null || !old.content.equals(d.content)) {
					closePlayer(key);
				}
			} catch (NumberFormatException ignored) {
			}
			return;
		}
		Matcher hello = HELLO.matcher(text);
		if (hello.find()) {
			String[] p = unb64(hello.group(1));
			if (p == null || p.length < 1 || !NAME.matcher(p[0]).matches()
					|| (chatSender != null && !chatSender.equalsIgnoreCase(p[0]))) {
				return;
			}
			ModPeers.mark(p[0]);
			SENT_TO.remove(p[0].toLowerCase(Locale.ROOT)); // send them our "everyone" screens on the next tick
			SEEN_ONLINE_AT.remove(p[0].toLowerCase(Locale.ROOT));
			return;
		}
		Matcher del = DELETE.matcher(text);
		if (del.find()) {
			String[] p = unb64(del.group(1));
			if (p == null || p.length < 2) {
				return;
			}
			if (chatSender != null && !chatSender.equalsIgnoreCase(p[0])) {
				return;
			}
			String key = p[0].toLowerCase(Locale.ROOT) + "/" + p[1];
			ModPeers.mark(p[0]);
			if (OTHERS.remove(key) != null) {
				closePlayer(key);
			}
		}
	}

	/** Once per session: tell a player whose screen we got that we have the mod (so we get their "everyone" screens too). */
	private static void greet(String owner) {
		if (GREETED.add(owner.toLowerCase(Locale.ROOT))) {
			whisper(owner, "mmfh1:" + b64(me()), "hello");
		}
	}

	/** Only YouTube (through Source's checks) or a player name – never arbitrary links. */
	static boolean safeContent(String content) {
		if (content == null || content.isBlank()) {
			return true;
		}
		if (content.startsWith("tv:") || content.startsWith("cam:")) {
			return NAME.matcher(content.substring(content.indexOf(':') + 1)).matches();
		}
		Source s = Source.parse(content);
		return s != null && !s.isLive();
	}

	/** Queued and rate-limited (ChatQueue); a newer message for the same screen and player replaces a waiting one. */
	private static void whisper(String to, String code, String screenId) {
		String command = "msg " + to + " [MultiMedia] " + code;
		if (command.length() > 255) {
			MultiMediaClient.LOGGER.warn("Schwebende Leinwand: Nachricht zu lang ({} Zeichen) – nicht gesendet", command.length());
			MultiMediaClient.error("This floating screen's link is too long to send to others.");
			return;
		}
		ChatQueue.normal(command, "fs:" + to.toLowerCase(Locale.ROOT) + ":" + screenId);
	}

	private static List<String> onlineNames() {
		List<String> out = new ArrayList<>();
		var conn = Minecraft.getInstance().getConnection();
		if (conn != null) {
			conn.getOnlinePlayers().forEach(i -> out.add(i.getProfile().name()));
		}
		return out;
	}

	/**
	 * Does this player get the screen? "Everyone" means players known to have the mod (so players without it
	 * never get code messages); picked friends always, because you chose them.
	 */
	private static boolean sees(FloatData d, String player) {
		return (d.visibility.equals("all") && ModPeers.has(player))
				|| (d.visibility.equals("friends") && containsIgnoreCase(d.friends, player));
	}

	private static boolean containsIgnoreCase(List<String> list, String s) {
		for (String x : list) {
			if (x.equalsIgnoreCase(s)) {
				return true;
			}
		}
		return false;
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

	private static String key(FloatData d) {
		return d.owner.toLowerCase(Locale.ROOT) + "/" + d.id;
	}

	// ------------------------------------------------------------------ Tick

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != lastLevel) {
			closeAll();
			lastLevel = level;
			if (level == null) {
				OTHERS.clear();
				SENT_TO.clear();
				GREETED.clear();
				SEEN_ONLINE_AT.clear();
				loadedFor = null;
			}
		}
		if (level == null || mc.player == null) {
			return;
		}
		String server = serverKey();
		if (!server.equals(loadedFor)) {
			loadedFor = server;
			load();
		}
		if (++tick % 10 != 0) {
			return;
		}
		sendToNewcomers();
		if (Settings.muted() || !Settings.screens()) {
			closeAll();
			return;
		}
		Vec3 me = mc.player.position();
		String dim = dimension();
		Set<String> wanted = new HashSet<>();
		for (FloatData d : all()) {
			if (d.dim != null && !d.dim.equals(dim) || d.content.isBlank()) {
				continue;
			}
			double dist = Math.sqrt(d.center().distanceToSqr(me));
			if (dist > PLAY_DISTANCE) {
				continue;
			}
			String key = key(d);
			wanted.add(key);
			if (!d.content.equals(PLAYER_SPEC.get(key))) {
				closePlayer(key);
			}
			if (!PLAYERS.containsKey(key)) {
				ScreenSource src = open(mc, level, d);
				if (src != null) {
					PLAYERS.put(key, src);
					PLAYER_SPEC.put(key, d.content);
				}
			} else if (PLAYERS.get(key) instanceof VideoPlayer vp) {
				Source s = Source.parse(d.content);
				if (s != null) {
					vp.update(s, level.getGameTime() / 20.0);
				}
			}
		}
		long now = System.currentTimeMillis();
		Iterator<String> it = new ArrayList<>(PLAYERS.keySet()).iterator();
		while (it.hasNext()) {
			String key = it.next();
			if (wanted.contains(key)) {
				UNUSED_SINCE.remove(key);
				continue;
			}
			long since = UNUSED_SINCE.computeIfAbsent(key, k -> now);
			if (now - since > CLOSE_DELAY_MS) {
				closePlayer(key);
			}
		}
	}

	/** Every online player who should see one of our screens gets them once, ~5 s after they appear. */
	private static void sendToNewcomers() {
		if (OWN.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		Set<String> online = new HashSet<>();
		for (String p : onlineNames()) {
			online.add(p.toLowerCase(Locale.ROOT));
		}
		SENT_TO.retainAll(online); // left -> gets them again next time
		SEEN_ONLINE_AT.keySet().retainAll(online);
		String mine = me().toLowerCase(Locale.ROOT);
		for (String p : onlineNames()) {
			String low = p.toLowerCase(Locale.ROOT);
			if (low.equals(mine) || SENT_TO.contains(low)) {
				continue;
			}
			long seen = SEEN_ONLINE_AT.computeIfAbsent(low, k -> now);
			if (now - seen < 5000) {
				continue;
			}
			SENT_TO.add(low);
			for (FloatData d : OWN) {
				if (sees(d, p)) {
					whisper(p, encode(d), d.id);
				}
			}
		}
	}

	private static List<FloatData> all() {
		List<FloatData> out = new ArrayList<>(OWN);
		out.addAll(OTHERS.values());
		return out;
	}

	private static ScreenSource open(Minecraft mc, ClientLevel level, FloatData d) {
		String c = d.content;
		if (c.startsWith("tv:") || c.startsWith("cam:")) {
			boolean cam = c.startsWith("cam:");
			String name = c.substring(c.indexOf(':') + 1);
			Settings.TvSource target = LiveFeed.targetFor(name, mc.getUser().getName());
			if (target == null) {
				return null; // no invite from that player
			}
			if (!cam && Settings.liveSound()) {
				startSound(mc, level, d, Source.live(name)); // in sync: the picture waits for the sound (~1.5 s)
			}
			return new LivePlayer(name, target, cam);
		}
		Source s = Source.parse(c);
		if (s == null) {
			return null;
		}
		startSound(mc, level, d, s);
		return new VideoPlayer(s, level.getGameTime() / 20.0);
	}

	/** Sound from the screen's position (like a jukebox), unless paused. */
	private static void startSound(Minecraft mc, ClientLevel level, FloatData d, Source s) {
		if (s.isPaused()) {
			return;
		}
		Playback playback = Playback.radio(s, () -> level.getGameTime() / 20.0);
		StreamSoundInstance sound = new StreamSoundInstance(playback, d.center(), 1F, 0);
		sound.userVolume = Settings.screenVolume(key(d)) / 100F;
		if (mc.getSoundManager().play(sound) != net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED) {
			SOUNDS.put(key(d), sound);
		}
	}

	/** Your own volume for a floating screen (0–100), applied live. */
	static void setVolume(String key, int percent) {
		Settings.setScreenVolume(key, percent);
		StreamSoundInstance s = SOUNDS.get(key);
		if (s != null) {
			s.userVolume = percent / 100F;
		}
	}

	private static void closePlayer(String key) {
		ScreenSource src = PLAYERS.remove(key);
		PLAYER_SPEC.remove(key);
		UNUSED_SINCE.remove(key);
		if (src != null) {
			src.close();
		}
		StreamSoundInstance s = SOUNDS.remove(key);
		if (s != null) {
			s.cancel();
			Minecraft.getInstance().getSoundManager().stop(s);
		}
	}

	public static void closeAll() {
		for (String key : new ArrayList<>(PLAYERS.keySet())) {
			closePlayer(key);
		}
		for (String key : new ArrayList<>(SOUNDS.keySet())) {
			closePlayer(key);
		}
	}

	// ------------------------------------------------------------------ Drawing

	public static void render(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || (OWN.isEmpty() && OTHERS.isEmpty())) {
			return;
		}
		Vec3 cam = ctx.gameRenderer().mainCamera().position();
		PoseStack poseStack = ctx.poseStack();
		String dim = dimension();
		Set<ScreenSource> uploaded = new HashSet<>();
		for (FloatData d : all()) {
			if (d.dim != null && !d.dim.equals(dim) || d.center().distanceToSqr(cam) > DRAW_DISTANCE * DRAW_DISTANCE) {
				continue;
			}
			ScreenSource src = PLAYERS.get(key(d));
			boolean frame = false;
			if (src != null) {
				if (uploaded.add(src)) {
					src.upload();
				}
				frame = src.hasFrame();
			}
			boolean mine = d.owner.equalsIgnoreCase(mc.getUser().getName());

			float w = d.width / 2;
			float h = d.height() / 2;
			poseStack.pushPose();
			poseStack.translate(d.x - cam.x, d.y - cam.y, d.z - cam.z);
			poseStack.rotateDegrees(com.mojang.math.Axis.YP, d.yaw);
			poseStack.rotateDegrees(com.mojang.math.Axis.XP, d.tilt);
			int brightness = Math.round(255 * Settings.screenBrightness() / 100F);
			int tint = 0xFF000000 | (brightness << 16) | (brightness << 8) | brightness;
			boolean showFrame = frame;
			float border = Math.max(0.04F, d.width * 0.015F);
			// plain colours (white 1x1 texture): black back, grey frame, dark-blue placeholder while nothing plays
			ctx.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.text(blackTexture()), (pose, buffer) -> {
				Matrix4f m = pose.pose();
				quad(m, buffer, w + border, h + border, -0.012F, 0xFF000000, true);
				quad(m, buffer, w + border, h + border, -0.006F, 0xFF6B7684, false);
				if (!showFrame) {
					quad(m, buffer, w, h, 0F, 0xFF1F2A3A, false);
				}
			});
			if (showFrame) {
				ctx.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.text(src.textureId()),
						(pose, buffer) -> quad(pose.pose(), buffer, w, h, 0F, tint, false));
			} else {
				String note = !mine && src == null && !d.content.startsWith("tv:") && !d.content.startsWith("cam:") ? d.owner + "'s screen – loading …"
						: d.content.isBlank() ? (mine ? "Empty – press M to pick a video" : d.owner + "'s screen (empty)")
						: src == null ? (d.content.startsWith("tv:") || d.content.startsWith("cam:") ? "Needs an invite from " + d.content.substring(d.content.indexOf(':') + 1) : "Loading …")
						: src.status() != null ? src.status() : "Loading …";
				drawNote(ctx, poseStack, d, note);
			}
			poseStack.popPose();
		}
	}

	/** Text in the middle of the screen (it already faces +Z here). */
	private static void drawNote(LevelRenderContext ctx, PoseStack poseStack, FloatData d, String note) {
		var font = Minecraft.getInstance().font;
		var text = net.minecraft.network.chat.Component.literal(note).getVisualOrderText();
		int textWidth = Math.max(1, font.width(text));
		float scale = Math.min(0.8F * d.width / textWidth, 0.12F * d.height() / font.lineHeight);
		poseStack.pushPose();
		poseStack.translate(0, 0, 0.01);
		poseStack.scale(scale, -scale, scale);
		ctx.submitNodeCollector().submitText(poseStack, -textWidth / 2F, -font.lineHeight / 2F, text, false,
				net.minecraft.client.gui.Font.DisplayMode.NORMAL, LightCoordsUtil.FULL_BRIGHT, 0xFFDDE4EE, 0, 0);
		poseStack.popPose();
	}

	private static void quad(Matrix4f m, VertexConsumer b, float w, float h, float z, int color, boolean back) {
		int light = LightCoordsUtil.FULL_BRIGHT;
		if (!back) {
			b.addVertex(m, -w, -h, z).setColor(color).setUv(0F, 1F).setLight(light);
			b.addVertex(m, w, -h, z).setColor(color).setUv(1F, 1F).setLight(light);
			b.addVertex(m, w, h, z).setColor(color).setUv(1F, 0F).setLight(light);
			b.addVertex(m, -w, h, z).setColor(color).setUv(0F, 0F).setLight(light);
		} else {
			b.addVertex(m, -w, h, z).setColor(color).setUv(0F, 0F).setLight(light);
			b.addVertex(m, w, h, z).setColor(color).setUv(0F, 0F).setLight(light);
			b.addVertex(m, w, -h, z).setColor(color).setUv(0F, 0F).setLight(light);
			b.addVertex(m, -w, -h, z).setColor(color).setUv(0F, 0F).setLight(light);
		}
	}

	private static net.minecraft.resources.Identifier black;

	private static net.minecraft.resources.Identifier blackTexture() {
		if (black == null) {
			black = net.minecraft.resources.Identifier.fromNamespaceAndPath(MultiMediaClient.MOD_ID, "float_black");
			net.minecraft.client.renderer.texture.DynamicTexture t =
					new net.minecraft.client.renderer.texture.DynamicTexture(black::toString, 1, 1, false);
			t.getPixels().setPixel(0, 0, 0xFFFFFFFF);
			t.upload();
			Minecraft.getInstance().getTextureManager().register(black, t);
		}
		return black;
	}

	// ------------------------------------------------------------------ Touch

	/**
	 * Right-click: if you're looking at a floating screen (closer than any block), open the touch screen for it.
	 * Returns true when handled.
	 */
	public static boolean use(Minecraft mc) {
		if (mc.player == null || mc.level == null || (OWN.isEmpty() && OTHERS.isEmpty()) || mc.player.isSecondaryUseActive()) {
			return false;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 dir = mc.player.getLookAngle();
		double blockDist = mc.hitResult != null && mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS
				? mc.hitResult.getLocation().distanceTo(eye) : 64;
		String dim = dimension();
		FloatData best = null;
		double bestT = Math.min(blockDist, 64);
		double bu = 0;
		double bv = 0;
		for (FloatData d : all()) {
			if (d.dim != null && !d.dim.equals(dim)) {
				continue;
			}
			double th = Math.toRadians(d.yaw);
			double ph = Math.toRadians(d.tilt);
			// screen axes after rotating Y by yaw, then X by tilt (same as in render)
			double[] r = {Math.cos(th), 0, -Math.sin(th)};
			double[] u = {Math.sin(ph) * Math.sin(th), Math.cos(ph), Math.sin(ph) * Math.cos(th)};
			double[] n = {Math.cos(ph) * Math.sin(th), -Math.sin(ph), Math.cos(ph) * Math.cos(th)};
			double denom = dir.x * n[0] + dir.y * n[1] + dir.z * n[2];
			if (Math.abs(denom) < 1e-6) {
				continue;
			}
			double t = ((d.x - eye.x) * n[0] + (d.y - eye.y) * n[1] + (d.z - eye.z) * n[2]) / denom;
			if (t <= 0 || t >= bestT) {
				continue;
			}
			double px = eye.x + dir.x * t - d.x;
			double py = eye.y + dir.y * t - d.y;
			double pz = eye.z + dir.z * t - d.z;
			double lu = px * r[0] + py * r[1] + pz * r[2];
			double lv = px * u[0] + py * u[1] + pz * u[2];
			if (Math.abs(lu) <= d.width / 2 && Math.abs(lv) <= d.height() / 2) {
				best = d;
				bestT = t;
				bu = lu / d.width + 0.5;
				bv = 0.5 - lv / d.height();
			}
		}
		if (best == null) {
			return false;
		}
		FloatData target = best;
		boolean mine = target.owner.equalsIgnoreCase(mc.getUser().getName());
		Source video = Source.parse(target.content);
		java.util.function.Consumer<String> actions = mine ? a -> action(target, a) : null;
		mc.gui.setScreen(new TouchScreen(PLAYERS.get(key(target)), video, actions, false,
				mine ? null : target.owner, bu, bv).withVolumeKey(key(target)));
		return true;
	}
}
