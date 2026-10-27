# DragSimQol

Minecraft 26.1.2 向けの Fabric クライアントMOD。採掘・戦闘マクロと、アラート・表示まわりのQoL機能をまとめたもの。
サーバー側への導入は不要。

## 動作環境

- Minecraft 26.1.2
- Fabric Loader 0.19.3 以上 / Fabric API
- Java 25

## 機能

### Mining Bot（その場で採掘）

立ち止まったまま、届く範囲の対象ブロックを順番に向いて掘る。
対象: `Obsidian` / `Crying Obsidian` / `Large Amethyst Bud`（下向き・水没なしのみ）/ `Amethyst Block`。

- 開始/停止: `J` キー、または `/miningbot start` / `/miningbot stop`（旧名 `/obsidian` も使える）
- 対象ブロックが読み込まれていて、見えていて、遮られておらず、バニラのリーチ内にあるときだけ開始する
- 動作中は攻撃キーを押し続ける。マウスを動かしても視点は変わらず、停止すると元に戻る
- 人の手に近づける調整: 回転は滑らかな曲線で動き、ブロック内の照準のずれ（Aim Drift）や手ぶれ（Hand Tremor）を加えられる。
  視点の変化はマウス感度の刻みに丸める
- ドリルのアビリティと燃料補給の自動化、採掘数・効率を表示するHUD

### Route Walker（ルート巡回採掘）

保存したルートを周回しながら End Stone を掘る。

- 開始/停止: `H` キー
- ルート編集: GUI、または `/mining route add|remove|clear|list|show`（`/routewalker` も同じ）

### Combat（Zealot）

Zealot を探して近づき、攻撃する。剣と Frozen Scythe に対応。

- 開始/停止: `G` キー
- 照準・接近（A*経路探索）・攻撃はそれぞれ個別に ON/OFF できる。遠い相手には Aspect of the End でワープして近づく

### Auto Clicker

GUI の Combat ページで ON にすると、左クリックを押している間、最低〜最大 CPS の間でランダムな速さで連打する。

- クリックはバニラの入力経路に流すだけで、パケットを直接送ることはない
- 照準がブロックに当たっている間は止まる（Break Blocks、オフにもできる）。長押しでそのまま掘れる
- 他のマクロが動いている間は動かない

### アラート

マクロ動作中の異常をデスクトップ通知・画面の赤いフラッシュ・トーストで知らせる。

- 近くのプレイヤー、チャットでの名前の言及・DM
- スタッフチェックのメッセージ、マクロ中に開いたサーバーGUI
- サーバーによるTP・視点変更・スロット変更・手持ちアイテムの変更、ダメージなしのノックバック
- 近くに出現したエンティティ、周囲のブロックの入れ替わり、有害なエフェクト、被ダメージ、ワールド移動

`/spawnlog` で近くに出現したエンティティの記録を確認できる。

### 表示・その他

- ESP、マクロが狙っているブロックのハイライト
- フルブライト、ブロックを叩いた/壊したときのパーティクルを非表示にする設定
- 手動停止時に視点がふわっと止まる Stop Inertia
- 自分にだけ見えるマントと China Hat
- **録画モード**: MODが画面に何も描かなくなる（HUD・チャット・トースト・ハイライトなど）。マクロとアラートは動き続ける

## 操作

| 操作 | 既定キー |
| --- | --- |
| Combat マクロ ON/OFF | `G` |
| Route Walker ON/OFF | `H` |
| Mining Bot ON/OFF | `J` |
| 設定GUIを開く | なし（`/zealotgui` でも開ける） |
| 録画モード ON/OFF | なし |

キーはMinecraftの「操作設定」の「DragSim Macro」カテゴリで変更できる。
設定はすべてGUIから変更でき、`config/dragsimmacro*.json` に保存される。

## ビルド

JDK 25 が必要。

```bash
./gradlew build       # build/libs/ に jar ができる
./gradlew test        # ユニットテスト
./gradlew runClient   # 開発用クライアントを起動
```

できた jar を Fabric API と一緒に `.minecraft/mods/` に入れる。

## ライセンス

[CC0 1.0](LICENSE)。同梱しているフォントと参考にした実装は [NOTICE.md](NOTICE.md) を参照。
