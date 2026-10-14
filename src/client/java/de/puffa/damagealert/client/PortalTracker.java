package de.puffa.damagealert.client;

import de.puffa.damagealert.StreamerBotClient;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Meldet "Portal Start" beim Betreten und "Portal Ende" beim Verlassen eines Nether-Portals. */
final class PortalTracker {
	private final StreamerBotClient streamerBot;
	private final String startAction;
	private final String endAction;

	/** true zwischen "Portal Start" und "Portal Ende". */
	private boolean active;
	/**
	 * Nach einem Dimensionswechsel steht man im Ziel-Portal. Damit dort nicht sofort wieder
	 * "Portal Start" kommt, wird gewartet, bis der Spieler das Portal einmal verlassen hat.
	 */
	private boolean waitForExit;

	PortalTracker(StreamerBotClient streamerBot, String startAction, String endAction) {
		this.streamerBot = streamerBot;
		this.startAction = startAction;
		this.endAction = endAction;
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

		if (inPortal && !active) {
			active = true;
			streamerBot.doAction(startAction);
		} else if (!inPortal && active) {
			endIfActive();
		}
	}

	private void endIfActive() {
		if (active) {
			active = false;
			streamerBot.doAction(endAction);
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
