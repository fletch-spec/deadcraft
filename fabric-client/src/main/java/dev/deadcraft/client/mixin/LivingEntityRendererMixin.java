package dev.deadcraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.deadcraft.client.Follow;
import dev.deadcraft.client.hero.HeroRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While linked, the local player is drawn as their Deadlock hero instead of Minecraft's player model. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD"), cancellable = true)
	private void deadcraft$heroModel(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
		CameraRenderState camera, CallbackInfo ci) {
		if (!(state instanceof AvatarRenderState avatar) || !Follow.linked()) return;
		var player = Minecraft.getInstance().player;
		if (player == null || avatar.id != player.getId() || !HeroRenderer.ready()) return;
		HeroRenderer.submit(state, poseStack, collector);
		ci.cancel();
	}
}
