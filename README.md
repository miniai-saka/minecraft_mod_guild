# Guild Quests & Economy (Fabric / Forge 1.20.1)

Minecraft 1.20.1 / Fabric・Forge 向けの、クエスト・ギルド通貨・銀行・ギャンブルを単体で実装するModです。
FTB Quests、Q Shop 等への依存はありません。

バージョン: **26.12.0**（公開版 v26.11.1 を基に Fabric サーバー自動更新を追加）

### Guild FX（26.11.0）

暗転背景とMinecraft標準ボタンの簡易UIを既定に、Mod専用仮想通貨のFXを追加しました。
成行・指値・逆指値に絞った簡易取引と、ローソク足付きの白背景ターミナルを「UI設定」で切り替えられます。
Gキーのクエスト画面 →「ギャンブル」→「Guild FX」、または`/guild fx`で開きます。F8は既定で無効で、FXの「UI設定」から有効化できます。
詳細UIは成行・指値・逆指値・OCO・IFD・IFO、最大1000倍に対応します。
追証は既定で無効（ゼロカット）。設定と`/guildadmin fx margincall true|false`で変更できます。
追証有効時の未払いは、既存の債務・定期返済・滞納ロック・クエスト報酬充当へ接続します。
相場・暴落・急騰はサーバー内部で独自生成し、外部の価格APIは使用しません。
クライアントとサーバーに同じバージョンを導入してください。
操作・取引仕様・全設定項目は [FX.md](FX.md) を参照してください。
管理者の借入・滞納チェストUIは、引数なしの`/guildadmin`から開けます。操作方法は [ADMIN.md](ADMIN.md) を参照してください。
友人のリポジトリへのPR手順と記入例は [PR_GUIDE.md](PR_GUIDE.md) にあります。

上流の`port/forge-1.20.1`、`9960829`（26.10.0）をpullして統合しています。
26.10.0のランク別借入・採掘返済、26.9.0の収集クエスト・クールダウン、共通コード・Fabric/Forge構成と既存FXを保持しています。

## 機能

### クエスト
- 日次クエスト掲示板（毎日自動抽選、1〜9問）
- 討伐クエスト (KILL)
- 納品クエスト (SUBMIT / アイテム消費)
- 探索クエスト (EXPLORE)
  - 座標 + 半径（ランダム座標対応）
  - バイオーム（複数指定・ランダム選択対応）
  - 構造物
- 累計達成数によるランク（E → S）とクエスト解放

### 通貨・経済
- 非アイテム型ギルドコイン
- 銀行（預け入れ・引き出し、手数料制度）
- プレイヤー間送金（手数料無料）
- 支払い方法: 引き落とし / リボ（後払い） / 無効
- リボ払い: 定期自動徴収、利息加算、延滞でギルド機能ロック、クエスト報酬で自動返済
- 入金ブロック（ホッパー対応、コインを自動で残高へ換金）
- 銀行ブロック（手数料無料で銀行を開く）

### ギャンブル
- ブラックジャック（手取り21、バースト、自然なBJは2.5倍）
- 競馬（上流26.8.2のオッズ・期待値修正を保持）
- チンチロリン、コイントス、ルーレット、スロット、ランキング

### オークション（プレイヤー間マーケット）
- グローバルGUIマーケット（サーバー側でアイテムをエスクロー保管）
- 出品時に「固定価格」または「入札式オークション」を選択
- 固定価格: 買い手が即購入
- 入札式: 開始価格から入札、期限到来で最高入札者が落札
- 入札は残高から即時引き落とし、更新時は前の入札者へ自動返金
- 落札品・期限切れ品・取消品は「受取ボックス」に届き、GUIから受け取り
- 売却成立時に出品者へ手数料を差し引いて残高入金
- 出品一覧はページ送り、自分の出品はキャンセル可能

### 緊急ボスイベント
- オンライン時に一定間隔で確率発生
- 強化されたホストMobが出現（強制グラウ効果・专用品質装備）
- 与ダメージに応じた報酬分配
- 環境ダメージによる撃破は無効（対策済み）
- クライアント側にボス位置ピン表示

### その他
- プレイヤー別永続化（コイン・クエスト進捗・累計達成数・日次カウンタ）
- 現実時刻 00:00 リセット / Minecraftゲーム日リセットの切替
- GキーでクエストGUIを即時オープン
- Create連携（ストックティッカーの支払いをギルドコインで自動決済）

