# Mining Bot: マウス視点の競合修正と開始時の対象確認

2026-09-19。実装は既存5.6-solタスク 01a0b815-25b9-7120-8bec-60cb7ebea370 のworktree C:\Users\guren\.codex\worktrees\04a3\DragSimQol。親タスクは設計・独立レビュー。既存変更を消さず、commit/pushはしない。

## ユーザー報告とコードでの確認

フォーカス中・Mining Bot ONでマウスを動かしても表示は固定なのに、採掘/送信側の向きが変わるとの報告。追加要望は、周囲に設定対象がなければBotを有効にしないこと。

MiningCameraMixinは表示カメラをtick snapshotから補間し、BotはEND_CLIENT_TICKでplayer角度を確定する。一方、通常のMouseHandler.turnPlayerからLocalPlayer.turnが実player角度を変える経路が残る。26.2のローカルバイトコードで、runTickのtick loop後にhandleAccumulatedMovement、turnPlayer内のoffset 292でLocalPlayer.turn(DD)V、handleAccumulatedMovementがその後accumulatedDX/DYをresetすることを確認。sendPositionは実getYRot/getXRotをRot/PosRotへ渡す。

実際のパケット捕捉は未実施。「サーバー検知」や不正パケットを断言しない。角度とXYZ位置、描画補間の時刻差と非表示の物理入力混入を区別する。

## マウス入力

- 有効なMining Botセッション中だけ通常マウスから実player角度への書込みを抑止する。全有効Stateで適用し、停止条件を満たせば所有を解除する。
- delta消費/reset、GUIカーソル、mouse button、scroll/hotbarは標準経路を残す。smoothCameraのSmoothDouble残量も抑止中に捨て、停止後に蓄積分を再生しない。
- Bot OFF、GUI、死亡/切断/持ち替え/ワールド変更等では通常操作へ戻す。Combat/Route Walkerへ適用範囲を広げない。
- tick側の角度を照準・標準送信の基準として維持。renderでplayer角度を書いたり、packetだけ別の角度にしたり、独自packetを追加しない。
- 必要最小限のclient Mixinを26.2実バイトコードに合わせて登録。Mixin追加変更を正確に報告。

## 開始条件

- 共通start()で既存scan/candidateForを用い、標準リーチ内・ロード済み・可視・遮蔽なし・正確な設定BlockStateを満たす対象が1つ以上ある場合だけ開始する。
- 適正ツール条件を維持し、探索は開始時の実player角度を使う。拒否時は入力・camera snapshot・排他ownerを取得しない。
- 英語通知/ログで選択対象とno reachable targetを明示。J/コマンド/GUIの全開始経路で共通処理を使う。
- 稼働後の採掘で対象が一時的に全消失した場合は停止せず、既存の復活待ちとAttack保持を継続する。
- 3対象と芽のfacing=down,waterlogged=false、Aim Drift、設定対象外への継続採掘は維持。

## 検証

関連する純粋な入力所有/開始処理/State遷移をテストする。対象なし拒否時の副作用、対象あり開始、待機中保持、マウス解除/平滑残量を確認し、実ゲーム統合の証明としない。全buildとdiff --check、成果JARの絶対パス/size/SHA256を確認する。
## 完了時の独立確認

- 既存実装タスク最終turn 01a0b91c-06ca-7221-967a-36924094e680 はcompleted。
- MiningBotMouseHandlerMixinをclient限定登録。LocalPlayer.turn呼出しだけを切り替え、抑止中は両SmoothDoubleをresetする。既存のhandleAccumulatedMovementによるraw delta resetを維持。
- startの初回scanから候補がなければ入力/owner/camera公開前に拒否。候補あり時はそのリストの先頭を再利用。稼働後の空候補はWAITING_REGEN。
- validateOrStopをtick・入力消費・マウスの共通検証へ使用。
- 親でもgradlew.bat build --no-daemon --console=plainを実行しBUILD SUCCESSFUL（全8タスクUP-TO-DATE）。直前のテストXMLは145件、failure/error/skipped全0。親がcleanTestで全テストを再実行したという意味ではない。
- git diff --check成功。ローカル26.2バイトコード・コード・成果物に含まれるMixinクラスと登録を確認。
- 最終導入用JAR: C:\Users\guren\.codex\worktrees\04a3\DragSimQol\build\libs\dragsimmacro-1.5.0.jar
- サイズ417761 bytes、生成2026-09-19 19:09:44 JST。
- SHA256: EBAD545224ED0B309249C518515506BF77D96A0A930489DC8325DE3490F2C559
- 実ゲームMixin適用、物理マウス操作、送信packet捕捉、Alt+Tab/最小化、他MODとの併用は未確認。commit/pushなし。元workspaceへ未統合。