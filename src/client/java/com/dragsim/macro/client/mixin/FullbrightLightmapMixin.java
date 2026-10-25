package com.dragsim.macro.client.mixin;

import com.dragsim.macro.client.FullbrightLightmapPolicy;
import com.dragsim.macro.client.StealthMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-only fullbright for Minecraft 26.2's lightmap UBO path.
 *
 * Minecraft extracts LightmapRenderState once per dirty update and the
 * lightmap.fsh shader then consumes it.  Gamma alone cannot lift a zero-light
 * texel, so the enabled state supplies a fresh white ambient input.  The
 * actual sky/block factors, night-vision intensity/color, darkness fields and
 * player effects are left intact.  No options.txt mutation or packet is used.
 */
@Mixin(LightmapRenderStateExtractor.class)
abstract class FullbrightLightmapMixin {
	@Shadow private boolean needsUpdate;

	@Unique private boolean dragsim$fullbrightLastEnabled;

	@Inject(method = "extract", at = @At("HEAD"))
	private void dragsim$refreshOnToggle(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		boolean enabled = StealthMode.fullbright();
		// tick() already marks the extractor dirty every tick in 26.2. This
		// transition check also covers a GUI toggle between ticks and OFF: the
		// next extraction runs vanilla code and restores every normal field.
		if (FullbrightLightmapPolicy.shouldRefresh(enabled, dragsim$fullbrightLastEnabled)) {
			needsUpdate = true;
			dragsim$fullbrightLastEnabled = enabled;
		}
	}

	@Inject(method = "extract", at = @At("RETURN"))
	private void dragsim$applyFullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (!StealthMode.fullbright() || !state.needsUpdate
				|| mc.level == null || mc.player == null) return;
		// Apply to the freshly extracted vanilla state only. The actual
		// night-vision/darkness fields and player effects remain untouched.
		state.ambientColor = FullbrightLightmapPolicy.whiteAmbient();
	}
}