## 必要環境

- Minecraft 1.20.1
- Fabric: Fabric Loader 0.16.10以上、Fabric API 0.92.x+1.20.1
- Forge: Forge 47.2.0（1.20.1）
- Java 17
- （任意）Create mod — ストックティッカー連携を使う場合

## ビルド

ビルドにはJDK 21と同梱Gradle Wrapperを使用します。生成Modの動作要件はJava 17です。

```bash
./gradlew :common:test :fabric:build :forge:build
```

生成物:

```text
fabric/build/libs/guildquests_fabric-1.20.1_26.12.0.jar
forge/build/libs/guildquests_forge-1.20.1_26.12.0.jar
```

## 導入

1. FabricまたはForgeでMinecraft 1.20.1を用意します。
2. ローダーに合う26.12.0 JARを`mods/`へ配置します。FabricではFabric APIも必要です。ワールドと設定を保存し、旧GuildQuests JARは重複させず置き換えます。
3. 一度起動すると `config/guildquests/` が作成されます。
4. 設定変更後はサーバー再起動、または `/guildadmin reload` を実行します。

クエスト・銀行等はMinecraft標準のチェスト画面、FXは専用のクライアント画面を使用します。

## コマンド

| コマンド | 説明 |
|---|---|
| `/guild` | クエストGUIを開く（Gキーでも可） |
| `/guild status` | ランク・コイン・累計達成・支払い方法・債務を表示 |
| `/guild bank` | 銀行GUIを開く |
| `/guild auction` | オークション市場GUIを開く |
| `/guild fx` | FXを開く（F8は任意で有効化） |
| `/guildadmin fx margincall [true\|false]` | 追証の確認・変更・保存（OP Lv2） |
| `/guildadmin fx reload` | FX設定を再読込（OP Lv2） |
| `/guildadmin reload` | JSON設定を再読込 (OP Lv2) |
| `/guildadmin` | 借入・滞納の管理チェストUI（OP Lv2） |
| `/guildadmin loan add\|set <player> <amount>` | 借入の加算・指定額設定（OP Lv2） |
| `/guildadmin loan clear <player>` | 借入を帳消し（OP Lv2） |
| `/guildadmin delinquent <player> on\|off` | 管理者による滞納ロック・解除（OP Lv2） |
| `/guildadmin boss` | 緊急ボスを即時出現 (OP Lv2) |
| `/guildadmin coins set <player> <amount>` | コインを設定 (OP Lv2) |
| `/guildadmin coins add <player> <amount>` | コインを追加 (OP Lv2) |

## GUI構成

| 画面 | サイズ | 内容 |
|---|---|---|
| クエスト | 9×6 | 掲示板・受注中クエスト・銀行/送金/ギャンブルへのボタン |
| 銀行 | 9×3 | 預入/引出・数字頭で金額指定・支払い方法切替・リボ債務表示 |
| 送金 | 9×3 | オンラインプレイヤーから宛先選択・金額指定・送信 |
| ギャンブル | 9×3 | 各ギャンブルとGuild FXへのハブ |
| ギルド管理 | 9×6 | プレイヤー選択・借入・滞納・数字入力・変更確認 |
| ブラックジャック | 9×3 | ヒット/スタンド・賭け金設定 |
| 競馬 | 9×6 | 馬選択・賭け・レーン走行演出・結果表示 |
| オークション | 9×6 | 出品一覧（ページ送り）・購入・入札額設定 |
| 出品 | 9×3 | 手に持ったアイテムの形式/価格設定・出品 |
| 自分の出品/受取 | 9×6 | 出品取消・受取ボックスからのアイテム受け取り |
| 入金ブロック設定 | 9×1 | 通知トグル・一括入金 |

## 設定ファイル

初回起動時に `config/guildquests/` へ自動生成されます。

```text
config/guildquests/config.json
config/guildquests/quests.json
config/guildquests/server-update.json  # Fabric 専用サーバー
```

### config.json

主な項目:

