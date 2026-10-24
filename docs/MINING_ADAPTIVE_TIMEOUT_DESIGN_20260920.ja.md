# Mining Bot: 適応型照準先行・対象タイムアウト

2026-09-20。実装は既存実装タスクのgpt-5.6-luna/high、親は設計と独立レビュー。

作業先: C:\Users\guren\.codex\worktrees\04a3\DragSimQol

## 方針

ユーザー確認: このサーバーではアメジストも瞬時には壊れない。vanillaの硬さやクライアント予測からサーバーの採掘所要時間を決め打ちせず、対象別の実際の更新観測値で扱う。

サーバーtickを推定したという表示はしない。観測値はvanillaの対象採掘試行から、その座標のサーバー由来の置換stateを受け取るまでの時間。RTTと採掘処理を分離できず、他プレイヤーやサーバー独自処理による置換を自分の採掘成功と断定できない。ping/2を固定で差し引かない。

標準のAttack長押し・vanilla破壊処理・既存旋回を使う。独自の掘削packetや進行値変更は行わない。対象外に照準が合っていても左クリックを保持するユーザー指定を維持。

## 設定案

- Adaptive Aim Lead: 既定OFF、ON/OFFと設定を保存。
- Max Lead: 既定200ms、0〜500ms。上限であり最初からこの時間を先行しない。
- 学習: 確認済みの通常採掘5サンプル以上、有限windowの中央値/ばらつき。少量から試し、確認失敗では減少または0へ戻し再学習。
- 実効先行量は推定時間の一部以下へ制限し、tickで判定する場合は約50msのclient tick粒度を考慮。
- Mining Timeout: 既定ON、15秒、調整範囲1〜60秒。先行トグルと独立。
- timeout対象は5秒程度候補除外し、即同じ対象へ戻らない。代替候補がなければ待機して再試行する。

GUIのMining Botへ配置。通常UIは短い英語。診断情報は/miningbot statusとイベントログへ。

## 相関と寿命

- target選択や旋回開始時刻を採掘開始としない。vanillaの実採掘試行を観測する。
- 同一座標のSTART繰返しでtimeout時刻をリセットしない。
- ローカル予測でworldがairになってもserver-confirm recordを破棄しない。
- single/sectionの両block updateをメインスレッド上で観測。受信payloadの座標/state、level/session、対象profile、採掘試行に相関する。
- 意図的な先行は別の可視・reach内候補がある場合だけ。未確認先行は最大1件。古い対象を確認待ちにし再選択を抑止。
- 先行したサンプルを通常所要時間へ混ぜない。確認後の再生は失敗扱いしない。期限切れ、二重・遅延更新、無関係座標を扱う。
- map/履歴に上限を設け、期限とstop/OFF/ワールド・対象・工具変更で掃除。タイムアウト失敗を成功学習に使わない。
- 設定保存失敗はrollback・通知。旧設定は既定値を使い、既存項目を保持。

## 26.2コード確認

- ClientPacketListener.handleBlockUpdateはPacketUtils.ensureRunningOnSameThread後にClientLevel.setServerVerifiedBlockStateを呼ぶ。
- handleChunkBlocksUpdateは同様のthread検査後、runUpdatesのlambdaから同じsetServerVerifiedBlockStateを呼ぶ。
- MultiPlayerGameMode.continueDestroyBlockはdestroyDelay>0でもtrueを返す。そのHEAD/return trueだけを採掘開始にしない。
- startDestroyBlockは権限/境界の拒否分岐、空気、即時ローカル破壊を持つ。RETURNでworldを見るだけでは元stateが失われる場合がある。

## 検証基準

時間/試行相関のproductionロジックを使ったdeterministic test、旧設定/保存失敗、学習不足・外れ値、先行成否・増減、別候補なし、timeout/cooldown、遅延/二重/古いsessionと再生を検証。

全build/test/diff check、JAR内容とhashを親で確認。ゲーム内動作、サーバー実測、効率改善の実証は別の検証。commit/pushや元workspace統合はしない。
