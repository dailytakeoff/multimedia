package de.schmodas.multimedia;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SignApplicator;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Über die Jukebox lehnen": Rechtsklick auf eine Jukebox lässt die Kamera zur Jukebox gleiten,
 * dort gibt es ein Bedienfeld (Platte wählen/rausnehmen, Schild bearbeiten, Pause/Neustart/Skip).
 * Alles, was die Welt verändert, läuft über normale Klicks (wie von Hand) – für den Server sieht es aus wie immer.
 */
public final class LeanView {
	/** Weiter weg als das (Blöcke) = Ansicht schließen. */
	private static final double MAX_DISTANCE = 6.0;

	private static BlockPos jukebox;
	private static long startNanos;
	private static boolean closing;
	private static long closeNanos;
	private static float closeFrom;
	/** Unsere eigenen Klicks dürfen durch (sonst würde die Ansicht sich selbst wieder öffnen). */
	private static boolean bypass;
	/** Aktion, die der Schild-Editor gleich unsichtbar ausführen soll ("pause", "restart", …); null = normal bearbeiten. */
	private static String pendingSignAction;
	private static BlockPos pendingSignPos;
	/** Platte, die eingelegt werden soll, sobald die alte draußen ist (Inventar-Platz), sonst -1. */
	private static int pendingInsertSlot = -1;
	private static int pendingInsertTicks;

	private LeanView() {
	}

	public static boolean active() {
		return jukebox != null;
	}

	public static BlockPos jukebox() {
		return jukebox;
	}

	public static boolean bypass() {
		return bypass;
	}

	// ------------------------------------------------------------------ öffnen / schließen

	/** Rechtsklick auf eine Jukebox: Ansicht öffnen. */
	public static void open(BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		jukebox = pos.immutable();
		computeTarget(mc.level, player.getEyePosition(), player.getYRot());
		startNanos = System.nanoTime();
		closing = false;
		mc.gui.setScreen(new LeanScreen(jukebox));
	}

	/** Bedienfeld zu (Escape): Kamera gleitet zurück. */
	public static void close() {
		if (jukebox == null || closing) {
			return;
		}
		closeFrom = progressIn();
		inNanos = Math.max(inNanos, 400_000_000L);
		closing = true;
		closeNanos = System.nanoTime();
		pendingInsertSlot = -1;
	}

	private static void finish() {
		Minecraft mc = Minecraft.getInstance();
		jukebox = null;
		closing = false;
		if (mc.gui.screen() instanceof LeanScreen) {
			mc.gui.setScreen(null);
		}
	}

