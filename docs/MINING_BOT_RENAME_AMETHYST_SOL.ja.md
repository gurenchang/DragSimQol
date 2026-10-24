# Mining Botへの改称とAmethyst Block追加

2026-09-19。前回の対象切替実装への追加要件。実装は同じ5.6-solタスク・worktreeで続け、既存の未コミット変更を保持する。

## 要件

- 定点採掘の名称をObsidian Botから **Mining Bot** に変更する。UIカテゴリ・Controls表示・開始停止通知・排他エラー・コマンドstatus/help・新規ログ・現行README等の案内を揃える。ブロック名のObsidianはそのまま。過去ログ・履歴用の設計文書は書き換え不要。
- 正式コマンドは **`/miningbot`** とし、既存のstart/stop/status/target/rotationサブコマンドを移す。旧`/obsidian`は同じ処理への互換aliasとして残す。`/mining`と`/routewalker`はRoute Walkerなので意味を変更しない。
- 初期JとControlsで変更済みのキー割当を維持する。内部KeyMapping IDは互換性のため旧IDを維持してよい。クラスをrenameする場合もRoute WalkerのMining系と衝突しないMiningBot系に限定し、無関係な再設計は不要。
- `Target Block`に **Amethyst Block** (`minecraft:amethyst_block`) を3つ目として追加する。3種類から一つだけ選ぶ。これはBudding Amethystとは別ブロックであり、完全一致で選別する。
- 既存対象はObsidianと`large_amethyst_bud[facing=down,waterlogged=false]`のまま。芽の状態制約を新しいAmethyst Blockへ流用しない。対象別の判定をswitch等で明示し、旧二択三項演算子の取り残しを確認する。
- 保存値はamethyst_block。UIと`/miningbot target amethyst_block`は共通の変更経路を使う。変更時は自動Attack解除・停止・対象/待機/カメラ/ワールド参照破棄、明示操作で再開。
- Amethyst Blockの標準ツール適性を26.2データで確認する。採掘速度や再生の置換先・待ち時間は未知なので、新たな固定時間や1tick破壊前提を入れない。対象が変わったら解除し、実際に選択対象が戻るのを待つ。
- 新しい設定ファイル名は **dragsimmacro-miningbot.json**。既存のdragsimmacro-obsidian.jsonの対象・速度を引き継ぐ。新ファイルがあればそちらを優先し、無い場合だけ旧設定を読む。旧ファイルは削除せず、移行書込み失敗を成功扱いしない。Route Walkerの設定ファイルへ干渉しない。
- 英語UI/通知/ログ、日本語報告。標準採掘のみ、背景動作・停止条件・排他を維持。commit/push/reset/cleanやログ削除はしない。

## 検証

- 3種類の完全一致と相互除外、Budding Amethyst除外、芽のDOWN/非水没条件を確認。
- 新対象の保存復元、旧ファイルからの対象と速度の移行、新ファイル優先、旧ファイル保持を一時ディレクトリで検証。既存の変更時入力解除テストを維持する。
- /miningbotと旧aliasが同じ処理であり、/miningと/routewalkerを壊していないことを確認。名称の残存はUI/新規ログと互換用ID/旧ファイル/過去文書を区別して確認する。
- 全体テスト・build・git diff --check。ゲーム起動/採掘/再生/背景動作は未確認なら明示し、テスト成功と区別。
- 完了報告に変更ファイル、名称互換性、移行方針、ツール条件、検証結果、JAR絶対パス・サイズ・SHA256を含める。
