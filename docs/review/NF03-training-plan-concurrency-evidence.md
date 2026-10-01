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
3. `完了→取消` と `取消→完了` の両順序を確認する。
4. event insert失敗を発生させ、実MySQL transactionでenrollment状態/version、plan状態/version、event行がまとめてrollbackすることを確認する。

競合テストはsleepやmock lockを使わない。先行transactionが`SELECT ... FOR UPDATE`を実行した後もbarrierで保持し、後続workerの開始barrier、`performance_schema.data_locks`のGRANTED plan lock、`information_schema.innodb_trx`または`performance_schema.data_lock_waits`の実waitを観測してから先行transactionを解放する。

## 初回の観測失敗と修正

初回実行は2つの競合ケースがlock wait観測timeoutになった。workerの起動をbarrierで確認しておらず、診断用transaction表示も`RUNNING`かつ`trx_query=NULL`だったため、後続SQLがplan lockを要求した事実を観測できていなかった。Future完了時は例外causeを出す診断も追加したが、worker開始と実lock取得の証拠は別々に見る必要があった。

修正後は先行transactionが実plan lockを保持することを`performance_schema.data_locks`でassertし、後続workerの起動barrier後にInnoDBのlock-wait記録が現れるまで進行を止める。最終MySQL実行では4ケース全てが成功し、このlock-wait条件を通過してから各barrierを解放した。単なるservice結果の順序確認ではなく、InnoDBが実際に待機した競合を確認している。

## 検証結果

1. 実MySQL競合回帰:

```powershell
.\apache-maven-3.9.6\bin\mvn.cmd -Pmysql-tests "-Dtest=TrainingPlanCompletionConcurrencyMySqlTest" test
```

MySQL image: `mysql:8.0` (Testcontainers)。結果: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。

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
