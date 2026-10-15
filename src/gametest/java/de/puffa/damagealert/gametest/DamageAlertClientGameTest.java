package de.puffa.damagealert.gametest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.puffa.damagealert.ConfigStore;
import de.puffa.damagealert.DamageAlertConfig;
import de.puffa.damagealert.DeathCounter;
import de.puffa.damagealert.EffectController;
import de.puffa.damagealert.OverlayEvents;
import de.puffa.damagealert.StateServer;
import de.puffa.damagealert.client.DamageAlertScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

/**
 * Automatischer Ingame-Test: startet einen echten Client, erzeugt eine Welt und loest Schaden,
 * Tod, Portal und Zustaende per Befehl aus. Geprueft wird, was die Overlays unter /state sehen.
 */
public class DamageAlertClientGameTest implements FabricClientGameTest {
	private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		// --- Hauptmenue ---
		api("/api/deaths?op=resetTotal"); // Zaehler aus frueheren Testlaeufen loeschen (run-Ordner bleibt bestehen)
		String state = getState();
		log("Hauptmenue: " + state);
		check(state.contains("\"inWorld\":false") && state.contains("\"biome\":null") && state.contains("\"health\":null")
				&& state.contains("\"deaths\":{\"total\":0,\"session\":0}") && damageSeq() == 0 && deathSeq() == 0,
				"Zustand ohne Welt");

		StateServer second = new StateServer(new EffectController(
				new ConfigStore(Path.of("build-gametest-unused.properties"), DamageAlertConfig.defaults()),
				new OverlayEvents(), DeathCounter.load(Path.of("build-gametest-unused-deaths.properties"))));
		check(!second.start(7475), "Zweiter Server auf belegtem Port liefert false, ohne Exception");
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
			check(damageSeq() == 0 && deathSeq() == 0, "Keine Ereignisse direkt nach dem Join");

			state = getState();
			log("Grundzustand: " + state);
			check(state.contains("\"inWorld\":true") && state.contains("\"dimension\":\"minecraft:overworld\"")
					&& state.contains("\"y\":-60") && state.contains("\"biome\":\"minecraft:plains\"")
					&& state.contains("\"health\":20") && state.contains("\"maxHealth\":20") && state.contains("\"inPortal\":false"),
					"Grundzustand Overworld");

