package dev.deadcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the crosshair points at, while the Deadlock camera is on: Minecraft picks along the player's eye ray, but
 * the camera sits behind and beside the hero, so the outlined block wasn't the one under the crosshair (round 10).
 * Picks along the camera's ray through the screen's centre instead, keeping Minecraft's reach measured from the
 * player's eye (the server checks it from there).
 */
public final class CrosshairPick {
	private CrosshairPick() {
	}

	/** After Minecraft's own pick: replaces {@code hitResult} and {@code crosshairPickEntity} with the camera ray's. */
	public static void pick(Minecraft mc) {
		var player = mc.player;
		if (!Follow.linked() || !DeadlockCamera.enabled() || player == null || mc.level == null) return;
		var camera = mc.gameRenderer.mainCamera();
		var f = camera.forwardVector();
		Vec3 from = camera.position(), dir = new Vec3(f.x(), f.y(), f.z()).normalize();
		double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
		double far = from.distanceTo(player.getEyePosition()) + reach + 1;
		Vec3 to = from.add(dir.scale(far));
		BlockHitResult block = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		double blockDistance = block.getType() == HitResult.Type.MISS ? far : from.distanceTo(block.getLocation());
		Vec3 end = from.add(dir.scale(blockDistance));
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, from, end, new AABB(from, end).inflate(1),
			e -> e != player && !e.isSpectator() && e.isPickable(), blockDistance * blockDistance);
		if (entity != null && player.isWithinEntityInteractionRange(entity.getEntity(), 0)) {
			mc.hitResult = entity;
			mc.crosshairPickEntity = entity.getEntity();
			return;
		}
		mc.crosshairPickEntity = null;
		mc.hitResult = block.getType() != HitResult.Type.MISS && player.isWithinBlockInteractionRange(block.getBlockPos(), 0) ? block
			: BlockHitResult.miss(to, Direction.UP, net.minecraft.core.BlockPos.containing(to));
	}
}
