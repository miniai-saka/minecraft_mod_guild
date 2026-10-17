# Fabric サーバー自動更新


対象は Minecraft 1.20.1 / Fabric の専用サーバーです。

公開版 v26.11.1 を基にした v26.12.0 です。既存の FX・銀行・ギャンブル・管理 GUI 等の機能を含みます。
初回はサーバーを終了し、既存の GuildQuests JAR を v26.12.0 に置き換えてください。


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


`/guildadmin update status` で状態・更新予定（UTC）を確認でき、`/guildadmin update check` で有効時に即時確認できます（OP Lv2）。
設定は `/guildadmin reload` または管理 GUI の設定再読込で反映します。
初回の設定ファイルが壊れている場合は自動更新を無効のままにし、再読込時の不正な設定は直前の有効な設定を保持します。
