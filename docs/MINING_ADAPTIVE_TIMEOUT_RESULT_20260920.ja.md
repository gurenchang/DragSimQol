# Mining Bot 観測型先行・タイムアウト 検証記録

2026-09-20。実装担当gpt-5.6-luna/high、親が設計・sourceレビューとビルド確認。

作業先: C:\Users\guren\.codex\worktrees\04a3\DragSimQol

## 設定

- GUI: Mining Bot → ADAPTIVE MINING。
- Adaptive Aim Lead: 既定OFF。Max Lead: 既定200ms、0〜500ms。
- 通常採掘の確認済み観測5件以上。初回先行50ms、確認成功時25msずつ増加。設定上限・観測中央値の25%以内。50ms未満の先行窓や大きな変動では先行しない。
- 先行確認待ちは最大1件。別の可視・リーチ内候補がある場合のみ旋回先を変更。失敗時は0へ戻して再学習。
- Mining Timeout: 既定ON、15秒。1〜60秒。期限切れは約5秒候補除外し、別候補へ移る。OFF時は現対象の時計を保持する。
- 設定保存、旧JSON既定値、保存失敗rollback、parse原子性を検証。
- 左クリック保持は対象選択と独立し、対象外に照準が合っても継続する。

サーバーの採掘tick数は測定していない。値はクライアントのvanilla採掘開始観測から、サーバー由来の対象置換state受信までの時間。アメジストもこのサーバーでは瞬時に壊れないというユーザー情報を反映し、固定所要時間を仮定しない。

## 観測とレビュー

開始前BlockStateをcaptureしaccepted RETURNを観測。vanillaの採掘中断は学習除外し、タイムアウト時計は繰返しSTARTで延長しない。ローカル予測による破壊とサーバー由来更新は区別。期限切れ処理はtickと更新callbackで共通化。古いlevel、工具変更、対象変更を検査する。

client Mixinへ MiningBotBreakObservationMixin と MiningBotServerBlockUpdateMixin を追加。ClientLevel.setServerVerifiedBlockStateは26.2ローカルbytecodeでvoidを確認。Mixinが実際の起動時に適用されることは未確認。

## 検証結果

- 全177テスト成功。failures/errors/skipped: 0。
- gradlew test / build 成功。
- git diff --check: 空白エラーなし。既存のLF→CRLF警告あり。
- 新規主要ソースにもno-indexによる空白検査を実施。
- JAR内にlearner/ledger、観測Mixin、client限定設定があることを確認。
- 実Minecraft・実サーバー、効率改善、Sodium/Iris、背景tick抑制環境は未確認。

## 成果物

JAR: C:\Users\guren\.codex\worktrees\04a3\DragSimQol\build\libs\dragsimmacro-1.5.0.jar

サイズ: 469179 bytes

SHA256: 6B09605185EDDEC7FB5055FB647DE5FDCB04B35D08CF1FDD0743FBBC65DE7C0D

生成時刻: 2026-09-20 01:05:08（ローカル）

元workspaceへコード統合、commit、pushは行っていない。導入時はゲーム終了後に通常JARを差し替える。実機確認手順は MINING_ADAPTIVE_TIMEOUT_MANUAL_CHECK_20260920.ja.md を参照。
