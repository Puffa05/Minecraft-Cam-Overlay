package de.puffa.damagealert.client;

import de.puffa.damagealert.StateSnapshot;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import net.minecraft.util.math.BlockPos;

/** Liest die dauerhaften Zustaende. Darf nur im Client-Tick (Render-Thread) aufgerufen werden. */
final class StateCollector {
	private StateCollector() {
	}

	static StateSnapshot collect(ClientPlayerEntity player, ClientWorld world, boolean inPortal) {
		BlockPos pos = player.getBlockPos();
		Identifier biomeId = world.getBiome(pos).getKey().map(key -> key.getValue()).orElse(null);
		String biome = biomeId == null ? null : biomeId.toString();
		// Name in der Spielsprache, z. B. "Kirschberghain" (Mod-Biome ohne Uebersetzung: die ID)
		String biomeName = biomeId == null ? null : Language.getInstance().get(biomeId.toTranslationKey("biome"), biome);

		return new StateSnapshot(
				true,
				player.isSubmergedInWater(),   // Augen unter Wasser
				player.isInLava(),
				player.getFreezingScale(),     // 0..1, gleicher Wert wie das Vanilla-Frost-Overlay
				biome,
				biomeName,
				world.getRegistryKey().getValue().toString(),
				pos.getY(),
				world.isRaining(),
				world.isThundering(),
				inPortal,
				player.getHealth(),
				player.getMaxHealth());
	}
}
