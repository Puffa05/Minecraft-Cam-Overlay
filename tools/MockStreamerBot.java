import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import com.sun.net.httpserver.HttpServer;

/**
 * Fake-Streamer.bot zum Testen ohne Streamer.bot.
 * Lauscht auf 127.0.0.1:7474 und gibt jeden eingehenden Request aus.
 *
 * Starten (JDK 21, keine Kompilierung noetig):
 *     java tools/MockStreamerBot.java          (Port 7474)
 *     java tools/MockStreamerBot.java 7480     (anderer Port)
 * Beenden mit Strg+C. Echter Streamer.bot darf dabei nicht laufen (gleicher Port).
 */
public class MockStreamerBot {
	public static void main(String[] args) throws Exception {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 7474;
		DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.createContext("/", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			System.out.printf("[%s] %s %s  %s%n", LocalTime.now().format(time),
					exchange.getRequestMethod(), exchange.getRequestURI(), body);
			exchange.sendResponseHeaders(204, -1); // wie Streamer.bot: 204 No Content
			exchange.close();
		});
		server.start();
		System.out.println("Fake-Streamer.bot lauscht auf http://127.0.0.1:" + port + "/  (Strg+C zum Beenden)");
	}
}
