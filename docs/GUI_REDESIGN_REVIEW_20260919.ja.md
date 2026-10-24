# GUI改修の独立確認 — 2026-09-19

実装場所: `C:\Users\guren\.codex\worktrees\04a3\DragSimQol`

## 利用方法と変更内容

`/zealotgui` またはMinecraftのControlsで割り当てた `Open DragSim GUI` キーから開く。

| ページ | 主な操作 |
|---|---|
| Mining Bot | 採掘対象、旋回速度、Aim Driftと量、状態と現在のキー、Start & Close |
| Route Walker | 旋回速度とリセット、ルート表示、現在位置/XYZからノード追加、一覧・選択削除、確認付き全削除、Start & Close |
| Combat | 戦闘モード、Attack/Approach/Rotation、対象種類・範囲、武器登録 |
| Visuals | ESP、Special Highlight |
| Advanced | Targets/Rotation/Navigation/Scytheから1分野を選択して詳細設定 |

Mining Botの対象はObsidian、Large Amethyst Bud（facing=downかつwaterlogged=false）、Amethyst Block。Target Blockは自動照準対象であり、保持するAttack入力は照準が通過する対象外ブロックにも作用する既存仕様を維持。

GUIを開くと採掘を停止する。`Start & Close`はGUIを閉じて既存のcontroller開始条件を検査する。Mining Botは可視・リーチ内の適切な対象がなければ開始を拒否する。

設定の条件表示、狭幅の配置、popupのクリック処理、説明tooltip、座標入力のfocusとclipping、確認付き削除をレビュー。ルート編集は保存成功後にメモリへ反映する。採掘設定の関連setterは保存失敗時に値を戻し、GUIに失敗を通知する。

## 検証結果

- 実装タスク完了後、親側で `gradlew.bat build --no-daemon --console=plain` を実行し成功。8タスクはUP-TO-DATEであり、この親側ビルドで全テストを再実行したわけではない。
- 直近のJUnit XMLを親側で集計: 16 suite、152 tests、failures 0、errors 0、skipped 0。
- `git diff --check` 成功。
- JAR ZIP整合性を確認。GUI/Mining Bot/既存Mixinの主要11クラスがbuild出力とバイト一致。
- client限定Mixin登録と既存3つのMixinを確認。今回のGUI改修によるMixin追加はない。
- ObsidianController.javaのSHA256はGUI改修前baselineと一致: `7B14C98E907B8CCD552E615D661964F245B0E9A49443AFF01295A9884D536B1E`。
- 320x240、426x240、854x480の論理サイズと幅191のコントロール配置をJava2Dモデルで確認。これらの画像はMinecraftの実ゲーム画面ではなく、描画APIを置き換えた配置確認用モデル。

## 最終JAR

- Path: `C:\Users\guren\.codex\worktrees\04a3\DragSimQol\build\libs\dragsimmacro-1.5.0.jar`
- Size: 448113 bytes
- Last write: 2026-09-19 23:10:43 JST
- SHA256: `CA10F4E2F18BFBE3CADE18FEE9A84C536FE779BD252EE869C486CFA68BE91EE5`

元workspaceのbuild/libsとは別の成果物。元workspaceへの実装統合、commit、push、Minecraftのmodsフォルダへの差し替えは行っていない。

## 実機未確認

Minecraft起動、GUIの実描画・実入力、Start & Closeからの実採掘、前回のマウス競合修正、非フォーカス中の継続は実ゲームで未確認。

導入時はゲームを終了して導入済みの同MOD JARを上記JARに差し替える。sources.jarは導入用ではない。最初に各ページ・対象切替・設定保存・ノード追加削除とCancel・対象なし開始拒否・開始と停止を確認する。