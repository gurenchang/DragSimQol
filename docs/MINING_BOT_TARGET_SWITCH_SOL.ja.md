# 5.6-sol 追加実装依頼: 定点採掘Botの対象切替

2026-09-19。既存の「Route Walkerの改称とObsidian Botの実装」タスクに対する追加依頼。
ユーザーは初版をまだ実ゲームで確認していない。既存作業を保持して、同じworktreeで続ける。

## 確定要件

- 定点採掘Botの対象を設定で `minecraft:obsidian` と `minecraft:large_amethyst_bud` のどちらか一つに切り替えられるようにする。
- 追加情報: ユーザー環境の芽は `minecraft:large_amethyst_bud[facing=down,waterlogged=false]` の想定。この具体的状態をアメジストの対象条件とし、他方向・水没は掘らない。想定と実際の状態が違う場合も条件を自動で緩めない。
- Route Walkerとは別機能のまま。移動しない、調整可能な滑らかな視点移動、フォーカス喪失だけでは止めない方針を維持する。
- 実装担当は5.6-sol、元タスクは設計・レビュー。日本語で報告、MODのUI・通知・ログは英語。
- 既存の未コミット変更を保持。commit/push、reset/clean、logs削除はしない。

## 設計・受入条件

1. 既存Bot設定画面に `Target Block` を追加し、`Obsidian` / `Large Amethyst Bud` を選択できるようにする。既存部品・デザインを使う。設定ファイル名・キー割当・コマンド互換性を維持し、大規模renameは不要。UI説明とstatusには選択中の種類が分かる表示を入れる。
2. 選択を `config/dragsimmacro-obsidian.json` に保存する。古い設定に項目がない場合はObsidianを採用。未知の値の読み込みは警告と既定値、操作時の不正値は変更せずエラーなど、一貫した方針にする。既存の旋回速度を維持し、rotation resetで対象が戻らないようにする。
3. UIと `/obsidian target obsidian|large_amethyst_bud` 等のコマンドは共通の変更経路を使う。種類の変更時は最初に自動Attackを解除して停止し、対象・待機座標・セッション・カメラ状態を破棄。再開は明示操作。保存失敗を成功扱いしない。
4. 探索、対象保持、再生待機、開始条件、毎tickおよびAttack消費直前の検証をすべて選択対象に統一する。アメジストはDOWNかつwaterlogged=falseを同一のmatches経路で判定する。選んでいない種類や別の成長段階は掘らない。ブロック種類・状態条件と現在の対象座標をstatusで区別する。
5. 黒曜石の既存の岩盤→復活動作を保持。Large Amethyst Budの置換先・復活時間は未知なので、空気・岩盤・別ブロックになったらAttackを解除し、実際に選択種類が戻ったことと可視・射程を確認して再開する。固定タイマーでの採掘、置換ブロックの採掘、1tick破壊前提は導入しない。
6. Budはフルキューブではない。現行candidateForのブロック中心±0.501だけでは不十分。実際のBlockStateの選択形状を基に照準点を選び、vanillaのpickと一致するraycastで可視・到達を確認する。6方向のFACING、水没、遮蔽、支持ブロックへの誤採掘、保存済み照準点の再評価を考慮する。Small/Medium Bud、Amethyst Cluster、Budding Amethystは対象外。
7. ツール判定は選択対象の標準特性を確認して決定する。Obsidian用のダイヤ相当制約をBudに流用しない。一方、任意のアイテムや素手を「適正ツール」とみなさず、標準の採掘適性を持つツールを許可する。ユーザーはSilk Touchやドロップ回収条件を要求していないので新設しない。サーバー固有ツールが標準情報では判定できない場合は、その制限を報告する。
8. 標準採掘・KeyMappingを使用し、独自パケット・OS入力・採掘ディレイ短縮・直接破壊との二重処理を追加しない。GUI/死亡/切断/持ち替え解除、Route Walker/Combatとの排他、Route Walkerのチャット中ノード表示を保持する。

## 検証と報告

- 既存テストとbuild、git diff --checkを実施する。選択切替・旧設定・保存復元・不正値・選択外対象の除外・変更時の入力解除を変更規模に応じて検証する。
- Budの各方向・実形状と遮蔽・支持ブロック誤採掘について、可能な自動検証を行う。MC実行が必要で自動確認できない項目はテスト成功へ含めず、実機未確認として列挙する。MC非依存の模倣テストだけで実際の形状やraycastを検証済みとしない。
- 実matches経路で6方向×waterlogged true/falseの12状態を確認し、DOWN/falseだけが対象になることを検証する。形状取得の汎用コードは維持してよい。
- フォーカス喪失とtick/pick更新は初版で実機未確認。今回の変更で保証が強まっていない限り保証済みと報告しない。関連不具合を見つけたら具体的に共有する。
- 完了時に変更ファイル、対象ごとのツール条件、設定操作、テスト件数、JARの絶対パス・サイズ・SHA256、実機未確認項目を報告する。元workspaceの古いJARとworktreeの成果物を混同しない。
- JDKは元workspaceの `C:\Users\guren\Documents\DragSimQol\.tools\java-home.txt` を利用可能。既知のjava.security AccessDeniedなら同じビルドの権限調整で対応し、JDK再インストールはしない。
