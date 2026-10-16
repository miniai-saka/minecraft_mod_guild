# Guild Quests & Economy (Fabric 1.20.1)

Minecraft 1.20.1 / Fabric 向けの、クエスト・ギルド通貨・銀行・ギャンブルを単体で実装するModです。
FTB Quests、Q Shop 等への依存はありません。

バージョン: **26.8.0**

### Guild FX（26.8.0）

白背景の独自取引画面、ローソク足チャート、Mod専用仮想通貨のFXを追加しました。
**F8**、`/guild fx`、またはGキーのクエスト画面の「Guild FX」から開きます。
成行・指値・逆指値・OCO・IFD・IFO、最大1000倍、ゼロカット（追証なし）に対応します。
相場・暴落・急騰はサーバー内部で独自生成し、外部の価格APIは使用しません。
クライアントとサーバーに同じバージョンを導入してください。
操作・取引仕様・全設定項目は [FX.md](FX.md) を参照してください。

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
- 競馬（4頭、オッズ2〜8倍、自動レース演出）

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
- Fabric Loader 0.16.10 以上
- Fabric API 0.92.x+1.20.1
- Java 17
- （任意）Create mod — ストックティッカー連携を使う場合

## ビルド

ビルドにはJDK 21と同梱Gradle Wrapperを使用します。生成Modの動作要件はJava 17です。

```bash
./gradlew test build
```

生成物:

```text
build/libs/guildquests_fabric-1.20.1_26.8.0.jar
```

## 導入

1. Fabric Loader で Minecraft 1.20.1 を用意します。
2. `guildquests_fabric-1.20.1_26.8.0.jar` と Fabric API を `mods/` に配置します。
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
| `/guildadmin reload` | JSON設定を再読込 (OP Lv2) |
| `/guildadmin boss` | 緊急ボスを即時出現 (OP Lv2) |
| `/guildadmin coins set <player> <amount>` | コインを設定 (OP Lv2) |
| `/guildadmin coins add <player> <amount>` | コインを追加 (OP Lv2) |

## GUI構成

| 画面 | サイズ | 内容 |
|---|---|---|
| クエスト | 9×6 | 掲示板・受注中クエスト・銀行/送金/ギャンブルへのボタン |
| 銀行 | 9×3 | 預入/引出・数字頭で金額指定・支払い方法切替・リボ債務表示 |
| 送金 | 9×3 | オンラインプレイヤーから宛先選択・金額指定・送信 |
| ギャンブル | 9×3 | ブラックジャック/競馬へのハブ |
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
