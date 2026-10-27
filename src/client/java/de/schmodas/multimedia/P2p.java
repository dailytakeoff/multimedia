package de.schmodas.multimedia;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Direkte Verbindung zwischen zwei PCs ohne Portfreigabe ("Loch bohren"):
 * Jeder fragt einen öffentlichen STUN-Dienst, unter welcher Adresse er von außen zu sehen ist, beide tauschen
 * das über den Minecraft-Chat aus und schicken sich gleichzeitig Pakete. Für den Router sieht das dann wie eine
 * Antwort aus, und er lässt die Pakete durch. Über UDP – fehlende Pakete werden nachgefordert.
 */
final class P2p {
	static final byte MAGIC = 'M';
	static final byte PUNCH = 1;
	static final byte DATA = 2;
	static final byte NACK = 3;
	static final byte HEARTBEAT = 4;
	static final byte BYE = 5;
	/** Viewer -> sender: a touch / remote-control event as UTF-8 text (see RemoteControl). */
	static final byte INPUT = 6;
	/** Sender -> viewer: remote-control answer ("granted", "denied", "revoked", "pending"). */
	static final byte CONTROL = 7;
	/** Kopf: Magic, Typ, Sitzung (8 Byte). */
	static final int HEADER = 10;
	/** Nutzdaten pro Paket: 6 MPEG-TS-Pakete (passt sicher in jede Internet-Leitung). */
	static final int PAYLOAD = 188 * 6;

	private static final String[] STUN_SERVERS = {"stun.l.google.com:19302", "stun1.l.google.com:19302", "stun.cloudflare.com:3478"};
	private static final int STUN_COOKIE = 0x2112A442;
	private static final SecureRandom RANDOM = new SecureRandom();

	private P2p() {
	}

	static long newSession() {
		long s;
		do {
			s = RANDOM.nextLong();
		} while (s == 0);
		return s;
	}

	static byte[] packet(byte type, long session, int extra) {
		byte[] b = new byte[HEADER + extra];
		b[0] = MAGIC;
		b[1] = type;
		ByteBuffer.wrap(b, 2, 8).putLong(session);
		return b;
	}

	static long session(byte[] b) {
		return ByteBuffer.wrap(b, 2, 8).getLong();
	}

	// ------------------------------------------------------------------ Adressen

