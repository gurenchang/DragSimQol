/*
 * World-to-screen projection technique adapted from Epsilon
 * (https://github.com/XTGY/Epsilon, Apache-2.0). See NOTICE.md.
 */
package com.dragsim.macro.client;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.dragsim.macro.client.gui.GuiTheme;
import com.dragsim.macro.client.gui.UiFont;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * NORMAL ESP(繧ｹ繧ｯ繝ｪ繝ｼ繝ｳ遨ｺ髢・D繧ｳ繝ｼ繝翫・ESP)縲・EBUG陦ｨ遉ｺ縺ｯEspOverlay/PathDebugOverlay/DebugHud縺梧球蠖薙・
 *
 * 繝・・繧ｿ繝輔Ο繝ｼ(謠冗判繝ｫ繝ｼ繝怜・縺ｧ讀懷・/繝ｬ繧､繝医Ξ/繝代・繧ｹ/繧ｹ繧ｳ繧｢險育ｮ励・縺励↑縺・:
 *   ZealotDetector(繧ｹ繧ｭ繝｣繝ｳ譎ょ愛螳・
 *     竊・TargetSelector 縺瑚ｩ穂ｾ｡貂医∩縺ｮ蛟呵｣懊Μ繧ｹ繝・0.5遘呈ｯ取峩譁ｰ)
 *       竊・豈弱ヵ繝ｬ繝ｼ繝: project 竊・draw 縺ｮ縺ｿ
 *
 * 謚募ｽｱ縺ｯvanilla蜈ｬ蠑就PI縺ｨ蜷御ｸ縺ｮ謨ｰ蠑上↓邨ｱ荳:
 *   GameRenderer#projectPointToScreen 縺ｨ蜷後§縺・
 *   (worldPos - camera.position()) 繧・Camera#getViewRotationProjectionMatrix
 *   (= projection * viewRotation 縺ｮ隍・粋陦悟・) 縺ｧ transform 縺吶ｋ縲・
 *   viewRotationMatrix 縺ｯ邏泌屓霆｢(繧ｫ繝｡繝ｩ蟷ｳ陦檎ｧｻ蜍輔ｒ蜷ｫ縺ｾ縺ｪ縺・縺ｮ縺溘ａ縲・
 *   繧ｫ繝｡繝ｩ逶ｸ蟇ｾ蠎ｧ讓吶∈縺ｮ螟画鋤縺悟ｿ・医・
 *
 * 譛邨よ緒逕ｻ蠎ｧ讓吶・GUI scaled coordinate
 *   x = (ndcX + 1) / 2 * guiScaledWidth
 *   y = (1 - ndcY) / 2 * guiScaledHeight
 * (NDC縺ｯ蟾ｦ荳句次轤ｹ/Y荳頑婿蜷代；UI縺ｯ蟾ｦ荳雁次轤ｹ/Y荳区婿蜷・縲・
 *
 * 諤ｧ閭ｽ:
 *   - 謠冗判蟇ｾ雎｡縺ｯ譛螟ｧ MAX_DRAWN 菴・Target縺ｯ蟶ｸ縺ｫ謠冗判)
 *   - 譌ｩ譛滄勁螟・ RANGE / DEAD / 繧ｫ繝｡繝ｩ蠕梧婿 / 螳悟・逕ｻ髱｢螟・
 *   - Matrix4f/Vector4f縺ｯ菴ｿ縺・屓縺励√Λ繝吶Ν譁・ｭ怜・縺ｯ繧ｹ繧ｭ繝｣繝ｳ譖ｴ譁ｰ譎ゅ・縺ｿ逕滓・
 *   - 險ｺ譁ｭHUD/繝ｭ繧ｰ縺ｯPIPELINE_DEBUG=true譎ゅ・縺ｿ(譛ｬ逡ｪ縺ｯfalse)
 */
public class CornerEspOverlay implements HudElement {
	// 濶ｲ縺ｯGuiTheme(鮟津礼ｴｫ繝医・繧ｯ繝ｳ)縺九ｉ蜿ら・縲ゅワ繝ｼ繝峨さ繝ｼ繝峨＠縺ｪ縺・
	private static final int RGB_NORMAL = GuiTheme.ESP_NORMAL;
	private static final int RGB_TARGET = GuiTheme.ESP_TARGET;
	private static final int RGB_SPECIAL = GuiTheme.ESP_SPECIAL;
	private static final int RGB_ID_TEXT = GuiTheme.TEXT_SECONDARY;
	private static final int RGB_GATE_READY = GuiTheme.SUCCESS;
	private static final int RGB_GATE_WAIT = GuiTheme.TEXT_SECONDARY;
	private static final int ALPHA_NORMAL_MAX = 150;
	// Target縺ｯ騾壼ｸｸZealot繧医ｊopacity鬮倥ａ(AttackGate迥ｶ諷玖牡縺ｨ蛻・屬縺怜ｸｸ縺ｫaccent)
	private static final int ALPHA_TARGET_MAX = 250;
	private static final int ALPHA_SPECIAL_MAX = 190;
	private static final int ALPHA_ID_TEXT = 120;
	private static final double BOX_HALF_WIDTH = 0.3;
	// Target Corner縺ｯ蟆代＠螟ｪ縺・蟆代＠髟ｷ縺・minimal design邯ｭ謖・
	private static final double TARGET_CORNER_LEN_SCALE = 1.35;
	/** 1繝輔Ξ繝ｼ繝譛螟ｧ謠冗判菴捺焚(Target髯､縺・縲よｧ閭ｽ荳企剞(螳壽焚蛹・ */
	private static final int MAX_DRAWN = 16;

	// 繝代う繝励Λ繧､繝ｳ險ｺ譁ｭ(NORMAL陦ｨ遉ｺ荳榊・蜷郁ｪｿ譟ｻ逕ｨ)縲よ悽逡ｪ縺ｯfalse(險ｺ譁ｭHUD/繝ｭ繧ｰ辟｡蜉ｹ)
	private static final boolean PIPELINE_DEBUG = false;
	// 繧ｿ繝ｼ繧ｲ繝・ヨ1菴薙・菴朱ｻ蠎ｦ險ｺ譁ｭ繝ｭ繧ｰ髢馴囈
	private static final long TARGET_LOG_INTERVAL_MS = 2000;

	// 蝗ｺ螳壹Λ繝吶Ν(襍ｷ蜍墓凾縺ｫ荳蠎ｦ縺縺醍函謌舌よｯ弱ヵ繝ｬ繝ｼ繝逕滓・縺励↑縺・
	private static final Component LABEL_TARGET = UiFont.text("TARGET");
	private static final Component LABEL_GATE_READY = UiFont.text("READY");
	private static final Component LABEL_GATE_WAIT = UiFont.text("WAIT");

	private final MacroController controller;
	private final AimSolver aimSolver;
	private final RotationController rotationController;
	private final AttackGate attackGate;
	private final Map<Integer, LabelCache> labels = new HashMap<>();

	// 豈弱ヵ繝ｬ繝ｼ繝菴ｿ縺・屓縺吶ヰ繝・ヵ繧｡(繧｢繝ｭ繧ｱ繝ｼ繧ｷ繝ｧ繝ｳ蜑頑ｸ・
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f projOut = new Vector4f();

	// 繝輔Ξ繝ｼ繝險域ｸｬ繧ｫ繧ｦ繝ｳ繧ｿ(HUD/debug蜿ら・逕ｨ縲よ悽逡ｪ縺ｧ縺ｯ繝ｭ繧ｰ蜃ｺ蜉帙＠縺ｪ縺・
	private int entries;
	private int projected;
	private int drawn;
	private int cornersBehind;
	private int rejectedRange;
	private int rejectedDead;
	private int rejectedNoCorner;
	private int rejectedOffscreen;
	private String targetReject;
	private double targetMinX;
	private double targetMinY;
	private double targetMaxX;
	private double targetMaxY;
	private String targetProjDetail;
	private long lastTargetLog;

	/** 譛邨ゅヵ繝ｬ繝ｼ繝縺ｮ謠冗判邨ｱ險・繝・ヰ繝・げHUD逕ｨgetter縲よ悽逡ｪ繝ｭ繧ｰ縺ｯ蜃ｺ縺輔↑縺・ */
	public int getLastEntries() {
		return entries;
	}

	public int getLastProjected() {
		return projected;
	}

	public int getLastDrawn() {
		return drawn;
	}

	private record LabelCache(EntitySnapshot snapshot, Component idText, Component distText) {
	}

	public CornerEspOverlay(MacroController controller, AimSolver aimSolver, RotationController rotationController,
			AttackGate attackGate) {
		this.controller = controller;
		this.aimSolver = aimSolver;
		this.rotationController = rotationController;
		this.attackGate = attackGate;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (controller.getEspMode() != EspMode.NORMAL) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();

		entries = 0;
		projected = 0;
		drawn = 0;
		cornersBehind = 0;
		rejectedRange = 0;
		rejectedDead = 0;
		rejectedNoCorner = 0;
		rejectedOffscreen = 0;
		targetReject = null;
		targetMinX = 0;
		targetMinY = 0;
		targetMaxX = 0;
		targetMaxY = 0;
		targetProjDetail = null;

		if (mc.player == null || mc.level == null) {
			if (PIPELINE_DEBUG) {
				drawPipelineHud(graphics, mc, "WAIT player/world");
			}
			return;
		}
		if (controller.getState() != MacroController.State.SCAN) {
			if (PIPELINE_DEBUG) {
				drawPipelineHud(graphics, mc, "WAIT state=" + controller.getState());
			}
			return;
		}

		// 繝舌ル繝ｩ縺ｮ繧ｫ繝｡繝ｩ陦悟・繧貞叙蠕・FOV/繧ｨ繝輔ぉ繧ｯ繝郁ｾｼ縺ｿ縺ｧ豁｣遒ｺ縺ｪ謚募ｽｱ)縲・
		// CameraRenderState繧堤峩謗･菴ｿ縺・→HUD extract譎らせ縺ｧ縺ｮpopulate鬆・ｺ上↓萓晏ｭ倥☆繧九◆繧√・
		// 螳溽ｸｾ縺ｮ縺ゅｋ mainCamera() 縺九ｉ蜈ｬ蠑就PI邨檎罰縺ｧ蜿門ｾ励☆繧九・
		Camera camera = mc.gameRenderer.mainCamera();
		camera.getViewRotationProjectionMatrix(viewProjection);
		Vec3 camPos = camera.position();

		int guiW = mc.getWindow().getGuiScaledWidth();
		int guiH = mc.getWindow().getGuiScaledHeight();

		double range = controller.getRange();
		double fadeStart = range * 0.75;
		Font font = mc.font;
		TargetCandidate currentTarget = controller.getTarget();

		for (TargetCandidate candidate : controller.getTargetSelector().getLastEvaluated()) {
			entries++;
			EntitySnapshot snapshot = candidate.snapshot();
			boolean isTarget = currentTarget != null && currentTarget.entityId() == candidate.entityId();

			Entity live = mc.level.getEntity(snapshot.id());
			String reject = null;
			if (candidate.distance() > range) {
				reject = "RANGE";
			} else if (live != null && !live.isAlive()) {
				reject = "DEAD";
			}

			double minX = 0;
			double minY = 0;
			double maxX = 0;
			double maxY = 0;
			if (reject == null) {
				// AABB: 逕溷ｭ倅ｸｭ縺ｮ螳滉ｽ薙′蜿悶ｌ繧後・螳溘・繝・け繧ｹ縲∫┌縺代ｌ縺ｰ繧ｹ繝翫ャ繝励す繝ｧ繝・ヨ逕ｱ譚･
				AABB box = live != null
						? live.getBoundingBox()
						: new AABB(
								snapshot.x() - BOX_HALF_WIDTH, snapshot.boxMinY(), snapshot.z() - BOX_HALF_WIDTH,
								snapshot.x() + BOX_HALF_WIDTH, snapshot.boxMaxY(), snapshot.z() + BOX_HALF_WIDTH);

				// 8髫・ｒ謚募ｽｱ縺励※繧ｹ繧ｯ繝ｪ繝ｼ繝ｳ遏ｩ蠖｢縺ｸ縲・
				// w <= eps (繧ｫ繝｡繝ｩ蟷ｳ髱｢縺ｮ蠕梧婿) / 髱樊怏髯・繧・reject 縺吶ｋ縲・
				// 隕夜倹蜿ｰz蛻､螳壹・謚募ｽｱ陦悟・縺ｮz諷｣鄙・GL/DX)縺ｫ萓晏ｭ倥＠縺ｪ縺・ｈ縺・ｽｿ繧上↑縺・・
				boolean anyValid = false;
				boolean allBehind = true;
				minX = Double.MAX_VALUE;
				minY = Double.MAX_VALUE;
				maxX = -Double.MAX_VALUE;
				maxY = -Double.MAX_VALUE;
				StringBuilder projLog = isTarget && PIPELINE_DEBUG ? new StringBuilder("proj=[") : null;
				for (int i = 0; i < 8; i++) {
					float wx = (float) ((i & 1) == 0 ? box.minX : box.maxX);
					float wy = (float) ((i & 2) == 0 ? box.minY : box.maxY);
					float wz = (float) ((i & 4) == 0 ? box.minZ : box.maxZ);
					projOut.set(
							(float) (wx - camPos.x),
							(float) (wy - camPos.y),
							(float) (wz - camPos.z),
							1.0F);
					viewProjection.transform(projOut);
					if (projOut.w <= 1.0E-3F || !Float.isFinite(projOut.w)) {
						cornersBehind++;
						if (projLog != null) {
							projLog.append("(B) ");
						}
						continue;
					}
					allBehind = false;
					float invW = 1.0F / projOut.w;
					float ndcX = projOut.x * invW;
					float ndcY = projOut.y * invW;
					if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
						if (projLog != null) {
							projLog.append("(F) ");
						}
						continue;
					}
					// NDC(蟾ｦ荳句次轤ｹ) 竊・GUI scaled coordinate(蟾ｦ荳雁次轤ｹ)
					double sx = (ndcX + 1.0F) * 0.5 * guiW;
					double sy = (1.0F - ndcY) * 0.5 * guiH;
					anyValid = true;
					if (sx < minX) minX = sx;
					if (sx > maxX) maxX = sx;
					if (sy < minY) minY = sy;
					if (sy > maxY) maxY = sy;
					if (projLog != null) {
						projLog.append(String.format(Locale.ROOT, "(%.0f,%.0f) ", sx, sy));
					}
				}
				if (projLog != null) {
					projLog.append(']');
					targetProjDetail = projLog.toString();
					targetMinX = minX;
					targetMinY = minY;
					targetMaxX = maxX;
					targetMaxY = maxY;
				}

				if (!anyValid) {
					reject = allBehind ? "BEHIND_CAMERA" : "NO_VALID_CORNERS";
				} else if (maxX < 0 || minX > guiW || maxY < 0 || minY > guiH) {
					// 螳悟・縺ｫ逕ｻ髱｢螟悶・遏ｩ蠖｢縺ｯ繧ｹ繧ｭ繝・・(screen bounds culling)
					reject = "OFFSCREEN";
				}
			}

			if (reject != null) {
				switch (reject) {
					case "RANGE" -> rejectedRange++;
					case "DEAD" -> rejectedDead++;
					case "BEHIND_CAMERA", "NO_VALID_CORNERS" -> rejectedNoCorner++;
					case "OFFSCREEN" -> rejectedOffscreen++;
					default -> {
					}
				}
				if (isTarget) {
					targetReject = reject;
				}
				continue;
			}

			projected++;

			// 謠冗判謨ｰ荳企剞(螳壽焚)縲５arget縺ｯ蟶ｸ縺ｫ謠冗判
			if (drawn >= MAX_DRAWN && !isTarget) {
				continue;
			}

			boolean isTargetDrawn = isTarget;
			// Phase 6.6: 髱杁arget Special Zealot縺ｯpurple縺ｧ隴伜挨(關ｽ縺｡逹縺・◆violet)
			boolean isSpecial = ZealotDetector.typeOf(snapshot) == ZealotDetector.ZealotType.SPECIAL;
			int baseColor = isTargetDrawn ? RGB_TARGET
					: (isSpecial && MacroConfig.get().specialEspHighlight ? RGB_SPECIAL : RGB_NORMAL);
			int alphaMax = isTargetDrawn ? ALPHA_TARGET_MAX
					: (isSpecial && MacroConfig.get().specialEspHighlight ? ALPHA_SPECIAL_MAX : ALPHA_NORMAL_MAX);
			int alpha = alphaFor(candidate.distance(), range, fadeStart, alphaMax);
			int lineColor = withAlpha(baseColor, alpha);
			int lineWidth = isTargetDrawn ? 2 : 1;

			drawCorners(graphics, minX, minY, maxX, maxY, lineWidth, lineColor,
					isTargetDrawn ? TARGET_CORNER_LEN_SCALE : 1.0);

			// 繝ｩ繝吶Ν: 譁・ｭ怜・/Component縺ｯ繧ｹ繧ｭ繝｣繝ｳ譖ｴ譁ｰ譎ゅ・縺ｿ逕滓・(豈弱ヵ繝ｬ繝ｼ繝縺ｮString.format蝗樣∩)
			LabelCache cache = labels.get(snapshot.id());
			if (cache == null || cache.snapshot() != snapshot) {
				cache = new LabelCache(snapshot,
						UiFont.text("#" + snapshot.id()),
						UiFont.text(String.format(Locale.ROOT, "%.1fm", candidate.distance())));
				labels.put(snapshot.id(), cache);
			}

			int textAlpha = (int) (alphaFor(candidate.distance(), range, fadeStart, 255) * 0.55);
			int idColor = withAlpha(RGB_ID_TEXT, textAlpha);
			int cx = (int) Math.round((minX + maxX) / 2);

			if (isTargetDrawn) {
				int targetColor = withAlpha(RGB_TARGET, alpha);

				// Aim Marker(Phase 4.0): 繝ｭ繝・け荳ｭTarget 1菴薙・縺ｿ縲・
				// RENDER_SMOOTH譎ゅ・render controller縺瑚ｨ育ｮ励＠縺溷酔縺・ime basis縺ｮ
				// Aim Point繧偵◎縺ｮ縺ｾ縺ｾ菴ｿ逕ｨ(陦ｨ遉ｺ縺ｨAim縺ｮtime axis邨ｱ荳)縲・
				AimSolution aim = aimSolver.getSolution();
				if (aim.valid() && aim.entityId() == candidate.entityId() && live != null && live.isAlive()) {
					// 蜷御ｸtime basis(RENDER_SMOOTH) or 逶ｴ蜑阪・tick陬憺俣(fallback)
					double[] marker = null;
					if (rotationController.isRenderAimPointFresh()) {
						marker = projectToGui(rotationController.getRenderAimX(),
								rotationController.getRenderAimY(), rotationController.getRenderAimZ(),
								camPos, guiW, guiH);
					}
					if (marker == null) {
						float pt = delta.getGameTimeDeltaPartialTick(true);
						Vec3 ip = live.getPosition(pt);
						AABB liveBox = live.getBoundingBox();
						double aimY = AimPoints.pointY(aim.pointType(),
								ip.y + (liveBox.minY - live.getY()),
								ip.y + (liveBox.maxY - live.getY()));
						marker = projectToGui(ip.x, aimY, ip.z, camPos, guiW, guiH);
					}
					if (marker != null) {
						drawAimMarker(graphics, marker[0], marker[1], withAlpha(RGB_TARGET, 230));
					}

					int y = (int) Math.round(minY) - font.lineHeight * 3 - 4;
					drawCentered(graphics, font, UiFont.text("AIM:" + aim.pointType().tag()), cx, y, targetColor);
					y += font.lineHeight + 1;
					drawCentered(graphics, font, LABEL_TARGET, cx, y, targetColor);
					drawCentered(graphics, font, cache.distText(), cx, y + font.lineHeight + 1, targetColor);

					// Phase 5.0: Attack Gate indicator(Target 1菴薙・縺ｿ縲∵･ｵ蟆・
					// AttackGate迥ｶ諷玖牡縺ｯTarget濶ｲ縺ｨ蛻・屬(Target=accent / READY=success / WAIT=secondary)
					AttackReadiness gate = attackGate.getReadiness();
					if (gate.entityId() == candidate.entityId()) {
						int gateColor = gate.ready() ? withAlpha(RGB_GATE_READY, 230) : withAlpha(RGB_GATE_WAIT, 180);
						Component gateText = gate.ready() ? LABEL_GATE_READY : LABEL_GATE_WAIT;
						drawCentered(graphics, font, gateText, cx, y + font.lineHeight * 2 + 2, gateColor);
					}
				} else {
					int y = (int) Math.round(minY) - font.lineHeight * 2 - 3;
					drawCentered(graphics, font, LABEL_TARGET, cx, y, targetColor);
					drawCentered(graphics, font, cache.distText(), cx, y + font.lineHeight + 1, targetColor);
				}
			}
			int idY = (int) Math.round(maxY) + 2;
			if (idY + font.lineHeight < guiH) {
				graphics.text(font, cache.idText(),
						(int) (cx - font.width(cache.idText()) / 2.0), idY, idColor, false);
			}

			drawn++;
			if (isTargetDrawn && targetReject == null) {
				targetReject = "DRAWN";
			}
		}

		if (labels.size() > 64) {
			labels.clear();
		}

		if (PIPELINE_DEBUG) {
			drawPipelineHud(graphics, mc, "OK");
			logTargetDiagnostics(mc, guiW, guiH);
		}
	}

	/**
	 * 繝代う繝励Λ繧､繝ｳ險ｺ譁ｭHUD(蟾ｦ荳雁崋螳・縲・EBUG繝｢繝ｼ繝我ｽｵ逕ｨ縺ｧ
	 * 縲檎匳骭ｲ邨瑚ｷｯ縺ｮ蝠城｡後°縲阪継rojection縺ｮ蝠城｡後°縲阪ｒ蛻・ｊ蛻・￠繧九・
	 */
	private void drawPipelineHud(GuiGraphicsExtractor graphics, Minecraft mc, String status) {
		Font font = mc.font;
		int x = 6;
		int y = 96;
		graphics.text(font, Component.literal("ESP PIPELINE " + status), x, y, GuiTheme.WARNING, false);
		graphics.text(font, Component.literal(String.format(Locale.ROOT,
				"entries=%d projected=%d drawn=%d", entries, projected, drawn)),
				x, y + 12, GuiTheme.TEXT_SECONDARY, false);
		graphics.text(font, Component.literal(String.format(Locale.ROOT,
				"behind=%d corner=%d off=%d rng=%d dead=%d",
				cornersBehind, rejectedNoCorner, rejectedOffscreen, rejectedRange, rejectedDead)),
				x, y + 24, GuiTheme.TEXT_SECONDARY, false);
		// 蝗ｺ螳壻ｽ咲ｽｮ縺ｮ蟆上＆縺ｪ遏ｩ蠖｢(HUD逋ｻ骭ｲ邨瑚ｷｯ縺ｮ逍朱夂｢ｺ隱咲畑)
		graphics.fill(x, y + 40, x + 20, y + 60, GuiTheme.ACCENT);
	}

	/**
	 * 繧ｿ繝ｼ繧ｲ繝・ヨ1菴薙・縺ｿ縲∽ｽ朱ｻ蠎ｦ縺ｧ繝代う繝励Λ繧､繝ｳ隧ｳ邏ｰ繧偵Ο繧ｰ縺吶ｋ縲・
	 * reject逅・罰: BEHIND_CAMERA / NO_VALID_CORNERS / OFFSCREEN / RANGE / DEAD /
	 * NOT_IN_CANDIDATES / DRAWN
	 */
	private void logTargetDiagnostics(Minecraft mc, int guiW, int guiH) {
		long now = System.currentTimeMillis();
		if (now - lastTargetLog < TARGET_LOG_INTERVAL_MS) {
			return;
		}
		lastTargetLog = now;

		TargetCandidate currentTarget = controller.getTarget();
		if (currentTarget == null) {
			MacroLogger.get().log("[ESPDBG] no target, entries=" + entries);
			return;
		}
		if (targetReject == null) {
			boolean inList = false;
			for (TargetCandidate candidate : controller.getTargetSelector().getLastEvaluated()) {
				if (candidate.entityId() == currentTarget.entityId()) {
					inList = true;
					break;
				}
			}
			targetReject = inList ? "NO_PROJECT" : "NOT_IN_CANDIDATES";
		}

		EntitySnapshot snapshot = currentTarget.snapshot();
		Entity live = mc.level.getEntity(snapshot.id());
		AABB box = live != null
				? live.getBoundingBox()
				: new AABB(
						snapshot.x() - BOX_HALF_WIDTH, snapshot.boxMinY(), snapshot.z() - BOX_HALF_WIDTH,
						snapshot.x() + BOX_HALF_WIDTH, snapshot.boxMaxY(), snapshot.z() + BOX_HALF_WIDTH);

		MacroLogger.get().log(String.format(Locale.ROOT,
				"[ESPDBG] id=%d reject=%s world=(%.2f,%.2f,%.2f) aabb=[x%.2f..%.2f y%.2f..%.2f z%.2f..%.2f]"
						+ " rect=(%.1f,%.1f)-(%.1f,%.1f) gui=%dx%d phys=%dx%d scale=%d",
				snapshot.id(), targetReject,
				snapshot.x(), snapshot.y(), snapshot.z(),
				box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ,
				targetMaxX > targetMinX ? targetMinX : -1, targetMaxY > targetMinY ? targetMinY : -1,
				targetMaxX > targetMinX ? targetMaxX : -1, targetMaxY > targetMinY ? targetMaxY : -1,
				guiW, guiH,
				mc.getWindow().getWidth(), mc.getWindow().getHeight(),
				mc.getWindow().getGuiScale()));
		if (targetProjDetail != null) {
			MacroLogger.get().log("[ESPDBG] " + targetProjDetail);
		}
	}

	/**
	 * world 1轤ｹ 竊・GUI scaled coordinate縲・
	 * AABB 8髫・兜蠖ｱ縺ｨ蜷御ｸ縺ｮ繝代う繝励Λ繧､繝ｳ(viewProjection / camPos / w蛻､螳・繧貞・蛻ｩ逕ｨ縺吶ｋ縲・
	 * 繧ｫ繝｡繝ｩ蠕梧婿/髱樊怏髯舌・null縲・
	 */
	private double[] projectToGui(double wx, double wy, double wz, Vec3 camPos, int guiW, int guiH) {
		projOut.set(
				(float) (wx - camPos.x),
				(float) (wy - camPos.y),
				(float) (wz - camPos.z),
				1.0F);
		viewProjection.transform(projOut);
		if (projOut.w <= 1.0E-3F || !Float.isFinite(projOut.w)) {
			return null;
		}
		float invW = 1.0F / projOut.w;
		float ndcX = projOut.x * invW;
		float ndcY = projOut.y * invW;
		if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
			return null;
		}
		return new double[] {
				(ndcX + 1.0F) * 0.5 * guiW,
				(1.0F - ndcY) * 0.5 * guiH };
	}

	/**
	 * Aim Point繝ｬ繝・ぅ繧ｯ繝ｫ: 9px隗偵・繧ｳ繝ｼ繝翫・繝悶Λ繧ｱ繝・ヨ + 荳ｭ蠢・せ縲・
	 * Entity繝懊ャ繧ｯ繧ｹ縺ｮ繧ｳ繝ｼ繝翫・ESP縺ｨ邏帙ｉ繧上＠縺上↑縺・ｰ上＆縺輔・
	 */
	private void drawAimMarker(GuiGraphicsExtractor graphics, double sx, double sy, int color) {
		int x = (int) Math.round(sx);
		int y = (int) Math.round(sy);
		// 蟾ｦ荳・
		graphics.fill(x - 4, y - 4, x - 1, y - 3, color);
		graphics.fill(x - 4, y - 4, x - 3, y - 1, color);
		// 蜿ｳ荳・
		graphics.fill(x + 2, y - 4, x + 5, y - 3, color);
		graphics.fill(x + 4, y - 4, x + 5, y - 1, color);
		// 蜿ｳ荳・
		graphics.fill(x + 2, y + 3, x + 5, y + 4, color);
		graphics.fill(x + 4, y + 2, x + 5, y + 4, color);
		// 蟾ｦ荳・
		graphics.fill(x - 4, y + 3, x - 1, y + 4, color);
		graphics.fill(x - 4, y + 2, x - 3, y + 4, color);
		// 荳ｭ蠢・せ
		graphics.fill(x, y, x + 1, y + 1, color);
	}

	private void drawCorners(GuiGraphicsExtractor graphics, double minX, double minY, double maxX, double maxY,
			int lineWidth, int color, double lenScale) {
		double rectW = maxX - minX;
		double rectH = maxY - minY;
		double len = Math.max(3.0, Math.min(Math.min(rectW, rectH) * 0.22, 14.0)) * lenScale;
		int ix1 = (int) Math.round(minX);
		int iy1 = (int) Math.round(minY);
		int ix2 = (int) Math.round(maxX);
		int iy2 = (int) Math.round(maxY);
		int ilen = (int) Math.round(len);
		int lw = Math.max(1, lineWidth);

		// 蟾ｦ荳・
		graphics.fill(ix1, iy1, ix1 + ilen, iy1 + lw, color);
		graphics.fill(ix1, iy1, ix1 + lw, iy1 + ilen, color);
		// 蜿ｳ荳・
		graphics.fill(ix2 - ilen, iy1, ix2, iy1 + lw, color);
		graphics.fill(ix2 - lw, iy1, ix2, iy1 + ilen, color);
		// 蜿ｳ荳・
		graphics.fill(ix2 - ilen, iy2 - lw, ix2, iy2, color);
		graphics.fill(ix2 - lw, iy2 - ilen, ix2, iy2, color);
		// 蟾ｦ荳・
		graphics.fill(ix1, iy2 - lw, ix1 + ilen, iy2, color);
		graphics.fill(ix1, iy2 - ilen, ix1 + lw, iy2, color);
	}

	private void drawCentered(GuiGraphicsExtractor graphics, Font font, Component text, int centerX, int y, int color) {
		if (y < 0) {
			return;
		}
		graphics.text(font, text,
				(int) (centerX - font.width(text) / 2.0), y, color, false);
	}

	private static int alphaFor(double distance, double range, double fadeStart, int maxAlpha) {
		double factor;
		if (distance <= fadeStart) {
			factor = 1.0;
		} else if (range <= fadeStart) {
			factor = 0.0;
		} else {
			factor = 1.0 - (distance - fadeStart) / (range - fadeStart);
		}
		if (factor < 0.25) {
			factor = 0.25;
		}
		return (int) Math.round(maxAlpha * factor);
	}

	private static int withAlpha(int rgb, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
	}
}
