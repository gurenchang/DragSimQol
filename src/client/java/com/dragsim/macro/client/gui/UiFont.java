package com.dragsim.macro.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * UI fonts (Oxanium, SIL Open Font License 1.1) in three sizes.
 *
 * body  (ui_Nx.json, 9.5px)  — labels and values
 * small (ui_small_Nx.json, 8px) — descriptions, captions, chips
 * title (ui_title_Nx.json, 14px) — page titles
 *
 * Each size exists once per GUI scale 1-4 with oversample = scale, so glyphs are
 * rasterised at exactly one texel per screen pixel (a larger oversample drops thin
 * strokes when the atlas is sampled down at small GUI scales). Components pick the
 * variant for the current scale when created, so build them at draw time rather
 * than caching them across frames.
 *
 * All sizes put the glyph tops about 1 unit below the y passed to text(), so a
 * line of each size occupies roughly: small 7, body 9, title 12 units.
 * Non-Latin characters fall back to minecraft:default through each font JSON.
 */
public final class UiFont {
	private UiFont() {
	}

	/** Visual line heights (units) used for vertical centring. */
	public static final int BODY_H = 9;
	public static final int SMALL_H = 7;
	public static final int TITLE_H = 12;

	private static final Style[] BODY = styles("ui");
	private static final Style[] SMALL = styles("ui_small");
	private static final Style[] TITLE = styles("ui_title");

	private static Style[] styles(String name) {
		Style[] styles = new Style[4];
		for (int i = 0; i < styles.length; i++) {
			Identifier id = Identifier.fromNamespaceAndPath("dragsimmacro", name + "_" + (i + 1) + "x");
			styles[i] = Style.EMPTY.withFont(new FontDescription.Resource(id));
		}
		return styles;
	}

	private static Style pick(Style[] styles) {
		return styles[Math.max(1, Math.min(styles.length, GuiPaint.scale())) - 1];
	}

	public static MutableComponent text(String value) {
		return Component.literal(value).setStyle(pick(BODY));
	}

	public static MutableComponent small(String value) {
		return Component.literal(value).setStyle(pick(SMALL));
	}

	public static MutableComponent title(String value) {
		return Component.literal(value).setStyle(pick(TITLE));
	}

	public static FormattedCharSequence sequence(String value) {
		return FormattedCharSequence.forward(value, pick(BODY));
	}
}
