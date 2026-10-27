package de.schmodas.multimedia;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Senden: Ein Monitor wird aufgenommen (Windows: ddagrab), mit der Grafikkarte (NVENC) oder notfalls dem
 * Prozessor (x264) zu H.264 gewandelt und als MPEG-TS an alle Zuschauer verteilt. Einmal umwandeln, an alle
 * verschicken. Zuschauer verbinden sich direkt mit diesem PC und müssen den geheimen Schlüssel aus der
 * Einladung mitschicken – sonst bekommen sie nichts.
 * Beim Schließen von Minecraft endet alles (ffmpeg merkt, dass niemand mehr liest, und beendet sich auch).
 */
public final class TvSender {
	static final String HELLO = "MMTV1 ";
	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
	/** Sent picture size and bitrate, from the quality setting when sharing starts (0 normal, 1 high, 2 very high). */
	private final int sendW = Settings.videoQuality() >= 2 ? 1920 : 1280;
	private final int sendH = Settings.videoQuality() >= 2 ? 1080 : 720;
	private final int baseBitrateK = switch (Settings.videoQuality()) {
		case 0 -> 3000;
		case 2 -> 8000;
		default -> 4500;
	};
	private final String SCALE = "scale=" + sendW + ":" + sendH + ":force_original_aspect_ratio=decrease:flags=lanczos,pad=" + sendW + ":" + sendH
			+ ":(ow-iw)/2:(oh-ih)/2";
	/** Puffer pro Zuschauer; ist er voll (zu langsames Internet), wird der Zuschauer getrennt und verbindet neu. */
	private static final int VIEWER_QUEUE = 512;

	private static volatile TvSender current;
	/** Webcam: runs alongside the screen share, as a second sender on its own port (see CamManager). */
	private static volatile TvSender camCurrent;

	/** true: this sender streams the webcam instead of a monitor. */
	private final boolean cam;
	/** Webcam device (Windows: dshow name, Linux: /dev/videoN); null = first one found. */
	private volatile String camDevice;
	/** Size of the captured picture before scaling (for remote control); 0 = unknown. */
	volatile int sourceWidth;
	volatile int sourceHeight;

	private volatile int monitor;
	private volatile String sound;
	/** One app window instead of the monitor (see Settings.tvWindow), or null. */
	private volatile String window = Settings.tvWindow(); // cleared in the constructor for the webcam
	/** Monitor/Ton wurde während des Sendens geändert: Aufnahme neu starten, Zuschauer bleiben verbunden. */
	private volatile boolean restart;
	/** Bitrate des Bildes (kbit/s): bis 3 Zuschauer 3000, darüber weniger, damit der Upload reicht. */
	private volatile int bitrateK = 3000;
	/** So viel Upload dürfen alle Zuschauer zusammen ungefähr brauchen (kbit/s). */
	private final int UPLOAD_BUDGET_K = Math.max(10_000, 2 * baseBitrateK);
	private volatile Consumer<String> changeStatus;
	private final List<Process> processes = new CopyOnWriteArrayList<>();
	private final List<Viewer> viewers = new CopyOnWriteArrayList<>();
	private volatile boolean stopped;
	private volatile Process ffmpeg;
	private ServerSocket server;
	private volatile String encoderName = "?";
	/** Direkte Verbindungen zu Freunden (UDP, siehe P2p). */
	private P2p.Endpoint udp;
	private volatile P2p.StunResult stun;
	private final Map<Long, UdpViewer> udpViewers = new ConcurrentHashMap<>();
	/** Zuschauer, die gefragt haben, während nicht gesendet wurde – bekommen beim Start Bescheid. */
	private static final Set<String> WAITING = ConcurrentHashMap.newKeySet();
	private static final Set<String> CAM_WAITING = ConcurrentHashMap.newKeySet();

	private Set<String> waiting() {
		return cam ? CAM_WAITING : WAITING;
	}
	/** Die letzten Pakete (zum Nachsenden), Nummer = seq. */
	private static final int RING = 8192;
	private final byte[][] ring = new byte[RING][];
	private volatile int producedSeq;
	private final Object newData = new Object();
	/** Höchstens so viel hochladen (Bytes/s, alle Zuschauer zusammen) – lässt bei 17 Mbit/s Luft. */
	private final double MAX_UPLOAD = Math.max(13_000, UPLOAD_BUDGET_K * 1.3) * 1000 / 8.0;

	private TvSender(int monitor, String sound) {
		this(monitor, sound, false, null);
	}

	private TvSender(int monitor, String sound, boolean cam, String camDevice) {
		this.monitor = monitor;
		this.sound = sound;
		this.cam = cam;
		this.camDevice = camDevice;
		if (cam) {
			this.window = null;
		}
		this.bitrateK = cam ? 800 : Math.min(baseBitrateK, 3000); // start safe, go up while nothing is lost
	}

	public static boolean isSending() {
		return current != null && !current.stopped;
	}

	public static TvSender current() {
		return current;
	}

	public boolean isCam() {
		return cam;
	}

	// ------------------------------------------------------------------ Webcam

	public static boolean isCamOn() {
		return camCurrent != null && !camCurrent.stopped;
	}

	/** Starts the webcam stream (or restarts it with another device). */
	public static synchronized void startCam(String device, Consumer<String> status, Runnable onReady) {
		if (isCamOn()) {
			camCurrent.stop();
		}
		TvSender s = new TvSender(0, "off", true, device);
		camCurrent = s;
		Thread t = new Thread(() -> s.run(status, onReady), "MultiMedia-CamSend");
		t.setDaemon(true);
		t.start();
	}

	public static synchronized void stopCam() {
		if (camCurrent != null) {
			camCurrent.stop();
			camCurrent = null;
		}
	}

	/** Stops only the screen share (the webcam keeps running). */
	public static synchronized void stopScreen() {
		if (current != null) {
			current.stop();
			current = null;
		}
	}

	// ------------------------------------------------------------------ Linux: sound of one app (PulseAudio / PipeWire-Pulse)

	/** One app playing sound: its stream number and name, from "pactl list sink-inputs". */
	private record SinkInput(int index, String name) {
	}

	private static List<SinkInput> sinkInputs() {
		List<SinkInput> out = new ArrayList<>();
		try {
			ProcessBuilder pb = new ProcessBuilder("pactl", "list", "sink-inputs").redirectErrorStream(true);
			pb.environment().put("LC_ALL", "C");
			Process p = pb.start();
			String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor(5, TimeUnit.SECONDS);
			int index = -1;
			String name = null;
			for (String line : text.split("\\R")) {
				String t = line.trim();
				if (t.startsWith("Sink Input #")) {
					if (index >= 0 && name != null) {
						out.add(new SinkInput(index, name));
					}
					name = null;
					try {
						index = Integer.parseInt(t.substring("Sink Input #".length()).trim());
					} catch (NumberFormatException e) {
						index = -1;
					}
				} else if (t.startsWith("application.name = ") && name == null) {
					name = t.substring("application.name = ".length()).replace("\"", "").trim();
				}
			}
			if (index >= 0 && name != null) {
				out.add(new SinkInput(index, name));
			}
		} catch (Exception e) {
			MultiMediaClient.LOGGER.info("pactl ging nicht: {}", e.toString());
		}
		out.removeIf(si -> si.name().isBlank() || !si.name().matches("[^,|\\r\\n]{1,80}"));
		return out;
	}

