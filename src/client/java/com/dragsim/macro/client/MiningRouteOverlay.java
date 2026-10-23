package com.dragsim.macro.client;

import java.util.ArrayList;
import java.util.List;

import com.dragsim.macro.client.gui.GuiTheme;
import com.dragsim.macro.client.gui.UiFont;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * 採掘ルート描画(HUD overlay)。戦闘ESPのON/OFF・DEBUGモード・戦闘マクロ状態に依存しない。
 * マクロOFF中・1ノード・再起動直後の読込ルートも表示する。
 * ノードは保存座標に固定し、表示の高さで保存座標・到達判定を変更しない。
 * 描画は状態を読むだけで、採掘の進行を進めない(アニメ位相は描画時刻のみ参照)。
 *
 * 見た目は黒×紫・Oxaniumに合わせる。次のノードを主役(太いリング+明滅+Target表示)、
 * 接続線で順序を補助、番号ラベルを添える。色・径・文字の組合せで役割を伝える。
 */
public final class MiningRouteOverlay implements HudElement {
	/** 表示距離(blocks)。 */
	public static final double VIEW_DISTANCE = 64.0;
	/** 1フレームの上限。 */
	public static final int MAX_MARKERS = 128;
	public static final int MAX_LABELS = 32;
	private static final int SAMPLES_PER_EDGE = 24;
	private static final int MAX_DOTS_PER_RUN = 128;
	private static final double MARKER_Y = 0.05;
	private static final double LABEL_Y = 0.25;
	/** 目標リングの明滅周期(ms)。位置は揺らさない。 */
	private static final long TARGET_PULSE_PERIOD_MS = 1500;
	/** グロー・線幅ぶんを含めた描画境界の余白(px)。 */
	private static final double CLIP_MARGIN_PX = 24.0;

	// 採掘専用の派生色(GuiThemeトークン基準。一箇所にまとめる)。
	private static final int COLOR_NODE_RING = withAlpha(GuiTheme.ACCENT_DIM, 0xE0);
	private static final int COLOR_NODE_CENTER = withAlpha(0xF5EDFF, 0xFF);
	private static final int COLOR_LINE = withAlpha(GuiTheme.ACCENT_DIM, 0x80);
	private static final int COLOR_TARGET_RING = withAlpha(GuiTheme.ACCENT, 0xFF);
	private static final int COLOR_TARGET_GLOW = withAlpha(GuiTheme.ACCENT, 0x50);
	private static final int COLOR_TARGET_LINE = withAlpha(GuiTheme.ACCENT, 0xC0);
	private static final int COLOR_CHEVRON = withAlpha(GuiTheme.ACCENT_BRIGHT, 0xFF);
	private static final int COLOR_TEXT = GuiTheme.TEXT_PRIMARY;
	private static final int COLOR_LABEL_BG = 0xA0000000;

	private final MiningController mining;

	// route変更時のみ作り直すsnapshot(フレーム毎のコピー・文字列生成・ファイルI/Oを避ける)。
	private long cachedVersion = -1;
	private List<MiningRouteNode> cachedNodes = List.of();
	private List<Component> cachedLabels = List.of();

	// 毎フレーム使い回すバッファ(選択ソート用List・投影scratch)。
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f clip = new Vector4f();
	private final double[] scratchA = new double[2];
	private final double[] scratchB = new double[2];
	private final List<Integer> orderBuffer = new ArrayList<>();

	public MiningRouteOverlay(MiningController mining) {
		this.mining = mining;
	}

