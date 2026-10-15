package de.puffa.damagealert.gametest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpServer;

import de.puffa.damagealert.ConfigStore;
import de.puffa.damagealert.DamageAlertConfig;
import de.puffa.damagealert.EffectController;
import de.puffa.damagealert.StateServer;
import de.puffa.damagealert.StreamerBotClient;
import de.puffa.damagealert.client.DamageAlertScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Automatischer Ingame-Test: startet einen echten Client, erzeugt eine Welt und loest
 * Schaden, Portal und Zustaende per Befehl aus. Ein Fake-Streamer.bot auf Port 7474
 * sammelt die gesendeten Actions.
 */
public class DamageAlertClientGameTest implements FabricClientGameTest {
	private static final Pattern NAME = Pattern.compile("\"name\":\"([^\"]*)\"");

	record Event(String name, String body, long nanos) {
	}

	private final List<Event> events = new CopyOnWriteArrayList<>();
	private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

	private HttpServer mock;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		mock = startMockStreamerBot();
		try {
			run(ctx);
		} finally {
			mock.stop(0);
		}
	}

	private void run(ClientGameTestContext ctx) {
		// --- Hauptmenue ---
		String state = getState();
		log("Hauptmenue: " + state);
		check(state.contains("\"inWorld\":false") && state.contains("\"underwater\":false")
				&& state.contains("\"freeze\":0") && state.contains("\"biome\":null"), "Zustand ohne Welt");

		// Belegter Port darf nur eine Warnung erzeugen (der Mod-Server belegt 7475 bereits)
		StateServer second = new StateServer(new EffectController(
				new ConfigStore(Path.of("build-gametest-unused.properties"), DamageAlertConfig.defaults()), new StreamerBotClient(7474)));
		check(!second.start(7475), "Zweiter Server auf belegtem Port muss false liefern, ohne Exception");
		check(getPage().contains("<title>Damage Alert</title>"), "Oberflaeche wird unter / ausgeliefert");

		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			TestServerContext server = sp.getServer();
			server.runCommand("difficulty peaceful");
			server.runCommand("gamemode survival @a");
			server.runCommand("gamerule natural_health_regeneration false");
			server.runCommand("gamerule immediate_respawn true");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("tp @a 8.5 -60 8.5");
			ctx.waitTicks(60); // Join-Schonfrist (40 Ticks) abwarten
			settle();
			check(events.isEmpty(), "Keine Events direkt nach dem Join, war: " + events);

			// --- Grundzustand ---
			state = getState();
			log("Grundzustand: " + state);
			check(state.contains("\"inWorld\":true") && state.contains("\"underwater\":false")
					&& state.contains("\"dimension\":\"minecraft:overworld\"") && state.contains("\"y\":-60")
					&& state.contains("\"biome\":\"minecraft:plains\""), "Grundzustand Overworld");
			ctx.takeScreenshot("01-grundzustand");

			testScreen(ctx);
			testDamage(ctx, server);
			testDamageToggle(ctx, server);
			testStates(ctx, server);
			testRespawnGrace(ctx, server);
			testHangingStreamerBot(ctx, server);
			testPortal(ctx, server);

			// Spieler steht wieder in einem Portal -> beim Verlassen der Welt muss "Portal Ende" kommen
			events.clear();
		}
		settle();
		check(count("Portal Ende") == 1, "Portal Ende beim Verlassen der Welt, war: " + events);
		state = getState();
		log("Nach Verlassen der Welt: " + state);
		check(state.contains("\"inWorld\":false") && state.contains("\"biome\":null"), "Zustand nach Verlassen");
		log("ALLE PRUEFUNGEN BESTANDEN");
	}

	private void testDamage(ClientGameTestContext ctx, TestServerContext server) {
		events.clear();
		server.runCommand("damage @p 4 minecraft:generic");
		ctx.waitTicks(3);
		settle();
		check(count("Minecraft Schaden") == 1, "1 Schadens-Event erwartet, war: " + events);
		Event first = events.get(0);
		check(first.body().contains("\"damage\":4") && first.body().contains("\"health\":16")
				&& first.body().startsWith("{\"action\":{\"name\":\"Minecraft Schaden\"},\"args\":{"),
				"Body Schaden: " + first.body());
		log("Schaden-Body: " + first.body());

		// Zweiter Treffer innerhalb des Cooldowns (1000 ms) -> kein Event.
		// Waehrend Minecrafts Treffer-Unverwundbarkeit zieht ein staerkerer Treffer nur die Differenz ab: 6 - 4 = 2 HP.
		ctx.waitTicks(1);
		server.runCommand("damage @p 6 minecraft:generic");
		ctx.waitTicks(2);
		long elapsedMs = (System.nanoTime() - first.nanos()) / 1_000_000;
		settle();
		check(ctx.computeOnClient(c -> c.player.getHealth()) == 14f, "Zweiter Treffer kam an (HP 14)");
		if (elapsedMs < 950) {
			check(count("Minecraft Schaden") == 1, "Cooldown: kein zweites Event nach " + elapsedMs + " ms, war: " + events);
		} else {
			log("WARNUNG: Cooldown-Pruefung uebersprungen, Ticks liefen zu langsam (" + elapsedMs + " ms)");
		}

		// Nach Ablauf des Cooldowns (und der Unverwundbarkeit) -> wieder ein Event
		ctx.waitTicks(25);
		sleep(1100);
		server.runCommand("damage @p 3 minecraft:generic");
		ctx.waitTicks(3);
		settle();
		check(count("Minecraft Schaden") == 2, "Schaden nach Cooldown erwartet, war: " + events);
		check(last().body().contains("\"damage\":3") && last().body().contains("\"health\":11"), "Body nach Cooldown: " + last().body());
	}

	private void testStates(ClientGameTestContext ctx, TestServerContext server) {
		int eventsBefore = events.size();
		server.runCommand("effect give @p minecraft:fire_resistance infinite 0 true");

		// Unterwasser
		server.runCommand("fill 6 -60 6 10 -56 10 minecraft:water");
		ctx.waitTicks(5);
		String state = getState();
		log("Unterwasser: " + state);
		check(state.contains("\"underwater\":true") && state.contains("\"inLava\":false"), "underwater");
		ctx.takeScreenshot("02-unterwasser");
		api("/api/toggle?effect=underwater&enabled=false");
		check(getState().contains("\"underwater\":false"), "Schalter aus: underwater wird als false gemeldet");
		api("/api/toggle?effect=underwater&enabled=true");
		check(getState().contains("\"underwater\":true"), "Schalter wieder an: underwater true");
		server.runCommand("fill 4 -60 4 12 -55 12 minecraft:air");

		// Frieren im Pulverschnee (vor Lava: brennende Spieler schmelzen Pulverschnee)
		server.runCommand("fill 7 -60 7 9 -58 9 minecraft:powder_snow");
		server.runCommand("tp @p 8.5 -60 8.5");
		ctx.waitTicks(80);
		state = getState();
		log("Pulverschnee: " + state);
		float freeze = Float.parseFloat(extract(state, "\"freeze\":([0-9.]+)"));
		check(freeze > 0.3f && freeze < 1.0f, "freeze zwischen 0.3 und 1, war " + freeze);
		ctx.takeScreenshot("03-frieren");
		server.runCommand("fill 4 -60 4 12 -55 12 minecraft:air");
		server.runCommand("tp @p 8.5 -60 8.5");
		ctx.waitTicks(100);
		check(getState().contains("\"freeze\":0,"), "freeze geht zurueck auf 0: " + getState());

		// Lava
		server.runCommand("fill 7 -60 7 9 -59 9 minecraft:lava");
		ctx.waitTicks(5);
		state = getState();
		log("Lava: " + state);
		check(state.contains("\"inLava\":true"), "inLava");
		server.runCommand("fill 4 -60 4 12 -55 12 minecraft:air");
		server.runCommand("tp @p 8.5 -60 8.5");
		ctx.waitTicks(10);

		// Biom
		server.runCommand("fillbiome 0 -64 0 15 -50 15 minecraft:cherry_grove");
		ctx.waitTicks(20);
		state = getState();
		log("Biom: " + state);
		check(state.contains("\"biome\":\"minecraft:cherry_grove\""), "biome cherry_grove");

		// Wetter (Regen-/Gewitterstaerke steigt auf dem Client langsam an)
		server.runCommand("weather thunder");
		ctx.waitTicks(120);
		state = getState();
		log("Gewitter: " + state);
		check(state.contains("\"raining\":true") && state.contains("\"thundering\":true"), "Gewitter");
		server.runCommand("weather clear");

		settle();
		check(events.size() == eventsBefore, "Keine Extra-Events bei Zustaenden: " + events);
	}

	private void testRespawnGrace(ClientGameTestContext ctx, TestServerContext server) {
		events.clear();
		sleep(1100); // Cooldown
		ClientPlayerEntity before = ctx.computeOnClient(c -> c.player);
		server.runCommand("kill @p");
		int ticksSinceRespawn = 0;
		ctx.waitFor(c -> c.player != null && c.player != before, 200);
		settle();
		check(count("Minecraft Schaden") == 1 && last().body().contains("\"health\":0"), "Tod meldet Schaden mit health 0: " + events);

		// Der Server nimmt erst Schaden an, wenn der Client fertig geladen hat
		ticksSinceRespawn += waitForServerLoaded(ctx, server);
		sleep(1100); // Cooldown, damit nur die Schonfrist ein Event verhindern kann
		server.runCommand("damage @p 3 minecraft:generic");
		ctx.waitTicks(3);
		ticksSinceRespawn += 3;
		settle();
		float health = ctx.computeOnClient(c -> c.player.getHealth());
		check(health == 17f, "Der Treffer kam tatsaechlich an (HP " + health + ")");
		if (ticksSinceRespawn < 38) {
			check(count("Minecraft Schaden") == 1, "Kein Schaden-Event in der Schonfrist (Tick " + ticksSinceRespawn + "), war: " + events);
		} else {
			log("WARNUNG: Schonfrist-Pruefung uebersprungen, Laden dauerte " + ticksSinceRespawn + " Ticks");
		}

		// Danach wieder normal
		ctx.waitTicks(45);
		sleep(1100);
		server.runCommand("damage @p 2 minecraft:generic");
		ctx.waitTicks(3);
		settle();
		check(count("Minecraft Schaden") == 2, "Schaden nach Schonfrist, war: " + events);
	}

	private void testPortal(ClientGameTestContext ctx, TestServerContext server) {
		server.runCommand("tp @p 8.5 -60 8.5");
		// Rahmen aus Obsidian bei x=20..23, z=8, Innenraum mit Portalbloecken (Achse X)
		server.runCommand("fill 20 -61 8 23 -57 8 minecraft:obsidian");
		server.runCommand("fill 21 -60 8 22 -58 8 minecraft:nether_portal[axis=x]");
		ctx.waitTicks(2);
		check(ctx.computeOnClient(c -> c.world.getBlockState(new BlockPos(21, -60, 8)).isOf(Blocks.NETHER_PORTAL)),
				"Portal wurde gebaut");
		float hpBefore = ctx.computeOnClient(c -> c.player.getHealth());
		check(hpBefore < 20f, "Fuer den HP-Sync-Test sollte HP < 20 sein, ist " + hpBefore);
		events.clear();

		// Ins Portal laufen
		server.runCommand("tp @p 21.5 -60 8.5");
		ctx.waitTicks(3);
		settle();
		check(names().equals(List.of("Portal Start")), "Portal Start erwartet, war: " + events);
		ctx.takeScreenshot("04-im-portal");

		// Warten auf Teleport in den Nether
		ctx.waitFor(c -> c.world != null && c.world.getRegistryKey() == World.NETHER, 1200);
		ctx.waitTicks(20);
		settle();
		check(names().equals(List.of("Portal Start", "Portal Ende")),
				"Nach Dimensionswechsel genau Portal Ende, kein neues Start und kein Schaden: " + events);
		boolean inPortal = ctx.computeOnClient(c -> standsInPortal(c.player));
		check(inPortal, "Spieler steht im Ziel-Portal");
		String state = getState();
		log("Nether: " + state);
		check(state.contains("\"dimension\":\"minecraft:the_nether\"") && state.contains("\"raining\":false"), "Nether-Zustand");
		ctx.takeScreenshot("05-nether-zielportal");

		// Aus dem Ziel-Portal heraustreten (senkrecht zur Portal-Ebene), Platz schaffen
		Direction out = ctx.computeOnClient(c -> {
			BlockPos p = portalPos(c.player);
			Direction.Axis axis = c.world.getBlockState(p).get(Properties.HORIZONTAL_AXIS);
			return axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
		});
		int dx = out.getOffsetX() * 2;
		int dz = out.getOffsetZ() * 2;
		server.runCommand("execute as @p at @s run fill ~" + (dx - 1) + " ~ ~" + (dz - 1) + " ~" + (dx + 1) + " ~2 ~" + (dz + 1) + " minecraft:air");
		server.runCommand("execute as @p at @s run fill ~" + (dx - 1) + " ~-1 ~" + (dz - 1) + " ~" + (dx + 1) + " ~-1 ~" + (dz + 1) + " minecraft:obsidian");
		server.runCommand("execute as @p at @s run tp @s ~" + dx + " ~ ~" + dz);
		ctx.waitTicks(5);
		check(!ctx.computeOnClient(c -> standsInPortal(c.player)), "Spieler ist aus dem Portal raus");
		settle();
		check(events.size() == 2, "Rausgehen nach Dimensionswechsel erzeugt kein Event: " + events);

		// Wieder hinein -> jetzt muss Portal Start kommen
		server.runCommand("execute as @p at @s run tp @s ~" + (-dx) + " ~ ~" + (-dz));
		ctx.waitTicks(3);
		settle();
		check(names().equals(List.of("Portal Start", "Portal Ende", "Portal Start")), "Erneutes Betreten: " + events);

		// Im Portal abschalten -> sofort "Portal Ende", damit der OBS-Effekt nicht haengen bleibt
		events.clear();
		api("/api/toggle?effect=portal&enabled=false");
		ctx.waitTicks(2);
		settle();
		check(names().equals(List.of("Portal Ende")), "Abschalten im Portal sendet Portal Ende: " + events);
		// Abgeschaltet raus und wieder rein -> nichts
		server.runCommand("execute as @p at @s run tp @s ~" + dx + " ~ ~" + dz);
		ctx.waitTicks(3);
		server.runCommand("execute as @p at @s run tp @s ~" + (-dx) + " ~ ~" + (-dz));
		ctx.waitTicks(3);
		settle();
		check(names().equals(List.of("Portal Ende")), "Abgeschaltet: kein Event beim Betreten/Verlassen: " + events);
		// Wieder an, raus und rein -> Start
		api("/api/toggle?effect=portal&enabled=true");
		server.runCommand("execute as @p at @s run tp @s ~" + dx + " ~ ~" + dz);
		ctx.waitTicks(3);
		settle();
		check(names().equals(List.of("Portal Ende")), "Wieder an, Verlassen ohne vorheriges Start sendet kein Ende: " + events);
		server.runCommand("execute as @p at @s run tp @s ~" + (-dx) + " ~ ~" + (-dz));
		ctx.waitTicks(3);
		settle();
		check(names().equals(List.of("Portal Ende", "Portal Start")), "Wieder an: Portal Start: " + events);
	}

	/** Ingame-Menue: per Taste oeffnen, schalten, testen, Abgleich mit der Weboberflaeche. */
	private void testScreen(ClientGameTestContext ctx) {
		// Auf Deutsch stellen, damit der Screenshot so aussieht wie bei dir
		ctx.runOnClient(c -> {
			c.options.language = "de_de";
			c.getLanguageManager().setLanguage("de_de");
			c.reloadResources();
		});
		ctx.waitFor(c -> c.getOverlay() == null, 1200);
		events.clear();

		KeyBinding key = KeyBinding.byId("key.damagealert.open_menu");
		check(key != null, "Tastenbelegung registriert");
		check(ctx.computeOnClient(c -> key.getBoundKeyTranslationKey()).equals("key.keyboard.j"), "Standard-Taste ist J");
		ctx.getInput().pressKey(key);
		ctx.waitForScreen(DamageAlertScreen.class);
		check(true, "Taste J oeffnet das Menue");
		check(events.isEmpty(), "Oeffnen sendet nichts");
		ctx.takeScreenshot("06-menue");

		// Schalter im Menue -> Config und Weboberflaeche
		press(ctx, "toggle.damage");
		check(cfg().contains("enabled.damage=false"), "Menue-Schalter speichert enabled.damage=false");
		check(getSettings().contains("\"damage\":false"), "Weboberflaeche sieht den Menue-Schalter");
		check(buttonText(ctx, "toggle.damage").equals("Schaden: Aus"), "Button zeigt 'Schaden: Aus', war: " + buttonText(ctx, "toggle.damage"));

		// Schalter in der Weboberflaeche -> Menue aktualisiert sich
		api("/api/toggle?effect=damage&enabled=true");
		ctx.waitTicks(2);
		check(buttonText(ctx, "toggle.damage").equals("Schaden: An"), "Menue uebernimmt Web-Schalter: " + buttonText(ctx, "toggle.damage"));

		// Test-Button Event
		press(ctx, "test.damage");
		ctx.waitTicks(2);
		settle();
		ctx.waitTicks(2);
		check(count("Minecraft Schaden") == 1 && last().body().contains("\"damage\":2,\"health\":18"), "Test-Button Schaden sendet: " + events);
		String status = ctx.computeOnClient(c -> ((DamageAlertScreen) c.currentScreen).status().getString());
		check(status.contains("✓") && status.contains("204"), "Menue zeigt Ergebnis: " + status);
		ctx.takeScreenshot("07-menue-test-ergebnis");

		// Test-Button Zustand
		press(ctx, "test.underwater");
		check(getState().contains("\"underwater\":true"), "Test Unterwasser im Menue -> /state underwater true");

		press(ctx, "done");
		ctx.waitForScreen(null);
		check(true, "Fertig schliesst das Menue");
		sleep(5200); // Zustands-Test ablaufen lassen
		check(getState().contains("\"underwater\":false"), "Zustands-Test endet nach 5 s");
		events.clear();
	}

	private static String cfg() {
		try {
			return java.nio.file.Files.readString(FabricLoader.getInstance().getConfigDir().resolve("damagealert.properties"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void press(ClientGameTestContext ctx, String id) {
		ctx.runOnClient(c -> {
			ButtonWidget b = ((DamageAlertScreen) c.currentScreen).button(id);
			if (b == null) {
				throw new AssertionError("Button fehlt: " + id);
			}
			b.onPress(new KeyInput(GLFW.GLFW_KEY_UNKNOWN, 0, 0));
		});
	}

	private static String buttonText(ClientGameTestContext ctx, String id) {
		return ctx.computeOnClient(c -> ((DamageAlertScreen) c.currentScreen).button(id).getMessage().getString());
	}

	private String getSettings() {
		try {
			return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:7475/api/settings")).build(),
					HttpResponse.BodyHandlers.ofString()).body();
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private void testDamageToggle(ClientGameTestContext ctx, TestServerContext server) {
		events.clear();
		api("/api/toggle?effect=damage&enabled=false");
		ctx.waitTicks(25);
		sleep(1100);
		server.runCommand("damage @p 1 minecraft:generic");
		ctx.waitTicks(3);
		settle();
		check(events.isEmpty(), "Schaden abgeschaltet: kein Event, war: " + events);
		api("/api/toggle?effect=damage&enabled=true");
		ctx.waitTicks(25);
		server.runCommand("damage @p 1 minecraft:generic");
		ctx.waitTicks(3);
		settle();
		check(count("Minecraft Schaden") == 1 && last().body().contains("\"health\":9"), "Schaden wieder an: " + events);
		// Die Config-Datei im run-Ordner muss den Schalter enthalten
		try {
			String cfg = java.nio.file.Files.readString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("damagealert.properties"));
			check(cfg.contains("enabled.damage=true") && cfg.contains("enabled.portal=true"), "Config enthaelt Schalter");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** Streamer.bot nimmt Verbindungen an, antwortet aber nie: das Spiel darf nicht langsamer werden. */
	private void testHangingStreamerBot(ClientGameTestContext ctx, TestServerContext server) {
		long base = timeTicks(ctx, 40);
		mock.stop(0);
		List<Socket> keep = new ArrayList<>();
		try (ServerSocket hanging = new ServerSocket(7474, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
			Thread acceptor = new Thread(() -> {
				try {
					while (true) {
						keep.add(hanging.accept());
					}
				} catch (IOException ignored) {
					// geschlossen
				}
			}, "haengender-streamerbot");
			acceptor.setDaemon(true);
			acceptor.start();

			long withHang = 0;
			for (int i = 0; i < 3; i++) {
				sleep(1100);
				server.runCommand("damage @p 1 minecraft:generic");
				withHang += timeTicks(ctx, 14);
			}
			log("40 Ticks normal: " + base + " ms, 42 Ticks mit haengendem Streamer.bot: " + withHang + " ms");
			check(withHang < base * 2 + 1500, "Spiel laeuft trotz haengendem Streamer.bot normal weiter");
			check(keep.size() >= 1, "Mod hat den haengenden Streamer.bot tatsaechlich angefragt (" + keep.size() + " Verbindungen)");
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			for (Socket sock : keep) {
				try {
					sock.close();
				} catch (IOException ignored) {
					// egal
				}
			}
		}
		sleep(3500); // laufende Requests in den Timeout laufen lassen
		mock = startMockStreamerBot();
		server.runCommand("effect give @p minecraft:instant_health 1 5 true");
		ctx.waitTicks(30);
		check(ctx.computeOnClient(c -> c.player.getHealth()) > 15f, "Wieder geheilt fuer den Portal-Test");
		sleep(1100);
		events.clear();
		server.runCommand("damage @p 2 minecraft:generic");
		ctx.waitTicks(3);
		settle();
		check(count("Minecraft Schaden") == 1, "Danach kommen Events wieder normal an: " + events);
	}

	private static long timeTicks(ClientGameTestContext ctx, int ticks) {
		long t = System.nanoTime();
		ctx.waitTicks(ticks);
		return (System.nanoTime() - t) / 1_000_000;
	}

	// ----------------------------------------------------------------- Hilfen

	/** Wartet, bis der Spieler auf dem Server fertig geladen ist (erst dann nimmt er Schaden). Liefert die Ticks. */
	private static int waitForServerLoaded(ClientGameTestContext ctx, TestServerContext server) {
		int ticks = 0;
		while (!serverLoaded(server) && ticks < 200) {
			ctx.waitTick();
			ticks++;
		}
		return ticks;
	}

	private static boolean serverLoaded(TestServerContext server) {
		return server.computeOnServer(s -> !s.getPlayerManager().getPlayerList().isEmpty()
				&& s.getPlayerManager().getPlayerList().get(0).networkHandler.canInteractWithGame());
	}

	private static BlockPos portalPos(ClientPlayerEntity player) {
		for (BlockPos p : BlockPos.iterate(player.getBlockPos().add(-1, 0, -1), player.getBlockPos().add(1, 1, 1))) {
			if (player.getEntityWorld().getBlockState(p).getBlock() instanceof NetherPortalBlock) {
				return p.toImmutable();
			}
		}
		throw new AssertionError("Kein Portalblock beim Spieler");
	}

	private static boolean standsInPortal(ClientPlayerEntity player) {
		var box = player.getBoundingBox().contract(1.0E-5);
		for (BlockPos p : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
			if (player.getEntityWorld().getBlockState(p).isOf(Blocks.NETHER_PORTAL)) {
				return true;
			}
		}
		return false;
	}

	private HttpServer startMockStreamerBot() {
		try {
			HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 7474), 0);
			s.createContext("/DoAction", ex -> {
				String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				Matcher m = NAME.matcher(body);
				events.add(new Event(m.find() ? m.group(1) : "?", body, System.nanoTime()));
				log("Streamer.bot <- " + ex.getRequestMethod() + " " + ex.getRequestURI() + " " + body);
				ex.sendResponseHeaders(204, -1);
				ex.close();
			});
			s.start();
			return s;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private String getState() {
		try {
			HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:7475/state")).build(),
					HttpResponse.BodyHandlers.ofString());
			check(r.statusCode() == 200, "Status /state " + r.statusCode());
			check("*".equals(r.headers().firstValue("Access-Control-Allow-Origin").orElse("")), "CORS-Header vorhanden");
			return r.body();
		} catch (Exception e) {
			throw new RuntimeException("GET /state fehlgeschlagen", e);
		}
	}

	/** Ruft die API so auf wie die Oberflaeche (POST mit eigenem Header). */
	private String api(String pathAndQuery) {
		try {
			HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:7475" + pathAndQuery))
					.header("X-DamageAlert", "1").POST(HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.ofString());
			check(r.statusCode() == 200, "API " + pathAndQuery + " -> " + r.statusCode() + " " + r.body());
			return r.body();
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private String getPage() {
		try {
			return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:7475/")).build(),
					HttpResponse.BodyHandlers.ofString()).body();
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private List<String> names() {
		return events.stream().map(Event::name).toList();
	}

	private long count(String name) {
		return events.stream().filter(e -> e.name().equals(name)).count();
	}

	private Event last() {
		return events.get(events.size() - 1);
	}

	private static String extract(String s, String regex) {
		Matcher m = Pattern.compile(regex).matcher(s);
		check(m.find(), "Nicht gefunden: " + regex + " in " + s);
		return m.group(1);
	}

	/** Kurz warten, damit asynchrone HTTP-Requests ankommen. */
	private static void settle() {
		sleep(400);
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("FEHLGESCHLAGEN: " + what);
		}
		log("OK: " + what);
	}

	private static void log(String msg) {
		System.out.println("[DamageAlertTest] " + msg);
	}
}
