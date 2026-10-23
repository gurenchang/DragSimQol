# Route Walker：リニア旋回・カメラ補間・ノード表示の修正指示

2026-09-19更新。実装担当は別タスクの5.6-sol。**この文書は修正時の設計指示。現在のコードにはリニア旋回とカメラMixin等の追加があるが、全要件の実装適合・実機動作は未検証。** 未実装と決めつけず現行差分を確認する。前版の加減速付き旋回案を本書で置き換える。

ユーザー向け名称は **Route Walker** に変更。新しい **Obsidian Bot** の仕様と5.6-solへの引き継ぎは [OBSIDIAN_BOT_SOL_HANDOFF.ja.md](OBSIDIAN_BOT_SOL_HANDOFF.ja.md) を参照。

## 1. 今回の要件

- yaw旋回はリニア（一定角速度）でよい。加速・減速の調整は廃止する。
- 初期速度を現状より少し速くし、カクつきを減らす。
- チャット中もノード・ルートを表示する。
- ノードの見た目を改善する。現在より多少の描画負荷増加は許容されている。
- 採掘・移動・経路進行のバックグラウンド継続を維持する。
- このタスクのCodexが設計・レビュー、別タスクの5.6-solが実装。既存未コミット変更を保持し、勝手にcommit/pushしない。

## 2. 調査結果と前回設計の訂正

作業ツリーには前版に基づく MiningRotationController、MiningSettings、MiningRouteOverlay と登録処理が追加されている。hotbar直後の再検証とtick状態更新の分離もコード上は確認できた。前回の「ノードrendererがない」「tickEnd全体を二度呼ぶ」という指摘は現在のコードには当てはまらない。

現在の問題:

1. MiningRotationController は加減速付きの台形速度プロファイル。初期最大速度90 deg/s、初期加速度240 deg/s²。リニア旋回の指定とは異なる。
2. MiningController.applyRotation は yRotO/xRotO に前tick角を入れているが、**これだけでは通常プレイ中の自分のカメラは補間されない。前回指示の不足だった。**
3. MiningRouteOverlay.extractRenderState は `mc.gui.screen() != null` で描画を中止するため、ChatScreenも非表示になる。コメントの「GUI表示中は3Dviewが見えない」という前提はチャットには当てはまらない。
4. マーカーは約5pxの十字、接続線は小さな点の列。ラベルはComponent.literalで、既存Oxanium用UiFontを通していない。

カクつきについては、ローカルの26.2クライアントJARを .tools/java-home.txt のjavapで確認した。Camera.alignWithEntity(float) は Entity.getViewYRot(partialTick)/getViewXRot(partialTick) を呼ぶが、LocalPlayer側は通常時、以下の動作となる（バイトコードからの要約）:

```java
// LocalPlayer
getViewXRot(partialTick) -> getXRot()
getViewYRot(partialTick) -> isPassenger()
    ? super.getViewYRot(partialTick)
    : getYRot()
```

そのため20Hzでplayer yawを書くだけでは、描画FPSが高くても角度はtickごとの段階変化になる。加減速をなくすだけ、速度を上げるだけ、yRotOを変更するだけではこの原因は解消しない。実際に導入したJARと作業ツリーの同一性、他MOD・フレーム落ちの寄与は未確認。

## 3. リニア旋回と設定

### 採用値

- Turn Speed: 範囲30–240 deg/s、**新しい初期値120 deg/s**。現状90から約33%増。ユーザー指定の具体値ではなく暫定設計値。
- 設定画面はTurn SpeedとShow Routeを残す。Turn Accelerationを削除する。
- /mining rotation speed <degreesPerSecond> を維持。/mining rotation reset で120へ戻せるようにする。
- 旧accelerationコマンドは成功したように無効値を保存しない。互換応答として英語で `Mining rotation is linear; acceleration is no longer used.` と表示し、設定は変えない。
- /mining status に `turnMode=LINEAR turnSpeed=120 deg/s showRoute=ON` のように実効値を表示。

リニア更新は以下。dtはclient tickごとの0.05秒。経路・旋回を1tickで一度だけ更新する既存の分離を維持する。

```text
error = wrapDegrees(targetYaw - currentYaw)
step = clamp(error, -turnSpeed * 0.05, turnSpeed * 0.05)
nextYaw = currentYaw + step
```

120 deg/sなら通常6°/tick。残角だけは小さいstepで停止し、目標を越えない。90°は15tick=0.75秒、180°は30tick=1.5秒（静止した目標、20 client tick/sの場合）。角加速度やランダムな速度変化は入れない。

内部yawは連続角として扱い、正規化は角差計算・表示で行う。179→-179は179→181相当の+2°。生の179と-179を直接lerpしない。目標変更、開始、再生待ちも同じ更新を通す。停止・再開時は現在のプレイヤー角度から同期する。

### 保存済み設定の扱い

初期値定数だけ変えても、既存JSONに90が保存されていれば90のままになる。保存済みのユーザー設定を黙って上書きしない。

