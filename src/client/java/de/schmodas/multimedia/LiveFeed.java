package de.schmodas.multimedia;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Eine Verbindung zum Live-Bildschirm eines Spielers (siehe TvSender), gemeinsam für Leinwände (Bild) und
 * Jukeboxen (Ton). Bild und Ton werden von zwei ffmpeg getrennt entschlüsselt: der Ton sofort, das Bild um
 * so viel verzögert, wie der Ton in den Jukeboxen braucht – dann passt beides zusammen.
 * Ist der Sender offline, wird alle paar Sekunden neu versucht.
 */
final class LiveFeed {
	static final int WIDTH = VideoPlayer.WIDTH;
	static final int HEIGHT = VideoPlayer.HEIGHT;
	/** Webcams are small above heads – a full-size picture would only waste memory and upload time. */
	static final int CAM_WIDTH = 640;
	static final int CAM_HEIGHT = 360;
	/** So viel später als ankommend ist der Ton in den Jukeboxen zu hören (siehe StreamHub, Live-Vorlauf). */
	static final double AUDIO_DELAY = 1.2;
	/** Picture size of this feed (screen share: quality setting, webcam: CAM_*). */
	final int width;
	final int height;
	private final int frameBytes;
	private final String vf;
	/** Ton in Blöcken von 50 ms (48 kHz, Stereo, 16 Bit). */
	private static final int AUDIO_BLOCK = 48000 / 20 * 4;

	private static final Map<String, LiveFeed> FEEDS = new HashMap<>();

	final String key;
	final String sender;
	/** true: the sender's webcam (shown above their head), false: their screen share. */
	final boolean cam;
	/** Direct connection while it's up – used to send touch / remote-control events back to the sender. */
	private volatile P2p.Endpoint liveEndpoint;
	private volatile InetSocketAddress livePeer;
	private volatile long liveSession;
	/** Remote control: "none", "pending", "granted", "denied", "revoked". */
	private volatile String control = "none";
	/** Name wie im Spiel geschrieben (für Anzeigen und den Chat). */
	private volatile String displayName;
	private final Settings.TvSource target;
	private int refs;
	private final List<Process> processes = new CopyOnWriteArrayList<>();
	private final List<BlockingQueue<byte[]>> audioListeners = new CopyOnWriteArrayList<>();
	private volatile Socket socket;
	private volatile boolean closed;
	private volatile String status = T.t("Verbinde …");

	// Bild: zwei Puffer (einer wird gefüllt, einer ist fertig)
	private ByteBuffer back;
	private ByteBuffer ready;
	private long readySeq;
	private volatile boolean hasFrame;
	/** Wie viel später als bei Ankunft der Ton gerade in den Jukeboxen zu hören ist (geglättet). */
	private volatile double audioLead = AUDIO_DELAY;

	private LiveFeed(String key, String sender, Settings.TvSource target, boolean cam) {
		this.key = key;
		this.sender = sender;
		this.cam = cam;
		this.width = cam ? CAM_WIDTH : WIDTH;
		this.height = cam ? CAM_HEIGHT : HEIGHT;
		this.frameBytes = width * height * 4;
		this.vf = "scale=" + width + ":" + height + ":force_original_aspect_ratio=decrease,"
				+ "pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2:color=black";
		this.back = MemoryUtil.memAlloc(frameBytes);
		this.ready = MemoryUtil.memAlloc(frameBytes);
		this.displayName = sender;
		this.target = target;
		Thread t = new Thread(this::run, "MultiMedia-Live");
		t.setDaemon(true);
		t.start();
	}

	// ------------------------------------------------------------------ Verwaltung

	/** Wohin man sich für diesen Spieler verbindet (eigener Name: dieser PC), oder null ohne Einladung. */
	static Settings.TvSource targetFor(String name, String me) {
		if (name.equalsIgnoreCase(me)) {
			return new Settings.TvSource("127.0.0.1", Settings.tvPort(), Settings.tvToken());
		}
		return Settings.tvSource(name);
	}

	static String keyFor(String name, Settings.TvSource target) {
		return keyFor(name, target, false);
	}

	static String keyFor(String name, Settings.TvSource target, boolean cam) {
		return (cam ? "cam:" : "tv:") + name.toLowerCase(Locale.ROOT) + "@" + target.host() + ":" + target.port();
	}

