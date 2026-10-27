package de.schmodas.multimedia;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class MultiMediaClient implements ClientModInitializer {
	public static final String MOD_ID = "multimedia";
	public static final Logger LOGGER = LoggerFactory.getLogger("MultiMedia");
	private static net.minecraft.client.KeyMapping MENU_KEY;

	@Override
	public void onInitializeClient() {
		Settings.load();
		SettingsShare.register();
		TvInvite.register();
		TvSignal.register();
		FloatingScreens.register();
		// menu hotkey (M by default, changeable under Controls)
		MENU_KEY = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new net.minecraft.client.KeyMapping(
				"key.multimedia.menu", com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD,
				org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_M, net.minecraft.client.KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (MENU_KEY.consumeClick()) {
				if (mc.gui.screen() == null && mc.player != null) {
					mc.gui.setScreen(new MenuScreen());
				}
			}
		});
		// Anzeige oben links, solange man sendet
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
				net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "sending"), (g, dt) -> {
					TvSender s = TvSender.current();
					int y = 6;
					if (s != null && TvSender.isSending()) {
						int n = s.viewerCount();
						g.text(Minecraft.getInstance().font, T.t("● Du sendest") + (n > 0 ? " – " + n + (n == 1 ? T.t(" Zuschauer") : (T.german() ? " Zuschauer" : " viewers")) : ""),
								6, y, 0xFFFF5555, true);
						y += 11;
					}
					if (TvSender.isCamOn()) {
						g.text(Minecraft.getInstance().font, "● Webcam on", 6, y, 0xFFFF5555, true);
						y += 11;
					}
					String controllers = RemoteControl.activeSummary();
					if (controllers != null) {
						g.text(Minecraft.getInstance().font, "⚠ " + controllers + " can control your PC – /mm control off", 6, y, 0xFFFFAA00, true);
					}
				});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> registerCommands(dispatcher));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			YtAudioStream.closeAll();
			StreamHub.closeAll();
			TvSender.stopAll(); // Server verlassen: Senden endet
			client.execute(() -> {
				RadioManager.stopAll(client);
				JukeboxVisuals.clear(client);
				ScreenManager.closeAll();
				CamManager.closeAll();
				FloatingScreens.closeAll();
			});
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			YtAudioStream.closeAll();
			TvSender.stopAll(); // Minecraft wird geschlossen: Senden endet
		});
		ClientTickEvents.END_CLIENT_TICK.register(StreamWatcher::tick);
		ClientTickEvents.END_CLIENT_TICK.register(RadioManager::tick);
		ClientTickEvents.END_CLIENT_TICK.register(Occlusion::tick);
		ClientTickEvents.END_CLIENT_TICK.register(Watchdog::tick);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			PauseClock.tick(mc);
			StreamHub.tick();
		});
		ClientTickEvents.END_CLIENT_TICK.register(JukeboxVisuals::tick);
		ClientTickEvents.END_CLIENT_TICK.register(ScreenManager::tick);
		ClientTickEvents.END_CLIENT_TICK.register(CamManager::tick);
		ClientTickEvents.END_CLIENT_TICK.register(FloatingScreens::tick);
		ClientTickEvents.END_CLIENT_TICK.register(LeanView::tick);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(ScreenManager::render);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(CamManager::render);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(FloatingScreens::render);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(LeanRender::render);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(LeanHands::render);
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
		var root = d.register(literal("disc")
				.executes(MultiMediaClient::help)
				.then(literal("check").executes(MultiMediaClient::check))
				.then(literal("wand")
						.executes(c -> {
							status(T.t("Wand-Dämpfung: ") + Settings.wallStrength() + T.t(" (0 = aus, 5 = Standard, 10 = sehr stark)"));
							return 1;
						})
						.then(argument("staerke", IntegerArgumentType.integer(0, 10)).executes(c -> {
							Settings.setWallStrength(IntegerArgumentType.getInteger(c, "staerke"));
							status(T.t("Wand-Dämpfung auf ") + Settings.wallStrength() + T.t(" gesetzt."));
							return 1;
						})))
				.then(literal("block")
						.executes(c -> blockInfo())
						.then(literal("reset").executes(c -> {
							String id = targetBlock();
							if (id == null) {
								return 0;
							}
							Settings.setBlockDamping(id, null);
							status(id + T.t(": wieder Standard-Dämpfung."));
							return 1;
						}))
						.then(literal("list").executes(c -> {
							var all = Settings.blockDampings();
							if (all.isEmpty()) {
								status(T.t("Keine eigenen Block-Einstellungen."));
							} else {
								status(T.t("Eigene Block-Dämpfung (0 = lässt alles durch, 10 = wie Stein):"));
								all.forEach((id, v) -> line(id.replace("minecraft:", ""), String.valueOf(v)));
							}
							return 1;
						}))
						.then(argument("daempfung", IntegerArgumentType.integer(0, 10)).executes(c -> {
							String id = targetBlock();
							if (id == null) {
								return 0;
							}
							int v = IntegerArgumentType.getInteger(c, "daempfung");
							Settings.setBlockDamping(id, v);
							status(id.replace("minecraft:", "") + T.t(" dämpft jetzt mit ") + v + T.t(" (0 = gar nicht, 10 = wie Stein)."));
							return 1;
						})))
				.then(literal("teilen")
						.executes(c -> share(null))
						.then(argument("spieler", StringArgumentType.word())
								.suggests((c, b) -> {
									var conn = c.getSource().getClient().getConnection();
									if (conn != null) {
										conn.getOnlinePlayers().forEach(p -> b.suggest(p.getProfile().name()));
									}
									return b.buildFuture();
								})
								.executes(c -> share(StringArgumentType.getString(c, "spieler")))))
				.then(literal("import").then(argument("code", StringArgumentType.greedyString()).executes(c -> {
					SettingsShare.Decoded dec = SettingsShare.decode(StringArgumentType.getString(c, "code").trim());
					if (dec == null) {
						error(T.t("Das ist kein gültiger Einstellungs-Code."));
						return 0;
					}
					SettingsShare.apply(dec);
					status(T.t("Einstellungen übernommen") + (dec.wall() != null ? T.t(": Wände ") + dec.wall() : "")
							+ (dec.blocks().isEmpty() ? "" : ", " + dec.blocks().size() + T.t(" Block-Einstellung(en)")) + ".");
					return 1;
				})))
				.then(literal("tv")
						.then(literal("annehmen").then(argument("code", StringArgumentType.greedyString()).executes(c -> {
							String code = StringArgumentType.getString(c, "code").trim();
							if (!TvInvite.accept(code)) {
								error(T.t("Das ist keine gültige Bildschirm-Einladung."));
								return 0;
							}
							status(T.t("Einladung von ") + TvInvite.nameOf(code) + T.t(" angenommen. Leinwände mit \"tv:") + TvInvite.nameOf(code)
									+ T.t("\" zeigen jetzt seinen Bildschirm."));
							return 1;
						})))
						.then(literal("stopp").executes(c -> {
							if (!TvSender.isSending()) {
								status(T.t("Du sendest gerade nicht."));
								return 1;
							}
							TvSender.stopScreen();
							status(T.t("Senden beendet."));
							return 1;
						}))
						.then(literal("einladen").then(argument("spieler", StringArgumentType.word()).executes(c -> {
							TvInvite.send(java.util.List.of(StringArgumentType.getString(c, "spieler")));
							return 1;
						}))))
				.then(literal("menu").executes(c -> {
					// opened next tick: the chat screen closes right after the command
					Minecraft mc = Minecraft.getInstance();
					StreamWatcher.later(50, () -> mc.gui.setScreen(new MenuScreen()));
					return 1;
				}))
				.then(literal("cam")
						.executes(c -> {
							status(TvSender.isCamOn() ? "Webcam is on. /mm cam off to stop."
									: "Webcam is off. /mm cam on to start (players you invited see it above your head).");
							return 1;
						})
						.then(literal("on").executes(c -> camOn()))
						.then(literal("off").executes(c -> {
							TvSender.stopCam();
							status("Webcam off.");
							return 1;
						}))
						.then(literal("devices").executes(c -> {
							Tools.ensure(MultiMediaClient::status).thenAccept(paths -> {
								var list = TvSender.camDevices(paths);
								Minecraft.getInstance().execute(() -> {
									if (list.isEmpty()) {
										error("No webcam found.");
									} else {
										status("Webcams (pick one with /mm cam use <name>):");
										list.forEach(dev -> line(dev, dev.equals(Settings.camDevice()) ? "selected" : ""));
									}
								});
							});
							return 1;
						}))
						.then(literal("use").then(argument("device", StringArgumentType.greedyString()).executes(c -> {
							Settings.setCamDevice(StringArgumentType.getString(c, "device").trim());
							status("Webcam set to " + Settings.camDevice() + ".");
							return TvSender.isCamOn() ? camOn() : 1;
						}))))
				.then(literal("control")
						.executes(c -> {
							String who = RemoteControl.activeSummary();
							status(who == null ? "Nobody can control your PC." : who + " can control your PC. /mm control off revokes everyone.");
							return 1;
						})
						.then(literal("allow").then(argument("spieler", StringArgumentType.word()).executes(c ->
								RemoteControl.allow(StringArgumentType.getString(c, "spieler")) ? 1 : 0)))
						.then(literal("deny").then(argument("spieler", StringArgumentType.word()).executes(c -> {
							RemoteControl.deny(StringArgumentType.getString(c, "spieler"));
							return 1;
						})))
						.then(literal("revoke").then(argument("spieler", StringArgumentType.word()).executes(c -> {
							RemoteControl.revoke(StringArgumentType.getString(c, "spieler"));
							return 1;
						})))
						.then(literal("off").executes(c -> {
							RemoteControl.stopAll();
							status("Remote control off – nobody can control your PC.");
							return 1;
						})))
				.then(literal("titel")
						.executes(c -> {
							status(T.t("Titel über der Jukebox: ") + (Settings.hideTitles() ? T.t("aus") : T.t("an")) + T.t(". Ändern mit /disc titel an|aus."));
							return 1;
						})
						.then(literal("an").executes(c -> {
							Settings.setHideTitles(false);
							status(T.t("Titel über der Jukebox werden angezeigt."));
							return 1;
						}))
						.then(literal("aus").executes(c -> {
							Settings.setHideTitles(true);
							status(T.t("Titel über der Jukebox ausgeblendet."));
							return 1;
						})))
				.then(literal("aus").executes(c -> {
					Settings.setMuted(true);
					RadioManager.stopAll(Minecraft.getInstance());
					status(T.t("MultiMedia ist für dich aus. Mit /disc an wieder einschalten."));
					return 1;
				}))
				.then(literal("an").executes(c -> {
					Settings.setMuted(false);
					status(T.t("MultiMedia ist wieder an."));
					return 1;
				}))
				.then(literal("setup").executes(c -> {
					Tools.ensure(MultiMediaClient::status)
							.thenRun(() -> status(T.t("Programme sind bereit.")))
							.exceptionally(e -> {
								error(T.t("Download fehlgeschlagen: ") + rootMessage(e));
								return null;
							});
					return 1;
				}))
				.then(literal("update").executes(c -> {
					status(T.t("Aktualisiere yt-dlp …"));
					Tools.updateYtDlp(MultiMediaClient::status)
							.thenAccept(MultiMediaClient::status)
							.exceptionally(e -> {
								error(T.t("Update fehlgeschlagen: ") + rootMessage(e));
								return null;
							});
					return 1;
				})));
		// englische Befehle (die deutschen bleiben)
		alias(root, "walls", "wand");
		alias(root, "share", "teilen");
		alias(root, "titles", "titel");
		alias(root, "on", "an");
		alias(root, "off", "aus");
		alias(root.getChild("titel"), "on", "an");
		alias(root.getChild("titel"), "off", "aus");
		var tv = root.getChild("tv");
		alias(tv, "accept", "annehmen");
		alias(tv, "stop", "stopp");
		alias(tv, "invite", "einladen");
		// Kurzformen für den neuen Namen
		d.register(literal("mm").executes(MultiMediaClient::help).redirect(root));
		d.register(literal("multimedia").executes(MultiMediaClient::help).redirect(root));
	}

	/** Starts the webcam and re-sends invites to the invited players who are online. */
	static int camOn() {
		status("Starting webcam …");
		TvSender.startCam(Settings.camDevice(), MultiMediaClient::status, () -> {
			status("Webcam on. Players you've invited (/mm tv invite <player>) see it above your head.");
			Minecraft.getInstance().execute(() -> {
				var conn = Minecraft.getInstance().getConnection();
				if (conn == null) {
					return;
				}
				java.util.Set<String> online = new java.util.HashSet<>();
				conn.getOnlinePlayers().forEach(p -> online.add(p.getProfile().name()));
				java.util.List<String> invite = new java.util.ArrayList<>();
				for (String name : Settings.tvInvited()) {
					if (online.contains(name)) {
						invite.add(name);
					}
				}
				if (!invite.isEmpty()) {
					TvInvite.send(invite);
				}
			});
		});
		return 1;
	}

	/** Zweiter Name für einen Unterbefehl (gleiche Wirkung, gleiche Argumente). */
	private static void alias(com.mojang.brigadier.tree.CommandNode<FabricClientCommandSource> parent, String name, String target) {
		var t = parent.getChild(target);
		parent.addChild(literal(name).executes(t.getCommand()).redirect(t).build());
	}

	/** Block, auf den der Spieler gerade schaut (Block-ID) oder null mit Fehlermeldung. */
	private static String targetBlock() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
				&& mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && mc.level != null) {
			var block = mc.level.getBlockState(hit.getBlockPos()).getBlock();
			return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
		}
		error(T.t("Schau dabei auf einen Block."));
		return null;
	}

	private static int blockInfo() {
		String id = targetBlock();
		if (id == null) {
			return 0;
		}
		Integer v = Settings.blockDamping(id);
		status(id.replace("minecraft:", "") + ": " + (v == null ? T.t("Standard") : v + T.t(" von 10"))
				+ T.t(". Ändern mit /disc block <0-10>, zurücksetzen mit /disc block reset."));
		return 1;
	}

	/** Einstellungen in den Chat (oder per Flüstern an einen Spieler) schicken. */
	private static int share(String player) {
		String code = SettingsShare.encode();
		if (code == null) {
			error(T.t("Zu viele Block-Einstellungen für eine Chat-Nachricht. Teile stattdessen die Datei config/multimedia-settings.json."));
			return 0;
		}
		var conn = Minecraft.getInstance().getConnection();
		if (conn == null) {
			return 0;
		}
		if (player == null) {
			conn.sendChat("[MultiMedia] " + code);
		} else {
			conn.sendCommand("msg " + player + " [MultiMedia] " + code);
		}
		status(T.t("Einstellungen geteilt") + (player != null ? T.t(" mit ") + player : "") + ".");
		return 1;
	}

	private static int help(CommandContext<FabricClientCommandSource> c) {
		status(T.t("Jukebox + Disc + Schild mit YouTube-Link (Strg+V) = Musik für alle mit Mod, an derselben Stelle."));
		status(T.t("Soundsystem: Haupt-Schild mit Link + Kanal (z.B. #1.1), Lautsprecher-Schilder nur mit dem Kanal. Jede Jukebox braucht eine Disc."));
		status(T.t("Auf dem Schild außerdem möglich: 80% (Lautstärke), mix (Playlist gemischt), links/rechts (Stereo) – auch in einer Zeile oder auf der Rückseite."));
		status(T.t("Leinwand: Wand aus einer Blocksorte, darunter ein Schild mit \"bildschirm\" + Link (oder nur Kanal). Ton kommt aus den Jukeboxen auf demselben Kanal."));
		line(T.t("Einstellungen"), T.t("über Mod Menu (Zahnrad bei MultiMedia)"));
		line(T.t("/disc check"), T.t("prüft das Schild, auf das du schaust"));
		line(T.t("/disc wand <0-10>"), T.t("wie stark Wände die Musik dämpfen (5 = Standard)"));
		line(T.t("/disc block <0-10>"), T.t("Dämpfung für den Block, auf den du schaust"));
		line(T.t("/disc teilen [Spieler]"), T.t("deine Wand-/Block-Einstellungen im Chat teilen"));
		line(T.t("/disc titel an | aus"), T.t("Songtitel über der Jukebox zeigen oder ausblenden"));
		line(T.t("/disc aus | an"), T.t("MultiMedia für dich aus- oder einschalten"));
		line(T.t("/disc update"), T.t("yt-dlp aktualisieren, falls YouTube streikt"));
		line("M  or  /mm menu", "menu for everything: share screen, webcam, floating screens, remote control, invites");
		line("/mm cam on | off", "webcam above your head for players you invited (/mm cam devices to pick one)");
		line("Right-click a screen", "touch screen: video controls, or control a friend's PC if they allow it");
		line("/mm control off", "stop everyone from controlling your PC");
		return 1;
	}

	/** Prüft das Schild, auf das man schaut (oder das an der Jukebox hängt, auf die man schaut). */
	private static int check(CommandContext<FabricClientCommandSource> c) {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
				|| mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || mc.level == null) {
			error(T.t("Schau dabei auf ein Schild oder eine Jukebox."));
			return 0;
		}
		RadioManager.SignInfo info = RadioManager.infoAt(mc.level, hit.getBlockPos());
		if (info == null) {
			String raw = RadioManager.rawTextAt(mc.level, hit.getBlockPos());
			if (Source.looksLikeOtherLink(raw.replace(" ", ""))) {
				error(T.t("Das ist kein YouTube-Link. Aus Sicherheitsgründen spielt MultiMedia nur YouTube (youtube.com, youtu.be, music.youtube.com)."));
			} else {
				error(T.t("Hier ist kein Schild mit Link oder Kanal."));
			}
			return 0;
		}
		String extra = (info.channel() != null ? T.t("Kanal #") + info.channel() + ", " : "")
				+ Math.round(info.volume() * 100) + " %"
				+ (info.stereo() == StreamHub.LEFT ? T.t(", links") : info.stereo() == StreamHub.RIGHT ? T.t(", rechts") : "");
		if (info.screen()) {
			Source src = info.source() != null ? info.source() : RadioManager.channelSource(info.channel(), hit.getBlockPos());
			ScreenGeometry geo = mc.level.getBlockEntity(hit.getBlockPos()) instanceof net.minecraft.world.level.block.entity.SignBlockEntity
					? ScreenGeometry.detect(mc.level, hit.getBlockPos()) : null;
			status(T.t("Leinwand: ") + (geo == null ? T.t("keine Wand über dem Schild gefunden (Wandschild unter eine Wand aus einer Blocksorte hängen)")
					: geo.width() + "x" + geo.height() + T.t(" Blöcke")) + ", " + extra
					+ (src == null ? T.t(", noch kein Video (Link oder Kanal mit Haupt-Jukebox fehlt)") : ""));
			if (src == null) {
				return 1;
			}
			if (info.source() == null) {
				status(T.t("Zeigt das Video von Kanal #") + info.channel() + ": " + src.describe());
				return 1;
			}
		}
		if (info.source() == null && !info.screen()) {
			status(T.t("Lautsprecher: ") + extra + T.t(". Spielt, was die Haupt-Jukebox dieses Kanals spielt."));
			return 1;
		}
		status(T.t("Gefunden: ") + info.source().describe() + " (" + extra + T.t(") – prüfe …"));
		Tools.fetchTitle(info.source().spec(), MultiMediaClient::status)
				.thenAccept(title -> status(T.t("Funktioniert: ") + title))
				.exceptionally(e -> {
					error(T.t("Link funktioniert nicht: ") + rootMessage(e));
					return null;
				});
		return 1;
	}

	// ---- Chat-Ausgabe (funktioniert auch aus Hintergrund-Threads) ----

	private static MutableComponent prefix() {
		return Component.literal("[MM] ").withStyle(ChatFormatting.LIGHT_PURPLE);
	}

	private static void line(String cmd, String text) {
		send(Component.literal("  " + cmd).withStyle(ChatFormatting.AQUA)
				.append(Component.literal(" – " + text).withStyle(ChatFormatting.GRAY)));
	}

	/** Meldung, die nicht direkt auf einen Befehl antwortet (z.B. Download-Fortschritt) – abschaltbar. */
	public static void info(String msg) {
		if (Settings.chatMessages()) {
			status(msg);
		}
	}

	public static void status(String msg) {
		send(prefix().append(Component.literal(msg).withStyle(ChatFormatting.GRAY)));
	}

	private static final java.util.Map<String, Long> LAST_ERRORS = new java.util.concurrent.ConcurrentHashMap<>();

	public static void error(String msg) {
		// dieselbe Meldung nicht immer wieder (z.B. wenn mehrere Jukeboxen denselben Fehler haben)
		long now = System.currentTimeMillis();
		Long last = LAST_ERRORS.put(msg, now);
		if (last != null && now - last < 60_000) {
			return;
		}
		send(prefix().append(Component.literal(msg).withStyle(ChatFormatting.RED)));
	}

	private static void send(Component msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.gui.hud.getChat().addClientSystemMessage(msg);
			}
		});
	}

	private static String rootMessage(Throwable e) {
		while (e.getCause() != null && e.getCause() != e) {
			e = e.getCause();
		}
		return e.getMessage() != null ? e.getMessage() : e.toString();
	}
}
