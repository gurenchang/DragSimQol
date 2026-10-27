# Dragon Spawn Timer（ドラゴン召喚タイマー）仕様

Summoning Eye を8個はめてからドラゴンが出現するまでのカウントダウン（照準の下に表示）。
出現地点のマーカー: 出現座標そのもの（ドラゴンのエンティティ位置）を中心に 0.4 ブロックの立方体と十字、祭壇からのビーム、
残り時間と距離のラベル（地形越しに見える）。最初はドラゴンの当たり判定全体（16×8×16）を描いたが、広すぎて狙えないので点にした。
召喚前の出現地点は構造物の中（y 45〜76 に黒コンクリート等 約1,500ブロック、祭壇中心から y 46 まで紫ガラスの柱）で、
サーバーはドラゴン出現の瞬間にそれを air に変える。深度テストありで描くと完全に隠れるので、マーカーは深度テストなしで描く。
実装: `DragonSpawnTimer`（判定）/ `DragonSpawnOverlay`（描画）。
設定: Visuals → Dragon Spawn Timer（`MacroConfig.dragonSpawnTimer`）/ Dragon Spawn Marker（`dragonSpawnMarker`）。

## 実測（Recorder リプレイ 2本、2026-10-27、サーバー 20 TPS）

`2026-10-27_19-54-58.mcrc` / `2026-10-27_20-01-31.mcrc`。どちらも自分で8個設置。0 = 8個目のチャット受信。

| 経過 | パケット |
|---|---|
| 0 | `» You placed a Summoning Eye! (8/8)`（§付きリテラル）、8個目のフレームが `eye=true`、`entity.ender_eye.death` |
| +1 tick | フレーム8個が同時に `eye=false` に戻る、各フレームにアーマースタンド、`block.end_portal.spawn` |
| +0.3 s / +0.8 s | 音楽 `dragsim:music.mythic_warfare` / `entity.ender_dragon.growl` |
| +4.8 s | 中心ブロックの列 (x,9..44,z) に air 更新が2 tickごとに1段ずつ上昇、`block.chain.place` を0.5 秒ごとに8回（演出） |
| +8.5 s | 出現地点の周り x±6・z±6・y 44〜76 にまとめて air 更新、`entity.generic.explode` ×6、`ender_dragon` 追加、`☠ The <種類> Dragon has spawned!` |

- 遅延: 8499 ms / 8506 ms → **170 server tick**。
- フレーム: x -673〜-669、z -278〜-274、y 9 の5×5リング（角なし）。
- 出現位置: 2回とも **(-671.0, 60.0, -276.0)** = リング中心ブロックの整数座標そのまま（+0.5 なし）、フレームの **+51**。
- ドラゴンの種類は出現時に決まる（`[Debug] ... Spawn 76.58/100`）。ボスバーは出現直後に前回のドラゴン名を出し、約1秒以内に今回の名前へ更新される。

## 判定

- 開始A: チャット `Summoning Eye! (n/8)` で n=8。プレイヤーの周り ±6（高さ ±3）でフレームを探し、リングとして中心を求める。
- 開始B: 同じリングのフレーム8個が 250 ms 以内に `eye=true → eye=false` になる（他プレイヤーの召喚もこれで拾う）。8個目の1 tick後なので残り 169 tick。
- 両方来たら先に来た方を使う。カウントは `ServerTickClock` の server tick（同期前は実時間）。
- 終了: 予測地点から 24 ブロック以内に `ender_dragon` が追加されたとき（ログに実測遅延 `DRAGON_TIMER_SPAWNED ... error=` を出す）、または予定から 100 tick 過ぎたとき。

## 未確認

- 他プレイヤーが8個目を置いたときのチャット文面（開始Bで拾える想定）。
