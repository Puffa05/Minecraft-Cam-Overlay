package de.puffa.damagealert;

/**
 * Einmalige Ereignisse fuer die Overlays. Jedes Ereignis erhoeht eine laufende Nummer (seq).
 * Die Overlays fragen /state regelmaessig ab und spielen einen Effekt, sobald sich die Nummer aendert.
 * "epoch" aendert sich bei jedem Spielstart, damit Overlays nach einem Neustart nicht durcheinanderkommen.
 */
public final class OverlayEvents {
	public record Damage(long seq, float amount, float health) {
	}

	private final long epoch = System.currentTimeMillis();
	private volatile Damage damage = new Damage(0, 0, 0);
	private volatile long deathSeq;

	public synchronized void damage(float amount, float health) {
		damage = new Damage(damage.seq() + 1, amount, health);
	}

	public synchronized void death() {
		deathSeq++;
	}

	public Damage lastDamage() {
		return damage;
	}

	public long deathSeq() {
		return deathSeq;
	}

	public String toJson() {
		Damage d = damage;
		return "{\"epoch\":" + epoch
				+ ",\"damage\":{\"seq\":" + d.seq() + ",\"amount\":" + Json.number(d.amount()) + ",\"health\":" + Json.number(d.health()) + "}"
				+ ",\"death\":{\"seq\":" + deathSeq + "}}";
	}
}
