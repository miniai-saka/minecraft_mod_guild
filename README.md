# Guild Quests & Economy (Fabric 1.20.1)

Minecraft 1.20.1 / Fabric 向けの、クエスト・電子通貨・ショップ・ギルドランクを単体で実装するModです。
FTB Quests、Q Shop 等への依存はありません。

## 実装済み機能

- クエストGUI (`/guild`)
- ショップGUI (`/guild shop`)
- 討伐クエスト (KILL)
- 納品クエスト (SUBMIT / アイテム消費)
- 探索クエスト (EXPLORE)
  - 座標 + 半径
  - バイオームID
  - 構造物ID
- 非アイテム型コイン
- 累計達成数によるランク表示とクエスト/商品解放
- プレイヤー別永続化
  - コイン
  - 受注中クエストと進捗
  - 累計達成数
  - 当日達成数
  - 当日獲得コイン
- 1日あたりクエスト達成上限
- 1日あたりコイン獲得上限
- 現実時刻 00:00 リセット / Minecraftゲーム日リセットの切替
- OP用設定リロード・コイン操作コマンド

## 必要環境

- Minecraft 1.20.1
- Fabric Loader 0.16.10 以上
- Fabric API 0.92.x+1.20.1
- Java 17

## ビルド

Gradle 8系を使用してください。

```bash
gradle build
```

生成物:

```text
build/libs/guildquests-1.0.0.jar
```

## 導入

1. Fabric Loader で Minecraft 1.20.1 サーバー/クライアントを用意します。
2. `guildquests-1.0.0.jar` と Fabric API を `mods/` に配置します。
3. 一度起動すると `config/guildquests/` が作成されます。
4. 設定変更後はサーバー再起動、または `/guildadmin reload` を実行します。

GUIはMinecraft標準の6段チェスト画面を利用しているため、専用テクスチャやクライアント専用Screenクラスは不要です。

## コマンド

- `/guild` - クエストGUIを開く
- `/guild shop` - ショップGUIを開く
- `/guild status` - 所持コイン、ランク、当日上限状況を表示
- `/guildadmin reload` - JSON設定を再読込 (OP Lv2)
- `/guildadmin coins set <player> <amount>` - コインを設定 (OP Lv2)
- `/guildadmin coins add <player> <amount>` - コインを追加 (OP Lv2)
- `/guildadmin resetdaily <player>` - 日次カウンタをリセット (OP Lv2)

## 設定ファイル

実行時に以下が自動生成されます。

```text
config/guildquests/config.json
config/guildquests/quests.json
config/guildquests/shop.json
```

`config-examples/` に編集例も同梱しています。

### config.json

主な項目:

- `maxActiveQuests`: 同時受注数。GUI都合で最大9。
- `dailyQuestLimit`: 1リセット期間あたりの達成上限。`0` で無制限。
- `dailyCoinCapEnabled`: 日次コイン上限のON/OFF。
- `dailyCoinCap`: 日次コイン上限。
- `dailyResetMode`: `REAL_TIME` または `GAME_DAY`。
- `resetTimezone`: `REAL_TIME` 時のIANAタイムゾーン。初期値 `Asia/Tokyo`。
- `coinCapPolicy`:
  - `BLOCK`: 報酬全額が上限を超える場合、そのクエストの報酬受領を翌リセットまでブロック。
  - `CLAMP`: 上限までの残額のみ付与してクエスト達成扱い。
- `exploreCheckIntervalTicks`: 探索判定間隔。初期値20tick。
- `rankThresholds`: ランク名と累計達成数の閾値。

### quests.json

共通項目:

```json
{
  "id": "unique_id",
  "title": "表示名",
  "description": "説明",
  "type": "KILL",
  "target": "minecraft:zombie",
  "amount": 20,
  "reward": 30,
  "requiredCompleted": 0,
  "repeatable": true
}
```

#### KILL

- `target`: Entity Type ID
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
  "radius": 32
}
```

#### EXPLORE / BIOME

```json
{
  "type": "EXPLORE",
  "exploreMode": "BIOME",
  "target": "minecraft:desert"
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

### shop.json

```json
{
  "id": "diamond",
  "title": "ダイヤモンド x1",
  "item": "minecraft:diamond",
  "count": 1,
  "price": 180,
  "requiredCompleted": 15
}
```

## データ保存

プレイヤーデータはOverworldのPersistentStateに保存され、ワールドの `data/guildquests_players.dat` として永続化されます。
UUIDをキーにしているため、ログアウト/再ログインやサーバー再起動後も維持されます。

## デフォルトバランス

- ゾンビ20体: 30コイン
- スケルトン20体: 40コイン
- 鉄インゴット32個納品: 50コイン
- 指定座標探索: 100コイン
- ウィザー1体: 300コイン（累計10件達成で解放）
- 日次達成上限: 5件
- 日次コイン上限: 500コイン

## 補足

討伐判定は「プレイヤー自身が直接キルしたEntity」を対象にします。自動トラップ等の経済対策は、仕様通り日次クエスト件数上限と日次獲得コイン上限で制御します。
