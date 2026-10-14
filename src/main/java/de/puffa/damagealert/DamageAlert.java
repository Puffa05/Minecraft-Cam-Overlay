package de.puffa.damagealert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemeinsame Konstanten. Der eigentliche Einstiegspunkt ist
 * {@code de.puffa.damagealert.client.DamageAlertClient} (reiner Client-Mod).
 */
public final class DamageAlert {
	public static final String MOD_ID = "damagealert";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private DamageAlert() {
	}
}