	/** Eigene Adressen im Heimnetz (für Freunde im selben Haus, da klappt der Weg über außen oft nicht). */
	static List<InetSocketAddress> localCandidates(int port) {
		List<InetAddress> found = new ArrayList<>();
		// zuerst die Adresse, über die es ins Internet geht (verschickt nichts)
		try (DatagramSocket probe = new DatagramSocket()) {
			probe.connect(InetAddress.getByName("8.8.8.8"), 53);
			InetAddress a = probe.getLocalAddress();
			if (a instanceof Inet4Address && !a.isAnyLocalAddress() && !a.isLoopbackAddress()) {
				found.add(a);
			}
		} catch (IOException ignored) {
		}
		try {
			for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) {
					continue;
				}
				for (InetAddress a : Collections.list(ni.getInetAddresses())) {
					if (a instanceof Inet4Address && !a.isLinkLocalAddress() && !found.contains(a)) {
						found.add(a);
					}
				}
			}
		} catch (SocketException ignored) {
		}
		List<InetSocketAddress> out = new ArrayList<>();
		for (InetAddress a : found) {
			if (out.size() < 2) {
				out.add(new InetSocketAddress(a, port));
			}
		}
		return out;
	}

	/** "1.2.3.4:5678" -> Adresse (nur IPv4-Zahlen, keine Namen). */
	static InetSocketAddress parse(String s) {
		int colon = s.lastIndexOf(':');
		if (colon < 0 || !s.substring(0, colon).matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
			return null;
		}
		try {
			int port = Integer.parseInt(s.substring(colon + 1));
			if (port <= 0 || port > 65535) {
				return null;
			}
			return new InetSocketAddress(InetAddress.getByName(s.substring(0, colon)), port);
		} catch (Exception e) {
			return null;
		}
	}

	static String format(InetSocketAddress a) {
		return a.getAddress().getHostAddress() + ":" + a.getPort();
	}

	// ------------------------------------------------------------------ UDP-Anschluss mit STUN

	/** Ein UDP-Anschluss: ein Empfangs-Thread, STUN-Antworten werden abgefangen, der Rest geht an den Empfänger. */
	static final class Endpoint implements AutoCloseable {
		final DatagramSocket socket;
		private final Map<String, CompletableFuture<InetSocketAddress>> stunPending = new ConcurrentHashMap<>();
		private volatile BiConsumer<byte[], InetSocketAddress> receiver = (b, a) -> {
		};
		private volatile boolean closed;

		Endpoint() throws SocketException {
			socket = new DatagramSocket(0);
			socket.setReceiveBufferSize(4 << 20);
			socket.setSendBufferSize(1 << 20);
			Thread t = new Thread(this::receiveLoop, "MultiMedia-Udp");
			t.setDaemon(true);
			t.start();
		}

		void onPacket(BiConsumer<byte[], InetSocketAddress> r) {
			receiver = r;
		}

		int port() {
			return socket.getLocalPort();
		}

		void send(byte[] data, InetSocketAddress to) {
			try {
				socket.send(new DatagramPacket(data, data.length, to));
			} catch (IOException ignored) {
				// Netz kurz weg – Nachforderung/Wiederholung regelt das
			}
		}

		private void receiveLoop() {
			byte[] buf = new byte[2048];
			while (!closed) {
				DatagramPacket p = new DatagramPacket(buf, buf.length);
				try {
					socket.receive(p);
				} catch (IOException e) {
					if (closed) {
						return;
					}
					continue;
				}
				byte[] data = Arrays.copyOf(p.getData(), p.getLength());
				InetSocketAddress from = (InetSocketAddress) p.getSocketAddress();
				if (data.length >= 20 && ByteBuffer.wrap(data, 4, 4).getInt() == STUN_COOKIE) {
					handleStun(data);
				} else if (data.length >= HEADER && data[0] == MAGIC) {
					receiver.accept(data, from);
				}
			}
		}

		/**
		 * Fragt die STUN-Dienste nach der eigenen Adresse von außen. Ergebnis: Adresse (oder null) und ob der
		 * Router für jedes Ziel einen anderen Port nimmt ("symmetrisch" – dann klappt Loch bohren meist nicht).
		 */
		StunResult stun() {
			List<InetSocketAddress> seen = new ArrayList<>();
			for (String server : STUN_SERVERS) {
				InetSocketAddress mapped = stunQuery(server);
				if (mapped != null) {
					seen.add(mapped);
					if (seen.size() == 2) {
						break;
					}
				}
			}
			if (seen.isEmpty()) {
				return new StunResult(null, false);
			}
			boolean symmetric = seen.size() == 2 && seen.get(0).getPort() != seen.get(1).getPort();
			return new StunResult(seen.get(0), symmetric);
		}

		private InetSocketAddress stunQuery(String server) {
			try {
				int colon = server.lastIndexOf(':');
				InetAddress host = null;
				for (InetAddress a : InetAddress.getAllByName(server.substring(0, colon))) {
					if (a instanceof Inet4Address) {
						host = a;
						break;
					}
				}
				if (host == null) {
					return null;
				}
				InetSocketAddress to = new InetSocketAddress(host, Integer.parseInt(server.substring(colon + 1)));
				byte[] req = new byte[20];
				ByteBuffer bb = ByteBuffer.wrap(req);
				bb.putShort((short) 0x0001).putShort((short) 0).putInt(STUN_COOKIE);
				byte[] tid = new byte[12];
				RANDOM.nextBytes(tid);
				bb.put(tid);
				String key = Arrays.toString(tid);
				CompletableFuture<InetSocketAddress> f = new CompletableFuture<>();
				stunPending.put(key, f);
				try {
					for (int attempt = 0; attempt < 3; attempt++) {
						send(req, to);
						try {
							return f.get(700, TimeUnit.MILLISECONDS);
						} catch (java.util.concurrent.TimeoutException ignored) {
							// nochmal
						}
					}
				} finally {
					stunPending.remove(key);
				}
			} catch (Exception ignored) {
			}
			return null;
		}

		private void handleStun(byte[] d) {
			ByteBuffer bb = ByteBuffer.wrap(d);
			int type = bb.getShort() & 0xFFFF;
			int len = bb.getShort() & 0xFFFF;
			bb.getInt();
			byte[] tid = new byte[12];
			bb.get(tid);
			CompletableFuture<InetSocketAddress> f = stunPending.get(Arrays.toString(tid));
			if (f == null || type != 0x0101 || d.length < 20 + len) {
				return;
			}
			InetSocketAddress plain = null;
			while (bb.remaining() >= 4) {
				int at = bb.getShort() & 0xFFFF;
				int al = bb.getShort() & 0xFFFF;
				if (bb.remaining() < al) {
					break;
				}
				int start = bb.position();
				if ((at == 0x0020 || at == 0x0001) && al >= 8 && d[start + 1] == 0x01) {
					int port = ((d[start + 2] & 0xFF) << 8) | (d[start + 3] & 0xFF);
					byte[] ip = Arrays.copyOfRange(d, start + 4, start + 8);
					if (at == 0x0020) {
						port ^= STUN_COOKIE >>> 16;
						for (int i = 0; i < 4; i++) {
							ip[i] ^= (byte) (STUN_COOKIE >>> (24 - 8 * i));
						}
					}
					try {
						InetSocketAddress a = new InetSocketAddress(InetAddress.getByAddress(ip), port);
						if (at == 0x0020) {
							f.complete(a);
							return;
						}
						plain = a;
					} catch (IOException ignored) {
					}
				}
				bb.position(start + ((al + 3) & ~3));
			}
			if (plain != null) {
				f.complete(plain);
			}
		}

		@Override
		public void close() {
			closed = true;
			socket.close();
		}
	}

	record StunResult(InetSocketAddress mapped, boolean symmetric) {
	}

	// ------------------------------------------------------------------ Empfang mit Nachforderung

	/**
	 * Sortiert ankommende Datenpakete, fordert fehlende nach und gibt den Strom der Reihe nach als InputStream
	 * weiter. Was nach kurzer Zeit immer noch fehlt, wird übersprungen (live: lieber kurz ein Bildfehler als Stillstand).
	 */
	static final class Receiver extends InputStream {
		private static final int WINDOW = 8192;
		private static final long NACK_EVERY_NS = 120_000_000L;
		private static final long GIVE_UP_NS = 700_000_000L;

		private final byte[][] slots = new byte[WINDOW][];
		private final long[] missingSince = new long[WINDOW];
		private final long[] lastNack = new long[WINDOW];
		private int expected = -1;
		private int highest = -1;
		private byte[] current;
		private int currentPos;
		private boolean ended;
		private long lastData = System.nanoTime();
		private long skipped;
		private long received;

		/** Neues Datenpaket (seq, Nutzdaten). */
		synchronized void put(int seq, byte[] payload) {
			lastData = System.nanoTime();
			received++;
			if (expected < 0) {
				expected = seq; // mitten im Strom eingestiegen
				highest = seq - 1;
			}
			if (seq - expected < 0 || seq - expected >= WINDOW) {
				if (seq - expected >= WINDOW) {
					// weit voraus (lange Lücke): neu aufsetzen
					Arrays.fill(slots, null);
					expected = seq;
					highest = seq - 1;
				} else {
					return; // schon gehabt
				}
			}
			slots[seq & (WINDOW - 1)] = payload;
			if (seq - highest > 0) {
				long now = System.nanoTime();
				for (int s = highest + 1; s - seq < 0; s++) {
					missingSince[s & (WINDOW - 1)] = now;
					lastNack[s & (WINDOW - 1)] = 0;
				}
				highest = seq;
			}
			notifyAll();
		}

		synchronized void end() {
			ended = true;
			notifyAll();
		}

		synchronized long idleNanos() {
			return System.nanoTime() - lastData;
		}

		synchronized int highestSeq() {
			return highest;
		}

		synchronized String stats() {
			return received + " Pakete, " + skipped + " verloren";
		}

		/** Welche Pakete jetzt nachgefordert werden sollen; überspringt, was zu lange fehlt. */
		synchronized List<Integer> due() {
			List<Integer> out = new ArrayList<>();
			if (expected < 0) {
				return out;
			}
			long now = System.nanoTime();
			for (int s = expected; s - highest <= 0 && out.size() < 200; s++) {
				int i = s & (WINDOW - 1);
				if (slots[i] != null) {
					continue;
				}
				if (now - missingSince[i] > GIVE_UP_NS) {
					continue; // wird in read() übersprungen
				}
				if (now - lastNack[i] > NACK_EVERY_NS) {
					lastNack[i] = now;
					out.add(s);
				}
			}
			notifyAll();
			return out;
		}

		@Override
		public synchronized int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) {
				return 0;
			}
			while (true) {
				if (current != null && currentPos < current.length) {
					int n = Math.min(len, current.length - currentPos);
					System.arraycopy(current, currentPos, b, off, n);
					currentPos += n;
					return n;
				}
				current = null;
				if (expected >= 0 && expected - highest <= 0) {
					int i = expected & (WINDOW - 1);
					if (slots[i] != null) {
						current = slots[i];
						slots[i] = null;
						currentPos = 0;
						expected++;
						continue;
					}
					if (System.nanoTime() - missingSince[i] > GIVE_UP_NS) {
						expected++; // verloren – weiter
						skipped++;
						continue;
					}
				}
				if (ended) {
					return -1;
				}
				try {
					wait(50);
				} catch (InterruptedException e) {
					throw new IOException("unterbrochen");
				}
			}
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int n = read(one, 0, 1);
			return n < 0 ? -1 : one[0] & 0xFF;
		}

		@Override
		public void close() {
			end();
		}
	}

	/** Wartet kurz (für Schleifen). */
	static boolean sleep(long ms) {
		try {
			Thread.sleep(ms);
			return true;
		} catch (InterruptedException e) {
			return false;
		}
	}

	static boolean isTimeout(Throwable t) {
		return t instanceof SocketTimeoutException;
	}
}
