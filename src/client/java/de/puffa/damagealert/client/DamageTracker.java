package de.puffa.damagealert.client;

import de.puffa.damagealert.ConfigStore;
import de.puffa.damagealert.Effect;
import de.puffa.damagealert.OverlayEvents;
import net.minecraft.client.network.ClientPlayerEntity;

/** Erkennt sinkende HP und meldet sie als Ereignis an die Overlays. */
final class DamageTracker {
	/**
	 * So viele Ticks (2 Sekunden) nach Join/Respawn/Dimensionswechsel keine Schadens-Ereignisse.
	 * Gezaehlt wird erst, wenn der Spieler fertig geladen ist (vorher nimmt der Server ohnehin keinen Schaden an).
	 */
	static final int GRACE_TICKS = 40;

	private final OverlayEvents events;
	private final ConfigStore config;

	private float lastHealth = -1f;
	private int graceTicksLeft;
	private long lastSentNanos;
	private boolean sentOnce;

	DamageTracker(OverlayEvents events, ConfigStore config) {
		this.events = events;
		this.config = config;
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
		if (damage > 0.001f && config.isEnabled(Effect.DAMAGE)) {
			long now = System.nanoTime();
			long cooldownNanos = config.get().damageCooldownMs() * 1_000_000L;
			if (!sentOnce || now - lastSentNanos >= cooldownNanos) {
				sentOnce = true;
				lastSentNanos = now;
				events.damage(damage, health);
			}
			// Schaden innerhalb des Cooldowns wird verworfen (nicht nachgeholt)
		}
		lastHealth = health;
	}
}
