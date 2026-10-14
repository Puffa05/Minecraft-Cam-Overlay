package de.puffa.damagealert.client;

import java.util.Map;

import de.puffa.damagealert.StreamerBotClient;
import net.minecraft.client.network.ClientPlayerEntity;

/** Erkennt sinkende HP und meldet sie an Streamer.bot. */
final class DamageTracker {
	/**
	 * So viele Ticks (2 Sekunden) nach Join/Respawn/Dimensionswechsel keine Schadens-Events.
	 * Gezaehlt wird erst, wenn der Spieler fertig geladen ist (vorher nimmt der Server ohnehin keinen Schaden an).
	 */
	static final int GRACE_TICKS = 40;

	private final StreamerBotClient streamerBot;
	private final String actionName;
	private final long cooldownNanos;

	private float lastHealth = -1f;
	private int graceTicksLeft;
	private long lastSentNanos;
	private boolean sentOnce;

	DamageTracker(StreamerBotClient streamerBot, String actionName, long cooldownMs) {
		this.streamerBot = streamerBot;
		this.actionName = actionName;
		this.cooldownNanos = cooldownMs * 1_000_000L;
	}

	/** Neues Spieler-Objekt: Der Server synct erst noch die echten HP, deshalb kurz stillhalten. */
	void onNewPlayer(ClientPlayerEntity player) {
		graceTicksLeft = GRACE_TICKS;
		lastHealth = player.getHealth();
	}

	void tick(ClientPlayerEntity player, boolean loaded) {
		float health = player.getHealth();

		if (!loaded) {
			lastHealth = health;
			return;
		}
		if (graceTicksLeft > 0) {
			graceTicksLeft--;
			lastHealth = health;
			return;
		}

		float damage = lastHealth - health;
		if (damage > 0.001f) {
			long now = System.nanoTime();
			if (!sentOnce || now - lastSentNanos >= cooldownNanos) {
				sentOnce = true;
				lastSentNanos = now;
				streamerBot.doAction(actionName, Map.of("damage", damage, "health", health));
			}
			// Schaden innerhalb des Cooldowns wird verworfen (nicht nachgeholt)
		}
		lastHealth = health;
	}
}
