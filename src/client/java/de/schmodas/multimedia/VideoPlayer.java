package de.schmodas.multimedia;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Spielt das Bild eines YouTube-Links (oder einer Playlist) für die Leinwände ab.
 * Wie die Musik richtet es sich nach der Spielzeit der Welt: Alle mit Mod sehen dieselbe Stelle.
 * Läuft zum selben Link schon Ton (Jukeboxen auf dem Kanal), richtet sich das Bild genau nach dem Ton.
 *
 * ffmpeg liefert fertige Bilder (RGBA, 854x480 mit schwarzen Rändern, 30 pro Sekunde);
 * pro Bild wird gewartet, bis seine Zeit gekommen ist, und es dann zum Hochladen bereitgelegt.
 */
public final class VideoPlayer implements ScreenSource {
	/** Picture size on screens, from the quality setting at start-up (changing it needs a restart). */
	public static final int WIDTH = switch (Settings.videoQuality()) {
		case 0 -> 854;
		case 2 -> 1920;
		default -> 1280;
	};
	public static final int HEIGHT = WIDTH * 9 / 16;
	/** Quality the screens were set up with this session (the menu shows "restart" if it differs). */
	static final int QUALITY_AT_START = Settings.videoQuality();
	private static final int FPS = 30;
	private static final int FRAME_BYTES = WIDTH * HEIGHT * 4;
	/** Video-Formate bis 480p (der Bildschirm hat eh nur so viele Pixel), H.264 bevorzugt. */
	private static final String FORMAT = "bestvideo[height<=" + HEIGHT + "][vcodec^=avc1]/bestvideo[height<=" + HEIGHT
			+ "]/best[height<=" + HEIGHT + "]/best";
	/** Bild so viel früher anfordern, wie das Starten von ffmpeg etwa dauert. */
	/** Gelernte Startzeit von ffmpeg (bis das erste Bild da ist) + Reserve – so weit vorne wird angefangen. */
	private static volatile double learnedLead = 3.0;

	private static int nextId;

	/** Was gerade gilt (Link + Start/Pause). Ändert sich beim Pausieren/Weiterspielen, ohne neu zu laden. */
	private volatile Source source;
	private volatile Playback playback;
	private volatile List<Playback.Track> tracks;
	/** Wird bei jeder Änderung hochgezählt; laufendes Dekodieren bricht dann ab. */
	private volatile int generation;
	private final List<Process> processes = new CopyOnWriteArrayList<>();
	/** Gemerkte direkte Adressen (auch über Neustarts der Leinwand hinweg; YouTube-Adressen gelten Stunden). */
	private static final Map<String, CompletableFuture<Tools.Direct>> DIRECT = new java.util.concurrent.ConcurrentHashMap<>();
	/** Titel, bei denen das direkte Abrufen nicht geht – dort lädt yt-dlp selbst und reicht durch (gemerkt). */
	private static final java.util.Set<String> PIPE_ONLY = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private volatile String lastError;
	private boolean errorShown;
	private volatile boolean closed;

	/** Eigene Uhr, falls kein Ton läuft: Stelle beim Start + vergangene Zeit (pausiert mit dem Spiel). */
	private volatile double ownClockBase;
	private volatile long ownClockNanos;

	// ---- Übergabe Dekoder -> Render-Thread (zwei Puffer, getauscht unter Sperre)
	private ByteBuffer back = MemoryUtil.memAlloc(FRAME_BYTES);
	private ByteBuffer ready = MemoryUtil.memAlloc(FRAME_BYTES);
	private long readySeq;
	private long uploadedSeq;
	private volatile boolean hasFrame;
	/** Uhrzeit des zuletzt bereitgelegten Bilds (für Tests/Diagnose). */
	volatile double shownClock = Double.NaN;

	// ---- nur Render-Thread
	private VideoTexture texture;
	private final Identifier textureId;

