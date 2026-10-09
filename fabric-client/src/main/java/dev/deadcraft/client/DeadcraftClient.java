package dev.deadcraft.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

public final class DeadcraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(Follow::clientTick);
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
