package de.schmodas.multimedia.mixin;

import de.schmodas.multimedia.ChatFilter;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Last line of defence: lines with the mod's hidden codes never get into the chat box, even when another mod
 * (Essential, chat mods, server plugins with their own whisper format) shows messages its own way.
 * require = 0: if a game update renames these methods, this just does nothing instead of crashing.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Inject(method = "addPlayerMessage", at = @At("HEAD"), cancellable = true, require = 0)
	private void multimedia$hidePlayer(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
		if (message != null && ChatFilter.isHiddenCode(message.getString())) {
			ci.cancel();
		}
	}

	@Inject(method = "addClientSystemMessage", at = @At("HEAD"), cancellable = true, require = 0)
	private void multimedia$hideClient(Component message, CallbackInfo ci) {
		if (message != null && ChatFilter.isHiddenCode(message.getString())) {
			ci.cancel();
		}
	}
}
