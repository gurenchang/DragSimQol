# GUI設定移行表

設定値・保存形式は変更せず、GUI上の所在だけを整理した。

| 旧ページ | 旧項目 | 新しい所在 |
|---|---|---|
| Mining Bot | Enabled / Target Block / Turn Speed / Aim Drift / Drift Amount | Mining Bot（Start & Close / Target & Aim / Aim Drift） |
| Route Walker | Enabled / Turn Speed / Show Route | Route Walker（Start & Close / Basic Settings） |
| コマンドのみ | Route add / remove / clear / list | Route Walker（Route Nodes / Add Coordinates） |
| General / Combat | Attack / Approach / Rotation | Combat |
| Targets | Normal / Bruiser / Special / Target Range | Combat / Targets |
| Combat | Combat Mode / weapon bindings | Combat / Loadout |
| ESP / General | ESP Mode / Special Highlight | Visuals |
| Targets | Selection Mode / tie / switch / memory / lookahead | Advanced / Targets |
| Rotation | Mode / Speed / Tracking / Cornering | Advanced / Rotation |
| Movement | Navigation / Jump / Warp / path values / timers / prediction | Advanced / Navigation |
| Combat | Scythe Use Mode / Range / Speed / Interval | Advanced / Scythe（Frozen Scythe時のみ。IntervalはAimed時のみ） |

Minecraft標準のキー設定画面は、GUIヘッダーの `Keys` / `Minecraft Controls` から開く。

Route Walker の `Movement Mode` は `Auto Turn`（従来の自動旋回）と `WASD Only` を切り替える。
`WASD Only` は真下向きを維持し、現在の左右の向きを基準に標準W/A/S/D入力でノードを順番にたどる。
左右の自動旋回は行わず、`Turn Speed` は `Auto Turn` のみに作用する。横移動・後退の区間はMinecraft標準の速度制約に従う。
モードは採掘設定JSONに保存し、旧設定で項目が欠けている場合は `Auto Turn` を使う。

ルート途中への追加は、挿入する場所に立って `/routewalker route add 2` のように番号を指定する。
この例では現在位置が新しい #2 になり、元の #2 以降は番号が1つずつ後ろへずれる。
指定範囲は1〜現在のノード数+1（末尾）。番号なしの `/routewalker route add` は従来どおり末尾追加。
挿入したノードは既存ルートと同じファイルへ保存される。走行を停止してから編集する。

Mining Bot の `Aim Lead Mode` は `Off` / `Adaptive` / `Fixed` を選択する。
`Fixed` は学習せず、`Expected Break Time`（想定採掘時間）から `Fixed Lead`（先行時間）を引いた経過時間で次の対象へ旋回を開始する。
例：1000msと150msなら、標準の採掘処理が対象への採掘を開始してから850ms後。視点が対象へ到達する時刻ではない。
先行0msは先行移行を無効化し、通常のブロック更新まで待つ。先行時間が大きすぎる場合は最低50msの採掘時間を残す。
判定はクライアントtickで行うため指定時刻ちょうどとは限らず、サーバーの実際の破壊時刻も保証しない。
他に到達可能な対象がない場合はその場で継続する。タイムアウトが先に到来する設定ではタイムアウト処理を優先する。
`Adaptive` の自動調整と既存のタイムアウト設定は引き続き利用可能。