- 新規設定／speed欠落時は120。
- 保存済みspeedは維持。従来90を利用している場合は `/mining rotation reset` または `/mining rotation speed 120` で適用できるよう、導入案内に明記。
- 古いturnAccelerationDps2は読込互換を保ちながら使用しない。旧設定を読めなくするschema変更を避け、ルートJSONを保持する。
- 加速度UI・status・テスト・説明の残骸を更新する。保存エラーを成功扱いしない。

## 4. カメラの滑らかさは描画側で補間する

**推奨: tickで確定した採掘yawの前値・現在値を、カメラが読む時だけpartialTickで線形補間する。** 実行時の進行と入力はtickが所有する。renderからplayerの角度、ノード、入力、タイマーを進めない。

```text
cameraYaw = previousTickYaw + (currentTickYaw - previousTickYaw) * clamp(partialTick, 0, 1)
```

- 前値と現在値は上記の連続角。1つの確定snapshotとして公開する。毎frame現在角から目標へ近づける方式は、FPSで速度・遅れが変わるので採用しない。
- Camera.alignWithEntityの角度取得箇所など、実際にカメラ行列を作る前の狭い範囲へclient Mixinを設ける。採掘セッション有効、カメラ対象が自分のLocalPlayer、通常の歩行中の場合だけ補間値を返す。採掘OFF・GUI停止後・死亡・切断・乗車・他エンティティ視点ではvanillaへ戻す。
- Entity/LocalPlayer.getViewYRot全体の戻り値を無条件変更しない。移動、攻撃照準、他エンティティ、戦闘マクロへ補間値を流さず、カメラの読み取りに限定する。
- 上記hookの実際の呼出し箇所・ordinal・descriptorを26.2で再確認し、Mixin登録と適用を確認する。既存Scythe用hookは保持する。カメラの行列・culling確定後に角度だけ直す実装にしない。
- ノードoverlayは、補間後の同じカメラ行列を使う。カメラだけ滑らかでノードだけ20Hzに飛ぶ状態を作らない。
- 通常は前tickから現在tickを表示するため、表示とtick値には最大1tick分の時間差がある。この差をさらに低域フィルタで増やさない。停止・再開時に古い旋回を再生しない。
- pitchは採掘中+90°を維持。yawだけに絞った補間で足りるならpitchの挙動を拡張しない。開始時のpitch変化も補間する場合、真下に揃う前の自動採掘を防ぐ。
- フォーカス喪失・最小化で描画callbackが減っても、tick側のyaw・移動・採掘を継続。復帰時は直近のsnapshotだけを使う。
- 前進許可と衝突判定はtick側の実yawを使い、描画用yawを使わない。旋回中の前進解除とスタック除外も維持。

次善: 既存のカメラ処理と競合する場合、同じtick snapshotを描画用のカメラ角度へ適用する別の限定hookを選ぶ。効果は同等だが行列・culling・描画MODとの整合確認が増え、実装コストと難易度は上がる。tickとrenderの双方でplayer yawを積分する代替は採用しない。

## 5. チャット中のノード表示

表示可否とマクロ実行可否を分ける。

- ワールドあり、F1非表示ではない、Show Route ON、world/dimension一致、かつscreenがnullまたはChatScreen（派生を含む）の場合に表示する。
- Esc、インベントリ、MOD設定など他のGUIの表示方針は従来どおりでよい。すべてのGUI条件を撤去する修正にしない。
- チャットを開くと採掘入力・移動は従来どおり停止する。**今回変えるのはノード表示。チャットを閉じても採掘を自動再開しない。**
- チャット入力、履歴、補完候補、クリック操作を遮らない。必要ならHUD登録位置をチャットより下へ変更する。画面条件だけでなくFabric側のHUD表示条件と重なり順も実機で確認。
- /mining route add/remove/clear の結果はチャットを閉じなくても次frameに反映。/mining route show off はチャット中でも即非表示。

## 6. ノードの見た目

### デザイン方針

- 視覚方針: 既存の黒×紫とOxaniumに合わせ、薄く発光するリングと細い経路線で、位置と進行方向が一目で分かる表示にする。
- 情報の優先順: 次のノードが主役、接続線で順序を補助、各点の番号を詳細として添える。画面を大きなパネルで覆わない。
- 動き: 目標リングの小さな明滅、現在の区間だけ方向を示す移動シェブロン、目標変更時の短い色フェード。通常ノードを全部動かさない。

### 描画仕様

