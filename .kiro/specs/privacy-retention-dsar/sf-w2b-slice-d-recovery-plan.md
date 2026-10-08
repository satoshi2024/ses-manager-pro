# SF-W2b Slice D 独立witness recovery gate計画

状態: **設計案 / Astra review待ち。実装・合格・NF07完了を意味しない。** 対象はsynthetic test環境のapplication projection/journal restore後に、独立witnessのlive latestを起点としてjournal chainを再検証し、projectionを再構築できるかの技術検証に限る。production接続、migration、外部provider、credential、dispatch/publicationの有効化、人工承認は含めない。

## 1. Slice Cから引き継ぐ信頼境界

Slice Cの`LatestWitnessPort`は`history(partition)`と`latest(partition)`を別々に読むinterface、`SignedWatermarkVerifier.verify(partition, history, latest)`は渡された値を検証するpure verifierである。現在のCには「どの値を読むか」を固定するrecovery serviceがない。したがって、Dの入口はpartitionだけを受け、呼出側の`history`、`latest`、署名済みwatermark、`JournalAck`、trusted booleanを受け取らない。D自身がtest-only primaryに束縛されたportからlive値を読む。

信頼経路は次の責務分離とする。

| 境界 | Dでの扱い |
|---|---|
| `LatestWitnessPort` | recovery serviceだけがlive witness history/latestを読取る。historyとlatestは別readなので、read間にappendが起きた不整合を検出する安定snapshot読取を行う。 |
| `SignedWatermarkVerifier` | Cの署名、canonical bytes、key state/audience、epoch/hash chain、latest一致、freshness検査を再利用する。古い呼出側snapshotを検証した結果をlive扱いしない。 |
| `PrivacyJournalPort` | journal primaryからheadと全eventを一貫したsnapshotとして取得し、revision連番、previous hash、event hash、head一致を全件検証する。 |
| `JournalProjectionService` / `PrivacyProjectionPort` | 現在の`projectAcknowledged(partition, ack)`はcaller ACK起点の通常投影であり、restore recoveryへ偽ACKを作って流用しない。共有chain/prefix検証とverified replayをpackage-private責務として抽出または拡張し、D自身のjournal読取結果からのみ`VerifiedJournalEvent`を発行する。 |
| projection state | 現行projection revision/hashと全projected fieldsがjournalの同revision prefixから再計算した値に一致するか検査する。整合しないstateを正本へ昇格しない。 |

app projectionとjournalが別々にrollbackされても、witnessをrestore対象外として維持する合成fixtureだけを使う。3 MySQLの分離はfixture上の境界であり、3つ全部またはwitness volume自体の過去snapshot rollbackをMySQLが検出できる証拠ではない。

## 2. Recovery契約と手順

Recoveryは`recover(partition)`相当のpackage-private operationとし、開始時から対象projectionをQUARANTINED/read-onlyとして扱う。どの中間状態もtrusted/publication-readyとして公開しない。Dの成功結果は合成recovery検証結果に限り、dispatch、hold解除、利用者向けpublication gateを開かない。

1. **witness stable read**: test-only witness primaryから`history`と`latest`を読み、readの前後でhistory全envelope（canonical payload/signature含む）とlatest tupleが同一であることを確認する。途中でappendされた場合は再読する。安定snapshotを得られない、空履歴、欠落epoch、同epoch分岐、latest/history不一致、署名/key/domain/audience/compromise/freshness不正は即QUARANTINED。
2. **journal snapshot**: journal primaryからheadと全eventを一貫して読み、全revision・previous hash・event hash・head revision/hashを最初から再検証する。単なるwatermark一致だけでhistory完全性を推定しない。全event連番がない、headよりeventが欠ける、partitionが違う、hash分岐はprojectionへのwrite前にQUARANTINED。
3. **watermark anchor照合**: witness最新signed checkpointの`eventRevision/eventHash`がjournal history中の同じrevision/hashに一致すること、journal headがwitness revision以上であることを要求する。journal headがwitnessより後ろなら、古いjournal snapshotへのrestore/巻戻りとして拒否する。witnessより後ろのjournal tailも全件hash-chain検証する。witness anchorをjournal内のold watermark copyで置き換えない。
4. **現projection検査**: projection snapshotが同partitionで、revisionがjournal head以下であり、そのrevisionまで再計算したheld/action fields・event hashと完全一致することを確認する。app projectionだけが古くても一致する過去prefixなら再構築可能。revision ahead、same revision異hash、field改変、prefixに対応しないstateはQUARANTINEDとし、勝手に初期値へ戻さない。
5. **verified replay**: witness anchorを含む完全journal chain検証が完了した後に限り、現projection revisionの次からjournal headまでrevision順に適用する。各apply後にprojectionを独立read-backし、revision/hash/held/action全fieldが検証済みjournal prefixと一致することを確認する。same revision/same hashのretryだけNO_OP。途中失敗時はprojectionが部分的に進んでも隔離を維持し、次回の再検証で収束させる。
6. **final live reread**: replay後にwitnessのhistory/latestとjournal head/eventsを再取得し、最初の検証後に進んだcheckpointまたはjournal eventを見逃していないか確認する。両DBのlive snapshotが進んでいれば、隔離を維持したまま新しい一貫snapshotからbounded retryする。安定回数上限/時間上限超過、read不達、改変、journalとwitnessの不整合はQUARANTINED。最終projectionが最終journal headと全fieldで一致した場合のみ`RECOVERED_SYNTHETIC`を返す。
7. **repeatability**:同じ状態でrecoveryをもう一度実行し、event追加や状態変化なしの全件NO_OPと同一最終head/hashを確認する。最終read後に新しいeventが発生し得るため、この結果を後続操作時点の認可として使わない。

