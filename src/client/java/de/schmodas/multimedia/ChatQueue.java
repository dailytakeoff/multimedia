package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * All hidden whispers the mod sends go through here, so the server never kicks anyone for spam.
 *
 * Vanilla adds 20 to a counter for every chat message or command and takes 1 away every tick; above 200 you're
 * kicked. We keep our own copy of that counter and stay far below it (about one message per second, short bursts
 * of a few). Connection messages (asking to watch, answers) go first; floating screens and invites wait.
 * A newer message with the same key (e.g. the same screen sent again) replaces the older one still waiting.
 */
public final class ChatQueue {
	private record Item(String command, String key, long queuedAt) {
	}

	/** Our estimate of the server's spam counter. */
	private static int budget;
	/** Stay well below the server's 200 (some servers are stricter). */
	private static final int LIMIT = 90;
	private static final int COST = 20;
	/** Messages waiting longer than this are no longer useful. */
	private static final long MAX_AGE_MS = 60_000;

	private static final Deque<Item> HIGH = new ArrayDeque<>();
	private static final Deque<Item> LOW = new ArrayDeque<>();

	private ChatQueue() {
	}

	/** Connection signalling: sent before everything else. */
	public static void urgent(String command) {
		add(HIGH, command, null);
	}

	/** Everything else; key: messages with the same key replace each other while waiting (null = never). */
	public static void normal(String command, String key) {
		add(LOW, command, key);
	}

	private static synchronized void add(Deque<Item> q, String command, String key) {
		if (key != null) {
			q.removeIf(i -> key.equals(i.key()));
		} else {
			q.removeIf(i -> i.command().equals(command)); // exact duplicate still waiting
		}
		if (q.size() >= 200) {
			q.pollFirst();
		}
		q.addLast(new Item(command, key, System.currentTimeMillis()));
	}

	/** Every client tick: count the budget down and send at most one message if there's room. */
	public static void tick(Minecraft mc) {
		Item next;
		synchronized (ChatQueue.class) {
			if (budget > 0) {
				budget--;
			}
			var conn = mc.getConnection();
			if (conn == null) {
				HIGH.clear();
				LOW.clear();
				budget = 0;
				return;
			}
			if (budget + COST > LIMIT) {
				return;
			}
			long now = System.currentTimeMillis();
			dropOld(HIGH, now);
			dropOld(LOW, now);
			next = HIGH.pollFirst();
			if (next == null) {
				next = LOW.pollFirst();
			}
			if (next == null) {
				return;
			}
			budget += COST;
		}
		mc.getConnection().sendCommand(next.command());
	}

	/** Messages the player sends themselves also count on the server – note them so we leave room. */
	public static synchronized void playerSent() {
		budget += COST;
	}

	private static void dropOld(Deque<Item> q, long now) {
		for (Iterator<Item> it = q.iterator(); it.hasNext(); ) {
			if (now - it.next().queuedAt() > MAX_AGE_MS) {
				it.remove();
			}
		}
	}
}