	/**
	 * 表示条件の画面判定(純粋)。ワールドあり・F1非表示・Show Route・world一致は別途確認し、
	 * screenはnullまたはChatScreen(派生含む)の場合に表示する。他GUIは従来どおり非表示。
	 */
	static boolean screenAllowsDraw(boolean hasScreen, boolean isChat) {
		return !hasScreen || isChat;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!mining.getSettings().isShowRoute()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		// F1等の標準HUD非表示を尊重する。チャット中も表示するが、他GUIでは描かない。
		// チャットを開いても採掘の実行可否は変わらない(表示のみ継続)。
		Screen screen = null;
		try {
			if (mc.options.hideGui) {
				return;
			}
			screen = mc.screen;
		} catch (Exception ignored) {
		}
		if (!screenAllowsDraw(screen != null, screen instanceof ChatScreen)) {
			return;
		}
		MiningController.RouteView view = mining.routeView();
		if (view.nodes().isEmpty()) {
			return;
		}
		// 描くルートと同じworld/dimensionのときだけ表示する。
		if (!MiningController.sameWorld(view.worldId(), MiningController.currentWorldId(mc))
				|| !view.dimension().isEmpty()
						&& !view.dimension().equals(MiningController.currentDimension(mc))) {
			return;
		}
		refreshCache(view);

		Camera camera = mc.gameRenderer.getMainCamera();
		if (camera == null || camera.position() == null) {
			return;
		}
		// 補間後の同じカメラ行列を使う(カメラだけ滑らかでノードだけ飛ぶ状態を作らない)。
		camera.getViewRotationProjectionMatrix(viewProjection);
		Vec3 camPos = camera.position();
		int guiW = mc.getWindow().getGuiScaledWidth();
		int guiH = mc.getWindow().getGuiScaledHeight();
		Font font = mc.font;
		Vec3 playerPos = mc.player.position();

		int n = cachedNodes.size();
		boolean[] drawn = selectDrawn(playerPos, n, view.targetIndex(), view.active());
		double pulse = pulsePhase();
		drawEdges(graphics, camPos, guiW, guiH, playerPos, n, drawn, view, pulse);
		drawMarkers(graphics, font, camPos, guiW, guiH, n, drawn, view, pulse);
	}

	private static double pulsePhase() {
		return (System.currentTimeMillis() % TARGET_PULSE_PERIOD_MS)
				/ (double) TARGET_PULSE_PERIOD_MS;
	}

	private void refreshCache(MiningController.RouteView view) {
		if (view.version() == cachedVersion) {
			return;
		}
		cachedVersion = view.version();
		cachedNodes = view.nodes();
		List<Component> labels = new ArrayList<>(cachedNodes.size());
		for (int i = 0; i < cachedNodes.size(); i++) {
			labels.add(UiFont.text(String.format(java.util.Locale.ROOT, "#%02d", i + 1)));
		}
		cachedLabels = List.copyOf(labels);
	}

	/**
	 * 描画対象の選択(純粋ロジック)。範囲内の目標と近いノードを優先し、番号は振り直さない。
	 * 上限内なら全件・順序どおり(割当なし)。
	 */
	static boolean[] selectDrawn(MiningRouteFollowerPositions positions, int targetIndex,
			boolean active) {
		return selectDrawn(positions, targetIndex, active, new ArrayList<>());
	}

	static boolean[] selectDrawn(MiningRouteFollowerPositions positions, int targetIndex,
			boolean active, List<Integer> buffer) {
		int n = positions.size();
		boolean[] drawn = new boolean[n];
		if (n <= MAX_MARKERS) {
			for (int i = 0; i < n; i++) {
				drawn[i] = positions.inRange(i);
			}
			return drawn;
		}
		// 上限超過時: 目標→近い順に詰める(再利用バッファをその場ソート)。
		buffer.clear();
		for (int i = 0; i < n; i++) {
			if (positions.inRange(i)) {
				buffer.add(i);
			}
		}
		buffer.sort((a, b) -> {
			boolean ta = active && a == targetIndex;
			boolean tb = active && b == targetIndex;
			if (ta != tb) {
				return ta ? -1 : 1;
			}
			return Double.compare(positions.distanceSq(a), positions.distanceSq(b));
		});
		for (int i = 0; i < Math.min(MAX_MARKERS, buffer.size()); i++) {
			drawn[buffer.get(i)] = true;
		}
		return drawn;
	}