	/** Apps that are playing sound right now (Minecraft itself left out). */
	private static List<String> linuxAudioApps() {
		List<String> out = new ArrayList<>();
		for (SinkInput si : sinkInputs()) {
			String n = si.name();
			if (!n.toLowerCase(Locale.ROOT).contains("minecraft") && !n.equalsIgnoreCase("java") && !n.equalsIgnoreCase("OpenAL Soft")
					&& !out.contains(n)) {
				out.add(n);
			}
		}
		return out;
	}

	/** Recorder for one app's sound, started right after ffmpeg (it writes into a FIFO ffmpeg reads). */
	private volatile List<String> pendingRecorder;
	private volatile java.nio.file.Path pendingFifo;

	/** Program file of each app window from the last appWindows() (title -> e.g. "Discord.exe"). */
	static final Map<String, String> APP_EXE = new ConcurrentHashMap<>();

	public static String appExe(String title) {
		return title == null ? null : APP_EXE.get(title);
	}

	/** Open app windows by title (Windows, via the bundled helper); empty elsewhere or if the helper is missing. */
	public static List<String> appWindows() {
		List<String> out = new ArrayList<>();
		java.nio.file.Path helper = WINDOWS ? Tools.inputHelper() : null;
		if (helper == null) {
			return out;
		}
		try {
			Process p = new ProcessBuilder(helper.toString()).redirectErrorStream(true).start();
			try (OutputStream o = p.getOutputStream()) {
				o.write("windows\n".getBytes(StandardCharsets.US_ASCII));
			}
			String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor(5, TimeUnit.SECONDS);
			for (String line : text.split("\\R")) {
				String[] parts = line.trim().split(" ");
				if (parts.length >= 2 && parts[0].equals("window")) {
					try {
						String title = new String(java.util.Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
						String exe = parts.length >= 3 ? new String(java.util.Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8) : "";
						if (!title.isBlank() && !out.contains(title) && !title.equals("Program Manager")) {
							out.add(title);
							if (exe.matches("[^,|\\r\\n]{1,80}")) {
								APP_EXE.put(title, exe);
							}
						}
					} catch (IllegalArgumentException ignored) {
					}
				}
			}
		} catch (Exception e) {
			MultiMediaClient.LOGGER.info("App-Fenster auflisten ging nicht: {}", e.toString());
		}
		return out;
	}

	/** Webcams on this PC: Windows by dshow name, Linux as /dev/videoN. */
	public static List<String> camDevices(Tools.Paths paths) {
		List<String> out = new ArrayList<>();
		if (WINDOWS) {
			try {
				Process p = new ProcessBuilder(paths.ffmpeg(), "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy")
						.redirectErrorStream(true).start();
				String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				p.waitFor(10, TimeUnit.SECONDS);
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*\\(video\\)").matcher(text);
				while (m.find()) {
					if (!out.contains(m.group(1))) {
						out.add(m.group(1));
					}
				}
			} catch (Exception e) {
				MultiMediaClient.LOGGER.info("Webcam list failed: {}", e.toString());
			}
		} else {
			for (int i = 0; i < 10; i++) {
				if (java.nio.file.Files.exists(java.nio.file.Path.of("/dev/video" + i))) {
					out.add("/dev/video" + i);
				}
			}
		}
		return out;
	}

	/** Zuschauer (ohne einen selbst). */
	public int viewerCount() {
		int n = 0;
		for (Viewer v : viewers) {
			if (!v.local) {
				n++;
			}
		}
		for (UdpViewer v : udpViewers.values()) {
			if (v.addr != null) {
				n++;
			}
		}
		return n;
	}

	int monitor() {
		return monitor;
	}

	/** The shared app window (title on Windows, "*" on Linux), or null for the whole monitor. */
	String window() {
		return window;
	}

	public String encoderName() {
		return encoderName;
	}

	/** Hinweis zur Erreichbarkeit (leer, wenn alles gut aussieht). */
	public String networkInfo() {
		P2p.StunResult r = stun;
		if (r == null) {
			return "";
		}
		if (r.mapped() == null) {
			return T.t("Achtung: Konnte nicht prüfen, wie du von außen erreichbar bist (UDP blockiert?) – Freunde sehen dich evtl. nicht.");
		}
		if (r.symmetric()) {
			return T.t("Achtung: Dein Router macht es schwer, direkt verbunden zu werden – bei manchen Freunden klappt es evtl. nicht.");
		}
		return "";
	}

	/** Startet das Senden (oder lässt ein laufendes weiterlaufen). Meldungen gehen an status. */
	public static synchronized void start(int monitor, String sound, Consumer<String> status, Runnable onReady) {
		if (isSending()) {
			if (current.monitor == monitor && current.sound.equals(sound) && java.util.Objects.equals(current.window, Settings.tvWindow())) {
				onReady.run();
				return;
			}
			current.stop();
		}
		TvSender s = new TvSender(monitor, sound);
		current = s;
		Thread t = new Thread(() -> s.run(status, onReady), "MultiMedia-TvSend");
		t.setDaemon(true);
		t.start();
	}

	/** Passt die Bildqualität an die Zahl der Zuschauer an (runter sofort nach 5 s, wieder rauf erst nach 30 s). */
	private void qualityLoop() {
		if (cam) {
			return; // webcam: fixed small bitrate
		}
		long wantSince = 0;
		int lastWant = bitrateK;
		long cleanSince = System.currentTimeMillis();
		long lastLossDrop = 0;
		while (!stopped) {
			P2p.sleep(2000);
			int viewers = viewerCount();
			// --- loss: if a friend asks for many packets again, the upload is full -> less bitrate right away
			double worst = 0;
			String worstName = null;
			for (UdpViewer v : udpViewers.values()) {
				int sent = v.sentCount.getAndSet(0);
				int lost = v.lostCount.getAndSet(0);
				if (sent > 50) {
					double loss = lost / (double) sent;
					if (loss > worst) {
						worst = loss;
						worstName = v.name;
					}
				}
			}
			long now = System.currentTimeMillis();
			if (worst > 0.03 && now - lastLossDrop > 6000 && bitrateK > 700) {
				lossCapK = Math.max(600, (int) (Math.min(lossCapK, bitrateK) * (worst > 0.15 ? 0.5 : 0.7)) / 100 * 100);
				lastLossDrop = now;
				cleanSince = now;
				MultiMediaClient.LOGGER.info("Senden: {} % Verlust bei {} – Bitrate runter auf {} kbit/s",
						Math.round(worst * 100), worstName, lossCapK);
			} else if (worst > 0.005) {
				cleanSince = now;
			} else if (now - cleanSince > 30_000 && lossCapK < baseBitrateK) {
				lossCapK = Math.min(baseBitrateK, (int) (lossCapK * 1.25) / 100 * 100); // carefully back up
				cleanSince = now;
			}
			int want = viewers <= 3 ? baseBitrateK : Math.max(1200, Math.min(baseBitrateK, (UPLOAD_BUDGET_K / viewers) / 100 * 100));
			want = Math.min(want, lossCapK);
			if (lastLossDrop == now && want < bitrateK) {
				wantSince = 0; // loss: change right now, don't wait
				lastWant = want;
			}
			if (want != lastWant) {
				lastWant = want;
				wantSince = System.currentTimeMillis();
			}
			long stable = System.currentTimeMillis() - wantSince;
			boolean down = want < bitrateK && stable > 5_000;
			boolean up = want > bitrateK && stable > 30_000;
			if ((down || up) && !restart && ffmpeg != null) {
				MultiMediaClient.LOGGER.info("Senden: {} Zuschauer – Bildqualität {} -> {} kbit/s", viewers, bitrateK, want);
				bitrateK = want;
				restart = true;
				for (Process p : processes) {
					p.descendants().forEach(ProcessHandle::destroyForcibly);
					p.destroyForcibly();
				}
				if (lastLossDrop == now) {
					MultiMediaClient.info("Your upload is full – lowering stream quality so it stops freezing.");
				} else if (lossCapK >= baseBitrateK || down) {
					MultiMediaClient.info(down ? T.t("Viele Zuschauer – Bildqualität etwas runter, damit dein Internet reicht.")
							: T.t("Weniger Zuschauer – volle Bildqualität."));
				}
			}
		}
	}

	/** Highest bitrate the friends' connections took without losing packets (starts at a safe 3 Mbit/s). */
	private volatile int lossCapK = 3000;

	/** Monitor/Ton ändern, ohne das Senden zu beenden. False, wenn gerade nicht gesendet wird. */
	public static synchronized boolean change(int monitor, String sound, Consumer<String> status) {
		TvSender s = current;
		if (s == null || s.stopped) {
			return false;
		}
		String window = Settings.tvWindow();
		if (s.monitor == monitor && s.sound.equals(sound) && java.util.Objects.equals(s.window, window)) {
			status.accept(T.t("Keine Änderung – du sendest schon Monitor ") + (monitor + 1) + ".");
			return true;
		}
		s.window = window;
		s.monitor = monitor;
		s.sound = sound;
		s.changeStatus = status;
		s.restart = true;
		for (Process p : s.processes) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
		}
		status.accept(T.t("Wechsle zu Monitor ") + (monitor + 1) + " …");
		return true;
	}

