package com.dragsim.macro.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * UIフォント適用ヘルパ(Oxanium / SIL Open Font License 1.1)。
 *
 * - フォント本体はFontManagerがリソースロード時に一度だけロードする(毎フレームのロードなし)
 * - Styleと固定ラベルのComponentはここで一元管理し、render内での生成を避ける
 * - 非ラテン文字はui.jsonのreference provider経由でminecraft:defaultへfallback
 */
public final class UiFont {
	private UiFont() {
	}

	public static final Identifier FONT_ID = Identifier.fromNamespaceAndPath("dragsimmacro", "ui");

	// Minecraft 26.2: Style.withFont は FontDescription を取る(Resource = font id指定)
	private static final Style STYLE = Style.EMPTY.withFont(new FontDescription.Resource(FONT_ID));

	public static final Component ON = text("ON");
	public static final Component OFF = text("OFF");
	public static final Component BIND_HELD = text("Bind Held");
	public static final Component MACRO = text("Macro");
	public static final Component MACRO_ON = text("ON");
	public static final Component MACRO_OFF = text("OFF");
	public static final Component ARROW_DOWN = text("▾");

	/** 動的な値表示用(呼び出し頻度は既存のString.format表示と同等) */
	public static MutableComponent text(String value) {
		return Component.literal(value).setStyle(STYLE);
	}
}
