package de.schmodas.multimedia.mixin;

import de.schmodas.multimedia.FloatingScreens;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right-click while looking at a floating screen (in the air, so no block is hit) opens its touch screen. */
@Mixin(Minecraft.class)
public abstract class MinecraftUseMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void multimedia$floatingScreen(CallbackInfo ci) {
		if (FloatingScreens.use((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}
}