	/** Ohne bekannte Längen und ohne Ton: welcher Titel gerade (nacheinander) gezeigt wird. */
	private int seqPass;
	private int seqIndex;
	private double seqStart;
	private volatile boolean lastEnded;
	/** Titel, dessen Bild zuletzt zu Ende gespielt wurde (nicht nochmal starten, solange der Ton ihn noch spielt). */
	private int endedGen = -1;
	private int endedPass;
	private int endedIndex;

	VideoPlayer(Source source, double gameSeconds) {
		this.source = source;
		this.playback = Playback.radioRaw(source, () -> clock());
		// eigene Uhr wie beim Ton: ab dem Startzeitpunkt auf dem Schild zählen (falls vorhanden)
		this.ownClockBase = source.positionAt(gameSeconds);
		this.ownClockNanos = PauseClock.now();
		this.textureId = Identifier.fromNamespaceAndPath(MultiMediaClient.MOD_ID, "video/" + (nextId++));
		Thread t = new Thread(this::run, "MultiMedia-Video");
		t.setDaemon(true);
		t.start();
	}

	public String spec() {
		return source.spec();
	}

	/** Radio-Uhr in Sekunden: angehalten bei Pause, sonst vom Ton, wenn er läuft, sonst die eigene. */
	double clock() {
		Source s = source;
		if (s.isPaused()) {
			return s.paused();
		}
		double audio = StreamHub.clock(s.spec());
		if (!Double.isNaN(audio)) {
			return audio;
		}
		return ownClockBase + (PauseClock.now() - ownClockNanos) / 1e9;
	}

	/**
	 * Schild wurde geändert (Pause, Weiter, Neustart): Bei Pause bleibt das aktuelle Bild sofort stehen,
	 * bei Weiter/Neustart bleibt es stehen, bis das neue Bild da ist – kein schwarzer Bildschirm dazwischen.
	 */
	synchronized void update(Source s, double gameSeconds) {
		if (s.spec().equals(source.spec())) {
			return;
		}
		Playback pb = Playback.radioRaw(s, () -> clock());
		List<Playback.Track> t = tracks;
		if (t != null) {
			pb.setTracks(t);
		}
		ownClockBase = s.positionAt(gameSeconds);
		ownClockNanos = PauseClock.now();
		if (t != null && !t.isEmpty() && (s.first() != source.first() || ownClockBase < 1)) {
			// anderer Titel (Skip) oder Neustart: ohne bekannte Längen wieder ab diesem Titel zählen
			seqPass = s.first() / t.size();
			seqIndex = s.first() % t.size();
			seqStart = 0;
		}
		source = s;
		playback = pb;
		generation++;
		for (Process p : processes) { // laufendes Dekodieren abbrechen
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
		}
		MultiMediaClient.LOGGER.info("Video: {}", s.isPaused() ? "Pause bei " + s.paused() + " s"
				: "weiter ab " + String.format(Locale.ROOT, "%.1f", ownClockBase) + " s");
	}

	public boolean hasFrame() {
		return hasFrame;
	}

	/** Zwischen zwei Titeln (nächster wird geladen): "lädt …" statt eingefrorenem letzten Bild. */
	private volatile boolean betweenTracks;

	@Override
	public String status() {
		return !hasFrame && betweenTracks ? T.t("lädt …") : null;
	}

	/** Wofür schon vorgeladen wurde (Durchlauf/Titel), damit es nur einmal passiert. */
	private String prefetchedFor;

	/** Nächsten Titel schon im Hintergrund auflösen (yt-dlp braucht dafür manchmal 20 s und mehr). */
	private void prefetchNext(Tools.Paths paths, Playback pb, Playback.Position pos) {
		String key = pos.pass() + "/" + pos.index();
		if (key.equals(prefetchedFor) || tracks == null || tracks.isEmpty()) {
			return;
		}
		prefetchedFor = key;
		List<Playback.Track> order = pb.order(pos.pass());
		Playback.Track next = pos.index() + 1 < order.size() ? order.get(pos.index() + 1)
				: (source.loop() || pb.isRadio()) ? pb.order(pos.pass() + 1).get(0) : null;
		if (next == null || PIPE_ONLY.contains(next.url())) {
			return;
		}
		String url = next.url();
		Thread pre = new Thread(() -> {
			try {
				directUrl(paths, url);
				MultiMediaClient.LOGGER.info("Video: nächster Titel vorgeladen");
			} catch (Exception ignored) {
			}
		}, "MultiMedia-VideoPrefetch");
		pre.setDaemon(true);
		pre.start();
	}

