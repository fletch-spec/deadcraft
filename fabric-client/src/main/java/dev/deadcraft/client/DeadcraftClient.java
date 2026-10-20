package dev.deadcraft.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.deadcraft.client.fx.FxSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class DeadcraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(Follow::clientTick);
		dev.deadcraft.client.fx.Effects.register();
		MobDamage.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("deadcraft-combat.properties"));
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("deadcraft", "test_hud"), (graphics, delta) -> TestHud.draw(graphics));
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("deadcraft", "crosshair"), (graphics, delta) -> TestHud.crosshair(graphics));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
			ClientCommands.literal("deadcraft")
				.then(ClientCommands.literal("anchor").executes(c -> reply(c.getSource(), Follow.anchor())))
				.then(ClientCommands.literal("on").executes(c -> reply(c.getSource(), Follow.setEnabled(true))))
				.then(ClientCommands.literal("off").executes(c -> reply(c.getSource(), Follow.setEnabled(false))))
				.then(ClientCommands.literal("camera")
					.then(ClientCommands.literal("on").executes(c -> reply(c.getSource(), Follow.camera(true))))
					.then(ClientCommands.literal("off").executes(c -> reply(c.getSource(), Follow.camera(false))))
					.then(ClientCommands.argument("distance", FloatArgumentType.floatArg(0, 1000))
						.then(ClientCommands.argument("right", FloatArgumentType.floatArg(-500, 500))
							.then(ClientCommands.argument("up", FloatArgumentType.floatArg(-500, 500))
								.executes(c -> reply(c.getSource(), Follow.camera(FloatArgumentType.getFloat(c, "distance"),
									FloatArgumentType.getFloat(c, "right"), FloatArgumentType.getFloat(c, "up")))))))
					.executes(c -> reply(c.getSource(), Follow.camera(null))))
				.then(ClientCommands.literal("look")
					.then(ClientCommands.literal("raw").executes(c -> reply(c.getSource(), Follow.look(true))))
					.then(ClientCommands.literal("deadlock").executes(c -> reply(c.getSource(), Follow.look(false))))
					.then(ClientCommands.literal("recalibrate").executes(c -> reply(c.getSource(), Follow.lookRecalibrate())))
					.executes(c -> reply(c.getSource(), Follow.look(null))))
				.then(ClientCommands.literal("delay")
					.then(ClientCommands.argument("ms", FloatArgumentType.floatArg(0, 100))
						.executes(c -> reply(c.getSource(), Follow.delay(FloatArgumentType.getFloat(c, "ms")))))
					.executes(c -> reply(c.getSource(), Follow.delay(null))))
				.then(ClientCommands.literal("hud").executes(c -> reply(c.getSource(), TestHud.toggle())))
				.then(ClientCommands.literal("fx")
					.then(ClientCommands.literal("bloom").then(ClientCommands.argument("strength", FloatArgumentType.floatArg(0, FxSettings.MAX_BLOOM))
						.executes(c -> reply(c.getSource(), FxSettings.setBloom(FloatArgumentType.getFloat(c, "strength"))))))
					.then(ClientCommands.literal("brightness").then(ClientCommands.argument("factor", FloatArgumentType.floatArg(FxSettings.MIN_SCALE, FxSettings.MAX_SCALE))
						.executes(c -> reply(c.getSource(), FxSettings.setBrightness(FloatArgumentType.getFloat(c, "factor"))))))
					.then(ClientCommands.literal("size").then(ClientCommands.argument("factor", FloatArgumentType.floatArg(FxSettings.MIN_SCALE, FxSettings.MAX_SCALE))
						.executes(c -> reply(c.getSource(), FxSettings.setSize(FloatArgumentType.getFloat(c, "factor"))))))
					.then(ClientCommands.literal("reset").executes(c -> reply(c.getSource(), FxSettings.reset())))
					.executes(c -> reply(c.getSource(), FxSettings.status())))
				.then(ClientCommands.literal("damage")
					.then(ClientCommands.argument("scale", FloatArgumentType.floatArg(0, MobDamage.MAX_SCALE))
						.executes(c -> reply(c.getSource(), MobDamage.setScale(FloatArgumentType.getFloat(c, "scale")))))
					.executes(c -> reply(c.getSource(), MobDamage.status())))
				.then(ClientCommands.literal("hero")
					.then(ClientCommands.literal("scale").then(ClientCommands.argument("factor", FloatArgumentType.floatArg(0.25f, 4f))
						.executes(c -> reply(c.getSource(), Follow.heroScale(FloatArgumentType.getFloat(c, "factor"))))))
					.then(ClientCommands.argument("name", StringArgumentType.word())
						.executes(c -> reply(c.getSource(), Follow.hero(StringArgumentType.getString(c, "name")))))
					.executes(c -> reply(c.getSource(), Follow.hero(null))))
				.then(ClientCommands.literal("overlay")
					.then(ClientCommands.literal("on").executes(c -> reply(c.getSource(), Follow.setOverlay(true))))
					.then(ClientCommands.literal("off").executes(c -> reply(c.getSource(), Follow.setOverlay(false)))))
				.executes(c -> reply(c.getSource(), Follow.status()))));
	}

	private static int reply(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String text) {
		source.sendFeedback(Component.literal(text));
		return Command.SINGLE_SUCCESS;
	}
}