	private boolean[] selectDrawn(Vec3 playerPos, int n, int targetIndex, boolean active) {
		MiningRouteFollowerPositions positions = new MiningRouteFollowerPositions() {
			@Override
			public int size() {
				return n;
			}

			@Override
			public boolean inRange(int i) {
				MiningRouteNode node = cachedNodes.get(i);
				double dx = node.x() - playerPos.x;
				double dz = node.z() - playerPos.z;
				return dx * dx + dz * dz <= VIEW_DISTANCE * VIEW_DISTANCE;
			}

			@Override
			public double distanceSq(int i) {
				MiningRouteNode node = cachedNodes.get(i);
				double dx = node.x() - playerPos.x;
				double dz = node.z() - playerPos.z;
				return dx * dx + dz * dz;
			}
		};
		return selectDrawn(positions, targetIndex, active, orderBuffer);
	}

	/** テスト用の位置source abstraction。 */
	interface MiningRouteFollowerPositions {
		int size();

		boolean inRange(int i);

		double distanceSq(int i);
	}

	/**
	 * 描画する辺の列挙(純粋ロジック)。登録順の隣接＋最後→先頭の閉路。
	 * 2ノードは同じ線の重複描画を避けて1辺だけにする。
	 */
	static List<int[]> edgeList(int nodeCount) {
		if (nodeCount < 2) {
			return List.of();
		}
		if (nodeCount == 2) {
			return List.of(new int[] {0, 1});
		}
		List<int[]> edges = new ArrayList<>(nodeCount);
		for (int i = 0; i < nodeCount; i++) {
			edges.add(new int[] {i, (i + 1) % nodeCount});
		}
		return edges;
	}

