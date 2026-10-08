package dev.deadcraft.client.mixin;

import dev.deadcraft.client.Follow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
	@Shadow
	public ServerPlayer player;

	/**
	 * Deadlock already collided the move. Without this the server re-collides it, and where its
	 * collision disagrees (stepping up stairs) it rejects the move and keeps teleporting the client
	 * back to the last accepted spot: a stale frame flashing every few seconds. noPhysics makes the
	 * server apply the move as sent and skips the correction (handleMovePlayer checks it). Player.tick
	 * resets noPhysics every tick, so it is set again for each packet.
	 */
	@Inject(method = "handleMovePlayer", at = @At("HEAD"))
	private void deadcraft$trustDeadlockMovement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		if (Follow.linked()) player.noPhysics = true;
	}

	@Inject(method = "forceSendPlayerSupportBlocks", at = @At("HEAD"), cancellable = true)
	private void deadcraft$deadlockOwnsGrounding(CallbackInfo ci) {
		if (Follow.linked()) ci.cancel();
	}
}