cross-DB transactionを装わない。bounded再試行とfinal rereadはrestore中の進行を検出するが、最終読取直後に新eventが発生するraceを消さない。将来の実利用者/workerは各操作の直前にもlive authorityを再検証する必要があり、そのgateはDの範囲外である。

## 3. 失敗時の状態

| 検出 | projection/recovery結果 |
|---|---|
| app projectionだけが、検証済みjournal prefixに一致する古いrevision | QUARANTINEを維持して残りのverified eventを再生可能 |
| journal headがlive witness latestより後退、旧signed checkpointだけが残る | QUARANTINED。旧watermarkの署名有効性だけで復旧しない |
| witness historyの先頭/中間欠落、revision/hash分岐、latestがhistory tailと不一致 | QUARANTINED。推測補完/署名履歴再生成をしない |
| journal event truncation/gap/hash改変/head不一致 | projection write前にQUARANTINED |
| projection revision ahead、same-revision異hash、projected field drift | QUARANTINED。app stateをauthorityとして採用しない |
| 初回または最終read中のwitness/journal更新、再読上限到達 | bounded retry後も安定しなければQUARANTINED |
| commit response loss | projectionを独立read-backし、exact prefixなら再開/NO_OP。それ以外またはread unavailableはQUARANTINED |
| witness unavailable、unknown/compromised key、署名/freshness不正 | QUARANTINED。cacheやjournal内checkpointへfallbackしない |

QUARANTINED解除の人手reconciliation、データ修復、production gateは実装しない。Dの再試行は入力がlive authorityに対して有効と証明できる場合だけ行う。

## 4. `maxAge`とidle partitionの未解決liveness

Cの`SignedWatermarkVerifier`はlatest checkpointの`issuedAt + maxAge`を検査する一方、`JdbcLatestWitnessAdapter`は新checkpointの`eventRevision`を既存latestより厳密に増加させ、verifierもhistory内revisionの厳密増加を要求する。そのためjournal eventがないidle partitionでは同じrevision/hashのまま署名時刻だけを更新できず、`maxAge`経過後はDのfreshness検証がQUARANTINEDになる。これは現Cの契約から直接生じるliveness制約であり、Dで黙ってfreshness条件を省略しない。

**このsliceの既定動作はfail-closed**とする。stale latestはrecovery/publication readinessを拒否する。古いwatermarkを再署名したり、app/journal時刻を使ってfresh扱いにしたり、無イベントの合成journal revisionを追加したりしない。現行C契約でfreshなより高revision checkpointが得られない限りquarantineが続き得ることを、test/evidenceと未解決項目に明記する。

別案として、同じ`eventRevision`/`eventHash`を保ったまま`epoch`だけ進める「freshness receipt/checkpoint renewal」契約を将来独立reviewへ提案できる。採用する場合でも、attestorが毎回journal primaryを再読し全prefixを検証し、live latest hashをCAS predecessorとして署名し、same revisionを許す条件を「eventHash完全一致・epoch連続・issuedAt単調増加」に限定し、DB全体rollbackはwitness分離で検出する必要がある。現在のC実装はこれを許さず、Dでは実装・受入済み扱いにしない。Astraが別protocol/version、canonical domain/schema変更、rollback条件をレビューするまで保留する。

