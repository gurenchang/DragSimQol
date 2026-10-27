# Notices

## Epsilon (Apache-2.0)

`CornerEspOverlay` のスクリーン投影手法(world→screen の8隅矩形投影・z∈[0,1]カリング)は
以下のOSS実装を参考に、本プロジェクトのMinecraft 26.1.2 / Fabric API 向けに再実装したものです。

- Epsilon — https://github.com/XTGY/Epsilon (branch: 26.1.x)
- License: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0)

該当ファイル:
- `common/src/main/java/com/github/epsilon/utils/render/WorldToScreen.java`

Apache-2.0 の条件に従い、本リポジトリで当該ライブラリ由来コードを改変・頒布する場合は
本NOTICEとライセンス表示を保持してください。

## Oxanium (SIL Open Font License 1.1)

GUIフォントとして Oxanium SemiBold(static) を使用しています。

- Font: Oxanium SemiBold (`Oxanium-SemiBold.ttf`、static instance)
  - OS/2 usWeightClass = 600(static、fvarテーブル無し)を実バイト確認済み
- Author: The Oxanium Project Authors — Sebastian Burkhard
- Source: https://github.com/sevmeyer/oxanium (公式upstream)
          `fonts/ttf/Oxanium-SemiBold.ttf`(master)
          (Google Fontsの公式収録: https://github.com/google/fonts/tree/main/ofl/oxanium は可変フォントのみのため、
           weight 600の静的フォントとして公式upstreamのstatic TTFを使用)
- License: SIL Open Font License 1.1 — `src/main/resources/assets/dragsimmacro/font/OFL.txt`
  (upstreamリポジトリのOFLライセンス表記に基づく)
- 使用箇所: `assets/dragsimmacro/font/ui.json` (`dragsimmacro:ui` font)、`assets/dragsimmacro/font/oxanium-semibold.ttf`
- OFL 1.1 により、本フォントの頒布・改変(フォントとしての改変)はライセンス条項に従う限り許諾されます。
  フォントファイル単体の再販・単体販売は禁止されています。フォント名を作品名等へ使用する場合は
  Reserved Font Name の規定を確認してください。

## その他

- Meteor Client (GPL-3.0) は構造調査のみでコードは一切コピー・改変していない。
- `Xenith-NextGen` (SparkyBlast) は公開リポジトリとして存在しなかった(404)。
