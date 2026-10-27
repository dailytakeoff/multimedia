package de.schmodas.multimedia;

import java.util.Locale;

/**
 * Players known to have MultiMedia: their mod has sent ours one of its hidden codes (an invite, a request to
 * watch, an answer, a floating screen). Automatic whispers go only to them, so players without the mod never
 * get code messages in their chat. Remembered between sessions.
 */
public final class ModPeers {
	private ModPeers() {
	}

	/** Remember that this player has the mod (name must already be validated). */
	static void mark(String name) {
		if (name != null && name.matches("[A-Za-z0-9_]{3,16}")) {
			Settings.addKnownModPlayer(name.toLowerCase(Locale.ROOT));
		}
	}

	public static boolean has(String name) {
		return name != null && Settings.knownModPlayers().contains(name.toLowerCase(Locale.ROOT));
	}
}
