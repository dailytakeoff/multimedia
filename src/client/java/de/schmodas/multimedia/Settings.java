package de.schmodas.multimedia;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Einstellungen der Mod (config/multimedia-settings.json; alte Disc-Stream-Datei wird übernommen). */
public final class Settings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("multimedia-settings.json");
	/** Einstellungen aus der Zeit, als die Mod noch "Disc Stream" hieß. */
	private static final Path OLD_FILE = FabricLoader.getInstance().getConfigDir().resolve("discstream-settings.json");

	private static final class Data {
		int wallStrength = 5;
		/** Titel über der Jukebox ausblenden. */
		boolean hideTitles;
		boolean titleBackground = true;
		/** Bis zu dieser Entfernung (Blöcke) ist der Titel zu sehen. */
		int titleDistance = 24;
		/** Noten: 0 = aus, 1 = wenig, 2 = normal, 3 = viel. */
		int notes = 2;
		boolean nowPlaying = true;
		boolean chatMessages = true;
		boolean reverb = true;
		/** Rechtsklick auf Jukebox: Kamera lehnt sich drüber, Bedienfeld. */
		boolean leanView = true;
		/** Videos auf Leinwänden anzeigen. */
		boolean screens = true;
		/** Helligkeit der Leinwand in Prozent (Shader machen leuchtende Flächen oft zu hell). */
		int screenBrightness = 100;
		/** Senden: geheimer Schlüssel (steht nur in Einladungen), Port, zuletzt gewählter Monitor, Eingeladene. */
		String tvToken;
		int tvPort = 25600;
		int tvMonitor = 0;
		/** Webcam device (Windows: dshow name, Linux: /dev/videoN); null = first one found. */
		String camDevice;
		/** Video quality: 0 normal (480p), 1 high (720p), 2 very high (1080p). */
		int videoQuality = 1;
		/** Players known to have the mod (lower case), see ModPeers. */
		java.util.Set<String> knownModPlayers = new java.util.TreeSet<>();
		/** Own floating screens per server address. */
		java.util.Map<String, java.util.List<FloatingScreens.FloatData>> floating = new java.util.HashMap<>();
		/** Ton beim Senden: all, except:<prog>, only:<prog>, off */
		String tvSound = "all";
		/** Share one app window instead of a monitor: "" = whole monitor, "*" = ask (Linux), else window title. */
		String tvWindow = "";
		java.util.Set<String> tvInvited = new java.util.TreeSet<>();
		/** Angenommene Einladungen: Spielername -> Adresse/Port/Schlüssel. */
		java.util.Map<String, TvSource> tvSources = new java.util.TreeMap<>();
		/** Eigene Dämpfung pro Block (Block-ID -> 0..10), z.B. "minecraft:bookshelf": 0 */
		java.util.Map<String, Integer> blocks = new java.util.TreeMap<>();
	}

	private static Data data = new Data();
	/** Wird hochgezählt, wenn sich etwas ändert, damit die Filter neu gesetzt werden. */
	static volatile int version;

	private Settings() {
	}

	public static void load() {
		try {
			if (!Files.exists(FILE) && Files.exists(OLD_FILE)) {
				Files.copy(OLD_FILE, FILE);
				MultiMediaClient.LOGGER.info("Einstellungen von Disc Stream übernommen");
			}
			if (Files.exists(FILE)) {
				Data d = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Data.class);
				if (d != null) {
					data = d;
				}
			}
		} catch (Exception e) {
			MultiMediaClient.LOGGER.warn("Konnte {} nicht lesen", FILE, e);
		}
		data.wallStrength = Math.max(0, Math.min(10, data.wallStrength));
		data.titleDistance = Math.max(8, Math.min(64, data.titleDistance));
		data.notes = Math.max(0, Math.min(3, data.notes));
		if (data.blocks == null) {
			data.blocks = new java.util.TreeMap<>();
		}
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (Exception e) {
			MultiMediaClient.LOGGER.warn("Konnte {} nicht speichern", FILE, e);
		}
	}

	private static volatile boolean muted;

	/** MultiMedia für diesen Spieler aus (gilt bis zum Neustart oder /disc an). */
	public static boolean muted() {
		return muted;
	}

	public static void setMuted(boolean value) {
		muted = value;
	}

	public static boolean titleBackground() {
		return data.titleBackground;
	}

	public static void setTitleBackground(boolean value) {
		data.titleBackground = value;
		save();
	}

	public static int titleDistance() {
		return data.titleDistance;
	}

	public static void setTitleDistance(int blocks) {
		data.titleDistance = Math.max(8, Math.min(64, blocks));
		save();
	}

	public static int notes() {
		return data.notes;
	}

	public static void setNotes(int value) {
		data.notes = Math.max(0, Math.min(3, value));
		save();
	}

	public static boolean nowPlaying() {
		return data.nowPlaying;
	}

	public static void setNowPlaying(boolean value) {
		data.nowPlaying = value;
		save();
	}

	public static boolean chatMessages() {
		return data.chatMessages;
	}

	public static void setChatMessages(boolean value) {
		data.chatMessages = value;
		save();
	}

	public static boolean screens() {
		return data.screens;
	}

	public static void setScreens(boolean value) {
		data.screens = value;
		save();
	}

	/** Wie man den Bildschirm eines anderen Spielers erreicht (aus seiner Einladung). */
	public record TvSource(String host, int port, String token) {
	}

	public static synchronized String tvToken() {
		if (data.tvToken == null || data.tvToken.length() < 16) {
			byte[] b = new byte[16];
			new java.security.SecureRandom().nextBytes(b);
			data.tvToken = java.util.HexFormat.of().formatHex(b);
			save();
		}
		return data.tvToken;
	}

	public static int tvPort() {
		return data.tvPort > 1024 && data.tvPort < 65536 ? data.tvPort : 25600;
	}

	public static synchronized java.util.List<FloatingScreens.FloatData> floating(String server) {
		if (data.floating == null) {
			return java.util.List.of();
		}
		java.util.List<FloatingScreens.FloatData> l = data.floating.get(server);
		return l == null ? java.util.List.of() : new java.util.ArrayList<>(l);
	}

	public static synchronized void setFloating(String server, java.util.List<FloatingScreens.FloatData> screens) {
		if (data.floating == null) {
			data.floating = new java.util.HashMap<>();
		}
		if (screens.isEmpty()) {
			data.floating.remove(server);
		} else {
			data.floating.put(server, screens);
		}
		save();
	}

	public static synchronized java.util.Set<String> knownModPlayers() {
		return data.knownModPlayers == null ? java.util.Set.of() : java.util.Set.copyOf(data.knownModPlayers);
	}

	public static synchronized void addKnownModPlayer(String name) {
		if (data.knownModPlayers == null) {
			data.knownModPlayers = new java.util.TreeSet<>();
		}
		if (data.knownModPlayers.size() < 2000 && data.knownModPlayers.add(name)) {
			save();
		}
	}

	public static String camDevice() {
		return data.camDevice == null || data.camDevice.isBlank() ? null : data.camDevice;
	}

	public static void setCamDevice(String device) {
		data.camDevice = device;
		save();
	}

	public static int tvMonitor() {
		return Math.max(0, data.tvMonitor);
	}

	public static void setTvMonitor(int idx) {
		data.tvMonitor = idx;
		save();
	}

	/** null = whole monitor; "*" = one app, chosen in the desktop's dialog (Linux); else the window title (Windows). */
	public static String tvWindow() {
		return data.tvWindow == null || data.tvWindow.isEmpty() ? null : data.tvWindow;
	}

	public static void setTvWindow(String window) {
		data.tvWindow = window == null ? "" : window;
		save();
	}

	public static String tvSound() {
		String s = data.tvSound;
		return s == null || !s.matches("all|off|(except|only):[^,|\\r\\n]{1,80}") ? "all" : s;
	}

	public static void setTvSound(String sound) {
		data.tvSound = sound;
		save();
	}

	public static java.util.Set<String> tvInvited() {
		return data.tvInvited == null ? java.util.Set.of() : java.util.Set.copyOf(data.tvInvited);
	}

	public static void setTvInvited(java.util.Set<String> names) {
		data.tvInvited = new java.util.TreeSet<>(names);
		save();
	}

	public static TvSource tvSource(String player) {
		return data.tvSources == null ? null : data.tvSources.get(player.toLowerCase(java.util.Locale.ROOT));
	}

	public static void setTvSource(String player, TvSource source) {
		if (data.tvSources == null) {
			data.tvSources = new java.util.TreeMap<>();
		}
		data.tvSources.put(player.toLowerCase(java.util.Locale.ROOT), source);
		save();
	}

	public static int videoQuality() {
		return Math.max(0, Math.min(2, data.videoQuality));
	}

	public static void setVideoQuality(int q) {
		data.videoQuality = Math.max(0, Math.min(2, q));
		save();
	}

	public static int screenBrightness() {
		return Math.max(10, Math.min(100, data.screenBrightness));
	}

	public static void setScreenBrightness(int percent) {
		data.screenBrightness = Math.max(10, Math.min(100, percent));
		save();
	}

	public static boolean leanView() {
		return data.leanView;
	}

	public static void setLeanView(boolean value) {
		data.leanView = value;
		save();
	}

	public static boolean reverb() {
		return data.reverb;
	}

	public static void setReverb(boolean value) {
		data.reverb = value;
		version++;
		save();
	}

	public static boolean hideTitles() {
		return data.hideTitles;
	}

	public static void setHideTitles(boolean value) {
		data.hideTitles = value;
		save();
	}

	/** Wand-Dämpfung 0 (aus) bis 10 (sehr stark), 5 = Standard. */
	public static int wallStrength() {
		return data.wallStrength;
	}

	/** Eigene Dämpfung für einen Block (0..10) oder null, wenn nicht eingestellt. */
	public static Integer blockDamping(String blockId) {
		return data.blocks.get(blockId);
	}

	public static void setBlockDamping(String blockId, Integer value) {
		if (value == null) {
			data.blocks.remove(blockId);
		} else {
			data.blocks.put(blockId, Math.max(0, Math.min(10, value)));
		}
		version++;
		save();
	}

	public static java.util.Map<String, Integer> blockDampings() {
		return new java.util.TreeMap<>(data.blocks);
	}

	public static void setWallStrength(int value) {
		data.wallStrength = Math.max(0, Math.min(10, value));
		version++;
		save();
	}
}