- 普通のノード: 足元の投影位置に、外径10–14 GUI pxの細い紫リングと白寄りの中心点。24分割程度で円周を描き、十字の集合に見えないようにする。投影元は保存座標に固定。
- 目標ノード: 外径16–20 GUI px、薄い外周リングを追加。1.5秒周期程度でalphaを緩やかに変え、位置は揺らさない。強調対象は現在のTargetだけ。
- 接続線: 疎な点列から、1–1.5 GUI px程度の連続した線へ。通常は紫を低いalpha、現在の走行区間だけ明るくする。目標に接続する両辺を無差別に強調せず、入っていく区間を特定する。合流中は現在位置→合流ノードを別扱いにし、経路上の無関係な辺を強調しない。
- 現在区間には1～3個の小さなシェブロンを進行方向へ流す。停止中は動かさず、単なるルート表示に戻す。2ノード折返しでも進行方向と閉路の意味が一致すること。
- グローは広く薄い外側と細く明るい内側の2層程度で表現。全画面blur/post effectの導入は不要。
- ラベル: 通常 `#01`、目標 `Target #03`。UiFont.text等で既存Oxaniumを指定し、同じComponentで幅を測る。近距離のラベルに小さな黒の半透明背景と十分な余白を付け、エンドストーンの明るい床でも読めるようにする。
- ラベルとマーカーが重なる場合はラベル位置を数pxずらす。ノード座標は動かさない。Targetのラベルを最優先、次に近いノード。遠い・重複するラベルは省いても番号を振り直さない。
- 輪郭と線はサブピクセル位置を維持できる描画経路を優先する。1px整数fillの大量描画を重ねてギザつき・ジッターを増やさない。26.2対応の頂点・ライン描画方法を確認する。
- near planeと画面矩形で線分をclipする。24点の分割サンプリングだけで済ませず、画面外の両端を持つ線分・カメラ後方・ゼロ長を正しく扱う。ライン幅・グローのぶんも描画境界に含める。
- 色はGuiThemeの既存トークンを使い、必要な採掘専用の派生トークンは一箇所にまとめる。色だけに頼らず、番号・リング径・Target文字でも役割を伝える。

### 負荷

多少の増加は許容されているが、無制限描画にはしない。距離64ブロック、最大128マーカー・32ラベルをまず維持し、線・リングの見栄えに描画予算を使う。形状・文字列・経路の隣接関係はcacheし、フレーム毎のJSON読込は行わない。アニメーション位相は描画用時間から読むだけで、マクロのtick時間を変えない。

既存overlayでも選択配列、ソート用List、edgeList等のframeごとの生成がある。エフェクトを足す前に再利用可能なバッファやroute変更時cacheへ移し、GCによるカクつきを増やさない。負荷比較は同一場所・ノード数・FPS制限・表示設定で行い、平均FPSだけでなくframe timeのp95とスパイクを記録する。性能値は実測前に断定しない。

## 7. 実装・検証・納品

主な対象: MiningRotationController、MiningController、MiningSettings、MiningCommand、MiningRouteOverlay、DragsimMacroScreen、DragsimMacroClient、必要なclient Camera Mixinと登録、旋回・描画ロジックのテスト。

必須のロジック検証:

- リニア120 deg/sでは0→90°が15tick、0→180°が30tick。最後の残角のみ小さいstep。逆回転、途中の目標変更、速度範囲、無効値、停止・再開。
- 179→181相当の中間角が180°となり、0°へ逆走しない。partialTick=0/0.5/1で正しい角。snapshot読取やrender回数でtick状態が進まない。
- 実際の状態更新経路とhotbar入力フックを同一tickに呼ぶfixtureで1tick1更新を確認。単に同じ純粋関数を2回同じ回数実行して一致を見るだけのテストでは、重複呼出しの回帰は検出できない。
- screen=null／ChatScreen／他GUI、F1、Show Route、world/dimensionの表示条件。補完表示でもノードsnapshotを保持する。
- 旧90設定の保持、新規120、reset120、旧acceleration読込、ルートファイル保持。描画の元の隣接関係、clip、目標優先ラベル。

実機確認（ビルドと区別して報告）:

1. 60/120/144 FPS等で旋回録画を比較。tick境界、±180°、ノード切替でカメラとノードが跳ねない。実測FPSが低いケースと、20Hzの角度段差を区別する。
2. チャットを開いたままノード追加・削除・clear・show切替。入力文字と補完候補が正常、ノードは描画継続、マクロは停止。
3. 真下視点、斜め視点、1ノード、2ノード、通常の4ノード、上限付近で番号・方向・リング・lineを確認。実ゲームのbefore/after画像または録画を残す。
4. Alt+Tabで採掘・移動・旋回を3周以上継続。最小化と低FPSは別確認。復帰後H、GUI、持ち替え、死亡・切断で解除。
5. Sword/Scytheの回転・Hold背景継続が変わらないこと。Camera Mixinの対象外では標準カメラを保持すること。
6. 描画ON/OFFと旧版のframe timeを同じ条件で比較。目標ノードの明滅・矢印を増やした際のスパイクを確認。

.tools/java-home.txt のJDKで `gradlew.bat build --no-daemon --console=plain` と `git diff --check`。JDKを再インストールしない。報告には変更ファイル、追加Mixin、リニア初期値、保存値移行、chat表示条件、カメラ補間の根拠、ビルド/テスト、生成JAR、実機確認済み・未確認項目を含める。