	public Identifier textureId() {
		return textureId;
	}

	// ------------------------------------------------------------------ Dekoder (eigener Thread)

	private void run() {
		try {
			Tools.Paths paths = Tools.ensure(MultiMediaClient::info).get();
			Source first = source;
			// Einzelnes Video: Adresse schon jetzt parallel holen (spart eine ganze YouTube-Anfrage Wartezeit)
			if (!first.playlist() && !PIPE_ONLY.contains(first.url())) {
				String url = first.url();
				Thread pre = new Thread(() -> {
					try {
						directUrl(paths, url);
					} catch (Exception ignored) {
					}
				}, "MultiMedia-VideoPrefetch");
				pre.setDaemon(true);
				pre.start();
			}
			tracks = resolveTracks(paths, first.url());
			if (tracks.isEmpty()) {
				MultiMediaClient.LOGGER.warn("Video: keine Titel für {}", first.spec());
				return;
			}
			seqPass = first.first() / tracks.size();
			seqIndex = first.first() % tracks.size();
			int stillFor = -1; // für welche Änderung das Standbild schon geholt wurde
			while (!closed) {
				int gen = generation;
				Source src = source;
				Playback pb = playback;
				if (pb.tracks() == null) {
					pb.setTracks(tracks);
				}
				double now = clock();
				// Welcher Titel? Am sichersten: der, den der Ton gerade spielt (YouTube liefert nicht immer die Längen).
				Playback.Position pos = null;
				boolean sequential = false;
				StreamHub.TrackAt heard = src.isPaused() ? null : StreamHub.track(src.spec());
				if (heard != null && heard.index() < tracks.size()) {
					if (gen == endedGen && heard.pass() == endedPass && heard.index() == endedIndex) {
						sleep(150); // Bild dieses Titels ist schon zu Ende, der Ton noch nicht: auf den nächsten Titel warten
						continue;
					}
					pos = new Playback.Position(heard.pass(), heard.index(), heard.seconds());
				}
				if (pos == null) {
					pos = pb.radioPosition(now); // aus den Längen berechnet
				}
				if (pos == null) {
					// Längen unbekannt und kein Ton: Titel einfach nacheinander zeigen
					sequential = true;
					pos = new Playback.Position(seqPass, seqIndex, Math.max(0, now - seqStart));
				}
				Playback.Track track = pb.order(pos.pass()).get(pos.index());
				if (!src.isPaused()) {
					prefetchNext(paths, pb, pos);
				}
				double trackStart = now - pos.offset();         // Uhrzeit, zu der dieser Titel begonnen hat
				if (src.isPaused()) {
					// Pause: Bild steht. Nur wenn noch keins da ist (Leinwand neu), das Standbild an der Stelle holen.
					if (!hasFrame && stillFor != gen) {
						stillFor = gen;
						playTrack(paths, track, trackStart, pos.offset(), gen, true);
					}
					while (!closed && gen == generation) {
						sleep(100);
					}
					continue;
				}
				double offset = pos.offset() + learnedLead;     // so weit voraus, wie das Starten bei diesem PC/Netz dauert
				if (track.duration() > 0 && offset >= track.duration() - 0.5) {
					sleep(500); // Titel ist gleich vorbei -> auf den nächsten warten
					continue;
				}
				lastEnded = false;
				int frames = playTrack(paths, track, trackStart, offset, gen, false);
				if (lastEnded && frames > 0 && !closed && gen == generation) {
					// Titel zu Ende: bis das erste Bild des nächsten da ist, "lädt …" zeigen
					betweenTracks = true;
					hasFrame = false;
				}
				if (lastEnded && frames > 0) {
					endedGen = gen;
					endedPass = pos.pass();
					endedIndex = pos.index();
				}
				if (sequential && lastEnded && frames > 0 && !closed && gen == generation) {
					seqStart = clock();
					if (++seqIndex >= tracks.size()) {
						seqIndex = 0;
						seqPass++;
					}
				}
				if (frames == 0 && !closed && gen == generation) {
					sleep(1000); // nichts gekommen (Fehler/Ende) -> nicht im Kreis drehen
				}
			}
		} catch (Exception e) {
			if (!closed) {
				MultiMediaClient.LOGGER.warn("Video fehlgeschlagen: {}", e.toString());
			}
		}
	}

