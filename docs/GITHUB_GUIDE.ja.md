# 自分版 minecraftdrag の開発ガイド

## この作業環境

| 名前 | 場所・役割 |
| --- | --- |
| 自分のFork | https://github.com/gurenchang/minecraftdrag （非公開） |
| 元リポジトリ | https://github.com/VaLoNIKI/minecraftdrag （非公開） |
| PC上のフォルダ | `C:\Users\guren\Documents\DragSimQol` |
| `origin` | 自分のFork。通常の保存先 |
| `upstream` | 元リポジトリ。更新を取り込むための参照先 |
| `main` | 自分のForkの標準ブランチ |
| `guren/qol` | 自分用の改修ブランチ。普段はここで作業する |

初期のコードと履歴は、元の `main` のコミット
`41b457b1759806eb55db4cfbcd81590abcfd7d31` から引き継いでいます。
ゲームの機能は、このセットアップでは変更していません。

このPCでは、誤操作対策として `upstream` のpush先を
`disabled://upstream-read-only` にしています。元から取得する操作は可能です。
この設定はPC内の設定なので、別のPCへcloneした場合は引き継がれません。

## 最初に覚える言葉

- **Fork**: GitHub上で、自分のアカウントに派生リポジトリを作ること。
- **Clone**: GitHubのコードとGitの履歴をPCへ取得すること。
- **Branch（ブランチ）**: 同じリポジトリ内に別の作業の流れを作ること。
- **Commit**: PC上で変更を説明付きの履歴に保存すること。
- **Push**: PC上のコミットをGitHubに送ること。
- **Pull Request（PR）**: あるブランチの変更を別のブランチへ取り込むための提案。
- **Merge**: 変更を取り込むこと。

ファイルを保存しただけではGitHubには反映されません。
「ファイル保存 → commit → push」の順で反映します。
Forkやブランチにpushしても、元リポジトリの `main` は変更されません。

## 普段の進め方

PowerShellでこのフォルダを開きます。

```powershell
Set-Location 'C:\Users\guren\Documents\DragSimQol'
git status
```

`On branch guren/qol` と表示されることを確認してから編集します。
編集後は、以下で変更内容を確認します。

```powershell
git diff
git status --short
```

記録するファイルを指定し、差分を再確認してcommitします。
次はREADMEだけを変更した場合の例です。ファイル名と説明は実際の変更に合わせます。

```powershell
git add -- README.md
git diff --cached
git commit -m "docs: READMEの説明を更新"
git push
```

`git add` はcommitの対象を選ぶ操作です。未追加の変更はcommitされません。
パスワード、トークン、個人用設定を追加しないよう、差分を確認してください。
`git push` の保存先は `origin`、つまり自分のForkです。

別のPCやGitHubの画面でも同じブランチを編集した場合は、PC側に未保存の変更がない状態で
`git pull --ff-only` を実行してから作業します。エラーが出たら、force pushで解決せず状況を確認します。

## 自分のmainに反映したいとき

1. 自分のForkの「Pull requests」からPRを作成します。
2. **base repositoryが `gurenchang/minecraftdrag`、baseが `main`** であることを確認します。
3. compareに `guren/qol` を選び、差分とビルド結果を確認します。
4. 自分の `main` へ取り込む準備ができたらmergeします。

ForkからPRを作る画面では、元リポジトリが送信先として選ばれることがあります。
自分版の変更を元の開発者へ提案したい場合だけ、base repositoryを `VaLoNIKI/minecraftdrag` にします。

## 元の開発者の更新を取り込みたいとき

まず自分の変更をcommitし、`git status` で作業中の変更がないことを確認します。

```powershell
git switch guren/qol
git fetch upstream
git log --oneline HEAD..upstream/main
git merge upstream/main
```

変更がぶつかると「conflict（競合）」になります。両方の変更内容を確認して解決する必要があります。
判断できない場合は `git merge --abort` で今回のmergeを取り消し、エラー内容を添えて相談してください。
mergeが完了したら、ビルドと必要な動作確認を行い、`git push` で自分のForkに保存します。

## このPCでビルドする

対象は **Minecraft 26.2 / Fabric / Java 25** です。
このPCで通常の `java` コマンドが使うJava 8ではビルドできません。
プロジェクト専用に、公式Eclipse AdoptiumのJDK 25を `.tools` 内へ配置しています。
Windows全体のJava設定は変更していません。

プロジェクトのフォルダで、PowerShellから実行します。

```powershell
$env:JAVA_HOME = (Get-Content .tools\java-home.txt -Raw).Trim()
.\gradlew.bat build --no-daemon
```

完成したModは `build\libs\` の、名前が `-sources.jar` で終わらないJARです。
ビルド成功はMinecraft内での動作保証ではありません。実際の機能はゲーム内で別途確認します。

`.tools` と `build` はGitHubへ送信しません。
別のPCではJDK 25を公式配布元から用意し、そのJDKのフォルダを `JAVA_HOME` に指定します。
Gradleは同梱の `gradlew.bat` が取得します。

## 非公開Forkの制約

このForkは非公開です。一般公開された独立リポジトリではありません。
非公開Forkのアクセス権は元リポジトリに連動し、元へのアクセスを失うとForkにも影響します。
完全に独立した権限管理が必要になった場合は、所有者と扱いを確認したうえで、
Fork関係を持たない別の非公開リポジトリを検討してください。

Forkはコードと履歴の派生を作る機能です。元のIssues、PR、Secrets、権限設定、
GitHub Actionsの実行履歴などを丸ごと移す機能ではありません。

元の `LICENSE` と `NOTICE.md` は維持しています。

参考:

- https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/working-with-forks/about-permissions-and-visibility-of-forks
- https://docs.github.com/en/get-started/start-your-journey/hello-world
