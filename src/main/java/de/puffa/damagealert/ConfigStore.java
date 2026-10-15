package de.puffa.damagealert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Haelt die aktuelle Config. Der Client-Tick liest sie, die Oberflaeche (HTTP-Thread) aendert sie.
 * Weil die Config ein unveraenderlicher Record ist, wird bei jeder Aenderung einfach ein neuer gesetzt.
 */
public final class ConfigStore {
	private final Path file;
	private final AtomicReference<DamageAlertConfig> current;

	public ConfigStore(Path file, DamageAlertConfig initial) {
		this.file = file;
		this.current = new AtomicReference<>(initial);
	}

	public static ConfigStore load(Path file) {
		return new ConfigStore(file, DamageAlertConfig.load(file));
	}

	public DamageAlertConfig get() {
		return current.get();
	}

	public boolean isEnabled(Effect effect) {
		return current.get().isEnabled(effect);
	}

	/** Schaltet einen Effekt um und speichert die Datei. */
	public synchronized void setEnabled(Effect effect, boolean enabled) {
		DamageAlertConfig updated = current.get().withEnabled(effect, enabled);
		current.set(updated);
		try {
			updated.save(file);
		} catch (IOException e) {
			DamageAlert.LOGGER.warn("[DamageAlert] Config konnte nicht gespeichert werden: {}", e.toString());
		}
	}
}
