package de.puffa.damagealert.client;

import de.puffa.damagealert.StateSnapshot;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/** Liest die dauerhaften Zustaende. Darf nur im Client-Tick (Render-Thread) aufgerufen werden. */
final class StateCollector {
	private StateCollector() {
	}

	static StateSnapshot collect(ClientPlayerEntity player, ClientWorld world) {
		BlockPos pos = player.getBlockPos();
		String biome = world.getBiome(pos).getKey()
				.map(key -> key.getValue().toString())
				.orElse(null);

		return new StateSnapshot(
				true,
				player.isSubmergedInWater(),   // Augen unter Wasser
				player.isInLava(),
				player.getFreezingScale(),     // 0..1, gleicher Wert wie das Vanilla-Frost-Overlay
				biome,
				world.getRegistryKey().getValue().toString(),
				pos.getY(),
				world.isRaining(),
				world.isThundering());
	}
}
