package de.puffa.damagealert.client;

import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Merkt sich, ob der Spieler in einem Nether-Portal steht (fuer den Portal-Effekt im Overlay).
 * Nach einem Dimensionswechsel steht man im Ziel-Portal; dort soll der Effekt nicht sofort wieder
 * angehen. Deshalb wird gewartet, bis der Spieler das Portal einmal verlassen hat.
 */
final class PortalTracker {
	private boolean active;
	private boolean waitForExit;

	/** Neues Spieler-Objekt (Join, Respawn, Dimensionswechsel). */
	void onNewPlayer() {
		active = false;
		waitForExit = true;
	}

	void onLeftWorld() {
		active = false;
		waitForExit = false;
	}

	boolean isActive() {
		return active;
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
		active = inPortal;
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
