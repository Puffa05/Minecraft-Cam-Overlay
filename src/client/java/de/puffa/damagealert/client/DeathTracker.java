package de.puffa.damagealert.client;

import de.puffa.damagealert.ConfigStore;
import de.puffa.damagealert.DamageAlert;
import de.puffa.damagealert.DeathCounter;
import de.puffa.damagealert.Effect;
import de.puffa.damagealert.OverlayEvents;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Zaehlt Tode: Ein Tod ist, wenn die HP eines Spieler-Objekts auf 0 fallen.
 * Pro Spieler-Objekt wird hoechstens einmal gezaehlt (nach dem Respawn gibt es ein neues Objekt).
 * Ein Totem der Unsterblichkeit zaehlt nicht, weil die HP dabei nie 0 erreichen.
 */
final class DeathTracker {
	private final DeathCounter counter;
	private final OverlayEvents events;
	private final ConfigStore config;

	private float lastHealth = -1f;
	private int graceTicksLeft;
	private boolean counted;

	DeathTracker(DeathCounter counter, OverlayEvents events, ConfigStore config) {
		this.counter = counter;
		this.events = events;
		this.config = config;
	}

	/**
	 * Das alte Spieler-Objekt wird gleich ersetzt. Bei sofortigem Respawn kann es sein, dass der
	 * Client die 0 HP nie in einem Tick gesehen hat - dann hier noch zaehlen.
	 */
	void onPlayerReplaced(ClientPlayerEntity oldPlayer) {
		if (oldPlayer != null && !counted && graceTicksLeft == 0 && lastHealth > 0 && oldPlayer.getHealth() <= 0) {
			countDeath();
		}
	}

	void onNewPlayer(ClientPlayerEntity player) {
		// Nach dem Join synct der Server erst die echten HP. Wer tot ausgeloggt hat, soll nicht doppelt zaehlen.
		graceTicksLeft = DamageTracker.GRACE_TICKS;
		lastHealth = player.getHealth();
		counted = false;
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
		if (!counted && lastHealth > 0 && health <= 0) {
			countDeath();
		}
		lastHealth = health;
	}

	private void countDeath() {
		counted = true;
		DeathCounter.Counts c = counter.increment();
		DamageAlert.LOGGER.info("[DamageAlert] Tod gezaehlt: gesamt {}, Stream {}", c.total(), c.session());
		if (config.isEnabled(Effect.DEATH)) {
			events.death();
		}
	}
}