	private static final String VF = "fps=" + FPS + ",scale=" + WIDTH + ":" + HEIGHT + ":force_original_aspect_ratio=decrease,"
			+ "pad=" + WIDTH + ":" + HEIGHT + ":(ow-iw)/2:(oh-ih)/2:color=black";

	/**
	 * Spielt einen Titel ab offset; kehrt zurück, wenn er zu Ende ist oder das Bild zu weit hinterherhängt.
	 * Erst direkt (schnelles Springen), und wenn dabei kein Bild kommt: yt-dlp lädt selbst und reicht durch.
	 */
	private int playTrack(Tools.Paths paths, Playback.Track track, double trackStart, double offset, int gen, boolean still)
			throws IOException, InterruptedException {
		List<String> once = still ? List.of("-frames:v", "1") : List.of(); // Standbild: nur ein Bild
		if (!PIPE_ONLY.contains(track.url())) {
			Tools.Direct direct = directUrl(paths, track.url());
			if (direct != null) {
				List<String> cmd = new ArrayList<>(List.of(paths.ffmpeg(), "-hide_banner", "-loglevel", "error"));
				cmd.addAll(direct.inputArgs());
				cmd.addAll(List.of("-ss", String.format(Locale.ROOT, "%.2f", offset), "-i", direct.url(), "-an", "-vf", VF));
				cmd.addAll(once);
				cmd.addAll(List.of("-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1"));
				long spawned = System.nanoTime();
				Process ff = start(new ProcessBuilder(cmd));
				drain(ff.getErrorStream());
				int frames = readFrames(ff, trackStart, offset, !still, still ? 0 : spawned, gen);
				if (frames > 0 || closed || gen != generation) {
					return frames;
				}
			}
			MultiMediaClient.LOGGER.info("Video: direkt kam kein Bild ({}) – lade über yt-dlp", lastError);
			PIPE_ONLY.add(track.url());
			DIRECT.remove(track.url());
		}
		// Ausweichweg wie beim Ton: yt-dlp lädt selbst, ffmpeg springt beim Lesen an die Stelle
		Process ytDlp = start(new ProcessBuilder(paths.ytDlp(), "-f", FORMAT, "--no-playlist", "--quiet", "--no-warnings",
				"--no-progress", "--js-runtimes", "deno:" + paths.deno(), "-o", "-", track.url()));
		drain(ytDlp.getErrorStream());
		List<String> pipeCmd = new ArrayList<>(List.of(paths.ffmpeg(), "-hide_banner", "-loglevel", "error", "-i", "pipe:0",
				"-ss", String.format(Locale.ROOT, "%.2f", offset), "-an", "-vf", VF));
		pipeCmd.addAll(once);
		pipeCmd.addAll(List.of("-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1"));
		Process ff = start(new ProcessBuilder(pipeCmd));
		drain(ff.getErrorStream());
		Thread copy = new Thread(() -> {
			try (InputStream in = ytDlp.getInputStream(); java.io.OutputStream out = ff.getOutputStream()) {
				in.transferTo(out);
			} catch (IOException ignored) {
				// passiert beim Stoppen
			}
		}, "MultiMedia-VideoPipe");
		copy.setDaemon(true);
		copy.start();
		try {
			int frames = readFrames(ff, trackStart, offset, false, 0, gen);
			if (frames == 0 && !closed && gen == generation && !errorShown) {
				errorShown = true;
				MultiMediaClient.error(T.t("Video geht nicht: ") + (lastError != null ? lastError : T.t("kein Bild erhalten")));
			}
			return frames;
		} finally {
			ytDlp.destroy();
			processes.remove(ytDlp);
		}
	}

