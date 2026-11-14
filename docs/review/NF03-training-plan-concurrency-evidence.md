# NF03学習計画完了判定のMySQL競合検証記録

## 対象変更

- 先行する完了判定修正: `5facee224d4e59dad15981d29b3accb8b89c9661`
- MySQL実競合回帰とshard登録: `1d8c3ba433dedf95a00d4802be990ee1fb36fc9b`
- branch: `codex/snf-prod-nf03-training`

今回の追補はテストとshard一覧だけで、migration、pom、共通schema、本番serviceコードは変更していない。shard一覧の登録はshard-2に1件だけであり、inventory testでも重複・欠落を検査した。

## テスト内容

MySQL 8.0 Testcontainersの4ケースを実行した。

1. 別々のenrollmentを完了するtransactionを同一planの実行行lockで直列化し、両eventとplan完了を確認する。同じversionでの再送がeventを増やさないことも確認する。
2. 新規enrollのcommitと最後のenrollment完了が競合するとき、後続完了が新規のPLANNED enrollmentを認識してplanをIN_PROGRESSのままにする。そのenrollment開始・完了後だけplanがCOMPLETEDになる。
3. 別connectionでcomplete/cancelを同時実行し、complete側が先にplan lockを得る場合とcancel側が先に得る場合の両方を確認する。
4. event insert失敗を発生させ、実MySQL transactionでenrollment状態/version、plan状態/version、event行がまとめてrollbackすることを確認する。

競合テストはsleepやmock lockを使わない。Dockerが使えない場合にskipする設定も置かず、Testcontainers/MySQL起動失敗をテスト失敗として扱う。先行workerと後続workerそれぞれから`CONNECTION_ID()`を記録する。`performance_schema.data_locks`では対象connection、現在schema、`t_learning_plan`、`PRIMARY`、`LOCK_TYPE=RECORD`、対象plan IDの全条件で先行lockを確認する。`data_lock_waits`もrequesting/blocking connection IDをthreadsへjoinし、双方の要求lockが同じ対象planのPRIMARY recordであることを照合してから先行barrierを解放する。table intention lockや他plan・他transactionの待機では通過しない。

## 初回の観測失敗と修正

初回実行は2つの競合ケースがlock wait観測timeoutになった。workerの起動をbarrierで確認しておらず、診断用transaction表示も`RUNNING`かつ`trx_query=NULL`だったため、後続SQLがplan lockを要求した事実を観測できていなかった。Future完了時は例外causeを出す診断も追加したが、worker開始と実lock取得の証拠は別々に見る必要があった。

以前のprobeは全DBの`LOCK WAIT`/`data_lock_waits`件数を見ていたため、Astra指摘の通り対象plan・workerとの対応は証明できなかった。本追補ではworker開始barrierに加え、両connection ID、対象主キーrecord lock、InnoDBのrequesting/blocking lock pairを結びつけるprobeへ変更した。Future完了時は成功・例外causeを即時伝播し、wait観測には10秒の上限を残す。complete/cancelの2順序も個別transaction、別enrollment、別connectionでlock waitまで観測した。

追加確認では、requesting workerの`CONNECTION_ID()`、`started` barrier、および対象service操作を同じ`TransactionTemplate`のtransaction内に置いた。これにより、接続ID取得後のHikari再借用でprobeが別connectionを観測する余地をなくした。先行workerと同様、serviceの`@Transactional`はこのtransactionに参加する。

## 検証結果

1. 実MySQL競合回帰:

```powershell
.\apache-maven-3.9.6\bin\mvn.cmd -Pmysql-tests "-Dtest=TrainingPlanCompletionConcurrencyMySqlTest" test
```

MySQL image: `mysql:8.0` (Testcontainers)。結果: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。
この追補後のSurefire test-class実行は2026-10-01 22:47:30〜22:48:37 JST（Maven終了ログの`Finished at: 2026-10-01T22:48:37+09:00`を根拠とする。commandにはcompile時間が含まれ、Surefireログ時刻はJST）。image id/digest: `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`。Surefire report `TrainingPlanCompletionConcurrencyMySqlTest.txt` のSHA-256: `F6D14E9B9FF14BAC3695FC067DFF44517A09C0A087C240FC36A7D8A0CD2C2286`。XML report SHA-256: `60346F3879974FAD7D7027B903CC178AC316EEB7DB3C0F69E4FDC7776765C5CD`。
その後のconnectionId同一transaction修正を含む最終再実行は2026-10-01 23:00:11〜23:01:10 JST（Surefire `Running`時刻およびMaven `Finished at`）。結果は4/0/0/0、BUILD SUCCESS。最終`.txt` report SHA-256は`9427AEB4857A0D8E35CE85CFD9F43CCE9C7A962260EE8E685E714B3EE1A87C9D`、XML report SHA-256は`A79A84CBF7F836F0658E0AE3E65001AD05B2E528A882BB743D6986CD08E41093`。先行実行の`F6D14E...`および初版`A74382...`は各時点の旧raw report hashであり、最終証跡として混同しない。

2. fast unitとshard inventory:

```powershell
.\apache-maven-3.9.6\bin\mvn.cmd "-Dtest=TrainingPlanServiceTest,MySqlTestShardInventoryTest" test
```

結果: `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。内訳はTrainingPlanServiceTest 17件（空status集合の回帰を含む）、MySqlTestShardInventoryTest 1件。inventoryは`mysql-tests` profileでは除外tagのため、別のdefault-profile invocationで実行した。

Surefire生レポートSHA-256:

- `TrainingPlanCompletionConcurrencyMySqlTest.txt`: `A74382C36E98B1D4624EFBBA3F7F85A41E8D59A1D05AF8C23AE8617FA7440436`
- `TrainingPlanServiceTest.txt`: `2DDD47B01049A97B1AFC8549BD4C87C52D68A6CA22899B72B04D4F3072D9F9E4`
- `MySqlTestShardInventoryTest.txt`: `37FE5E3D2ECA945FB1846CA88FB44ABA0DF056406CC5464D5097C2BAC405581F`

レポート保存先は`target/surefire-reports/`。全suiteは実行していない。
