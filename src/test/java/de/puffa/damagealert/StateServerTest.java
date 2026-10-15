package de.puffa.damagealert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Testet Oberflaeche/API und Streamer.bot-Versand ohne Minecraft. */
class StateServerTest {
	@TempDir
	Path dir;

	private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
	private StateServer server;
	private ConfigStore store;
	private DeathCounter deaths;
	private String base;

	@BeforeEach
	void setUp() {
		store = ConfigStore.load(dir.resolve("damagealert.properties"));
		deaths = DeathCounter.load(dir.resolve("damagealert-deaths.properties"));
		server = new StateServer(new EffectController(store, new OverlayEvents(), deaths));
		assertTrue(server.start(0));
		base = "http://127.0.0.1:" + server.port();
	}

	@AfterEach
	void tearDown() {
		server.stop();
	}

	private HttpResponse<String> get(String path, String... headers) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
		if (headers.length > 0) {
			b.headers(headers);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, String... headers) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody());
		if (headers.length > 0) {
			b.headers(headers);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void stateHasCorsButApiDoesNot() throws Exception {
		HttpResponse<String> state = get("/state");
		assertEquals(200, state.statusCode());
		assertEquals("*", state.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
		assertTrue(state.body().contains("\"biome\":null"));

		HttpResponse<String> settings = get("/api/settings");
		assertEquals(200, settings.statusCode());
		assertTrue(settings.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
		assertTrue(settings.body().contains("\"damage\":true"), settings.body());
	}

	@Test
	void pageIsServed() throws Exception {
		HttpResponse<String> page = get("/");
		assertEquals(200, page.statusCode());
		assertTrue(page.body().contains("<title>Damage Alert</title>"));
		assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
	}

	@Test
	void toggleRequiresOwnPageAndPersists() throws Exception {
		// Ohne eigenen Header (z. B. Formular einer fremden Seite) -> verboten
		assertEquals(403, post("/api/toggle?effect=damage&enabled=false").statusCode());
		// Fremde Origin -> verboten, auch mit Header
		assertEquals(403, post("/api/toggle?effect=damage&enabled=false",
				"X-DamageAlert", "1", "Origin", "https://evil.example").statusCode());
		assertTrue(store.isEnabled(Effect.DAMAGE));

		HttpResponse<String> ok = post("/api/toggle?effect=damage&enabled=false",
				"X-DamageAlert", "1", "Origin", base);
		assertEquals(200, ok.statusCode());
		assertTrue(ok.body().contains("\"damage\":false"), ok.body());
		assertFalse(store.isEnabled(Effect.DAMAGE));
		assertFalse(DamageAlertConfig.load(dir.resolve("damagealert.properties")).isEnabled(Effect.DAMAGE));

		assertEquals(400, post("/api/toggle?effect=gibtsnicht&enabled=false", "X-DamageAlert", "1").statusCode());
		assertEquals(400, post("/api/toggle?effect=damage&enabled=vielleicht", "X-DamageAlert", "1").statusCode());
	}

	@Test
	void wrongHostIsRejected() throws Exception {
		// Simuliert DNS-Rebinding: Anfrage kommt an, aber mit fremdem Host-Header
		try (Socket s = new Socket("127.0.0.1", server.port())) {
			s.getOutputStream().write(("GET /state HTTP/1.1\r\nHost: evil.example:" + server.port()
					+ "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			String response = new String(s.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
			assertTrue(response.startsWith("HTTP/1.1 403"), response);
		}
	}

	@Test
	void testButtonsBumpEventSequence() throws Exception {
		store.setEnabled(Effect.DAMAGE, false); // Test geht trotzdem
		assertTrue(get("/state").body().contains("\"damage\":{\"seq\":0"));
		assertEquals("{\"ok\":true}", post("/api/test?event=damage", "X-DamageAlert", "1").body());
		String state = get("/state").body();
		assertTrue(state.contains("\"damage\":{\"seq\":1,\"amount\":2,\"health\":18}"), state);
		post("/api/test?event=death", "X-DamageAlert", "1");
		state = get("/state").body();
		assertTrue(state.contains("\"death\":{\"seq\":1}"), state);
		assertTrue(state.contains("\"deaths\":{\"total\":0,\"session\":0}"), "Test-Tod zaehlt nicht: " + state);
		assertEquals(400, post("/api/test?event=gibtsnicht", "X-DamageAlert", "1").statusCode());
	}

	@Test
	void deathCounterApi() throws Exception {
		deaths.increment();
		deaths.increment();
		assertTrue(get("/state").body().contains("\"deaths\":{\"total\":2,\"session\":2}"));
		assertEquals(403, post("/api/deaths?op=resetTotal").statusCode()); // ohne eigenen Header verboten
		HttpResponse<String> r = post("/api/deaths?op=inc", "X-DamageAlert", "1");
		assertTrue(r.body().contains("\"deaths\":{\"total\":3,\"session\":3}"), r.body());
		post("/api/deaths?op=resetSession", "X-DamageAlert", "1");
		post("/api/deaths?op=dec", "X-DamageAlert", "1");
		assertEquals(new DeathCounter.Counts(2, 0), deaths.get());
		post("/api/deaths?op=resetTotal", "X-DamageAlert", "1");
		assertEquals(new DeathCounter.Counts(0, 0), deaths.get());
		assertEquals(400, post("/api/deaths?op=quatsch", "X-DamageAlert", "1").statusCode());
	}

	@Test
	void stateTestTurnsStateOnTemporarily() throws Exception {
		store.setEnabled(Effect.UNDERWATER, false);
		assertTrue(get("/state").body().contains("\"underwater\":false"));
		HttpResponse<String> r = post("/api/test?state=underwater", "X-DamageAlert", "1");
		assertEquals(200, r.statusCode());
		assertTrue(get("/state").body().contains("\"underwater\":true"));
		assertEquals(400, post("/api/test?state=biome", "X-DamageAlert", "1").statusCode());
		post("/api/test?state=portal", "X-DamageAlert", "1");
		assertTrue(get("/state").body().contains("\"inPortal\":true"));
	}

	@Test
	void guards() {
		assertTrue(StateServer.isAllowedHost("127.0.0.1:7475", 7475));
		assertTrue(StateServer.isAllowedHost("LOCALHOST:7475", 7475));
		assertFalse(StateServer.isAllowedHost("127.0.0.1:7476", 7475));
		assertFalse(StateServer.isAllowedHost("evil.example:7475", 7475));
		assertFalse(StateServer.isAllowedHost(null, 7475));
		assertTrue(StateServer.isTrustedPost(null, "1", 7475));
		assertTrue(StateServer.isTrustedPost("http://localhost:7475", "1", 7475));
		assertFalse(StateServer.isTrustedPost(null, null, 7475));
		assertFalse(StateServer.isTrustedPost("null", "1", 7475));
		assertFalse(StateServer.isTrustedPost("http://127.0.0.1:7475.evil.example", "1", 7475));
		assertNull(Effect.byId("nope"));
	}
}
