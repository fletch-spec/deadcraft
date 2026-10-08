package dev.deadcraft.client.mixin;

import dev.deadcraft.client.Follow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Singleplayer only (the integrated server runs in this process): while the player follows the
 * Deadlock hero, Deadlock decides whether they're on the ground, and the server's check (a reported
 * on-ground with no downward collision, e.g. stepping up stairs) resends the chunk below and makes
 * it flash. The M5 server mod does the same for opted-in players on a dedicated server.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Inject(method = "forceSendPlayerSupportBlocks", at = @At("HEAD"), cancellable = true)
	private void deadcraft$deadlockOwnsGrounding(CallbackInfo ci) {
		if (Follow.linked()) ci.cancel();
	}
}