	/** Verbindung holen (oder aufbauen); mit release() wieder abgeben. */
	static synchronized LiveFeed acquire(String name, Settings.TvSource target) {
		return acquire(name, target, false);
	}

	static synchronized LiveFeed acquire(String name, Settings.TvSource target, boolean cam) {
		String key = keyFor(name, target, cam);
		LiveFeed f = FEEDS.get(key);
		if (f == null) {
			f = new LiveFeed(key, name, target, cam);
			FEEDS.put(key, f);
		}
		f.refs++;
		return f;
	}

	void release() {
		synchronized (LiveFeed.class) {
			if (--refs > 0) {
				return;
			}
			FEEDS.remove(key);
		}
		close();
	}

	String status() {
		return status;
	}

	/** Is there a direct connection to a friend's screen right now (needed for remote control)? */
	boolean canSendInput() {
		if (isLocal()) {
			return !cam && TvSender.isSending();
		}
		return liveEndpoint != null && livePeer != null && !cam;
	}

	/** Your own screen share (viewed on this PC): you may always control it, no permission needed. */
	boolean isLocal() {
		return "127.0.0.1".equals(target.host());
	}

	String controlState() {
		return isLocal() ? "granted" : control;
	}

	/** Sends one touch / remote-control event to the sender (see RemoteControl for the format). */
	void sendInput(String event) {
		if (cam) {
			return;
		}
		if (isLocal()) {
			RemoteControl.localEvent(event);
			return;
		}
		P2p.Endpoint ep = liveEndpoint;
		InetSocketAddress to = livePeer;
		if (ep == null || to == null) {
			return;
		}
		byte[] text = event.getBytes(StandardCharsets.UTF_8);
		if (text.length > 900) {
			return;
		}
		byte[] pkt = P2p.packet(P2p.INPUT, liveSession, text.length);
		System.arraycopy(text, 0, pkt, P2p.HEADER, text.length);
		ep.send(pkt, to);
		if (event.equals("req") && control.equals("none")) {
			control = "pending";
		}
	}

	/** The feed for this key, if one is open (for the touch screen). */
	static synchronized LiveFeed find(String key) {
		return FEEDS.get(key);
	}

	boolean hasFrame() {
		return hasFrame && status == null;
	}

	/** Neuestes Bild kopieren, wenn es neuer als seq ist. Gibt die neue Nummer zurück. */
	synchronized long copyFrame(long seq, long dst) {
		if (readySeq == seq || ready == null) {
			return seq;
		}
		MemoryUtil.memCopy(MemoryUtil.memAddress0(ready), dst, frameBytes);
		return readySeq;
	}

	/** Ton abonnieren: Blöcke (48 kHz, Stereo, s16le) landen in der Schlange, sobald sie ankommen. */
	BlockingQueue<byte[]> listenAudio() {
		BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(200);
		audioListeners.add(q);
		return q;
	}

	void audioLead(double seconds) {
		double old = audioLead;
		// Sprünge (Lücke/verworfenes Stück) sofort übernehmen, sonst sanft nachführen
		audioLead = Math.abs(seconds - old) > 0.15 ? seconds : old + (seconds - old) * 0.05;
	}

	void stopListening(BlockingQueue<byte[]> q) {
		audioListeners.remove(q);
	}

	// ------------------------------------------------------------------ Empfang

