# Route Walker旋回前進 / Misc Fullbright — 独立確認

2026-09-19。ユーザー指定により既存実装タスクをgpt-5.6-luna / highへ変更して実装を依頼し、親でレビューした。

実装worktree: C:\Users\guren\.codex\worktrees\04a3\DragSimQol

- Route Walkerは合流後、旋回誤差による前進・sprint解除を行わない。初期合流は従来の向き合わせ後に歩行する。リニア旋回速度は既存設定を使用。
- evaluateSafety、validateSession、stopの各実処理がレビューsnapshotから変更されていないことを比較した。
- GUIはMining Bot / Route Walker / Combat / Visuals / Misc / Advancedの6ページ。MiscにFullbrightを追加。初期OFFでMacroConfigへ永続化。
- FullbrightはLightmapRenderStateExtractor.extractのHEADで切替を検出して再抽出を要求し、RETURNで更新済み・world/player有り・ONの場合だけambientColorを白にする。通常の明るさ設定、player effects、sky/block factor、暗視、darkness/boss darkeningの値は変更しない。OFFはvanillaの再抽出で通常値へ戻る。
- クライアント専用FullbrightLightmapMixinを1つ追加。既存3つと合わせて4つ。
- 対象メソッド・dirty flag・早期returnとlightmap.fshの計算順をMinecraft 26.2のローカルJARから独立確認。

## 検証

- 全テストXML: 157 tests、failures/errors/skippedはいずれも0。
- 実Follower、RotationController、controllerの前進判定を使った理想化シミュレーションで初期1.5blockの歩行合流、120deg/sで四角ルートの複数周回を確認。歩行0.215、sprint0.28 block/tick。実Minecraftの慣性・摩擦・速度効果を再現するテストではない。
- 遅い180度旋回では従来の逸脱停止を維持するテストを確認。低速旋回・急な折返しでは実用上も停止する可能性がある。逸脱範囲を拡大して隠す変更は行っていない。
- Fullbrightのpolicy単体テストは状態遷移と非蓄積ambient生成を確認する範囲。Mixin適用・実GPU描画の検証ではない。
- 親でgradlew.bat build --no-daemon --console=plainを実行し成功。8タスクはUP-TO-DATEで、親が全テストを再実行したわけではない。
- git diff --check成功。JAR ZIP整合性、主要5クラスのコンパイル出力とのバイト一致、4つのclient Mixin登録を確認。
- 既存ObsidianController.javaは前回確認時のSHA256と一致。
- 実ゲーム、Sodium/Iris/シェーダーとの互換性は未確認。

## JAR

Path: C:\Users\guren\.codex\worktrees\04a3\DragSimQol\build\libs\dragsimmacro-1.5.0.jar

Size: 450424 bytes

SHA256: 28309F191F954BD492D9F900DA3A1437EC6D76A854ED7388E65E380D535EBEF1

元workspaceへの統合、commit/push、modsフォルダの差し替えは行っていない。前回JARと同名のため導入時にこのworktree側のファイルを使う。