- `maxActiveQuests`: 同時受注数（1〜9）
- `dailyQuestCountMin` / `dailyQuestCountMax`: 日次掲示板の出題数範囲
- `exploreCheckIntervalTicks`: 探索判定間隔（初期値20）
- `exploreRandomRange`: ランダム座標の探索範囲
- `bossEventEnabled`: 緊急ボスイベントのON/OFF
- `bossEventIntervalMinutes`: ボス出現判定間隔（分）
- `bossEventChancePercent`: ボス出現確率（%）
- `bossEventReward`: ボス撃破報酬（コイン）
- `bossEventHealth`: ボスの体力
- `bankFeePercent`: 銀行手数料（%）
- `riboInstallment`: リボの1回あたり徴収額
- `riboInterestPercent`: リボの周期利息（%）
- `riboIntervalMinutes`: リボ徴収間隔（分）
- `auctionFeePercent`: オークション売却時の手数料（%）
- `auctionDurationMinutes`: 出品が期限切れになるまでの時間（分）
- `auctionMaxListingsPerPlayer`: 1人あたりの同時出品上限
- `auctionBidStep`: 入札時に上乗せが必要な最低単位
- `rankThresholds`: ランク名と累計達成数の閾値
- `createIntegrationEnabled`: Create連携のON/OFF

### quests.json

共通項目:

```json
{
  "id": "unique_id",
  "title": "表示名",
  "description": "説明",
  "type": "KILL",
  "target": "minecraft:zombie",
  "targets": ["minecraft:zombie", "minecraft:skeleton"],
  "amount": 20,
  "amountMin": 10,
  "amountMax": 30,
  "reward": 30,
  "requiredCompleted": 0,
  "repeatable": true
}
```

`targets` を指定すると抽選時にランダム選択されます。`amountMin` / `amountMax` も同様です。

#### KILL

- `target` / `targets`: Entity Type ID
- `amount`: 討伐数

#### SUBMIT

- `target`: Item ID
- `amount`: 納品数
- GUIの受注中クエストを左クリックすると、所持分から必要数まで消費します。

#### EXPLORE / COORDINATE

```json
{
  "type": "EXPLORE",
  "exploreMode": "COORDINATE",
  "dimension": "minecraft:overworld",
  "x": 1000,
  "y": 64,
  "z": 1000,
  "radius": 32,
  "randomCoordinates": true,
  "coordRange": 3000
}
```

`randomCoordinates` を true にすると `coordRange` 範囲内で座標が抽選されます。

#### EXPLORE / BIOME

```json
{
  "type": "EXPLORE",
  "exploreMode": "BIOME",
  "target": "minecraft:desert",
  "biomes": ["minecraft:desert", "minecraft:badlands"]
}
```

#### EXPLORE / STRUCTURE

```json
{
  "type": "EXPLORE",
  "exploreMode": "STRUCTURE",
  "target": "minecraft:fortress"
}
```

STRUCTUREはプレイヤーの現在座標が対象構造物の内部に入った時点で達成になります。

## データ保存

プレイヤーデータはOverworldのPersistentStateに保存され、ワールドの `data/guildquests_players.dat` として永続化されます。
UUIDをキーにしているため、ログアウト/再ログインやサーバー再起動後も維持されます。

## 補足

- 討伐判定は「プレイヤー自身が直接キルしたEntity」を対象にします。自動トラップ等の経済対策は、日次クエスト件数上限と日次獲得コイン上限で制御します。
- リボで延滞すると銀行・送金・入金ブロックがロックされます。クエスト報酬は自動で債務返済に充当されます。オークションの購入・入札も延滞中はロックされます（出品は可能）。
- オークションの出品アイテムはサーバー側（ワールドデータ）にエスクロー保存されます。落札品・返却品は各プレイヤーの受取ボックスに届きます。
- ボスの環境ダメージ（溶岩・落下等）による撃破は無効です。対策として落下保護と脱出テレポートが入っています。

## Fabric サーバーの自動更新

対象は Minecraft 1.20.1 / Fabric の専用サーバーです。


Fabric 専用サーバーで `config/guildquests/server-update.json` を自動生成します。
既存の設定ファイルへの項目追加は不要です。設定例は `config-examples/server-update.json` にあります。
編集後に `/guildadmin reload` を実行すると、待機中の更新にも新しい設定が反映されます。
シングルプレイや LAN 公開ワールドの統合サーバーは自動停止しません。