	public static void tick(Minecraft mc) {
		if (jukebox == null) {
			return;
		}
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null || !level.getBlockState(jukebox).is(Blocks.JUKEBOX)
				|| player.getEyePosition().distanceTo(Vec3.atCenterOf(jukebox)) > MAX_DISTANCE) {
			if (!closing) {
				close();
				if (mc.gui.screen() instanceof LeanScreen) {
					mc.gui.setScreen(null);
				}
			}
		}
		if (closing) {
			if (progressOut() >= 1) {
				finish();
			}
			return;
		}
		// Platte wechseln: erst wenn die alte draußen ist, die neue einlegen
		if (pendingInsertSlot >= 0) {
			if (!hasRecord(level.getBlockState(jukebox))) {
				int slot = pendingInsertSlot;
				pendingInsertSlot = -1;
				insert(mc, slot);
			} else if (++pendingInsertTicks > 40) {
				pendingInsertSlot = -1; // Server hat nicht reagiert
			}
		}
		// nach Schild-Editor usw. wieder zum Bedienfeld
		if (mc.gui.screen() == null) {
			mc.gui.setScreen(new LeanScreen(jukebox));
		}
	}

	// ------------------------------------------------------------------ Kamera
	//
	// Die Kamera "geht" auf Augenhöhe zur Jukebox (mit leichtem Wippen wie beim Laufen) und beugt sich am Ende
	// darüber (bzw. schaut bei Decken-Jukeboxen nach oben). Von der Seite, von der man kommt.

	/** Gesamtfortschritt 0..1 (linear, ohne Glättung). */
	private static float raw() {
		if (jukebox == null) {
			return 0;
		}
		return closing ? closeFrom * (1 - progressOut()) : progressIn();
	}

	/** Wie weit man sich schon vorgebeugt hat (0..1) – für die Hände und die schwebenden Leisten. */
	public static float lean() {
		return smooth(Mth.clamp((raw() - 0.45f) / 0.55f, 0, 1));
	}

	public static float blend() {
		return raw();
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}

	private static float progressIn() {
		return Mth.clamp((System.nanoTime() - startNanos) / (float) inNanos, 0, 1);
	}

	private static float progressOut() {
		return Mth.clamp((System.nanoTime() - closeNanos) / (float) (inNanos * 0.8f), 0, 1);
	}

	private static long inNanos = 600_000_000L;
	/** Wohin man tritt (Augenhöhe, vor der Jukebox) und wo der Kopf am Ende ist (vorgebeugt). */
	private static Vec3 stepPos;
	private static Vec3 leanPos;
	private static Vec3 lookAt;
	private static double walkDistance;

	public static Vec3 cameraPos() {
		return leanPos;
	}

	/** Kamera für diesen Moment, ausgehend von der normalen Kamera (pos, yaw, pitch). Null = nichts ändern. */
	public static float[] apply(Vec3 from, float yaw, float pitch, float[] outPos) {
		float t = raw();
		if (t <= 0 || leanPos == null) {
			return null;
		}
		// 1) gehen: 0 .. 0.65
		float walk = smooth(Mth.clamp(t / 0.65f, 0, 1));
		Vec3 step = new Vec3(stepPos.x, from.y, stepPos.z);
		Vec3 p = from.lerp(step, walk);
		// Wippen wie beim Laufen (nur während des Gehens)
		if (walk > 0 && walk < 1 && walkDistance > 0.3) {
			double steps = Math.max(1, Math.round(walkDistance / 0.55));
			p = p.add(0, -0.045 * Math.abs(Math.sin(walk * Math.PI * steps)), 0);
		}
		// 2) vorbeugen: 0.45 .. 1
		float lean = lean();
		p = p.lerp(leanPos, lean * lean);
		p = new Vec3(p.x, Mth.lerp(lean, p.y, leanPos.y), p.z);
		// Blickrichtung: beim Gehen zur Jukebox drehen, beim Vorbeugen nach unten
		Vec3 d = lookAt.subtract(p);
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		float targetYaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
		float targetPitch = (float) -Math.toDegrees(Math.atan2(d.y, h));
		float turn = smooth(Mth.clamp(t / 0.5f, 0, 1));
		float levelPitch = Mth.lerp(turn, pitch, Mth.clamp(targetPitch, -25, 25));
		outPos[0] = (float) p.x;
		outPos[1] = (float) p.y;
		outPos[2] = (float) p.z;
		return new float[]{yaw + Mth.wrapDegrees(targetYaw - yaw) * turn, Mth.lerp(lean, levelPitch, targetPitch)};
	}

	/** Ziel ausrechnen: von der Seite, auf der man steht. */
	private static void computeTarget(ClientLevel level, Vec3 eye, float playerYaw) {
		Vec3 center = Vec3.atCenterOf(jukebox);
		double dx = eye.x - center.x;
		double dz = eye.z - center.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 0.2) { // direkt drüber/drunter: von der Blickrichtung her
			double rad = Math.toRadians(playerYaw);
			dx = -Math.sin(rad);
			dz = Math.cos(rad);
			len = 1;
		}
		dx /= len;
		dz /= len;
		// Jukebox über Augenhöhe (z. B. an der Decke): nicht hochklettern, sondern stehen bleiben und hochschauen
		boolean below = eye.y < center.y;
		double up = below ? -1 : 1;
		// Stehen: knapp vor der Jukebox, aber nicht näher als man schon ist
		double stand = Math.min(len, 1.3);
		Vec3 step = center.add(dx * stand, 0, dz * stand);
		if (!free(level, new Vec3(step.x, eye.y, step.z))) {
			step = new Vec3(eye.x, eye.y, eye.z);
		}
		stepPos = step;
		walkDistance = Math.sqrt((eye.x - step.x) * (eye.x - step.x) + (eye.z - step.z) * (eye.z - step.z));
		Vec3 lean = null;
		if (below) {
			// nicht hochklettern: auf Augenhöhe bleiben; hängt die Jukebox knapp über dem Kopf, leicht in die Knie
			// gehen und etwas Abstand halten, damit man bequem auf die Unterseite schaut
			double camY = Math.min(eye.y, jukebox.getY() - 0.7);
			for (double t : new double[]{1.35, 1.15, 1.6, 0.95}) {
				Vec3 cand = new Vec3(center.x + dx * t, camY, center.z + dz * t);
				if (free(level, cand)) {
					lean = cand;
					break;
				}
			}
		} else {
			// vorgebeugt: Kopf über der Vorderkante
			double[][] tries = {{1.12, 1.12}, {0.95, 1.05}, {0.8, 0.98}, {1.2, 0.85}};
			for (double[] t : tries) {
				Vec3 cand = center.add(dx * t[0], up * t[1], dz * t[0]);
				if (free(level, cand)) {
					lean = cand;
					break;
				}
			}
		}
		leanPos = lean != null ? lean : new Vec3(step.x, eye.y, step.z);
		// etwas hinter die Mitte schauen, damit die Leisten an der Hinterkante gut zu sehen sind
		lookAt = center.add(-dx * 0.3, up * 0.45, -dz * 0.3);
		inNanos = (long) (Mth.clamp(0.35 + walkDistance * 0.3, 0.5, 1.2) * 1_000_000_000L);
	}

	private static boolean free(ClientLevel level, Vec3 p) {
		BlockPos bp = BlockPos.containing(p);
		return level.getBlockState(bp).getCollisionShape(level, bp).isEmpty();
	}

	// ------------------------------------------------------------------ Aktionen (wie Klicks von Hand)

	public static boolean hasRecord(BlockState state) {
		return state.is(Blocks.JUKEBOX) && state.getValue(JukeboxBlock.HAS_RECORD);
	}

	/** Schild an dieser Jukebox (das mit Link/Kanal zuerst), oder null. */
	public static BlockPos signOf(ClientLevel level, BlockPos jb) {
		BlockPos any = null;
		for (Direction d : Direction.values()) {
			BlockPos p = jb.relative(d);
			if (level.getBlockEntity(p) instanceof SignBlockEntity sign) {
				if (RadioManager.parseSign(sign) != null) {
					return p;
				}
				if (any == null) {
					any = p;
				}
			}
		}
		return any;
	}

	/** Platte aus dem Inventar einlegen (eine alte vorher rausnehmen). */
	public static void choose(Minecraft mc, int slot) {
		if (jukebox == null || mc.level == null) {
			return;
		}
		if (hasRecord(mc.level.getBlockState(jukebox))) {
			eject(mc);
			pendingInsertSlot = slot;
			pendingInsertTicks = 0;
		} else {
			insert(mc, slot);
		}
	}

	/** Platte rausnehmen: ein Klick auf die Jukebox (wie mit der Hand). */
	public static void eject(Minecraft mc) {
		click(mc, jukebox, InteractionHand.MAIN_HAND);
	}

	private static void insert(Minecraft mc, int slot) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) {
			return;
		}
		Inventory inv = player.getInventory();
		if (slot == Inventory.SLOT_OFFHAND) {
			click(mc, jukebox, InteractionHand.OFF_HAND);
			return;
		}
		int selected = inv.getSelectedSlot();
		if (Inventory.isHotbarSlot(slot)) {
			inv.setSelectedSlot(slot);
			click(mc, jukebox, InteractionHand.MAIN_HAND);
			inv.setSelectedSlot(selected);
		} else {
			// aus dem Rucksack: kurz in die Hand tauschen, einlegen, zurücktauschen
			int menuSlot = slot; // Inventar-Plätze 9–35 haben im Inventar-Menü dieselbe Nummer
			int id = player.inventoryMenu.containerId;
			mc.gameMode.handleContainerInput(id, menuSlot, selected, net.minecraft.world.inventory.ContainerInput.SWAP, player);
			click(mc, jukebox, InteractionHand.MAIN_HAND);
			mc.gameMode.handleContainerInput(id, menuSlot, selected, net.minecraft.world.inventory.ContainerInput.SWAP, player);
		}
	}

	/** Schild bearbeiten (action null) oder eine Schild-Aktion unsichtbar ausführen ("pause", "resume", "restart", "next", "prev"). */
	public static void signAction(Minecraft mc, String action) {
		if (jukebox == null || mc.level == null || mc.player == null) {
			return;
		}
		BlockPos sign = signOf(mc.level, jukebox);
		if (sign == null) {
			return;
		}
		signActionAt(mc, sign, action);
	}

	/** Same as signAction, for a sign given directly (e.g. a screen sign, from the touch screen). */
	public static void signActionAt(Minecraft mc, BlockPos sign, String action) {
		if (mc.level == null || mc.player == null || sign == null) {
			return;
		}
		pendingSignAction = action;
		pendingSignPos = sign;
		// Farbstoff/Leuchttinte/Honigwabe in der Hand würden das Schild färben bzw. wachsen – kurz eine andere Hand nehmen
		Inventory inv = mc.player.getInventory();
		int selected = inv.getSelectedSlot();
		int use = selected;
		if (inv.getItem(selected).getItem() instanceof SignApplicator) {
			for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
				if (!(inv.getItem(i).getItem() instanceof SignApplicator)) {
					use = i;
					break;
				}
			}
		}
		inv.setSelectedSlot(use);
		click(mc, sign, InteractionHand.MAIN_HAND);
		inv.setSelectedSlot(selected);
	}

	/** Für den Schild-Editor: soll er gleich eine Aktion ausführen? (Einmal abholen.) */
	public static String takeSignAction(BlockPos signPos) {
		if (pendingSignPos == null || !pendingSignPos.equals(signPos)) {
			return null;
		}
		String a = pendingSignAction;
		pendingSignAction = null;
		pendingSignPos = null;
		return a;
	}

	/** Gibt es gleich eine unsichtbare Schild-Aktion für dieses Schild? (Dann den Editor nicht zeigen.) */
	public static boolean silentFor(BlockPos signPos) {
		return pendingSignPos != null && pendingSignPos.equals(signPos) && pendingSignAction != null;
	}

	private static void click(Minecraft mc, BlockPos pos, InteractionHand hand) {
		if (mc.gameMode == null || mc.player == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 c = Vec3.atCenterOf(pos);
		Direction face = Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
		Vec3 hit = c.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		bypass = true;
		try {
			mc.gameMode.useItemOn(mc.player, hand, new BlockHitResult(hit, face, pos, false));
		} finally {
			bypass = false;
		}
	}

	/** Platten im Inventar (Platz-Nummer und Stapel), Hand zuerst. */
	public static java.util.List<int[]> discSlots(LocalPlayer player) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		Inventory inv = player.getInventory();
		int sel = inv.getSelectedSlot();
		if (isDisc(inv.getItem(sel))) {
			out.add(new int[]{sel});
		}
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			if (i != sel && isDisc(inv.getItem(i))) {
				out.add(new int[]{i});
			}
		}
		if (isDisc(inv.getItem(Inventory.SLOT_OFFHAND))) {
			out.add(new int[]{Inventory.SLOT_OFFHAND});
		}
		return out;
	}

	public static boolean isDisc(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.JUKEBOX_PLAYABLE);
	}
}
