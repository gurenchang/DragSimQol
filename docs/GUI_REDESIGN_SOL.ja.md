# DragSimQol GUI 大規模改修 — 2026-09-19

ユーザー依頼: GUIが見にくい。採掘関連をGUIから設定したい。設定項目が多すぎて理解しづらい。実装は既存5.6-solタスク、親は設計・独立レビュー。最新のマウス競合/開始対象ガードは実ゲーム未確認のまま維持する。

## デザイン方針（frontend-skill）

- Visual thesis: 不透明に近いチャコール面、読みやすい明るい文字、控えめな紫の操作アクセントで構成する、落ち着いた採掘操作パネル。
- Content plan: 左に5項目以内の機能ナビ、中央に選択機能の状態と1つの開始操作、その下に基本設定、追加設定は明示的なAdvanced導線。装飾カードやマーケティング文は置かない。
- Interaction thesis: 選択/hoverの短い色遷移、toggleつまみの短い時間ベース移動、Advancedの開閉状態が分かる表示。描画FPSで進行する操作や、移動するクリック領域は避ける。
- 黒/紫と既存静的Oxaniumを継承。ESPやフォント幅計測を巻き添えで壊さない。説明は日本語、MOD内は英語。

## 情報設計

ナビゲーション: Mining Bot / Route Walker / Combat / Visuals / Advanced（最大5件）。初回はMining Bot。セッション中の前回ページ/scrollを適切に維持し、Basic/Advancedやリサイズで無効なscroll/選択をclamp。

Mining Bot基本: Target Block（3種類、選択したブロックのitemアイコン可）、Turn Speed、Aim Drift。Drift Amountは関連する詳細領域にまとめる。上部は対象/選択ツール/停止理由や状態の短い表示、現在のキー割当、Start & Close。採掘可能な対象なし拒否は既存controllerガードを必ず通す。ターゲットは自動照準対象であり、保持中のAttackは他ブロックにも作用する説明を残す。Budはdown/dryの対象制約を簡潔に表示。設定保存失敗を成功扱いしない。

Route Walker基本: 上部にノード数と状態、Start & Close、現在のキー割当。Turn Speed / Show Route。ルート編集はGUIで完結:
- Add Current Position（実player座標、既存検証/API利用）
- 順序と座標が読めるノード一覧、選択ノードRemove、Clear Route（確認UI必須）
- Add Coordinates（XYZ入力と有限値/既存重複・上限検証）
- 旋回速度Reset（速度だけを戻す）。ルート一覧は512ノードでも画面からはみ出さず、全件を毎frame文字列化しない。
- 可能なら小さな平面ルートプレビュー。装飾目的でなく、ノード順/閉路/選択を理解するために描く。追加・削除後の選択やscrollを安全に更新。
- 保存/読込とworld/dimensionの既存形式を維持。他worldのルートに勝手に混ぜない。既存編集APIのsave失敗がGUIへ返せない場合は最小限の戻り値改善で伝達する。新規ルート管理システムやnamed profiles等は増やさない。

Combat基本: Combat Mode、Attack/Approach/Rotation主要スイッチ、適用モードに必要な武器登録、Target種類/Range。Frozen Scythe設定はそのモード時だけ。重複したAttack/Approach/ESPを複数ページに置かない。戦闘の開始操作を設けるなら共通controller・所有権・GUI close条件を守る。

Visuals: ESP mode、Special Highlight等。Route表示の唯一の編集箇所はRoute Walker。Controlsへの導線は共通footer等に置き、Minecraft標準キー設定を開く。H/J/G固定表示ではなくユーザーの現設定を参照。

Advanced: ターゲット選別詳細/記憶/lookahead、戦闘Rotation詳細、navigationタイマー/予測/warp、Scythe弾速・射程・間隔など従来機能はここへ整理する。分野ごとに見出しと短い説明/折り畳みまたはsubsectionを使い、一面に全項目を並べない。従来の有効な設定を削除しない。非適用項目は条件に応じて隠す/disable（Aimed-only間隔等）。グローバル設定resetや自動preset上書きは追加しない。

## 見やすさ・操作要件

- 設定名、説明、現在値/入力欄の領域を分離。右コントロールが説明文へ重ならない。長文は利用可能幅でwrap/省略+tooltip。UIラベルを小さく縮めて詰め込まない。
- 大きなGUI scaleにも対応。320x240/426x240/854x480等のGUI論理サイズでnav/本文/閉じる/開始/入力のgeometryが画面内、最小幅で負サイズにならない。狭幅では上下2段の行またはcompact nav、低高さはbody scroll。
- dropdownは後段の上に描画する独立popup領域で、画面端では上へ開く/scroll。背面クリックを透過しない。screen scissor外の項目へクリックしない。slider dragのmouse up/画面閉じ/ページ変更でcaptureを残さない。
- keyboard: Esc、Tab/Shift+Tabのfocus、Enter/Spaceで主要buttons、text fieldsの通常入力。可能ならslider左右キー。少なくとも座標入力がゲーム操作へ流れない。
- UI開閉時の既存入力解除を維持。描画やページ切替で自動開始/保存/route編集をしない。開始操作だけGUIを閉じて既存controllerへ渡し、拒否理由を分かる形で表示。閉じただけで自動再開しない。
- getter/setterから実状態を読む。UIだけ別の設定コピーを保持しない。route/settings形式やキーIDを維持。ファイルI/Oを無駄に毎frame行わない。
- animationは時間ベース、短時間。リスト/ボタン境界を不安定に動かさない。

## 検証と納品

- 旧GUIの操作一覧と新しい所在を簡単な表にし、設定の取りこぼしをレビュー可能にする。
- 意味のあるテスト: responsive layoutとクリック範囲/clipping、popup配置、Basic/Advancedの項目割当/条件表示、座標入力validation/route保存失敗/clear確認/選択更新、既存controller経由の開始条件等。単にラベル文字列を探すテストやifをそのまま繰り返すテストは不要。
- 全build、全単体テスト、diff --check。成果JARの絶対パス/サイズ/SHA256。実ゲームの描画/操作と純粋layoutテストを区別する。
- UIを視覚確認できる方法があれば活用する。HTML等のpreviewを用いる場合は設計previewであり実ゲーム画面ではないと明記。実ゲーム未確認なら率直に報告。
- commit/push/reset/clean、未追跡/logs削除、元workspaceへの統合は行わない。