	/** Liest Bilder von ffmpeg und legt jedes zu seiner Zeit bereit. */
	private int readFrames(Process ff, double trackStart, double offset, boolean restartIfLate, long spawnedNanos, int gen)
			throws IOException, InterruptedException {
		int frames = 0;
		boolean synced = false; // erst ab dem ersten pünktlichen Bild zeigen – kein Ruckel-Start
		long lastShownNanos = System.nanoTime();
		long startedNanos = System.nanoTime();
		byte[] tmp = new byte[64 * 1024];
		try (InputStream in = ff.getInputStream()) {
			while (!closed && gen == generation) {
				// ein ganzes Bild lesen
				ByteBuffer target;
				synchronized (this) {
					target = back;
				}
				target.clear();
				while (target.hasRemaining()) {
					int n = in.read(tmp, 0, Math.min(tmp.length, target.remaining()));
					if (n < 0) {
						lastEnded = true;
						return frames; // Titel zu Ende
					}
					target.put(tmp, 0, n);
				}
				double frameClock = trackStart + offset + frames / (double) FPS;
				if (frames == 0 && spawnedNanos != 0) {
					// Startzeit lernen: so lange hat es bis zum ersten Bild gedauert (+ Reserve)
					double startup = (System.nanoTime() - spawnedNanos) / 1e9;
					double wanted = Math.max(1.0, Math.min(12.0, startup * 1.15 + 0.4));
					learnedLead = 0.4 * learnedLead + 0.6 * wanted;
					MultiMediaClient.LOGGER.info("Video: Start dauerte {} s, fange künftig {} s voraus an",
							String.format(Locale.ROOT, "%.1f", startup), String.format(Locale.ROOT, "%.1f", learnedLead));
				}
				frames++;
				// warten, bis das Bild dran ist (Pause im Spiel hält die Uhr an)
				double wait;
				while (!closed && gen == generation && (wait = frameClock - clock()) > 0.005) {
					Thread.sleep((long) Math.min(50, Math.max(1, wait * 1000)));
				}
				if (gen != generation) {
					break; // Schild geändert (Pause/Weiter): dieses Bild nicht mehr zeigen
				}
				double late = clock() - frameClock;
				long nowNanos = System.nanoTime();
				if (late > 2 && restartIfLate && nowNanos - startedNanos > 5_000_000_000L) {
					// deutlich hinterher: an die richtige Stelle springen (statt lange nur aufzuholen)
					MultiMediaClient.LOGGER.info("Video hängt {} s hinterher – springe neu", String.format(Locale.ROOT, "%.1f", late));
					return frames;
				}
				if (!synced) {
					if (late > 0.1 && nowNanos - startedNanos < 4_000_000_000L) {
						continue; // Anfang zu spät: unsichtbar aufholen, dann flüssig loslegen
					}
					synced = true;
				} else if (late > 0.1 && nowNanos - lastShownNanos < 250_000_000L) {
					continue; // etwas zu spät: überspringen, damit es aufholt – aber nie einfrieren (mind. 4 Bilder/s)
				}
				lastShownNanos = nowNanos;
				synchronized (this) {
					ByteBuffer t = ready;
					ready = back;
					back = t;
					readySeq++;
				}
				shownClock = frameClock;
				hasFrame = true;
				betweenTracks = false;
			}
		} finally {
			ff.destroy();
			processes.remove(ff);
		}
		return frames;
	}