| 設定 | 初期値 | 説明 |
|---|---|---|
| `enabled` | `true` | サーバー自動更新の有効・無効 |
| `checkOnStartup` | `true` | 起動・設定再読込の直後に更新確認。`false` の場合は確認間隔経過後 |
| `checkIntervalMinutes` | `30` | 定期確認の間隔（1〜10080分） |
| `updateDelayMinutes` | `30` | 最初の検出からダウンロード開始までの待機期間（0〜525600分）。`0` は即時 |
| `retryIntervalMinutes` | `5` | 確認・ダウンロード・適用準備に失敗した際の再試行間隔（1〜1440分） |
| `shutdownAfterDownload` | `true` | 検証完了後、ワールドを保存してサーバーを正常停止。`false` は次の手動終了後に適用 |
| `notifyPlayers` | `true` | 検出・カウントダウン・更新準備完了をチャット通知。ログは常に出力 |
| `countdownSeconds` | `[300,60,30,10,5,4,3,2,1]` | ダウンロード開始前に通知する残り秒数。空配列でカウントダウン無効 |
| `connectTimeoutSeconds` | `10` | 接続タイムアウト（1〜120秒） |
| `requestTimeoutSeconds` | `120` | 確認・ダウンロード要求のタイムアウト（1〜600秒） |
| `releaseApiUrl` | GitHub Releases API | 更新確認先。通常は変更不要。HTTPS またはローカル試験用の loopback HTTP |

時間は実時間で計測し、TPS やプレイヤー数に左右されません。
検出時刻は `config/guildquests/update/detected-update.json` に保存するため、待機中の再起動でも期限を引き継ぎます。
繰り返しの確認や待機中にさらに新しいバージョンが公開された場合も、最初の検出時刻から数えます。
待機期間の変更は、その最初の検出時刻を基準に反映します。
更新を無効にすると待機と停止を中断し、未実行の適用予約を解除します。再度有効にした場合は保存した検出時刻を使用します。

### 自動で起動し直す方法（Windows）

`start-server.ps1` を Fabric のサーバー JAR と同じフォルダーに配置し、次のように起動します。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-server.ps1
```

サーバー JAR 名やメモリを変更する場合:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-server.ps1 -ServerJar fabric-server-launch.jar -MinMemory 1G -MaxMemory 4G
```

スクリプトは更新のために停止した場合だけ、新 JAR の適用完了を待って自動起動します。
`stop` コマンドによる終了やクラッシュでは自動起動しません。Linux は `sh start-server.sh` を利用できます。
既存のサーバー管理サービスから起動する場合も、必ず新 JAR の適用完了後に Fabric を起動してください。
通常の `java -jar ...` 起動では、更新ファイルの差し替え後に手動で起動し直す必要があります。

### 更新ファイルと復旧

公開された安定版 GitHub Release の `guildquests_fabric-1.20.1_<数値バージョン>.jar` のみを対象とします。
ドラフト・プレリリース・他ローダー・他 Minecraft バージョンのファイルは適用しません。
GitHub に Release/JAR がない場合や現在と同じ・古い版の場合は更新しません。
API のエラー・タイムアウト時はサーバーを稼働させたまま再試行します。

ダウンロードは `mods` 外の `config/guildquests/update/` で行い、サイズ、提供されている場合の SHA-256、
JAR 全エントリーの CRC、Mod ID・バージョン・サーバー対応・Minecraft/Java/Fabric Loader の依存条件を検証します。
失敗した部分ファイルは削除し、稼働中の JAR は保持します。
検証後は Java 17 の独立ヘルパーが元の Java プロセスの終了を待つため、Windows の JAR ロックにも対応します。
現在の JAR だけを `config/guildquests/update/backups/` にバックアップして差し替えます。
ワールドや他の Mod は置き換えません。バックアップは自動削除しません。

適用中に失敗した場合、元の JAR を可能な限り復元し、`pending-install.properties` を残します。
`start-server.ps1` は未完了の適用を先に再試行し、解決できない場合は再起動を止めます。
詳細は `config/guildquests/update/installer.log` を確認してください。
手動で元の版へ戻す場合はサーバーを終了し、現在の GuildQuests JAR を取り除いて、バックアップを `.jar` に戻して `mods/` に配置します。
未完了の更新予約がある場合は `pending-install.properties` と `restart.requested` を退避してから起動してください。
