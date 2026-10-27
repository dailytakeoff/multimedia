package de.schmodas.multimedia.mixin;

import de.schmodas.multimedia.SignParser;
import de.schmodas.multimedia.Source;
import de.schmodas.multimedia.T;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Schild-Editor:
 * - Strg+V: Ein YouTube-Link wird gekürzt ("yt:VIDEOID" bzw. "list=…") und auf so viele Zeilen verteilt,
 *   wie nötig (Vanilla würde einen zu langen Link ablehnen). Dazu kommt der Startzeitpunkt (@…).
 * - Steht schon ein Link drauf: Knöpfe Pause/Weiter und Neustart. Sie schreiben den neuen Zustand aufs Schild –
 *   so gilt er für alle mit Mod.
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class SignEditScreenMixin extends Screen {
	@Shadow @Final private SignTextSlot slot;

	protected SignEditScreenMixin(Component title) {
		super(title);
	}

	@Shadow
	private void onDone() {
		throw new AssertionError();
	}

	@Shadow @Final private SignBlockEntity sign;
	@Shadow @Final private String[] messages;
	@Shadow private int line;
	@Shadow @Final private TextFieldHelper signField;

	@Shadow
	private void setMessage(String newMessage) {
		throw new AssertionError();
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void multimedia$paste(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!event.isPaste()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Source src = Source.parse(mc.keyboardHandler.getClipboard().trim());
		if (src == null) {
			return; // kein Link -> normales Einfügen
		}
		List<String> lines = split(mc, src.compact(), this.sign.getMaxTextLineWidth());
		if (this.line + lines.size() > this.messages.length) {
			this.line = 0; // passt ab hier nicht mehr -> oben anfangen
		}
		if (lines.size() > this.messages.length) {
			return;
		}
		int first = this.line;
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				this.line++;
			}
			setMessage(lines.get(i));
		}
		int last = this.line;
		// Startzeitpunkt (Spielzeit in Sekunden) dazuschreiben, damit es für alle von vorne anfängt.
		// Alte Startzeitpunkte auf dem Schild entfernen.
		if (mc.level != null) {
			String stamp = "@" + (mc.level.getGameTime() / 20);
			for (int i = 0; i < this.messages.length; i++) {
				if (i >= first && i <= last) {
					continue;
				}
				String cleaned = this.messages[i].replaceAll("\\s*(@\\d+|⏸\\d+(\\.\\d+)?|▶\\d+)", "").trim();
				if (!cleaned.equals(this.messages[i])) {
					this.line = i;
					setMessage(cleaned);
				}
			}
			int target = -1;
			for (int i = last + 1; i < this.messages.length && target < 0; i++) {
				if (fits(mc, this.messages[i], stamp)) {
					target = i;
				}
			}
			for (int i = 0; i < first && target < 0; i++) {
				if (fits(mc, this.messages[i], stamp)) {
					target = i;
				}
			}
			if (target < 0 && fits(mc, this.messages[last], stamp)) {
				target = last;
			}
			if (target >= 0) {
				this.line = target;
				setMessage(this.messages[target].isBlank() ? stamp : this.messages[target] + " " + stamp);
			}
			this.line = last;
		}
		this.signField.setCursorToEnd();
		cir.setReturnValue(true);
	}

	private boolean fits(Minecraft mc, String existing, String stamp) {
		String joined = existing.isBlank() ? stamp : existing + " " + stamp;
		return mc.font.width(joined) <= this.sign.getMaxTextLineWidth();
	}

	/** Teilt Text so, dass jede Zeile auf das Schild passt. */
	private static List<String> split(Minecraft mc, String text, int maxWidth) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (char c : text.toCharArray()) {
			if (mc.font.width(cur.toString() + c) > maxWidth) {
				out.add(cur.toString());
				cur.setLength(0);
			}
			cur.append(c);
		}
		if (!cur.isEmpty()) {
			out.add(cur.toString());
		}
		return out;
	}

	// ------------------------------------------------------------------ Pause / Weiter / Neustart

	// ------------------------------------------------------------------ Kanal schon belegt?

	@org.spongepowered.asm.mixin.Unique
	private Button multimedia$channelWarn;
	@org.spongepowered.asm.mixin.Unique
	private int multimedia$warnTick;

	/** Aus der Jukebox-Ansicht: Aktion (Pause, Skip …), die dieser Editor gleich unsichtbar ausführt. */
	@org.spongepowered.asm.mixin.Unique
	private String multimedia$leanAction;

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void multimedia$leanTick(CallbackInfo ci) {
		if (multimedia$leanAction != null) {
			String a = multimedia$leanAction;
			multimedia$leanAction = null;
			multimedia$setState(a);
			onDone();
			ci.cancel();
		}
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void multimedia$leanHide(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (multimedia$leanAction != null) {
			ci.cancel(); // Editor nur kurz im Hintergrund, nicht zeigen
		}
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void multimedia$checkChannel(CallbackInfo ci) {
		if (multimedia$channelWarn == null || multimedia$warnTick++ % 10 != 0) {
			return;
		}
		String conflict = multimedia$channelConflict();
		multimedia$channelWarn.visible = conflict != null;
		if (conflict != null) {
			String[] parts = conflict.split("\\|");
			multimedia$channelWarn.setMessage(Component.literal("⚠ #" + parts[0] + T.t(" ist schon belegt – Klick: freien nehmen")));
			multimedia$channelWarn.setTooltip(Tooltip.create(Component.literal(T.t("Eine andere Haupt-Quelle bei ") + parts[1]
					+ T.t(" benutzt #") + parts[0] + T.t(" schon. Lautsprecher spielen immer die nähere – ein eigener Kanal vermeidet Durcheinander."))));
		}
	}

	/** Ist dieses Schild eine Haupt-Quelle, deren Kanal in der Nähe schon eine andere Haupt-Quelle benutzt? */
	@org.spongepowered.asm.mixin.Unique
	private String multimedia$channelConflict() {
		List<String> other = new ArrayList<>();
		SignTextSlot otherSlot = this.slot == SignTextSlot.FRONT ? SignTextSlot.BACK : SignTextSlot.FRONT;
		this.sign.getText(otherSlot).getMessages(false).forEach(c -> other.add(c.getString()));
		SignParser.Info info = SignParser.parse(List.of(this.messages), other);
		if (info == null || info.channel() == null || !(info.source() != null || (info.screen() && info.tv() != null))) {
			return null;
		}
		net.minecraft.core.BlockPos me = this.sign.getBlockPos();
		for (de.schmodas.multimedia.RadioManager.Master m : de.schmodas.multimedia.RadioManager.masters()) {
			if (m.channel().equals(info.channel()) && m.pos().distSqr(me) > 2.5
					&& m.pos().distSqr(me) < de.schmodas.multimedia.RadioManager.CHANNEL_DISTANCE * de.schmodas.multimedia.RadioManager.CHANNEL_DISTANCE) {
				return info.channel() + "|" + m.pos().getX() + " " + m.pos().getY() + " " + m.pos().getZ();
			}
		}
		return null;
	}

	/** Ersetzt den Kanal durch einen, den in der Nähe noch keine Haupt-Quelle benutzt (#kino -> #kino2). */
	@org.spongepowered.asm.mixin.Unique
	private void multimedia$freeChannel() {
		List<String> other = new ArrayList<>();
		SignTextSlot otherSlot = this.slot == SignTextSlot.FRONT ? SignTextSlot.BACK : SignTextSlot.FRONT;
		this.sign.getText(otherSlot).getMessages(false).forEach(c -> other.add(c.getString()));
		SignParser.Info info = SignParser.parse(List.of(this.messages), other);
		if (info == null || info.channel() == null) {
			return;
		}
		String base = info.channel().replaceAll("\\d+$", "");
		if (base.isEmpty()) {
			base = "kanal";
		}
		net.minecraft.core.BlockPos me = this.sign.getBlockPos();
		double far = 2 * de.schmodas.multimedia.RadioManager.CHANNEL_DISTANCE;
		for (int i = 2; i < 100; i++) {
			String cand = base + i;
			boolean used = false;
			for (de.schmodas.multimedia.RadioManager.Master m : de.schmodas.multimedia.RadioManager.masters()) {
				if (m.channel().equals(cand) && m.pos().distSqr(me) < far * far) {
					used = true;
				}
			}
			if (!used) {
				multimedia$replaceToken("(?i)#\\s*" + java.util.regex.Pattern.quote(info.channel()) + "(?![\\p{L}0-9_.,-])", "#" + cand);
				multimedia$channelWarn.visible = false;
				return;
			}
		}
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void multimedia$controls(CallbackInfo ci) {
		if (multimedia$leanAction == null) {
			multimedia$leanAction = de.schmodas.multimedia.LeanView.takeSignAction(this.sign.getBlockPos());
		}
		multimedia$channelWarn = Button.builder(Component.literal(""), b -> multimedia$freeChannel())
				.bounds(this.width / 2 - 150, 14, 300, 20).tooltip(Tooltip.create(Component.literal(
						T.t("Zwei Haupt-Quellen mit demselben Kanal: Lautsprecher spielen die nähere. Ein eigener Kanal vermeidet Durcheinander."))))
				.build();
		multimedia$channelWarn.visible = false;
		this.addRenderableWidget(multimedia$channelWarn);
		multimedia$warnTick = 0;
		if (multimedia$isScreenSign()) {
			multimedia$sendControls();
		}
		Source src = currentSource();
		if (src == null) {
			return;
		}
		boolean paused = src.isPaused();
		int y = this.height / 4 + 120;
		if (src.playlist()) {
			this.addRenderableWidget(Button.builder(Component.literal("◀◀"), b -> {
				multimedia$setState("prev");
				onDone();
			}).bounds(this.width / 2 - 146, y, 44, 20).tooltip(Tooltip.create(Component.literal(
					T.t("Vorheriger Titel (läuft der aktuelle schon länger: an dessen Anfang) – für alle mit Mod.")))).build());
			this.addRenderableWidget(Button.builder(Component.literal("▶▶"), b -> {
				multimedia$setState("next");
				onDone();
			}).bounds(this.width / 2 + 102, y, 44, 20).tooltip(Tooltip.create(Component.literal(
					T.t("Nächster Titel – für alle mit Mod.")))).build());
		}
		this.addRenderableWidget(Button.builder(Component.literal(paused ? T.t("▶ Weiter") : T.t("⏸ Pause")), b -> {
			multimedia$setState(paused ? "resume" : "pause");
			onDone();
		}).bounds(this.width / 2 - 100, y, 98, 20).tooltip(Tooltip.create(Component.literal(paused
				? T.t("Spielt für alle mit Mod an der angehaltenen Stelle weiter.")
				: T.t("Hält Bild und Ton für alle mit Mod an dieser Stelle an.")))).build());
		this.addRenderableWidget(Button.builder(Component.literal(T.t("⟲ Neustart")), b -> {
			multimedia$setState("restart");
			onDone();
		}).bounds(this.width / 2 + 2, y, 98, 20).tooltip(Tooltip.create(Component.literal(
				T.t("Startet für alle mit Mod von vorne.")))).build());
		// Haupt-Schild mit Kanal: was Lautsprecher bei Pause machen (für alle gleich, steht als "⇆" auf dem Schild)
		SignParser.Info info = currentInfo();
		if (info != null && info.channel() != null) {
			Button release = Button.builder(Component.literal(""), b -> {
				boolean now = !currentReleases();
				multimedia$replaceToken("\\s*" + SignParser.RELEASE, now ? SignParser.RELEASE : null);
				multimedia$updateRelease(b);
			}).bounds(this.width / 2 - 100, y + 48, 200, 20).build();
			if (y + 48 + 20 > this.height) {
				// kleines Fenster: unter "Fertig" ist kein Platz mehr -> oben rechts in die Ecke
				release.setPosition(this.width - 204, 4);
			}
			multimedia$updateRelease(release);
			this.addRenderableWidget(release);
		}
	}

	// ------------------------------------------------------------------ Senden (Leinwand-Schilder)

	private static final java.util.regex.Pattern MM_SCREEN = java.util.regex.Pattern.compile("(?i)(^|\\s)(bildschirm|leinwand|screen)(\\s|$)");
	private static final java.util.regex.Pattern MM_TV = java.util.regex.Pattern.compile("(?i)tv:([A-Za-z0-9_]{3,16})");

	private String multimedia$allText() {
		StringBuilder sb = new StringBuilder();
		for (String m : this.messages) {
			sb.append(' ').append(m);
		}
		SignTextSlot otherSlot = this.slot == SignTextSlot.FRONT ? SignTextSlot.BACK : SignTextSlot.FRONT;
		this.sign.getText(otherSlot).getMessages(false).forEach(c -> sb.append(' ').append(c.getString()));
		return sb.append(' ').toString();
	}

	private boolean multimedia$isScreenSign() {
		return MM_SCREEN.matcher(multimedia$allText()).find();
	}

	private String multimedia$tvName() {
		java.util.regex.Matcher m = MM_TV.matcher(multimedia$allText());
		return m.find() ? m.group(1) : null;
	}

	private void multimedia$sendControls() {
		Minecraft mc = Minecraft.getInstance();
		String me = mc.getUser().getName();
		String tv = multimedia$tvName();
		int y = this.height / 4 + 96;
		boolean mine = tv != null && tv.equalsIgnoreCase(me);
		boolean otherOnline = false;
		if (tv != null && !mine && mc.getConnection() != null) {
			for (var p : mc.getConnection().getOnlinePlayers()) {
				if (p.getProfile().name().equalsIgnoreCase(tv)) {
					otherOnline = true;
				}
			}
		}
		if (mine && de.schmodas.multimedia.TvSender.isSending()) {
			this.addRenderableWidget(Button.builder(Component.literal(T.t("⚙ Ändern")), b -> {
				onDone();
				mc.gui.setScreen(new de.schmodas.multimedia.SendScreen(true));
			}).bounds(this.width / 2 - 100, y, 98, 20).tooltip(Tooltip.create(Component.literal(
					T.t("Anderen Monitor oder Ton wählen, weitere Freunde einladen – Zuschauer bleiben dabei.")))).build());
			this.addRenderableWidget(Button.builder(Component.literal(T.t("■ Beenden")), b -> {
				multimedia$replaceToken("\\s*(?i:tv:[A-Za-z0-9_]{3,16})", null);
				de.schmodas.multimedia.TvSender.stopScreen();
				onDone();
			}).bounds(this.width / 2 + 2, y, 98, 20).tooltip(Tooltip.create(Component.literal(
					T.t("Hört auf zu senden. Die Leinwand ist dann wieder frei.")))).build());
		} else if (otherOnline) {
			Button busy = Button.builder(Component.literal(T.t("Belegt von ") + tv), b -> {
			}).bounds(this.width / 2 - 100, y, 200, 20).tooltip(Tooltip.create(Component.literal(
					tv + T.t(" sendet gerade auf diese Leinwand. Wer zuerst kommt, mahlt zuerst.")))).build();
			busy.active = false;
			this.addRenderableWidget(busy);
		} else {
			this.addRenderableWidget(Button.builder(Component.literal(T.t("▶ Senden")), b -> {
				multimedia$replaceToken("\\s*(?i:tv:[A-Za-z0-9_]{3,16})", "tv:" + me);
				onDone();
				mc.gui.setScreen(new de.schmodas.multimedia.SendScreen());
			}).bounds(this.width / 2 - 100, y, 200, 20).tooltip(Tooltip.create(Component.literal(
					T.t("Deinen Bildschirm auf diese Leinwand senden (Monitor und Einladungen wählst du gleich).")))).build());
		}
	}

	/** Entfernt passende Angaben auf dieser Seite und schreibt (falls gegeben) eine neue in die erste Zeile mit Platz. */
	private void multimedia$replaceToken(String removeRegex, String token) {
		Minecraft mc = Minecraft.getInstance();
		int keep = this.line;
		for (int i = 0; i < this.messages.length; i++) {
			String cleaned = this.messages[i].replaceAll(removeRegex, "").trim();
			if (!cleaned.equals(this.messages[i])) {
				this.line = i;
				setMessage(cleaned);
			}
		}
		if (token != null) {
			int target = -1;
			for (int i = 0; i < this.messages.length && target < 0; i++) {
				if (this.messages[i].isBlank()) {
					target = i;
				}
			}
			for (int i = this.messages.length - 1; i >= 0 && target < 0; i--) {
				if (fits(mc, this.messages[i], token)) {
					target = i;
				}
			}
			if (target >= 0) {
				this.line = target;
				setMessage(this.messages[target].isBlank() ? token : this.messages[target] + " " + token);
			}
		}
		this.line = keep;
	}

	/** Beschriftung des Pause-Knopfs für Lautsprecher (halten / freigeben). */
	private void multimedia$updateRelease(Button b) {
		boolean release = currentReleases();
		b.setMessage(Component.literal(release ? T.t("Bei Pause: Kanal freigeben") : T.t("Bei Pause: Kanal halten")));
		b.setTooltip(Tooltip.create(Component.literal(release
				? T.t("Ist hier Pause, spielen Lautsprecher mit einem zweiten Kanal (z. B. \"#kino #1.2\") solange den anderen Kanal.")
				: T.t("Ist hier Pause, bleiben die Lautsprecher dieses Kanals still – auch wenn sie noch einen zweiten Kanal haben."))));
	}

	private boolean currentReleases() {
		SignParser.Info info = currentInfo();
		return info != null && info.release();
	}

	/** Alles, was auf diesem Schild steht (beide Seiten, mit den aktuellen Eingaben), oder null. */
	private SignParser.Info currentInfo() {
		List<String> other = new ArrayList<>();
		SignTextSlot otherSlot = this.slot == SignTextSlot.FRONT ? SignTextSlot.BACK : SignTextSlot.FRONT;
		this.sign.getText(otherSlot).getMessages(false).forEach(c -> other.add(c.getString()));
		return SignParser.parse(List.of(this.messages), other);
	}

	/** Link auf diesem Schild (beide Seiten) samt Start/Pause, oder null. */
	private Source currentSource() {
		List<String> other = new ArrayList<>();
		SignTextSlot otherSlot = this.slot == SignTextSlot.FRONT ? SignTextSlot.BACK : SignTextSlot.FRONT;
		this.sign.getText(otherSlot).getMessages(false).forEach(c -> other.add(c.getString()));
		SignParser.Info info = SignParser.parse(List.of(this.messages), other);
		return info == null ? null : info.source();
	}

	/** Schreibt den neuen Zustand aufs Schild: Pause (⏸Stelle), Weiter (@neuer Start) oder Neustart (@jetzt). */
	/** Zu welchem Titel (0 = erster, über Durchläufe gezählt) der Skip-Knopf springt. */
	@org.spongepowered.asm.mixin.Unique
	private static int multimedia$skipTarget(Source src, double now, boolean forward) {
		List<de.schmodas.multimedia.Playback.Track> tracks = de.schmodas.multimedia.Tools.cachedTracks(src.url());
		int size = tracks == null ? 0 : tracks.size();
		int cur = src.first();
		double into = 0;
		de.schmodas.multimedia.StreamHub.TrackAt heard = de.schmodas.multimedia.StreamHub.track(src.spec());
		if (heard != null && size > 0) {
			cur = heard.pass() * size + heard.index(); // was gerade zu hören ist
			into = heard.seconds();
		} else if (size > 0) {
			de.schmodas.multimedia.Playback pb = de.schmodas.multimedia.Playback.radio(src, () -> now);
			pb.setTracks(tracks);
			de.schmodas.multimedia.Playback.Position p = pb.radioPosition(pb.radioNow());
			if (p != null) {
				cur = p.pass() * size + p.index();
				into = p.offset();
			}
		}
		if (forward) {
			return cur + 1;
		}
		return into > 5 ? cur : Math.max(0, cur - 1); // wie beim Handy: erst an den Anfang, dann zurück
	}

	private void multimedia$setState(String action) {
		Minecraft mc = Minecraft.getInstance();
		Source src = currentSource();
		if (src == null || mc.level == null) {
			return;
		}
		double now = mc.level.getGameTime() / 20.0;
		double pos = src.positionAt(now);
		String token = switch (action) {
			case "pause" -> "⏸" + String.format(java.util.Locale.ROOT, "%.1f", pos);
			case "resume" -> "@" + Math.round(now - pos);
			case "next", "prev" -> "▶" + (multimedia$skipTarget(src, now, action.equals("next")) + 1)
					+ (src.isPaused() ? " ⏸0.0" : " @" + (long) Math.floor(now));
			default -> "@" + (long) Math.floor(now);
		};
		// Pause/Weiter behalten den Titel, Neustart und Skip setzen ihn neu
		String remove = action.equals("pause") || action.equals("resume")
				? "\\s*(@\\d+|⏸\\d+(\\.\\d+)?)" : "\\s*(@\\d+|⏸\\d+(\\.\\d+)?|▶\\d+)";
		int keep = this.line;
		// alte Start-/Pause-Angaben entfernen
		for (int i = 0; i < this.messages.length; i++) {
			String cleaned = this.messages[i].replaceAll(remove, "").trim();
			if (!cleaned.equals(this.messages[i])) {
				this.line = i;
				setMessage(cleaned);
			}
		}
		// neue Angabe in die erste Zeile, in die sie passt (leere Zeilen zuerst)
		int target = -1;
		for (int i = 0; i < this.messages.length && target < 0; i++) {
			if (this.messages[i].isBlank()) {
				target = i;
			}
		}
		for (int i = this.messages.length - 1; i >= 0 && target < 0; i--) {
			if (fits(mc, this.messages[i], token)) {
				target = i;
			}
		}
		if (target >= 0) {
			this.line = target;
			setMessage(this.messages[target].isBlank() ? token : this.messages[target] + " " + token);
		}
		this.line = keep;
	}
}