	/** Stops everything (screen share and webcam), e.g. when leaving the server. */
	public static synchronized void stopAll() {
		if (current != null) {
			current.stop();
			current = null;
		}
		stopCam();
	}

	// ------------------------------------------------------------------ Ablauf

	private void run(Consumer<String> status, Runnable onReady) {
		try {
			Tools.Paths paths = Tools.ensure(status).get();
			int port = cam ? Settings.tvPort() + 1 : Settings.tvPort();
			server = new ServerSocket();
			server.setReuseAddress(true);
			server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16); // nur für die eigene Leinwand
			Thread accept = new Thread(this::acceptLoop, "MultiMedia-TvAccept");
			accept.setDaemon(true);
			accept.start();

			// Direkte Verbindungen zu Freunden: UDP-Anschluss, Adresse von außen erfragen (und wach halten)
			udp = new P2p.Endpoint();
			udp.onPacket(this::onUdp);
			Thread router = new Thread(() -> {
				while (!stopped) {
					P2p.StunResult r = udp.stun();
					if (stun == null || r.mapped() != null) {
						stun = r;
					}
					MultiMediaClient.LOGGER.info("Senden: von außen erreichbar als {}{}", r.mapped() == null ? "?" : P2p.format(r.mapped()),
							r.symmetric() ? " (Router wechselt Ports – schwierig)" : "");
					for (int i = 0; i < 25 && !stopped; i++) {
						P2p.sleep(1000);
					}
				}
			}, "MultiMedia-Stun");
			router.setDaemon(true);
			router.start();
			Thread quality = new Thread(this::qualityLoop, "MultiMedia-TvQuality");
			quality.setDaemon(true);
			quality.start();
			Thread pacer = new Thread(this::pacerLoop, "MultiMedia-TvPacer");
			pacer.setDaemon(true);
			pacer.start();

			// Aufnahme starten: erst Grafikkarte, dann Prozessor, dann einfache Aufnahme
			// Bricht eine Variante kurz nach dem Start ab, gleich die nächste versuchen.
			// Wird Monitor/Ton geändert, startet die Aufnahme neu – Zuschauer bleiben verbunden.
			boolean ready = false;
			outer:
			while (!stopped) {
				restart = false;
				boolean started = false;
				List<Variant> list = variants();
				if (list.isEmpty() && "unbekannt".equals(lastError)) {
					lastError = cam ? "no webcam found" : "no screen capture tool found";
				}
				for (int vi = 0; vi < list.size() && !stopped; vi++) {
					Variant v = list.get(vi);
					if (!startEncoder(paths, v)) {
						continue;
					}
					started = true;
					encoderName = T.t(v.name);
					if (!ready) {
						ready = true;
						for (int i = 0; i < 60 && stun == null; i++) {
							P2p.sleep(100);
						}
						onReady.run();
						// wer vorhin gefragt hat, bekommt Bescheid, dass es losgeht
						for (String w : waiting()) {
							TvSignal.answer(w, 0, "on", List.of(), cam);
						}
						waiting().clear();
					} else if (changeStatus != null && !cam) {
						changeStatus.accept(T.t("Du sendest jetzt Monitor ") + (monitor + 1) + " (" + encoderName + ", " + soundInfo + ").");
						changeStatus = null;
					}
					long began = System.currentTimeMillis();
					try {
						pump();
					} catch (IOException e) {
						if (!restart && !stopped) {
							throw e;
						}
					}
					processes.forEach(Process::destroyForcibly);
					processes.clear();
					if (stopped) {
						return;
					}
					if (restart) {
						continue outer;
					}
					if (System.currentTimeMillis() - began > 20_000 || vi == list.size() - 1) {
						break outer; // lief eine Weile – echter Abbruch
					}
					MultiMediaClient.LOGGER.info("Senden: Variante '{}' brach gleich ab ({}), versuche die nächste", v.name, lastError);
				}
				if (!started) {
					break;
				}
			}
			if (!stopped) {
				MultiMediaClient.LOGGER.warn("Senden: Aufnahme beendet ({})", lastError);
				if (cam) {
					status.accept(ready ? "Webcam stopped: capture ended (" + lastError + ")"
							: "Webcam didn't start: " + lastError);
				} else {
					status.accept(ready ? T.t("Senden beendet: Aufnahme ist abgebrochen (") + lastError + ")"
							: T.t("Bildschirm-Aufnahme ging nicht: ") + lastError + (WINDOWS ? "" : LINUX_HINT));
				}
				stop();
			}
		} catch (Exception e) {
			if (!stopped) {
				MultiMediaClient.LOGGER.warn("Senden fehlgeschlagen", e);
				status.accept(T.t("Senden fehlgeschlagen: ") + e.getMessage());
			}
			stop();
		}
	}

	/**
	 * One way of capturing. pre: an extra program whose output (any container ffmpeg understands) is piped into
	 * ffmpeg's stdin – used on Wayland, where ffmpeg can't grab the screen by itself. waitMs: how long to wait for
	 * the first data (longer when the system shows a "which screen?" dialog first).
	 */
	private record Variant(String name, List<String> input, List<String> output, List<String> pre, String preStdin, long waitMs) {
		Variant(String name, List<String> input, List<String> output) {
			this(name, input, output, null, null, 6000);
		}
	}

	private volatile String lastError = "unbekannt";
	private volatile String soundInfo = "";

	public String soundInfo() {
		return soundInfo;
	}

	static final String LINUX_HINT = " – on Linux install wf-recorder (Hyprland/Niri/Sway), gpu-screen-recorder (any Wayland desktop)"
			+ " or use an X11 session. Check latest.log for details.";

	/** Aufnahme-Varianten, beste zuerst. */
	private List<Variant> variants() {
		String bitrate = bitrateK + "k";
		String bufsize = (bitrateK / 2) + "k";
		// p5 + adaptive quantisation: more detail where it shows (text, faces) at the same bitrate, still low delay
		List<String> nvenc = List.of("-c:v", "h264_nvenc", "-preset", "p5", "-tune", "ll", "-rc", "cbr", "-spatial-aq", "1",
				"-b:v", bitrate, "-maxrate", bitrate, "-bufsize", bufsize, "-g", "60", "-bf", "0", "-pix_fmt", "yuv420p");
		List<String> amf = List.of("-c:v", "h264_amf", "-usage", "lowlatency", "-quality", "quality", "-rc", "cbr",
				"-b:v", bitrate, "-maxrate", bitrate, "-bufsize", bufsize, "-g", "60", "-bf", "0", "-pix_fmt", "yuv420p");
		List<String> qsv = List.of("-c:v", "h264_qsv", "-preset", "medium", "-low_power", "0", "-b:v", bitrate,
				"-maxrate", bitrate, "-bufsize", bufsize, "-g", "60", "-bf", "0", "-pix_fmt", "nv12");
		List<String> x264 = List.of("-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-b:v", bitrate,
				"-maxrate", bitrate, "-bufsize", bufsize, "-g", "60", "-bf", "0", "-pix_fmt", "yuv420p");
		if (cam) {
			return camVariants(x264);
		}
		List<Variant> out = new ArrayList<>();
		String win = window;
		if (WINDOWS && win != null && !win.equals("*")) {
			// one app window (GDI): graphics card if possible, else processor
			List<String> gdi = List.of("-f", "gdigrab", "-framerate", "30", "-draw_mouse", "1", "-i", "title=" + win);
			out.add(new Variant("App (Grafikkarte)", gdi, concat(List.of("-vf", SCALE + ",format=yuv420p"), nvenc)));
			out.add(new Variant("App (AMD)", gdi, concat(List.of("-vf", SCALE), amf)));
			out.add(new Variant("App (Intel)", gdi, concat(List.of("-vf", SCALE), qsv)));
			out.add(new Variant("App (Prozessor)", gdi, concat(List.of("-vf", SCALE), x264)));
			return out;
		}
		if (WINDOWS) {
			List<String> dda = List.of("-f", "lavfi", "-i", "ddagrab=output_idx=" + monitor + ":framerate=30:draw_mouse=0");
			List<String> ddaVf = List.of("-vf", "hwdownload,format=bgra," + SCALE);
			// am sparsamsten: Bild bleibt komplett auf der Grafikkarte (aufnehmen, verkleinern, umwandeln)
			List<String> ddaGpu = List.of("-f", "lavfi", "-i", "ddagrab=output_idx=" + monitor
					+ ":framerate=30:draw_mouse=0,scale_d3d11=width=" + sendW + ":height=" + sendH + ":format=nv12");
			List<String> nvencGpu = List.of("-c:v", "h264_nvenc", "-preset", "p5", "-tune", "ll", "-rc", "cbr", "-spatial-aq", "1",
					"-b:v", bitrate, "-maxrate", bitrate, "-bufsize", bufsize, "-g", "60", "-bf", "0");
			out.add(new Variant("Grafikkarte", ddaGpu, nvencGpu));
			out.add(new Variant("Grafikkarte+Prozessor", dda, concat(ddaVf, nvenc)));
			// AMD / Intel graphics (a missing encoder fails at once and the next one is tried)
			out.add(new Variant("Grafikkarte (AMD)", dda, concat(ddaVf, amf)));
			out.add(new Variant("Grafikkarte (Intel)", dda, concat(ddaVf, qsv)));
			out.add(new Variant("Prozessor", dda, concat(ddaVf, x264)));
			out.add(new Variant("Prozessor, ganzer Desktop", List.of("-f", "gdigrab", "-framerate", "30", "-i", "desktop"),
					concat(List.of("-vf", SCALE), x264)));
		} else {
			out.addAll(linuxVariants(x264));
		}
		return out;
	}

	/**
	 * Linux: Wayland doesn't let programs simply read the screen, so the capture goes through a helper that asks
	 * the desktop (PipeWire portal or wlroots screencopy). X11 can be grabbed directly. Every option that isn't
	 * installed or doesn't work is skipped automatically (see run()).
	 */
	private List<Variant> linuxVariants(List<String> x264) {
		List<Variant> out = new ArrayList<>();
		List<String> fromPipe = List.of("-fflags", "nobuffer", "-thread_queue_size", "512", "-i", "pipe:0");
		List<String> scaled = concat(List.of("-vf", SCALE), x264);
		boolean wayland = System.getenv("WAYLAND_DISPLAY") != null
				|| "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"));
		String display = System.getenv("DISPLAY");
		if (window != null) {
			// one app: only the screen-share portal can do that on Wayland (the desktop asks which window)
			if (wayland && ffmpegHasFilter("pipewiregrab")) {
				out.add(new Variant("PipeWire (ffmpeg)", List.of("-f", "lavfi", "-i", "pipewiregrab=framerate=30"), scaled,
						null, null, 60_000));
			}
			if (wayland && onPath("gpu-screen-recorder")) {
				List<String> gsr = List.of("gpu-screen-recorder", "-w", "portal", "-f", "30", "-k", "h264", "-q", "very_high", "-c", "mkv",
						"-o", "/dev/stdout");
				out.add(new Variant("gpu-screen-recorder", fromPipe, scaled, gsr, null, 60_000));
			}
			if (out.isEmpty()) {
				lastError = "sharing one app on Linux needs gpu-screen-recorder (Wayland)";
			}
			return out;
		}
		if (wayland) {
			if (ffmpegHasFilter("pipewiregrab")) {
				out.add(new Variant("PipeWire (ffmpeg)", List.of("-f", "lavfi", "-i", "pipewiregrab=framerate=30"), scaled,
						null, null, 60_000));
			}
			if (onPath("wf-recorder")) {
				// wlroots desktops (Hyprland, Sway, Niri …). With several monitors it asks which one on stdin -> answer it.
				// near-lossless in-between step (crf 16), so our own encode gets a clean picture
				List<String> wf = List.of("wf-recorder", "-y", "-r", "30", "-c", "libx264", "-p", "preset=ultrafast",
						"-p", "tune=zerolatency", "-p", "crf=16", "-m", "matroska", "-f", "/dev/stdout");
				out.add(new Variant("wf-recorder", fromPipe, scaled, wf, (monitor + 1) + "\n", 15_000));
			}
			if (onPath("gpu-screen-recorder")) {
				// works on every Wayland desktop through the screen-share portal (the desktop asks which screen)
				List<String> gsr = List.of("gpu-screen-recorder", "-w", "portal", "-f", "30", "-k", "h264", "-q", "very_high", "-c", "mkv",
						"-o", "/dev/stdout");
				out.add(new Variant("gpu-screen-recorder", fromPipe, scaled, gsr, null, 60_000));
			}
		}
		if (display != null && !display.isBlank()) {
			// X11 (on Wayland this only sees X11 windows via XWayland, so it comes last)
			out.add(new Variant("X11", List.of("-f", "x11grab", "-framerate", "30", "-draw_mouse", "1", "-i", display), scaled));
		}
		return out;
	}

	/** Webcam: cropped to 16:9 and small, it's only shown above the player's head. */
	private List<Variant> camVariants(List<String> x264) {
		List<String> vf = List.of("-vf", "crop='min(iw,ih*16/9)':'min(ih,iw*9/16)',scale=640:360,setsar=1");
		List<String> devices = new ArrayList<>();
		if (camDevice != null && !camDevice.isBlank()) {
			devices.add(camDevice);
		}
		List<Variant> out = new ArrayList<>();
		if (WINDOWS) {
			if (devices.isEmpty()) {
				try {
					devices.addAll(camDevices(Tools.ensure(s -> { }).get()));
				} catch (Exception ignored) {
				}
			}
			for (String d : devices) {
				out.add(new Variant("Webcam " + d, List.of("-f", "dshow", "-rtbufsize", "64M", "-i", "video=" + d), concat(vf, x264)));
			}
		} else {
			// chosen device first, then every other one (a webcam often has a second, picture-less node)
			for (String d : camDevices(null)) {
				if (!devices.contains(d)) {
					devices.add(d);
				}
			}
			for (String d : devices) {
				// MJPEG first: most USB webcams only do 30 fps at higher sizes that way; then whatever the camera offers
				out.add(new Variant("Webcam " + d + " (MJPEG)", List.of("-f", "v4l2", "-input_format", "mjpeg",
						"-video_size", "1280x720", "-framerate", "30", "-i", d), concat(vf, x264)));
				out.add(new Variant("Webcam " + d, List.of("-f", "v4l2", "-i", d), concat(vf, x264)));
			}
		}
		return out;
	}

	private static final Map<String, Boolean> FILTER_CACHE = new ConcurrentHashMap<>();

	private static boolean ffmpegHasFilter(String name) {
		return FILTER_CACHE.computeIfAbsent(name, n -> {
			try {
				Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-h", "filter=" + n).redirectErrorStream(true).start();
				String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				p.waitFor(5, TimeUnit.SECONDS);
				return text.contains("Filter " + n);
			} catch (Exception e) {
				return false;
			}
		});
	}

	static boolean onPath(String program) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (!dir.isEmpty() && java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, program))) {
				return true;
			}
		}
		return false;
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> l = new ArrayList<>(a);
		l.addAll(b);
		return l;
	}

	/** Ton-Eingang für ffmpeg (Eingang Nr. 1); helper ist der Ton-Helfer-Befehl, wenn der Ton über stdin kommt. */
	private List<String> audioInput(List<String> helper) {
		if (helper != null) {
			return List.of("-f", "s16le", "-ar", "48000", "-ac", "2", "-i", "pipe:0");
		}
		pendingRecorder = null;
		pendingFifo = null;
		if (!WINDOWS && !cam && sound.startsWith("only:")) {
			// Linux, one app: record just its stream (parec --monitor-stream) into a FIFO
			String app = sound.substring(5);
			SinkInput match = null;
			for (SinkInput si : sinkInputs()) {
				if (si.name().equalsIgnoreCase(app)) {
					match = si;
					break;
				}
			}
			if (match != null && onPath("parec") && onPath("mkfifo")) {
				try {
					java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("multimedia-audio");
					java.nio.file.Path fifo = dir.resolve("app.pcm");
					Process mk = new ProcessBuilder("mkfifo", fifo.toString()).start();
					if (mk.waitFor(5, TimeUnit.SECONDS) && mk.exitValue() == 0) {
						pendingFifo = fifo;
						pendingRecorder = List.of("parec", "--monitor-stream=" + match.index(), "--format=s16le", "--rate=48000",
								"--channels=2", "--raw", "--latency-msec=40");
						soundInfo = "sound: only " + app;
						return List.of("-f", "s16le", "-ar", "48000", "-ac", "2", "-thread_queue_size", "512", "-i", fifo.toString());
					}
				} catch (Exception e) {
					MultiMediaClient.LOGGER.info("App-Ton ging nicht: {}", e.toString());
				}
			}
			soundInfo = match == null ? "sound: whole PC (" + app + " isn't playing anything)" : "sound: whole PC (parec missing)";
		}
		if (!WINDOWS && !cam && !sound.equals("off")) {
			// Linux: everything the PC plays (PulseAudio / PipeWire-Pulse), Minecraft included
			return List.of("-f", "pulse", "-thread_queue_size", "512", "-sample_rate", "48000", "-channels", "2",
					"-i", "@DEFAULT_MONITOR@");
		}
		return List.of("-re", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo");
	}

	/** Befehl für den Ton-Helfer, oder null (Ton aus / nicht Windows / Helfer fehlt). */
	private List<String> helperCommand() {
		if (cam || sound.equals("off")) {
			soundInfo = cam ? "no sound" : "ohne Ton";
			return null;
		}
		if (!WINDOWS) {
			soundInfo = sound.startsWith("only:") ? "sound: only " + sound.substring(5) : "sound: whole PC";
			return null;
		}
		java.nio.file.Path helper = Tools.audioHelper();
		if (helper == null) {
			soundInfo = T.t("ohne Ton (Ton-Helfer fehlt)");
			return null;
		}
		String self = Long.toString(ProcessHandle.current().pid());
		if (sound.startsWith("except:")) {
			soundInfo = T.t("Ton: alles außer ") + sound.substring(7);
			return List.of(helper.toString(), "except", self, sound.substring(7));
		}
		if (sound.startsWith("only:")) {
			soundInfo = T.t("Ton: nur ") + sound.substring(5);
			return List.of(helper.toString(), "only", self, sound.substring(5));
		}
		soundInfo = T.t("Ton: alles");
		return List.of(helper.toString(), "all", self);
	}

	/** Startet ffmpeg (mit Ton-Helfer bzw. Bild-Helfer davor); true, wenn rechtzeitig Daten kommen. */
	private boolean startEncoder(Tools.Paths paths, Variant v) throws IOException, InterruptedException {
		List<String> helper = v.pre != null ? null : helperCommand();
		if (v.pre != null) {
			helperCommand(); // only sets soundInfo (Linux: sound comes from PulseAudio, not stdin)
		}
		List<String> front = v.pre != null ? v.pre : helper;
		List<String> cmd = new ArrayList<>(List.of(paths.ffmpeg(), "-hide_banner", "-loglevel", WINDOWS ? "error" : "info", "-nostats"));
		if (front == null) {
			cmd.add("-nostdin");
		}
		cmd.addAll(v.input);
		cmd.addAll(audioInput(helper));
		cmd.addAll(List.of("-map", "0:v:0", "-map", "1:a:0"));
		cmd.addAll(v.output);
		cmd.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2", "-max_interleave_delta", "500000",
				"-f", "mpegts", "-muxdelay", "0", "-flush_packets", "1", "pipe:1"));
		sourceWidth = 0;
		sourceHeight = 0;
		List<Process> started;
		try {
			if (front != null) {
				started = ProcessBuilder.startPipeline(List.of(new ProcessBuilder(front), new ProcessBuilder(cmd)));
				logErrors(started.get(0), v.pre != null ? "bild" : "ton");
				OutputStream first = started.get(0).getOutputStream();
				try {
					if (v.preStdin != null) {
						first.write(v.preStdin.getBytes(StandardCharsets.UTF_8));
						first.flush();
					}
				} catch (IOException ignored) {
				}
			} else {
				started = List.of(new ProcessBuilder(cmd).start());
			}
		} catch (IOException e) {
			lastError = e.getMessage(); // z. B. Programm nicht installiert
			MultiMediaClient.LOGGER.info("Senden: Variante '{}' ging nicht: {}", v.name, lastError);
			return false;
		}
		Process p = started.get(started.size() - 1);
		logErrors(p, v.name);
		List<String> recorder = pendingRecorder;
		java.nio.file.Path fifo = pendingFifo;
		if (recorder != null && fifo != null) {
			// opening the FIFO for writing waits until ffmpeg reads it – so in its own thread
			Thread rec = new Thread(() -> {
				try {
					Process r = new ProcessBuilder(recorder).redirectOutput(fifo.toFile()).start();
					processes.add(r);
					logErrors(r, "ton");
				} catch (IOException e) {
					MultiMediaClient.LOGGER.info("parec startet nicht: {}", e.toString());
				}
			}, "MultiMedia-AppAudio");
			rec.setDaemon(true);
			rec.start();
		}
		// auf erste Daten warten
		InputStream in = p.getInputStream();
		long deadline = System.currentTimeMillis() + v.waitMs;
		while (System.currentTimeMillis() < deadline && !stopped) {
			if (in.available() > 0) {
				ffmpeg = p;
				processes.addAll(started);
				MultiMediaClient.LOGGER.info("Senden: Aufnahme läuft ({}, {}), Monitor {}", v.name, soundInfo, monitor);
				return true;
			}
			if (!p.isAlive()) {
				break;
			}
			Thread.sleep(50);
		}
		started.forEach(Process::destroyForcibly);
		MultiMediaClient.LOGGER.info("Senden: Variante '{}' ging nicht: {}", v.name, lastError);
		return false;
	}

	/** "Stream #0:0…: Video: … 2560x1440" – the first video input is the capture. */
	private static final java.util.regex.Pattern SOURCE_SIZE =
			java.util.regex.Pattern.compile("Stream #0:0.*Video:.*?\\b(\\d{2,5})x(\\d{2,5})\\b");
	private static final java.util.regex.Pattern ERRORISH =
			java.util.regex.Pattern.compile("(?i)error|fail|invalid|denied|cannot|could not|couldn't|no such|unable|not found|refused");

	private void logErrors(Process p, String tag) {
		Thread err = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (!line.isBlank()) {
						String t = line.trim();
						if (sourceWidth == 0) {
							java.util.regex.Matcher m = SOURCE_SIZE.matcher(t);
							if (m.find()) {
								sourceWidth = Integer.parseInt(m.group(1));
								sourceHeight = Integer.parseInt(m.group(2));
							}
						}
						// Windows: ffmpeg only prints errors; Linux: also info lines (for the size), so pick the errors
						if (!tag.equals("ton") && (WINDOWS || ERRORISH.matcher(t).find())) {
							lastError = t;
						}
						MultiMediaClient.LOGGER.info("[senden/{}] {}", tag, t);
					}
				}
			} catch (IOException ignored) {
			}
		}, "MultiMedia-TvErr");
		err.setDaemon(true);
		err.start();
	}

	/** Programme, die gerade Ton machen (für die Auswahl im Menü). */
	public static List<String> audioPrograms() {
		if (!WINDOWS) {
			return linuxAudioApps();
		}
		java.nio.file.Path helper = Tools.audioHelper();
		if (helper == null) {
			return List.of();
		}
		try {
			Process p = new ProcessBuilder(helper.toString(), "list", Long.toString(ProcessHandle.current().pid())).start();
			p.getErrorStream().close();
			String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor(5, TimeUnit.SECONDS);
			List<String> out = new ArrayList<>();
			for (String line : text.split("\\R")) {
				String name = line.trim();
				if (name.matches("[^,|\\r\\n]{1,80}") && !out.contains(name)) {
					out.add(name);
				}
			}
			return out;
		} catch (Exception e) {
			return List.of();
		}
	}

	/** ffmpeg-Ausgabe an alle Zuschauer verteilen. */
	private void pump() throws IOException {
		byte[] buf = new byte[32 * 1024];
		try (InputStream in = ffmpeg.getInputStream()) {
			int n;
			while (!stopped && (n = in.read(buf)) > 0) {
				byte[] chunk = java.util.Arrays.copyOf(buf, n);
				for (int off = 0; off < n; off += P2p.PAYLOAD) {
					int len = Math.min(P2p.PAYLOAD, n - off);
					int seq = producedSeq;
					byte[] pkt = P2p.packet(P2p.DATA, 0, 4 + len);
					java.nio.ByteBuffer.wrap(pkt, P2p.HEADER, 4).putInt(seq);
					System.arraycopy(buf, off, pkt, P2p.HEADER + 4, len);
					ring[seq & (RING - 1)] = pkt;
					producedSeq = seq + 1;
				}
				synchronized (newData) {
					newData.notifyAll();
				}
				for (Viewer v : viewers) {
					if (!v.queue.offer(chunk)) {
						MultiMediaClient.LOGGER.info("Senden: Zuschauer {} kommt nicht hinterher – getrennt", v.name);
						v.close();
					}
				}
			}
		}
	}

	private void acceptLoop() {
		while (!stopped) {
			try {
				Socket s = server.accept();
				Thread t = new Thread(() -> handshake(s), "MultiMedia-TvViewer");
				t.setDaemon(true);
				t.start();
			} catch (IOException e) {
				if (!stopped) {
					MultiMediaClient.LOGGER.warn("Senden: Annehmen fehlgeschlagen: {}", e.toString());
				}
			}
		}
	}

	private void handshake(Socket s) {
		try {
			s.setSoTimeout(5000);
			s.setTcpNoDelay(true);
			String line = readLine(s.getInputStream());
			String expected = HELLO + Settings.tvToken();
			if (line == null || !MessageDigest.isEqual(line.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
				MultiMediaClient.LOGGER.info("Senden: Verbindung ohne gültigen Schlüssel von {} abgelehnt", s.getInetAddress());
				s.close();
				return;
			}
			s.setSoTimeout(0);
			OutputStream out = s.getOutputStream();
			out.write("OK\n".getBytes(StandardCharsets.US_ASCII));
			out.flush();
			Viewer v = new Viewer(s, s.getInetAddress().isLoopbackAddress());
			viewers.add(v);
			MultiMediaClient.LOGGER.info("Senden: Zuschauer verbunden ({}), jetzt {}", v.name, viewerCount());
			v.writeLoop();
		} catch (IOException e) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static String readLine(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int c;
		while ((c = in.read()) >= 0 && c != '\n') {
			if (sb.length() > 200) {
				return null;
			}
			sb.append((char) c);
		}
		return c < 0 ? null : sb.toString().trim();
	}

	public void stop() {
		if (stopped) {
			return;
		}
		stopped = true;
		for (Process p : processes) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
		}
		try {
			if (server != null) {
				server.close();
			}
		} catch (IOException ignored) {
		}
		viewers.forEach(Viewer::close);
		viewers.clear();
		if (udp != null) {
			for (UdpViewer v : udpViewers.values()) {
				if (v.addr != null) {
					udp.send(P2p.packet(P2p.BYE, v.session, 0), v.addr);
				}
			}
			udpViewers.clear();
			udp.close();
		}
		synchronized (newData) {
			newData.notifyAll();
		}
		if (!cam) {
			RemoteControl.stopAll();
		}
		MultiMediaClient.LOGGER.info("Senden beendet");
	}

	// ------------------------------------------------------------------ Direkte Verbindungen (UDP)

	/** Ein Freund, der direkt (über das gebohrte Loch) zuschaut. */
	private static final class UdpViewer {
		final String name;
		final long session;
		final List<InetSocketAddress> candidates;
		volatile InetSocketAddress addr;
		volatile long lastHeard = System.nanoTime();
		int cursor = -1;
		/** Packets sent new / asked for again (lost) since the last quality check – to notice a full upload. */
		final java.util.concurrent.atomic.AtomicInteger sentCount = new java.util.concurrent.atomic.AtomicInteger();
		final java.util.concurrent.atomic.AtomicInteger lostCount = new java.util.concurrent.atomic.AtomicInteger();
		final java.util.concurrent.ConcurrentLinkedQueue<Integer> resend = new java.util.concurrent.ConcurrentLinkedQueue<>();

		UdpViewer(String name, long session, List<InetSocketAddress> candidates) {
			this.name = name;
			this.session = session;
			this.candidates = candidates;
		}
	}

	/** Anfrage eines Freundes (kam über den Chat, siehe TvSignal). */
	static void handleRequest(String viewer, String token, long session, List<InetSocketAddress> candidates) {
		handleRequest(viewer, token, session, candidates, false);
	}

	static void handleRequest(String viewer, String token, long session, List<InetSocketAddress> candidates, boolean cam) {
		if (!TvSignal.LOOPBACK && viewer.equalsIgnoreCase(net.minecraft.client.Minecraft.getInstance().getUser().getName())) {
			return;
		}
		if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), Settings.tvToken().getBytes(StandardCharsets.UTF_8))) {
			MultiMediaClient.LOGGER.info("Senden: Anfrage von {} mit alter/falscher Einladung", viewer);
			TvSignal.answer(viewer, session, "bad", List.of(), cam);
			return;
		}
		TvSender s = cam ? camCurrent : current;
		if (s == null || s.stopped || s.udp == null) {
			(cam ? CAM_WAITING : WAITING).add(viewer);
			TvSignal.answer(viewer, session, "off", List.of(), cam);
			return;
		}
		s.accept(viewer, session, candidates);
	}

	private void accept(String viewer, long session, List<InetSocketAddress> candidates) {
		udpViewers.values().removeIf(v -> v.name.equalsIgnoreCase(viewer)); // alte Verbindung desselben Freundes ersetzen
		UdpViewer v = new UdpViewer(viewer, session, candidates);
		udpViewers.put(session, v);
		List<InetSocketAddress> mine = new ArrayList<>();
		P2p.StunResult r = stun;
		if (r != null && r.mapped() != null) {
			mine.add(r.mapped());
		}
		InetSocketAddress forwarded = udp.upnp(0);
		if (forwarded != null && !mine.contains(forwarded)) {
			mine.add(forwarded); // reachable even from hotspots (router forwards it)
		}
		mine.addAll(P2p.localCandidates(udp.port()));
		TvSignal.answer(viewer, session, "ok", mine, cam);
		MultiMediaClient.LOGGER.info("Senden: {} möchte zuschauen – bohre Loch (meine Adressen {}, seine {})", viewer, mine.size(), candidates.size());
		// eine Weile an alle bekannten Adressen klopfen, bis eine antwortet
		Thread t = new Thread(() -> {
			byte[] punch = P2p.packet(P2p.PUNCH, session, 0);
			long end = System.currentTimeMillis() + 15_000;
			while (!stopped && v.addr == null && System.currentTimeMillis() < end && udpViewers.get(session) == v) {
				for (InetSocketAddress c : candidates) {
					udp.send(punch, c);
				}
				P2p.sleep(100);
			}
			if (v.addr == null && udpViewers.remove(session) != null && !stopped) {
				MultiMediaClient.LOGGER.info("Senden: Direkte Verbindung zu {} hat nicht geklappt", viewer);
			}
		}, "MultiMedia-TvPunch");
		t.setDaemon(true);
		t.start();
	}

	private void onUdp(byte[] d, InetSocketAddress from) {
		UdpViewer v = udpViewers.get(P2p.session(d));
		if (v == null) {
			return;
		}
		if (v.addr == null) {
			v.addr = from; // Loch ist offen
			MultiMediaClient.LOGGER.info("Senden: {} ist direkt verbunden ({}), jetzt {} Zuschauer", v.name, P2p.format(from), viewerCount());
		} else if (!v.addr.equals(from)) {
			if (d[1] != P2p.HEARTBEAT) {
				return;
			}
			v.addr = from; // Router hat den Port gewechselt
		}
		v.lastHeard = System.nanoTime();
		switch (d[1]) {
			case P2p.PUNCH -> udp.send(P2p.packet(P2p.PUNCH, v.session, 0), from);
			case P2p.NACK -> {
				java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(d, P2p.HEADER, d.length - P2p.HEADER);
				while (bb.remaining() >= 4 && v.resend.size() < 2000) {
					v.resend.add(bb.getInt());
					v.lostCount.incrementAndGet();
				}
				synchronized (newData) {
					newData.notifyAll();
				}
			}
			case P2p.BYE -> {
				udpViewers.remove(v.session);
				MultiMediaClient.LOGGER.info("Senden: {} schaut nicht mehr zu, jetzt {}", v.name, viewerCount());
			}
			case P2p.INPUT -> {
				// touch / remote control from a viewer (only for the screen share, never the webcam)
				if (!cam && d.length > P2p.HEADER) {
					String event = new String(d, P2p.HEADER, d.length - P2p.HEADER, StandardCharsets.UTF_8);
					RemoteControl.onEvent(this, v.name, event, reply -> {
						byte[] text = reply.getBytes(StandardCharsets.UTF_8);
						byte[] pkt = P2p.packet(P2p.CONTROL, v.session, text.length);
						System.arraycopy(text, 0, pkt, P2p.HEADER, text.length);
						InetSocketAddress to = v.addr;
						if (to != null && !stopped) {
							udp.send(pkt, to);
						}
					});
				}
			}
			default -> {
			}
		}
	}

	/** Verteilt die Pakete an alle direkt verbundenen Freunde – mit Nachsenden und gleichmäßigem Tempo. */
	private void pacerLoop() {
		long budgetAt = System.nanoTime();
		double budget = 0;
		while (!stopped) {
			boolean sent = false;
			long now = System.nanoTime();
			budget = Math.min(MAX_UPLOAD * 0.05, budget + (now - budgetAt) / 1e9 * MAX_UPLOAD);
			budgetAt = now;
			for (UdpViewer v : udpViewers.values()) {
				if (v.addr == null) {
					continue;
				}
				if (now - v.lastHeard > 10_000_000_000L) {
					udpViewers.remove(v.session);
					MultiMediaClient.LOGGER.info("Senden: {} antwortet nicht mehr – getrennt, jetzt {}", v.name, viewerCount());
					continue;
				}
				int produced = producedSeq;
				if (v.cursor < 0 || produced - v.cursor > RING / 2) {
					v.cursor = produced; // neu dabei oder viel zu weit hinten: live einsteigen
				}
				// erst Nachforderungen, dann Neues (ein paar Pakete je Zuschauer pro Runde, damit alle drankommen)
				for (int k = 0; k < 8 && budget > 0; k++) {
					Integer again = v.resend.poll();
					int seq;
					if (again != null) {
						seq = again;
						if (produced - seq > RING - 16 || produced - seq <= 0) {
							continue; // zu alt oder gibt's nicht
						}
					} else if (v.cursor - produced < 0) {
						seq = v.cursor++;
						v.sentCount.incrementAndGet();
					} else {
						break;
					}
					byte[] pkt = ring[seq & (RING - 1)];
					if (pkt == null) {
						continue;
					}
					byte[] out = pkt.clone();
					java.nio.ByteBuffer.wrap(out, 2, 8).putLong(v.session);
					udp.send(out, v.addr);
					budget -= out.length + 28;
					sent = true;
				}
			}
			if (!sent || budget <= 0) {
				synchronized (newData) {
					try {
						newData.wait(budget <= 0 ? 2 : 20);
					} catch (InterruptedException e) {
						return;
					}
				}
			}
		}
	}

	/** Ein Zuschauer mit eigenem Puffer und Schreib-Thread. */
	private final class Viewer {
		final Socket socket;
		final boolean local;
		final String name;
		final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(VIEWER_QUEUE);
		volatile boolean closed;

		Viewer(Socket socket, boolean local) {
			this.socket = socket;
			this.local = local;
			this.name = local ? "dieser PC" : socket.getInetAddress().getHostAddress();
		}

		void writeLoop() {
			try (OutputStream out = socket.getOutputStream()) {
				while (!closed && !stopped) {
					byte[] chunk = queue.poll(500, TimeUnit.MILLISECONDS);
					if (chunk != null) {
						out.write(chunk);
					}
				}
			} catch (IOException | InterruptedException ignored) {
				// Zuschauer weg
			} finally {
				close();
				viewers.remove(this);
				if (!stopped) {
					MultiMediaClient.LOGGER.info("Senden: Zuschauer getrennt ({}), jetzt {}", name, viewerCount());
				}
			}
		}

		void close() {
			closed = true;
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	// ------------------------------------------------------------------ Vorschau der Monitore (fürs Menü)

	/** Ein kleines Vorschaubild (PNG) pro Monitor; leer, wenn keine Aufnahme möglich ist. */
	public static List<byte[]> monitorPreviews(Tools.Paths paths) {
		List<byte[]> out = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			List<String> cmd = new ArrayList<>(List.of(paths.ffmpeg(), "-hide_banner", "-loglevel", "error"));
			if (WINDOWS) {
				cmd.addAll(List.of("-f", "lavfi", "-i", "ddagrab=output_idx=" + i + ":framerate=5:draw_mouse=0",
						"-vf", "hwdownload,format=bgra,scale=256:144:force_original_aspect_ratio=decrease,pad=256:144:(ow-iw)/2:(oh-ih)/2"));
			} else {
				// Linux: no live previews – on Wayland every grab would pop up the screen-share dialog.
				// One neutral tile; the desktop (or wf-recorder) asks which monitor when sending starts.
				if (i >= 1) {
					break;
				}
				cmd.addAll(List.of("-f", "lavfi", "-i", "color=c=0x1d2733:s=256x144:r=5"));
			}
			cmd.addAll(List.of("-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "pipe:1"));
			try {
				Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
				p.getErrorStream().close();
				byte[] png = p.getInputStream().readAllBytes();
				p.waitFor(5, TimeUnit.SECONDS);
				if (png.length < 100) {
					break; // kein weiterer Monitor
				}
				out.add(png);
			} catch (Exception e) {
				break;
			}
		}
		return out;
	}
}
