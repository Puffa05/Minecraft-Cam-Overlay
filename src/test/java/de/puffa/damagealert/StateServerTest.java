package de.puffa.damagealert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Testet Oberflaeche/API und Streamer.bot-Versand ohne Minecraft. */
class StateServerTest {
	@TempDir
	Path dir;

	private final List<String> received = new CopyOnWriteArrayList<>();
	private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
	private HttpServer mockBot;
	private StateServer server;
	private ConfigStore store;
	private String base;

	@BeforeEach
	void setUp() throws IOException {
		mockBot = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		mockBot.createContext("/DoAction", ex -> {
			received.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			ex.sendResponseHeaders(204, -1);
			ex.close();
		});
		mockBot.start();
		store = ConfigStore.load(dir.resolve("damagealert.properties"));
		server = new StateServer(store, new StreamerBotClient(mockBot.getAddress().getPort()));
		assertTrue(server.start(0));
		base = "http://127.0.0.1:" + server.port();
	}

	@AfterEach
	void tearDown() {
		server.stop();
		mockBot.stop(0);
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
	void testButtonsSendEventsAndReportResult() throws Exception {
		// Test sendet auch, wenn das Event abgeschaltet ist
		store.setEnabled(Effect.DAMAGE, false);
		HttpResponse<String> r = post("/api/test?action=damage", "X-DamageAlert", "1");
		assertEquals("{\"ok\":true,\"status\":204,\"error\":null}", r.body());
		assertEquals(List.of("{\"action\":{\"name\":\"Minecraft Schaden\"},\"args\":{\"damage\":2,\"health\":18}}"), received);

		post("/api/test?action=portalStart", "X-DamageAlert", "1");
		post("/api/test?action=portalEnd", "X-DamageAlert", "1");
		assertEquals(3, received.size());
		assertTrue(received.get(2).contains("Portal Ende"));
	}

	@Test
	void stateTestTurnsStateOnTemporarily() throws Exception {
		store.setEnabled(Effect.UNDERWATER, false);
		assertTrue(get("/state").body().contains("\"underwater\":false"));
		HttpResponse<String> r = post("/api/test?state=underwater", "X-DamageAlert", "1");
		assertEquals(200, r.statusCode());
		assertTrue(get("/state").body().contains("\"underwater\":true"));
		assertEquals(400, post("/api/test?state=biome", "X-DamageAlert", "1").statusCode());
	}

	@Test
	void reportsWhenStreamerBotIsDown() throws Exception {
		int freePort;
		try (ServerSocket ss = new ServerSocket(0)) {
			freePort = ss.getLocalPort();
		}
		StreamerBotClient down = new StreamerBotClient(freePort);
		long t = System.nanoTime();
		StreamerBotClient.SendResult result = down.doAction("X").get(5, TimeUnit.SECONDS);
		assertFalse(result.ok());
		assertTrue(result.error().contains("nicht erreichbar"), result.error());
		assertTrue((System.nanoTime() - t) / 1_000_000 < 3000);
	}

	@Test
	void hangingStreamerBotTimesOutAndDoesNotBlockCaller() throws Exception {
		// Nimmt Verbindungen an, antwortet aber nie
		try (ServerSocket hanging = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
			Thread acceptor = new Thread(() -> {
				List<Socket> keep = new java.util.ArrayList<>();
				try {
					while (true) {
						keep.add(hanging.accept());
					}
				} catch (IOException ignored) {
					// Socket zu
				}
			});
			acceptor.setDaemon(true);
			acceptor.start();

			StreamerBotClient client = new StreamerBotClient(hanging.getLocalPort());
			long t = System.nanoTime();
			var future = client.doAction("X");
			long callMs = (System.nanoTime() - t) / 1_000_000;
			assertTrue(callMs < 200, "doAction muss sofort zurueckkehren, dauerte " + callMs + " ms");
			StreamerBotClient.SendResult result = future.get(10, TimeUnit.SECONDS);
			long totalMs = (System.nanoTime() - t) / 1_000_000;
			assertFalse(result.ok());
			assertTrue(result.error().contains("Zeitüberschreitung"), result.error());
			assertTrue(totalMs < 5000, "Timeout nach " + totalMs + " ms");
		}
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
