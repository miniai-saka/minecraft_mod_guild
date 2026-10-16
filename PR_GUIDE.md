# 友人のリポジトリへ提案する場合のPR手順と例

Forkを使って友人のリポジトリへ変更を提案する手順と、PR本文の例です。
作業ブランチは`feature/guild-fx`、取り込み先は`port/forge-1.20.1`です。

## 取り込んだ上流

- リポジトリ: https://github.com/takoyaki150141/minecraft_mod_guild
- 更新先: `port/forge-1.20.1`
- 取り込み時点: `9960829a656a5b5aca03019b31db436377b8d3ec` / 26.10.0
- 取得日: 2026-10-16（日本時間）
- `main`とは別に、新しいFabric/Forge共通構成がこのブランチへ公開されていたため、このブランチをpullしてマージしています。

## 友人へ提案するときの流れ

1. 取り込み先に`port/forge-1.20.1`を選びます。今回の差分はこのブランチを基準にレビューする構成です。
2. GitHubで元リポジトリを開き、右上の「Fork」から自分のアカウントへForkします。
3. 下記でローカルの送信先を自分のForkへ変更します。`YOUR_ACCOUNT`は自分のGitHub名に置き換えてください。コマンドはリポジトリのフォルダーで実行します。

```powershell
# 現在の保存状態と送信先を確認
 git status
 git branch --show-current
 git remote -v

# originが友人のリポジトリのままの場合だけ実行
 git remote rename origin upstream
 git remote add origin https://github.com/YOUR_ACCOUNT/minecraft_mod_guild.git

# originが自分のFork、upstreamが友人のリポジトリであることを確認
 git remote -v

# 自分のForkへだけ送信
 git push -u origin feature/guild-fx
```

4. 友人のリポジトリの「Pull requests」→「New pull request」→「compare across forks」を開きます。
5. **base repository**を`takoyaki150141/minecraft_mod_guild`、**base branch**を`port/forge-1.20.1`、**head fork**を自分のFork、**compare branch**を`feature/guild-fx`にします。
6. 「Files changed」でFXと必要な統合修正だけが差分に出ることを確認し、下のタイトル・本文例を入力します。
7. 相談しながら進める場合は「Create Draft Pull Request」で下書きにします。PRを作るだけでは友人のコードへマージされません。レビュー後のマージは友人に任せます。

Forkから変更ブランチを送信し、取り込み先のbaseと自分のcompareを選ぶ流れは[GitHub公式のPR手順](https://docs.github.com/en/pull-requests/how-tos/create-pull-requests/creating-a-pull-request-from-a-fork)に沿っています。Forkの作り方は[GitHub公式ガイド](https://docs.github.com/en/pull-requests/how-tos/work-with-forks/fork-a-repo?platform=windows&tool=webui)を参照してください。

## PRのタイトル例

26.10.0統合: 借入・滞納の管理チェストUIとFX入口・F8設定

## PRの本文例

ギルドコインを証拠金として、Mod内の架空通貨を取引できるGuild FXを追加します。相場はサーバー内部で生成し、外部の価格APIは使いません。

既定の画面は、暗転背景とMinecraft標準のボタンを使う簡易UIです。成行・指値・逆指値、建玉決済、注文取消、入出金に絞っています。「UI設定」から、ローソク足・OCO・IFD・IFO・部分決済・トレーリングに対応した白背景UIへ切り替えられます。

### 管理UIとFXへの入口

- `/guildadmin`で対象選択・数字入力・変更確認を行うチェストUIを追加。既存のloan add/set/clearとdelinquent on/offも同じ処理を利用。
- 管理者の固定ロックを保存し、完済後・再起動後も明示解除まで保持。借入とFX／リボ債務の残高を別表示。
- F8のFX起動は既定で無効。クライアントのUI設定・fx-client.jsonのhotkeyEnabledで変更。ギャンブル画面のエメラルドからは常に開ける。

### 取引と追証

- Mod専用のAUR/GC・EMB/GC・TID/GC。銘柄・変動率・暴落／急騰確率・手数料等は設定可能。
- 最大1000倍。サーバー側で数量・証拠金・注文条件を検証し、維持率によるロスカットを実行。
- 追証は既定で無効（ゼロカット）。`fx.json`の`marginCallEnabled`と`/guildadmin fx margincall true|false`で変更可能。コマンドはOPレベル2、変更をファイルへ保存。
- 有効時はロスカット後の不足を所持GCから徴収。不足分は既存の`debt`に加算して`delinquent`を立て、従来の滞納ロック・クエスト報酬充当・定期返済・利息へ接続。
- 両UIから債務を返済でき、完済すると取引を再開。モードを無効に戻しても発生済み債務は維持。

### 上流・保存・導入

`port/forge-1.20.1`の26.10.0（9960829）を統合し、共通コード・Fabric/Forge構成と、競馬等の修正を保持しています。
口座・相場・注文・追証は既存のギルド保存データと同じファイルに保存し、旧データの不足フィールドは既定値で読み込みます。
クライアントとサーバーは同じ版へ更新してください。FabricとForgeのJARはそれぞれ別ファイルです。

### 検証

- JDK17で取引・保存・追証の55件の回帰テスト。
- Fabric版・Forge版の製品JARのビルド。
- Fabric 1.20.1の専用ワールドで、実際のクライアント通信と画面操作を検証（結果は同梱の検証報告を参照）。
- 検証用Modとコマンドは配布JARに含めない構成。

設定の全項目、返済・滞納の仕様、ビルド方法は`FX.md`、設定例は`config-examples/`に記載しています。

## PR後に修正する場合

同じ`feature/guild-fx`で修正・コミットして自分のForkへpushすれば、そのPRの差分が更新されます。
友人側が進んだ場合は`git fetch upstream`後、`git merge upstream/port/forge-1.20.1`で取り込み、競合を解消して再検証してからpushします。