	private void run() {
		Tools.Paths paths;
		try {
			paths = Tools.ensure(MultiMediaClient::info).get();
		} catch (Exception e) {
			status = T.t("Programme fehlen");
			return;
		}
		while (!closed) {
			try {
				receive(paths);
			} catch (IOException e) {
				MultiMediaClient.LOGGER.info("Live ({}): {}", sender, e.getMessage());
			} catch (InterruptedException e) {
				return;
			}
			if (closed) {
				return;
			}
			boolean local = "127.0.0.1".equals(target.host());
			if (local || status == null || status.startsWith("Verbinde")) {
				status = T.t("Kein Signal – ") + displayName + T.t(" sendet gerade nicht");
			}
			try {
				Thread.sleep(local ? 4000 : 1000);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private Process ffmpeg(Tools.Paths paths, String tag, List<String> output) throws IOException {
		List<String> cmd = new java.util.ArrayList<>(List.of(paths.ffmpeg(), "-hide_banner", "-loglevel", "error",
				"-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "500000", "-analyzeduration", "500000",
				"-f", "mpegts", "-i", "pipe:0"));
		cmd.addAll(output);
		Process p = new ProcessBuilder(cmd).start();
		processes.add(p);
		daemon("MultiMedia-LiveErr", () -> {
			String text = Tools.readQuiet(p.getErrorStream()).trim();
			if (!text.isEmpty() && !closed) {
				MultiMediaClient.LOGGER.info("[live/{}] {}", tag, Tools.lastLine(text, text));
			}
		});
		return p;
	}

	private record Packet(long at, byte[] data) {
	}

	/** Verbinden (eigener PC: direkt; Freund: Loch bohren) und dann entschlüsseln, bis die Verbindung endet. */
	private void receive(Tools.Paths paths) throws IOException, InterruptedException {
		if ("127.0.0.1".equals(target.host())) {
			Socket s = new Socket();
			socket = s;
			try (s) {
				s.connect(new InetSocketAddress(target.host(), cam ? target.port() + 1 : target.port()), 4000);
				s.setTcpNoDelay(true);
				s.setSoTimeout(8000);
				OutputStream out = s.getOutputStream();
				out.write((TvSender.HELLO + target.token() + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
				InputStream in = s.getInputStream();
				byte[] ok = new byte[3];
				if (in.readNBytes(ok, 0, 3) != 3 || ok[0] != 'O' || ok[1] != 'K') {
					throw new IOException("abgelehnt");
				}
				s.setSoTimeout(15000); // kommt so lange nichts, ist der Sender weg
				decode(paths, in);
			}
			return;
		}
		receiveDirect(paths);
	}

	// ------------------------------------------------------------------ Direkt zum Freund (Loch bohren)

	private static final Object WAKE = new Object();
	private volatile long wokenAt;

	/** Der Sender meldet "ich sende jetzt" – wartende Leinwände sofort verbinden. */
	static void wake(String sender) {
		wake(sender, false);
	}

	static void wake(String sender, boolean cam) {
		synchronized (LiveFeed.class) {
			for (LiveFeed f : FEEDS.values()) {
				if (f.cam == cam && (f.sender.equalsIgnoreCase(sender) || TvSignal.LOOPBACK)) {
					f.wokenAt = System.nanoTime();
				}
			}
		}
		synchronized (WAKE) {
			WAKE.notifyAll();
		}
	}

	/** Warten (bis zu ms), oder kürzer, wenn der Sender Bescheid gibt. */
	private void pause(long ms) throws InterruptedException {
		long since = wokenAt;
		long end = System.currentTimeMillis() + ms;
		while (!closed && wokenAt == since && System.currentTimeMillis() < end) {
			synchronized (WAKE) {
				WAKE.wait(Math.max(1, Math.min(500, end - System.currentTimeMillis())));
			}
		}
	}

	private void receiveDirect(Tools.Paths paths) throws IOException, InterruptedException {
		String online = TvSignal.onlineName(sender);
		if (online == null) {
			status = sender + T.t(" ist gerade nicht online");
			pause(10_000);
			return;
		}
		displayName = online;
		try (P2p.Endpoint ep = new P2p.Endpoint()) {
			status = T.t("Verbinde mit ") + displayName + " …";
			P2p.StunResult stun = ep.stun();
			List<InetSocketAddress> mine = new java.util.ArrayList<>();
			if (stun.mapped() != null) {
				mine.add(stun.mapped());
			}
			InetSocketAddress forwarded = ep.upnp(3000);
			if (forwarded != null && !mine.contains(forwarded)) {
				mine.add(forwarded);
			}
			mine.addAll(P2p.localCandidates(ep.port()));
			long session = P2p.newSession();
			MultiMediaClient.LOGGER.info("Live ({}): frage an (eigene Adresse {}{})", sender,
					stun.mapped() == null ? "?" : P2p.format(stun.mapped()), stun.symmetric() ? ", Router wechselt Ports" : "");
			TvSignal.Answer answer;
			try {
				answer = TvSignal.request(displayName, target.token(), session, mine, cam).get(10, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				status = T.t("Keine Antwort von ") + displayName + T.t(" (gleiche MultiMedia-Version?)");
				pause(30_000);
				return;
			} catch (java.util.concurrent.ExecutionException e) {
				return;
			}
			MultiMediaClient.LOGGER.info("Live ({}): Antwort {}", sender, answer.status());
			switch (answer.status()) {
				case "off" -> {
					status = T.t("Kein Signal – ") + displayName + T.t(" sendet gerade nicht");
					pause(120_000); // der Sender sagt Bescheid, wenn es losgeht
					return;
				}
				case "bad" -> {
					status = T.t("Einladung veraltet – ") + displayName + T.t(" muss dich neu einladen");
					pause(60_000);
					return;
				}
				case "ok" -> {
				}
				default -> {
					pause(30_000);
					return;
				}
			}
			// Loch bohren: an alle Adressen des Senders klopfen, bis etwas zurückkommt
			P2p.Receiver rx = new P2p.Receiver();
			java.util.concurrent.atomic.AtomicReference<InetSocketAddress> peer = new java.util.concurrent.atomic.AtomicReference<>();
			ep.onPacket((d, from) -> {
				if (P2p.session(d) != session) {
					return;
				}
				peer.compareAndSet(null, from);
				if (!from.equals(peer.get())) {
					return;
				}
				switch (d[1]) {
					case P2p.DATA -> {
						if (d.length > P2p.HEADER + 4) {
							int seq = java.nio.ByteBuffer.wrap(d, P2p.HEADER, 4).getInt();
							rx.put(seq, java.util.Arrays.copyOfRange(d, P2p.HEADER + 4, d.length));
						}
					}
					case P2p.BYE -> rx.end();
					case P2p.CONTROL -> {
						String reply = new String(d, P2p.HEADER, d.length - P2p.HEADER, StandardCharsets.UTF_8).trim();
						if (reply.matches("granted|denied|revoked|pending|unavailable")) {
							control = reply;
						}
					}
					default -> {
					}
				}
			});
			byte[] punch = P2p.packet(P2p.PUNCH, session, 0);
			long end = System.currentTimeMillis() + 12_000;
			while (!closed && peer.get() == null && System.currentTimeMillis() < end) {
				for (InetSocketAddress c : answer.candidates()) {
					ep.send(punch, c);
				}
				Thread.sleep(100);
			}
			if (peer.get() == null) {
				status = "Can't connect to " + displayName + " – one of your networks blocks it (phone hotspot?). "
						+ "Works if either of you is on home internet with UPnP on the router.";
				MultiMediaClient.LOGGER.info("Live ({}): Loch bohren fehlgeschlagen (eigene Adresse {}, seine {})", sender,
						stun.mapped() == null ? "?" : P2p.format(stun.mapped()), answer.candidates().size());
				pause(30_000);
				return;
			}
			MultiMediaClient.LOGGER.info("Live ({}): direkt verbunden über {}", sender, P2p.format(peer.get()));
			ep.send(punch, peer.get());
			liveEndpoint = ep;
			livePeer = peer.get();
			liveSession = session;
			// Lebenszeichen und Nachforderungen; ohne Daten 10 s -> Verbindung weg
			Thread keep = new Thread(() -> {
				long lastBeat = 0;
				while (!closed && !Thread.currentThread().isInterrupted()) {
					InetSocketAddress to = peer.get();
					List<Integer> missing = rx.due();
					if (!missing.isEmpty()) {
						byte[] nack = P2p.packet(P2p.NACK, session, missing.size() * 4);
						java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(nack, P2p.HEADER, missing.size() * 4);
						missing.forEach(bb::putInt);
						ep.send(nack, to);
					}
					long now = System.nanoTime();
					if (now - lastBeat > 1_000_000_000L) {
						ep.send(P2p.packet(P2p.HEARTBEAT, session, 0), to);
						lastBeat = now;
					}
					if (rx.idleNanos() > 10_000_000_000L) {
						rx.end();
						return;
					}
					if (!P2p.sleep(40)) {
						return;
					}
				}
			}, "MultiMedia-LiveKeep");
			keep.setDaemon(true);
			keep.start();
			try {
				decode(paths, rx);
			} finally {
				keep.interrupt();
				rx.end();
				liveEndpoint = null;
				livePeer = null;
				control = "none";
				ep.send(P2p.packet(P2p.BYE, session, 0), peer.get());
				MultiMediaClient.LOGGER.info("Live ({}): getrennt ({})", sender, rx.stats());
			}
		}
	}

	/** MPEG-TS entschlüsseln: Ton sofort an die Jukeboxen, Bild (verzögert) auf die Leinwand. */
	private void decode(Tools.Paths paths, InputStream in) throws IOException, InterruptedException {
		{
			MultiMediaClient.LOGGER.info("Live ({}): verbunden", sender);
			Process video = ffmpeg(paths, "bild", List.of("-map", "0:v:0", "-vf", vf, "-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1"));
			Process audio = ffmpeg(paths, "ton", List.of("-map", "0:a:0?", "-ar", "48000", "-ac", "2", "-f", "s16le", "pipe:1"));
			BlockingQueue<Packet> delayed = new LinkedBlockingQueue<>();
			// Netz -> Ton sofort, Bild in die Warteschlange
			daemon("MultiMedia-LiveNet", () -> {
				byte[] buf = new byte[16 * 1024];
				try (OutputStream audioIn = audio.getOutputStream()) {
					int n;
					while (!closed && (n = in.read(buf)) > 0) {
						byte[] chunk = java.util.Arrays.copyOf(buf, n);
						delayed.add(new Packet(System.nanoTime(), chunk));
						try {
							audioIn.write(chunk);
							audioIn.flush();
						} catch (IOException e) {
							// Ton-ffmpeg weg (z. B. ohne Tonspur) – Bild läuft weiter
						}
					}
				} catch (IOException ignored) {
				} finally {
					delayed.add(new Packet(0, null));
				}
			});
			// Bild verzögert weitergeben (nur, wenn jemand den Ton hört – sonst sofort)
			daemon("MultiMedia-LiveDelay", () -> {
				try (OutputStream videoIn = video.getOutputStream()) {
					while (!closed) {
						Packet p = delayed.take();
						if (p.data == null) {
							break;
						}
						long delayNs = audioListeners.isEmpty() ? 0 : (long) (audioLead * 1e9);
						long wait = p.at + delayNs - System.nanoTime();
						if (wait > 0) {
							TimeUnit.NANOSECONDS.sleep(Math.min(wait, 2_000_000_000L));
						}
						videoIn.write(p.data);
						videoIn.flush();
					}
				} catch (IOException | InterruptedException ignored) {
				} finally {
					video.destroy();
				}
			});
			// Ton an die Zuhörer (Jukeboxen)
			daemon("MultiMedia-LiveAudio", () -> {
				try (InputStream pcm = audio.getInputStream()) {
					byte[] block = new byte[AUDIO_BLOCK];
					while (!closed) {
						int got = pcm.readNBytes(block, 0, block.length);
						if (got <= 0) {
							break;
						}
						got -= got % 4;
						byte[] data = java.util.Arrays.copyOf(block, got);
						for (BlockingQueue<byte[]> q : audioListeners) {
							q.offer(data); // voll = Zuhörer hängt; dann fällt eben etwas weg
						}
					}
				} catch (IOException ignored) {
				}
			});
			// Bilder lesen (live: jedes sofort zeigen)
			try (InputStream frames = video.getInputStream()) {
				byte[] tmp = new byte[64 * 1024];
				while (!closed) {
					ByteBuffer dst;
					synchronized (this) {
						dst = back;
					}
					if (dst == null) {
						return;
					}
					dst.clear();
					while (dst.hasRemaining()) {
						int n = frames.read(tmp, 0, Math.min(tmp.length, dst.remaining()));
						if (n < 0) {
							return; // Verbindung/Übertragung zu Ende
						}
						dst.put(tmp, 0, n);
					}
					synchronized (this) {
						ByteBuffer t = ready;
						ready = back;
						back = t;
						readySeq++;
					}
					hasFrame = true;
					status = null;
				}
			} finally {
				video.destroyForcibly();
				audio.destroyForcibly();
				processes.remove(video);
				processes.remove(audio);
			}
		}
	}

	private static void daemon(String name, Runnable r) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		t.start();
	}

	private void close() {
		closed = true;
		try {
			Socket s = socket;
			if (s != null) {
				s.close();
			}
		} catch (IOException ignored) {
		}
		for (Process p : processes) {
			p.destroyForcibly();
		}
		daemon("MultiMedia-LiveFree", () -> {
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
		});
	}
}
