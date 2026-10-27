package de.schmodas.multimedia;

import java.util.regex.Pattern;

/** Recognises the mod's hidden codes in any chat line, however another mod (e.g. Essential) formats it. */
public final class ChatFilter {
	private static final Pattern CODE = Pattern.compile("mm(?:tv[qa]1|tv[12]|cm[qa]1|f[sdh]1):[A-Za-z0-9_-]{4,}");

	private ChatFilter() {
	}

	public static boolean isHiddenCode(String text) {
		return text != null && text.contains("mm") && CODE.matcher(text).find();
	}
}