	/**
	 * Liang–Barskyによる画面矩形clip(純粋)。可視部分がなければnull、
	 * あればclip後の{x1,y1,x2,y2}を返す。ゼロ長は点として扱う。
	 */
	static double[] clipLineToRect(double x1, double y1, double x2, double y2,
			double minX, double minY, double maxX, double maxY) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) {
			return (x1 >= minX && x1 <= maxX && y1 >= minY && y1 <= maxY)
					? new double[] {x1, y1, x2, y2}
					: null;
		}
		double t0 = 0.0;
		double t1 = 1.0;
		double[] p = {-dx, dx, -dy, dy};
		double[] q = {x1 - minX, maxX - x1, y1 - minY, maxY - y1};
		for (int i = 0; i < 4; i++) {
			if (Math.abs(p[i]) < 1e-12) {
				if (q[i] < 0) {
					return null;
				}
			} else {
				double r = q[i] / p[i];
				if (p[i] < 0) {
					if (r > t1) {
						return null;
					}
					if (r > t0) {
						t0 = r;
					}
				} else {
					if (r < t0) {
						return null;
					}
					if (r < t1) {
						t1 = r;
					}
				}
			}
		}
		if (t0 > t1) {
			return null;
		}
		return new double[] {x1 + dx * t0, y1 + dy * t0, x1 + dx * t1, y1 + dy * t1};
	}

	/** 現在の走行区間の入口ノード。入っていく区間だけを特定する。 */
	static int activeEdgeStart(int nodeCount, int targetIndex) {
		if (nodeCount < 2 || targetIndex < 0 || targetIndex >= nodeCount) {
			return -1;
		}
		return (targetIndex + nodeCount - 1) % nodeCount;
	}

	private void drawEdges(GuiGraphicsExtractor graphics, Vec3 camPos, int guiW, int guiH,
			Vec3 playerPos, int n, boolean[] drawn, MiningController.RouteView view,
			double pulse) {
		double minX = -CLIP_MARGIN_PX;
		double minY = -CLIP_MARGIN_PX;
		double maxX = guiW + CLIP_MARGIN_PX;
		double maxY = guiH + CLIP_MARGIN_PX;
		int activeStart = view.active() && !view.joining()
				? activeEdgeStart(n, view.targetIndex()) : -1;
		for (int[] edge : edgeList(n)) {
			int a = edge[0];
			int b = edge[1];
			// 間引いた点同士を新しい接続線で結ばない。元の隣接関係だけで描く。
			if (!drawn[a] || !drawn[b]) {
				continue;
			}
			boolean isActiveEdge = a == activeStart && b == view.targetIndex();
			drawEdge(graphics, camPos, minX, minY, maxX, maxY,
					cachedNodes.get(a), cachedNodes.get(b),
					isActiveEdge ? COLOR_TARGET_LINE : COLOR_LINE,
					isActiveEdge && view.active() ? pulse : -1.0);
		}
		// 合流中は現在位置→合流ノードを別扱いで描く(経路上の無関係な辺は強調しない)。
		if (view.active() && view.joining() && view.targetIndex() >= 0
				&& view.targetIndex() < n && drawn[view.targetIndex()]) {
			MiningRouteNode t = cachedNodes.get(view.targetIndex());
			drawEdge(graphics, camPos, minX, minY, maxX, maxY,
					new MiningRouteNode(playerPos.x, playerPos.y, playerPos.z), t,
					COLOR_TARGET_LINE, pulse);
		}
	}

	private void drawEdge(GuiGraphicsExtractor graphics, Vec3 camPos,
			double minX, double minY, double maxX, double maxY,
			MiningRouteNode na, MiningRouteNode nb, int color, double chevronPhase) {
		boolean pa = projectInto(na.x(), na.y() + MARKER_Y, na.z(), camPos,
				(int) (maxX - minX), (int) (maxY - minY), scratchA);
		boolean pb = projectInto(nb.x(), nb.y() + MARKER_Y, nb.z(), camPos,
				(int) (maxX - minX), (int) (maxY - minY), scratchB);
		// scratchはGUI scaled座標(余白ぶんずれる)なのでclip後に余白を戻す。
		double ax = scratchA[0] - CLIP_MARGIN_PX;
		double ay = scratchA[1] - CLIP_MARGIN_PX;
		double bx = scratchB[0] - CLIP_MARGIN_PX;
		double by = scratchB[1] - CLIP_MARGIN_PX;
		if (pa && pb) {
			double[] clipped = clipLineToRect(ax, ay, bx, by, minX, minY, maxX, maxY);
			if (clipped == null) {
				return;
			}
			drawDenseRun(graphics, clipped[0], clipped[1], clipped[2], clipped[3], color);
			if (chevronPhase >= 0) {
				drawChevrons(graphics, ax, ay, bx, by, minX, minY, maxX, maxY, chevronPhase);
			}
			return;
		}
		// 片方・両方が投影不可(カメラ後方等)の線分は分割サンプリングで可視部分だけ描く。
		drawSampledFallback(graphics, camPos, minX, minY, maxX, maxY, na, nb, color);
	}

	/** 1px間隔の密な点列で連続した線に見せる(26.2のHudElement APIに斜線primitiveはない)。 */
	private void drawDenseRun(GuiGraphicsExtractor graphics, double x1, double y1,
			double x2, double y2, int color) {
		double dist = Math.hypot(x2 - x1, y2 - y1);
		if (dist < 0.5) {
			graphics.fill((int) Math.round(x1), (int) Math.round(y1),
					(int) Math.round(x1) + 1, (int) Math.round(y1) + 1, color);
			return;
		}
		int steps = Math.min(MAX_DOTS_PER_RUN, Math.max(1, (int) Math.ceil(dist)));
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			int x = (int) Math.round(x1 + (x2 - x1) * t);
			int y = (int) Math.round(y1 + (y2 - y1) * t);
			graphics.fill(x, y, x + 1, y + 1, color);
		}
	}

	/** 現在区間に進行方向へのシェブロンを流す(停止中は呼ばない)。 */
	private void drawChevrons(GuiGraphicsExtractor graphics, double ax, double ay,
			double bx, double by, double minX, double minY, double maxX, double maxY,
			double phase) {
		double dx = bx - ax;
		double dy = by - ay;
		double len = Math.hypot(dx, dy);
		if (len < 12.0) {
			return;
		}
		double ux = dx / len;
		double uy = dy / len;
		double nx = -uy;
		double ny = ux;
		for (int k = 0; k < 2; k++) {
			double t = (phase + k * 0.5) % 1.0;
			// 端のマーカーと重ならないよう中央寄りに配置する。
			double tt = 0.25 + 0.5 * t;
			double px = ax + dx * tt;
			double py = ay + dy * tt;
			if (px < minX || px > maxX || py < minY || py > maxY) {
				continue;
			}
			int x0 = (int) Math.round(px);
			int y0 = (int) Math.round(py);
			int xb = (int) Math.round(px - ux * 3);
			int yb = (int) Math.round(py - uy * 3);
			graphics.fill(x0, y0, x0 + 1, y0 + 1, COLOR_CHEVRON);
			graphics.fill((int) Math.round(xb + nx * 2), (int) Math.round(yb + ny * 2),
					(int) Math.round(xb + nx * 2) + 1, (int) Math.round(yb + ny * 2) + 1,
					COLOR_CHEVRON);
			graphics.fill((int) Math.round(xb - nx * 2), (int) Math.round(yb - ny * 2),
					(int) Math.round(xb - nx * 2) + 1, (int) Math.round(yb - ny * 2) + 1,
					COLOR_CHEVRON);
		}
	}

	/** 投影不可端を含む線分の可視部分だけを分割サンプリングで描く。 */
	private void drawSampledFallback(GuiGraphicsExtractor graphics, Vec3 camPos,
			double minX, double minY, double maxX, double maxY,
			MiningRouteNode na, MiningRouteNode nb, int color) {
		int guiW = (int) (maxX - minX);
		int guiH = (int) (maxY - minY);
		boolean havePrev = projectInto(na.x(), na.y() + MARKER_Y, na.z(), camPos,
				guiW, guiH, scratchA);
		double ax = scratchA[0] - CLIP_MARGIN_PX;
		double ay = scratchA[1] - CLIP_MARGIN_PX;
		for (int i = 1; i <= SAMPLES_PER_EDGE; i++) {
			double t = i / (double) SAMPLES_PER_EDGE;
			double wx = na.x() + (nb.x() - na.x()) * t;
			double wy = na.y() + (nb.y() - na.y()) * t + MARKER_Y;
			double wz = na.z() + (nb.z() - na.z()) * t;
			boolean cur = projectInto(wx, wy, wz, camPos, guiW, guiH, scratchB);
			double bx = scratchB[0] - CLIP_MARGIN_PX;
			double by = scratchB[1] - CLIP_MARGIN_PX;
			if (havePrev && cur) {
				double[] clipped = clipLineToRect(ax, ay, bx, by, minX, minY, maxX, maxY);
				if (clipped != null) {
					drawDenseRun(graphics, clipped[0], clipped[1], clipped[2], clipped[3], color);
				}
			}
			havePrev = cur;
			ax = bx;
			ay = by;
		}
	}

	private void drawMarkers(GuiGraphicsExtractor graphics, Font font, Vec3 camPos,
			int guiW, int guiH, int n, boolean[] drawn, MiningController.RouteView view,
			double pulse) {
		int labels = 0;
		// 目標を先に描き、残りは登録順。ラベル上限超過分はマーカーのみ。
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < n; i++) {
				boolean isTarget = view.active() && i == view.targetIndex();
				if ((pass == 0) != isTarget) {
					continue;
				}
				if (!drawn[i]) {
					continue;
				}
				MiningRouteNode node = cachedNodes.get(i);
				if (!projectInto(node.x(), node.y() + MARKER_Y, node.z(),
						camPos, guiW, guiH, scratchA)) {
					continue;
				}
				if (isTarget) {
					drawTargetMarker(graphics, scratchA[0], scratchA[1], pulse);
				} else {
					drawNodeMarker(graphics, scratchA[0], scratchA[1]);
				}
				if (labels >= MAX_LABELS) {
					continue;
				}
				if (!projectInto(node.x(), node.y() + LABEL_Y, node.z(),
						camPos, guiW, guiH, scratchB)) {
					continue;
				}
				int sx = (int) Math.round(scratchB[0]);
				int sy = (int) Math.round(scratchB[1]) - 12;
				if (sx < -60 || sy < -24 || sx > guiW + 60 || sy > guiH + 24) {
					continue;
				}
				Component text = isTarget
						? UiFont.text(String.format(java.util.Locale.ROOT, "Target #%02d", i + 1))
						: cachedLabels.get(i);
				int half = font.width(text) / 2;
				graphics.fill(sx - half - 3, sy - 2, sx + half + 3, sy + font.lineHeight + 1,
						COLOR_LABEL_BG);
				graphics.centeredText(font, text, sx, sy,
						isTarget ? COLOR_TARGET_RING : COLOR_TEXT);
				labels++;
			}
		}
	}

	/** 通常ノード: 外径12pxの細い紫リングと白寄りの中心点。 */
	private void drawNodeMarker(GuiGraphicsExtractor graphics, double cx, double cy) {
		drawRing(graphics, cx, cy, 6.0, COLOR_NODE_RING);
		int x = (int) Math.round(cx);
		int y = (int) Math.round(cy);
		graphics.fill(x, y, x + 1, y + 1, COLOR_NODE_CENTER);
	}

	/** 目標ノード: 外径18px＋薄い外周リング。alphaだけ明滅し位置は揺らさない。 */
	private void drawTargetMarker(GuiGraphicsExtractor graphics, double cx, double cy,
			double pulse) {
		double glow = 0.6 + 0.4 * Math.sin(pulse * 2.0 * Math.PI);
		int ring = withAlpha(GuiTheme.ACCENT, (int) Math.round(200 + 55 * glow));
		drawRing(graphics, cx, cy, 13.0, COLOR_TARGET_GLOW);
		drawRing(graphics, cx, cy, 9.0, ring);
		int x = (int) Math.round(cx);
		int y = (int) Math.round(cy);
		graphics.fill(x - 1, y, x + 2, y + 1, COLOR_NODE_CENTER);
		graphics.fill(x, y - 1, x + 1, y + 2, COLOR_NODE_CENTER);
	}

	/** 円周を密な点列で描く(十字の集合に見せない)。 */
	private void drawRing(GuiGraphicsExtractor graphics, double cx, double cy, double radius,
			int color) {
		int dots = Math.min(128, Math.max(24, (int) Math.ceil(2.0 * Math.PI * radius)));
		for (int i = 0; i < dots; i++) {
			double a = 2.0 * Math.PI * i / dots;
			int x = (int) Math.round(cx + radius * Math.cos(a));
			int y = (int) Math.round(cy + radius * Math.sin(a));
			graphics.fill(x, y, x + 1, y + 1, color);
		}
	}

	/**
	 * world 1点 → GUI scaled coordinate。カメラ後方・near plane・非有限をrejectし、
	 * 文字や線の反転・巨大化を防ぐ(PathDebugOverlayと同一pipeline)。
	 */
	private boolean projectInto(double wx, double wy, double wz, Vec3 camPos,
			int guiW, int guiH, double[] out) {
		clip.set((float) (wx - camPos.x), (float) (wy - camPos.y), (float) (wz - camPos.z), 1.0F);
		viewProjection.transform(clip);
		if (clip.w <= 1.0E-3F || !Float.isFinite(clip.w)) {
			return false;
		}
		float invW = 1.0F / clip.w;
		float ndcX = clip.x * invW;
		float ndcY = clip.y * invW;
		if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
			return false;
		}
		out[0] = (ndcX + 1.0F) * 0.5 * guiW;
		out[1] = (1.0F - ndcY) * 0.5 * guiH;
		return true;
	}

	private static int withAlpha(int rgb, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
	}
}
