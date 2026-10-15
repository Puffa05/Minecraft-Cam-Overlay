package de.puffa.damagealert.client;

import de.puffa.damagealert.ConfigStore;
import de.puffa.damagealert.Effect;
import de.puffa.damagealert.StreamerBotClient;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Meldet "Portal Start" beim Betreten und "Portal Ende" beim Verlassen eines Nether-Portals. */
final class PortalTracker {
	private final StreamerBotClient streamerBot;
	private final ConfigStore config;

	/** true, solange der Spieler (nach unserer Zaehlung) im Portal steht. */
	private boolean active;
	/** true, wenn fuer den aktuellen Portal-Aufenthalt "Portal Start" gesendet wurde. Nur dann kommt "Portal Ende". */
	private boolean startSent;
	/**
	 * Nach einem Dimensionswechsel steht man im Ziel-Portal. Damit dort nicht sofort wieder
	 * "Portal Start" kommt, wird gewartet, bis der Spieler das Portal einmal verlassen hat.
	 */
	private boolean waitForExit;

	PortalTracker(StreamerBotClient streamerBot, ConfigStore config) {
		this.streamerBot = streamerBot;
		this.config = config;
	}

	/** Neues Spieler-Objekt (Join, Respawn, Dimensionswechsel). */
	void onNewPlayer() {
		endIfActive();
		waitForExit = true;
	}

	void onLeftWorld() {
		endIfActive();
		waitForExit = false;
	}

	void tick(ClientPlayerEntity player, ClientWorld world, boolean loaded) {
		if (!loaded) {
			// Waehrend des Ladens sind die Chunks evtl. noch leer: Portalbloecke wuerden als Luft gelesen
			return;
		}
		boolean inPortal = isInNetherPortal(player, world);

		if (waitForExit) {
			if (!inPortal) {
				waitForExit = false;
			}
			return;
		}

		boolean enabled = config.isEnabled(Effect.PORTAL);
		if (inPortal && !active) {
			active = true;
			if (enabled) {
				startSent = true;
				streamerBot.doAction(config.get().actionPortalStart());
			}
		} else if (!inPortal && active) {
			endIfActive();
		} else if (active && startSent && !enabled) {
			// Waehrend man im Portal steht abgeschaltet: Effekt in OBS sauber beenden
			sendEnd();
		}
	}

	private void endIfActive() {
		if (active) {
			active = false;
			sendEnd();
		}
	}

	private void sendEnd() {
		if (startSent) {
			startSent = false;
			streamerBot.doAction(config.get().actionPortalEnd());
		}
	}

	/** Prueft alle Bloecke, die die Hitbox des Spielers beruehrt (wie Vanilla), nicht nur den Block unter den Fuessen. */
	static boolean isInNetherPortal(ClientPlayerEntity player, ClientWorld world) {
		Box box = player.getBoundingBox().contract(1.0E-5);
		BlockPos min = BlockPos.ofFloored(box.minX, box.minY, box.minZ);
		BlockPos max = BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ);
		for (BlockPos pos : BlockPos.iterate(min, max)) {
			if (world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL)) {
				return true;
			}
		}
		return false;
	}
}