			testScreen(ctx);
			testDamage(ctx, server);
			testStates(ctx, server);
			testDeath(ctx, server);
			testPortal(ctx, server);
		}

		state = getState();
		log("Nach Verlassen der Welt: " + state);
		check(state.contains("\"inWorld\":false") && state.contains("\"inPortal\":false"), "Zustand nach Verlassen");
		check(state.contains("\"deaths\":{\"total\":2,\"session\":2}"), "Todeszaehler bleibt nach Verlassen erhalten");
		String file = read(FabricLoader.getInstance().getConfigDir().resolve("damagealert-deaths.properties"));
		check(file.contains("total=2") && file.contains("session=2"), "Todeszaehler ist gespeichert");
		log("ALLE PRUEFUNGEN BESTANDEN");
	}

	/** Ingame-Menue: per Taste oeffnen, schalten, testen, Todeszaehler, Abgleich mit der Weboberflaeche. */
	private void testScreen(ClientGameTestContext ctx) {
		ctx.runOnClient(c -> {
			c.options.language = "de_de";
			c.getLanguageManager().setLanguage("de_de");
			c.reloadResources();
		});
		ctx.waitFor(c -> c.getOverlay() == null, 1200);

		KeyBinding key = KeyBinding.byId("key.damagealert.open_menu");
		check(key != null && ctx.computeOnClient(c -> key.getBoundKeyTranslationKey()).equals("key.keyboard.j"), "Taste J registriert");
		ctx.getInput().pressKey(key);
		ctx.waitForScreen(DamageAlertScreen.class);
		check(damageSeq() == 0 && deathSeq() == 0, "Menue oeffnen loest nichts aus");
		ctx.takeScreenshot("01-menue");

		press(ctx, "toggle.damage");
		check(read(FabricLoader.getInstance().getConfigDir().resolve("damagealert.properties")).contains("enabled.damage=false"),
				"Menue-Schalter speichert enabled.damage=false");
		check(getSettings().contains("\"damage\":false"), "Weboberflaeche sieht den Menue-Schalter");
		check(buttonText(ctx, "toggle.damage").equals("Schaden: Aus"), "Button 'Schaden: Aus': " + buttonText(ctx, "toggle.damage"));
		api("/api/toggle?effect=damage&enabled=true");
		ctx.waitTicks(2);
		check(buttonText(ctx, "toggle.damage").equals("Schaden: An"), "Menue uebernimmt Web-Schalter");

		press(ctx, "test.damage");
		check(damageSeq() == 1 && getState().contains("\"amount\":2,\"health\":18"), "Test Schaden -> Ereignis im Overlay");
		press(ctx, "test.death");
		check(deathSeq() == 1 && getState().contains("\"deaths\":{\"total\":0,\"session\":0}"), "Test Tod -> Ereignis, zaehlt aber nicht");
		press(ctx, "test.portal");
		check(getState().contains("\"inPortal\":true"), "Test Portal -> inPortal 5 s lang true");

		press(ctx, "deaths.inc");
		press(ctx, "deaths.inc");
		check(getState().contains("\"deaths\":{\"total\":2,\"session\":2}"), "+1 zweimal");
		press(ctx, "deaths.dec");
		press(ctx, "deaths.resetSession");
		check(getState().contains("\"deaths\":{\"total\":1,\"session\":0}"), "-1 und Stream auf 0");
		String text = ctx.computeOnClient(c -> ((DamageAlertScreen) c.currentScreen).deathsText().getString());
		check(text.contains("1") && text.contains("0"), "Menue zeigt Zaehler: " + text);
		api("/api/deaths?op=resetTotal");
		ctx.waitTicks(2);
		ctx.takeScreenshot("02-menue-test");

		press(ctx, "done");
		ctx.waitForScreen(null);
		sleep(5200); // Portal-Test ablaufen lassen
		check(getState().contains("\"inPortal\":false"), "Zustands-Test endet nach 5 s");
	}

	private void testDamage(ClientGameTestContext ctx, TestServerContext server) {
		long seq = damageSeq();
		server.runCommand("damage @p 4 minecraft:generic");
		ctx.waitTicks(3);
		check(damageSeq() == seq + 1 && getState().contains("\"amount\":4,\"health\":16"), "Schaden 4 -> Ereignis: " + getState());
		long t = System.nanoTime();

		// Zweiter Treffer innerhalb des Cooldowns -> kein neues Ereignis
		ctx.waitTicks(1);
		server.runCommand("damage @p 6 minecraft:generic"); // waehrend Unverwundbarkeit zaehlt nur 6-4 = 2
		ctx.waitTicks(2);
		long ms = (System.nanoTime() - t) / 1_000_000;
		check(ctx.computeOnClient(c -> c.player.getHealth()) == 14f, "Zweiter Treffer kam an (HP 14)");
		if (ms < 950) {
			check(damageSeq() == seq + 1, "Cooldown: kein zweites Ereignis nach " + ms + " ms");
		} else {
			log("WARNUNG: Cooldown-Pruefung uebersprungen (" + ms + " ms)");
		}

		ctx.waitTicks(25);
		sleep(1100);
		server.runCommand("damage @p 3 minecraft:generic");
		ctx.waitTicks(3);
		check(damageSeq() == seq + 2 && getState().contains("\"amount\":3,\"health\":11"), "Schaden nach Cooldown");

		// Abgeschaltet -> kein Ereignis
		api("/api/toggle?effect=damage&enabled=false");
		ctx.waitTicks(25);
		sleep(1100);
		server.runCommand("damage @p 1 minecraft:generic");
		ctx.waitTicks(3);
		check(damageSeq() == seq + 2, "Schaden abgeschaltet: kein Ereignis");
		api("/api/toggle?effect=damage&enabled=true");
		ctx.waitTicks(25);
		server.runCommand("damage @p 1 minecraft:generic");
		ctx.waitTicks(3);
		check(damageSeq() == seq + 3 && getState().contains("\"health\":9"), "Schaden wieder an");
	}

	private void testStates(ClientGameTestContext ctx, TestServerContext server) {
		long dmg = damageSeq();
		server.runCommand("effect give @p minecraft:fire_resistance infinite 0 true");

		server.runCommand("fill 6 -60 6 10 -56 10 minecraft:water");
		ctx.waitTicks(5);
		check(getState().contains("\"underwater\":true"), "underwater");
		api("/api/toggle?effect=underwater&enabled=false");
		check(getState().contains("\"underwater\":false"), "Schalter aus: underwater false");
		api("/api/toggle?effect=underwater&enabled=true");
		server.runCommand("fill 4 -60 4 12 -55 12 minecraft:air");

		// Frieren vor Lava: brennende Spieler schmelzen Pulverschnee
		server.runCommand("fill 7 -60 7 9 -58 9 minecraft:powder_snow");
		server.runCommand("tp @p 8.5 -60 8.5");
		ctx.waitTicks(80);
		float freeze = Float.parseFloat(extract(getState(), "\"freeze\":([0-9.]+)"));
		check(freeze > 0.3f && freeze < 1.0f, "freeze zwischen 0.3 und 1, war " + freeze);
		server.runCommand("fill 4 -60 4 12 -55 12 minecraft:air");
		server.runCommand("tp @p 8.5 -60 8.5");
		ctx.waitTicks(100);
		check(getState().contains("\"freeze\":0,"), "freeze zurueck auf 0");

		server.runCommand("fill 7 -60 7 9 -59 9 minecraft:lava");
		ctx.waitTicks(5);
		check(getState().contains("\"inLava\":true"), "inLava");
		server.runCommand("fill 4 -60 4 12 -55 12 minecraft:air");
		server.runCommand("tp @p 8.5 -60 8.5");

		server.runCommand("fillbiome 0 -64 0 15 -50 15 minecraft:cherry_grove");
		ctx.waitTicks(20);
		check(getState().contains("\"biome\":\"minecraft:cherry_grove\"") && getState().contains("\"biomeName\":\"Kirschberghain\""),
				"biome cherry_grove, Name auf Deutsch: " + extract(getState(), "\"biomeName\":(\"[^\"]*\")"));

		server.runCommand("weather thunder");
		ctx.waitTicks(120);
		check(getState().contains("\"raining\":true") && getState().contains("\"thundering\":true"), "Gewitter");
		server.runCommand("weather clear");
		check(damageSeq() == dmg, "Keine Schadens-Ereignisse durch Zustaende (Feuerresistenz)");
	}

	private void testDeath(ClientGameTestContext ctx, TestServerContext server) {
		long deathSeq = deathSeq();
		ClientPlayerEntity before = ctx.computeOnClient(c -> c.player);
		server.runCommand("kill @p");
		ctx.waitFor(c -> c.player != null && c.player != before, 200);
		ctx.waitTicks(5);
		check(getState().contains("\"deaths\":{\"total\":1,\"session\":1}") && deathSeq() == deathSeq + 1,
				"Tod gezaehlt und Ereignis: " + getState());
		ctx.takeScreenshot("03-nach-tod");

		// Schonfrist nach dem Respawn: Schaden ohne Ereignis, kein weiterer Tod
		long dmg = damageSeq();
		int ticks = waitForServerLoaded(ctx, server);
		sleep(1100);
		server.runCommand("damage @p 3 minecraft:generic");
		ctx.waitTicks(3);
		check(ctx.computeOnClient(c -> c.player.getHealth()) == 17f, "Treffer kam an (HP 17)");
		if (ticks + 3 < 38) {
			check(damageSeq() == dmg, "Kein Schadens-Ereignis in der Schonfrist (Tick " + (ticks + 3) + ")");
		}
		ctx.waitTicks(60);
		check(getState().contains("\"total\":1,\"session\":1"), "Nach dem Respawn wird nicht doppelt gezaehlt");

		// Tod-Effekt aus: zaehlt trotzdem, aber ohne Ereignis
		api("/api/toggle?effect=death&enabled=false");
		ClientPlayerEntity before2 = ctx.computeOnClient(c -> c.player);
		server.runCommand("kill @p");
		ctx.waitFor(c -> c.player != null && c.player != before2, 200);
		ctx.waitTicks(5);
		check(getState().contains("\"deaths\":{\"total\":2,\"session\":2}") && deathSeq() == deathSeq + 1,
				"Tod-Effekt aus: gezaehlt, aber kein Ereignis");
		api("/api/toggle?effect=death&enabled=true");
		waitForServerLoaded(ctx, server);
		ctx.waitTicks(45);
		sleep(1100);
		server.runCommand("damage @p 2 minecraft:generic"); // HP < 20 fuer den Portal-Test
		ctx.waitTicks(3);
	}

	private void testPortal(ClientGameTestContext ctx, TestServerContext server) {
		long dmg = damageSeq();
		server.runCommand("tp @p 8.5 -60 8.5");
		server.runCommand("fill 20 -61 8 23 -57 8 minecraft:obsidian");
		server.runCommand("fill 21 -60 8 22 -58 8 minecraft:nether_portal[axis=x]");
		ctx.waitTicks(2);
		check(ctx.computeOnClient(c -> c.world.getBlockState(new BlockPos(21, -60, 8)).isOf(Blocks.NETHER_PORTAL)), "Portal gebaut");

		server.runCommand("tp @p 21.5 -60 8.5");
		ctx.waitTicks(3);
		check(getState().contains("\"inPortal\":true"), "Im Portal: inPortal true");
		ctx.takeScreenshot("04-im-portal");

		ctx.waitFor(c -> c.world != null && c.world.getRegistryKey() == World.NETHER, 1200);
		ctx.waitTicks(20);
		check(ctx.computeOnClient(c -> standsInPortal(c.player)), "Spieler steht im Ziel-Portal");
		String state = getState();
		check(state.contains("\"inPortal\":false") && state.contains("\"dimension\":\"minecraft:the_nether\""),
				"Im Ziel-Portal nach Dimensionswechsel: inPortal false (kein sofortiger Neustart des Effekts)");
		check(damageSeq() == dmg, "Kein falsches Schadens-Ereignis durch HP-Sync nach Dimensionswechsel");

		Direction out = ctx.computeOnClient(c -> {
			BlockPos p = portalPos(c.player);
			return c.world.getBlockState(p).get(Properties.HORIZONTAL_AXIS) == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
		});
		int dx = out.getOffsetX() * 2;
		int dz = out.getOffsetZ() * 2;
		server.runCommand("execute as @p at @s run fill ~" + (dx - 1) + " ~ ~" + (dz - 1) + " ~" + (dx + 1) + " ~2 ~" + (dz + 1) + " minecraft:air");
		server.runCommand("execute as @p at @s run fill ~" + (dx - 1) + " ~-1 ~" + (dz - 1) + " ~" + (dx + 1) + " ~-1 ~" + (dz + 1) + " minecraft:obsidian");
		server.runCommand("execute as @p at @s run tp @s ~" + dx + " ~ ~" + dz);
		ctx.waitTicks(5);
		check(getState().contains("\"inPortal\":false"), "Aus dem Portal raus");
		server.runCommand("execute as @p at @s run tp @s ~" + (-dx) + " ~ ~" + (-dz));
		ctx.waitTicks(3);
		check(getState().contains("\"inPortal\":true"), "Erneut betreten: inPortal true");

		api("/api/toggle?effect=portal&enabled=false");
		check(getState().contains("\"inPortal\":false"), "Portal-Schalter aus: inPortal false");
		api("/api/toggle?effect=portal&enabled=true");
		check(getState().contains("\"inPortal\":true"), "Wieder an: inPortal true");
	}

	// ----------------------------------------------------------------- Hilfen

	private static int waitForServerLoaded(ClientGameTestContext ctx, TestServerContext server) {
		int ticks = 0;
		while (!server.computeOnServer(s -> !s.getPlayerManager().getPlayerList().isEmpty()
				&& s.getPlayerManager().getPlayerList().get(0).networkHandler.canInteractWithGame()) && ticks < 200) {
			ctx.waitTick();
			ticks++;
		}
		return ticks;
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

	private long damageSeq() {
		return Long.parseLong(extract(getState(), "\"damage\":\\{\"seq\":(\\d+)"));
	}

	private long deathSeq() {
		return Long.parseLong(extract(getState(), "\"death\":\\{\"seq\":(\\d+)"));
	}

	private String getState() {
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:7475/state")).build());
		if (r.statusCode() != 200 || !"*".equals(r.headers().firstValue("Access-Control-Allow-Origin").orElse(""))) {
			throw new AssertionError("FEHLGESCHLAGEN: /state " + r.statusCode() + " " + r.headers().map());
		}
		return r.body();
	}

	private String getSettings() {
		return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:7475/api/settings")).build()).body();
	}

	private String getPage() {
		return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:7475/")).build()).body();
	}

	/** Ruft die API so auf wie die Oberflaeche (POST mit eigenem Header). */
	private String api(String pathAndQuery) {
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:7475" + pathAndQuery))
				.header("X-DamageAlert", "1").POST(HttpRequest.BodyPublishers.noBody()).build());
		if (r.statusCode() != 200) {
			throw new AssertionError("FEHLGESCHLAGEN: API " + pathAndQuery + " -> " + r.statusCode() + " " + r.body());
		}
		return r.body();
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static String extract(String s, String regex) {
		Matcher m = Pattern.compile(regex).matcher(s);
		if (!m.find()) {
			throw new AssertionError("FEHLGESCHLAGEN: Nicht gefunden: " + regex + " in " + s);
		}
		return m.group(1);
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