## 5. D専用の実MySQL試験案

専用`@Tag("mysql")` recovery test classを追加し、既存test shard inventoryへ一度だけ登録する。既存の3-container harness/ACLを再利用し、witness DBはfixture restoreから除外する。testは実`PrivacyJournalPort`、`LatestWitnessPort`、verifier、projection/recovery serviceを通し、手書きSQLだけのsimulationやcaller-supplied trusted flagを成功根拠にしない。test-only signer/keyのみを使い、provider call countは常に0。

| ケース | 入力/操作 | 必須検証 |
|---|---|---|
| app-only restore | live witness/journalは最新、app projectionだけ古い整合prefix | latestを自力readし、残りを再生して全field/head一致。再実行は全件NO_OP。 |
| app+journal old restore | 古いapp/journal snapshot、witness volumeは新しいまま | validだが旧署名済checkpointがsnapshot内にあってもjournal head < live latestならwrite/publication readinessを拒否。 |
| stale supplied snapshot回避 | callerに古い署名済watermarkを持たせた状態でrecoverを呼ぶ | APIにsnapshot引数がなく、serviceのlive witness読取結果が使われることを実DBと結果で証明。 |
| witness history truncation | 先頭/中間checkpointを除去、latest row保持または改変 | verifier/recoveryがchainまたはlatest照合で拒否し、projection stateをtrustedと返さない。 |
| journal truncation/gap/hash fork | event row欠落、gap、previous/event/head hash改変 | apply前quarantine、event追加/補正なし。 |
| old signed watermark | 過去の署名自体は有効だがlive witness latestより古いjournal backup | old signatureだけで復旧不可、latest/head mismatchを観測。 |
| projection tamper/ahead | revision/hash/held/action field改変またはjournalよりahead | `PROJECTION_MISMATCH`相当でquarantine、自動上書きなし。 |
| concurrent event during replay | barrierでprojection replay中に別connectionからjournal event/checkpointを進める | 最終rereadが進行を検知し、隔離を保ったretryで最新に収束するか、bound超過ならquarantine。Future/lock waitはbounded、失敗を握りつぶさない。 |
| concurrent witness append between history/latest reads | 2 connectionでlatest/history読取間にattestor append | 安定snapshot helperが混合snapshotを採用せず再読し、latestとhistoryの同じtailを検証。 |
| maxAge idle boundary | event-free partitionのvalid checkpointをclockでmaxAge前/ちょうど/後へ進める | 期限後はfail-closed。same-revision renewalが未実装であることを明示し、成功と誤分類しない。 |
| witness unavailable/rollback | witness read停止、latest epoch/revision/hash後退 | fallbackせずquarantine。witnessまで全体rollbackした場合は検出保証外と明記。 |

runtime assertionには3 containerの識別子/image/version、partition opaque ID、checkpoint epoch/revision、判定reason、replay count、skip=0を安全に記録する。private key、DB credential/URI、PII/raw journal payloadは記録しない。Maven/Dockerはrootが直列実行する。

## 6. Dの実装範囲とレビュー条件

実装承認後の最小候補はpackage-private `PrivacyRecoveryService`（partitionのみ受ける）、stable witness snapshot reader、stable journal snapshot/prefix verifierの共有化、projection replay/read-back、bounded retry/quarantine resultと上記MySQL testである。mainにsigner/private key/production bean/controllerを追加せず、schema migrationも作らない。Cのstrict revision/maxAge契約は変更しない。

Astra reviewでは特に、(1) history/latest stable-readの線形化とappend競合、(2) witness anchor以降のjournal tailをreplayできる権威根拠、(3) projection partial progress中もquarantine/read-onlyを保つ仕組み、(4) final reread直後のTOCTOUをD結果が認可に転用しない境界、(5) idle maxAge後の運用可能性と別protocol refresh案、(6) full witness rollbackで証明できない限界を確認する。これらに合意するまで実装しない。

## 参照

- `sf-w2b-execution-plan.md` §§3–7（C watermark、projection CAS、P12/P12b、slice D）
- `sf-w2-protocol.md` §§3–5、§7（journal authority、ACK、watermark freshness、restore/quarantine）
- `sf-w2a-jdbc-review-packet.md`（固定W2a source境界）

