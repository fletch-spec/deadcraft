package dev.deadcraft.client;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

public final class DeadcraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
			ClientCommands.literal("deadcraft")
				.then(ClientCommands.literal("anchor").executes(c -> reply(c.getSource(), Follow.anchor())))
				.then(ClientCommands.literal("on").executes(c -> reply(c.getSource(), Follow.setEnabled(true))))
				.then(ClientCommands.literal("off").executes(c -> reply(c.getSource(), Follow.setEnabled(false))))
				.executes(c -> reply(c.getSource(), Follow.status()))));
	}

	private static int reply(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String text) {
		source.sendFeedback(Component.literal(text));
		return Command.SINGLE_SUCCESS;
	}
}
