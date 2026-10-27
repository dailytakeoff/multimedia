package de.schmodas.multimedia.mixin;

import de.schmodas.multimedia.LeanView;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rechtsklick auf eine Leinwand öffnet den Touch-Screen, auf eine Jukebox die Jukebox-Ansicht (Schleichen: wie immer, z.B. um Blöcke dranzusetzen). */
@Mixin(MultiPlayerGameMode.class)
public abstract class GameModeMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void multimedia$jukebox(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		// right-click on a screen: touch screen (sneaking: normal, e.g. to place blocks)
		if (!LeanView.bypass() && !player.isSecondaryUseActive() && hand == InteractionHand.MAIN_HAND
				&& de.schmodas.multimedia.ScreenManager.touch(hit)) {
			cir.setReturnValue(InteractionResult.CONSUME);
			return;
		}
		if (LeanView.bypass() || player.isSecondaryUseActive() || hand != InteractionHand.MAIN_HAND
				|| !de.schmodas.multimedia.Settings.leanView()
				|| !player.level().getBlockState(hit.getBlockPos()).is(Blocks.JUKEBOX)) {
			return;
		}
		LeanView.open(hit.getBlockPos());
		cir.setReturnValue(InteractionResult.CONSUME);
	}
}