	/** Direkte Video-Adresse samt Kopfzeilen von yt-dlp (wird pro Titel gemerkt; gleichzeitige Anfragen teilen sich eine). */
	private Tools.Direct directUrl(Tools.Paths paths, String url) throws IOException, InterruptedException {
		CompletableFuture<Tools.Direct> mine = new CompletableFuture<>();
		CompletableFuture<Tools.Direct> existing = DIRECT.putIfAbsent(url, mine);
		if (existing != null) {
			try {
				Tools.Direct d = existing.get();
				if (d != null) {
					return d;
				}
			} catch (java.util.concurrent.ExecutionException e) {
				// unten neu versuchen
			}
			DIRECT.remove(url, existing);
			return directUrl(paths, url);
		}
		try {
			Tools.Direct direct = Tools.resolveDirect(paths, url, FORMAT, this::noteError);
			if (direct == null) {
				MultiMediaClient.LOGGER.warn("Video: keine Adresse für {} ({})", url, lastError);
				DIRECT.remove(url, mine);
			}
			mine.complete(direct);
			return direct;
		} catch (IOException | InterruptedException | RuntimeException e) {
			DIRECT.remove(url, mine);
			mine.completeExceptionally(e);
			throw e;
		}
	}

	private void noteError(String text) {
		lastError = text;
		MultiMediaClient.LOGGER.warn("[video] {}", text);
	}

	private List<Playback.Track> resolveTracks(Tools.Paths paths, String url) throws IOException, InterruptedException {
		return Tools.tracks(paths, url, this::noteError);
	}

	private Process start(ProcessBuilder pb) throws IOException {
		if (closed) {
			throw new IOException("gestoppt");
		}
		Process p = pb.start();
		processes.add(p);
		if (closed) {
			p.destroyForcibly();
		}
		return p;
	}

	private void drain(InputStream err) {
		Thread t = new Thread(() -> {
			String text = Tools.readQuiet(err).trim();
			if (!text.isEmpty() && !closed) {
				noteError(Tools.lastLine(text, text));
			}
		}, "MultiMedia-VideoErr");
		t.setDaemon(true);
		t.start();
	}

	private static void sleep(long ms) throws InterruptedException {
		Thread.sleep(ms);
	}

	// ------------------------------------------------------------------ Render-Thread

	/** Neues Bild (falls da) in die Textur kopieren. Nur im Render-Thread aufrufen. */
	public void upload() {
		if (texture == null) {
			texture = new VideoTexture(textureId);
			Minecraft.getInstance().getTextureManager().register(textureId, texture);
		}
		synchronized (this) {
			if (readySeq == uploadedSeq) {
				return;
			}
			MemoryUtil.memCopy(MemoryUtil.memAddress0(ready), texture.getPixels().getPointer(), FRAME_BYTES); // ab Pufferanfang (nicht ab position)
			uploadedSeq = readySeq;
		}
		texture.upload();
	}

	/** Beendet alles; Textur und Speicher werden im Render-Thread freigegeben. */
	public void close() {
		closed = true;
		for (Process p : processes) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
		}
		Minecraft.getInstance().execute(() -> {
			if (texture != null) {
				Minecraft.getInstance().getTextureManager().release(textureId);
				texture = null;
			}
			// Puffer erst freigeben, wenn der Dekoder sicher nicht mehr schreibt
			Thread free = new Thread(() -> {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException ignored) {
				}
				synchronized (this) {
					MemoryUtil.memFree(back);
					MemoryUtil.memFree(ready);
					back = null;
					ready = null;
				}
			}, "MultiMedia-VideoFree");
			free.setDaemon(true);
			free.start();
		});
	}

	/** Textur für das Videobild: weich skaliert (nicht pixelig), am Rand nicht wiederholt, anfangs schwarz. */
	static final class VideoTexture extends DynamicTexture {
		VideoTexture(Identifier id) {
			this(id, WIDTH, HEIGHT);
		}

		VideoTexture(Identifier id, int w, int h) {
			super(id::toString, w, h, false);
			getPixels().fillRect(0, 0, w, h, 0xFF000000);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			upload();
		}
	}
}
