# NF07 SYNTHETIC_FOUNDATION 技術契約案

状態: **履歴設計案（後続specへ同期済み）**。最新の技術Planはspec側`synthetic-foundation.md`、
実装・独立Reviewは`2026-10-01-snf-production-fix-reviews.md`を参照する。
Plan `f007ef9a`はPASS、SF-W1 `6ec4ca28`は限定CODE PASS。W2以降は未実装。
旧`NF07-DEV-GATE-20260828`の範囲と署名を変更しない。
根拠は2026-10-01の依頼者による技術方式選定・開発実行の委任である。
本incrementはgeneric安全機構を合成データで実装・検証する案であり、DG-07、Privacy Owner、
法的保持期間、実DSAR交付、実処分、実provider、production enablementを承認しない。
実装前にfeature requirements/design/tasksと同期し、Astra Plan Reviewを受ける。

## SF-01 実行領域と拒否条件

- main側はpolicy/approval/hold/claim/disposition/recoveryの型と安全機構を実装し、
  synthetic authority/provider/storage fixtureはtest側にのみ置く。production artifactにtest署名鍵を含めない。
- 合成環境はharnessが作成したtemporary MySQL/object store/journalだけを使う。
  実account、既存DB、uploads、credential、外部providerを参照しない。sourceデータはgenerated fixture manifestへ限定する。
- harnessはcontainer identity、random database/session ID、生成manifest hash、network、volumeを検証する。
  任意DB_URLやpathへの差替えを受け付けない。host既存serviceや他worktree環境は停止・再利用しない。
- prodまたはprod,test混在ではsynthetic verifier/policy/providerを常に拒否する。
  production用verifier/provider未登録なら起動・処分・exportはfail-closedで、mock fallbackを持たない。
- mainの実adapterは正式policy/署名・外部契約・環境gateが揃った後の別incrementに残す。
  synthetic testで処分が動くことを、旧F1-M全体やNF07 production完成としない。

## SF-02 信頼境界

policy approvalとcase approvalは別のimmutable envelopeで表現する。
共通にissuer/authority/key ID、schema version、tenant、scope、issued/effective/expiry、revocation reference、
canonical payload hash、signatureを含む。単なる`approved=true`入力は不可。

- policy envelope: dataElement/provider/action、purpose、trigger、期間・基準、hold precedence、
  allowed recipient/disposition、version、承認主体の権限範囲。
- case envelope: verified subject resolution reference、対象ID/version/content hash、policy hash、
  action/provider、requester、別approver、提出/確認時刻、独立case revision。
- 署名verifierはconfigured trust anchorからauthorityと対象scopeを検証する。
  未知issuer、期限切れ、失効、改竄、tenant/scope/action不一致、同一requester/approverを拒否する。
- 合成fixture鍵とauthorityにはSYNTHETIC audienceを強制し、production audienceへ変換しない。
- 実authority/署名責任者の登録は人の決定を必要とする。root/実装agent自身の署名をLegal承認としない。

## SF-03 hold・claim・処分

状態候補: REQUESTED→RESOLVED→PREVIEWED→APPROVAL_PENDING→APPROVED→CLAIMED→
EXECUTING→CONFIRMED、またはBLOCKED/UNKNOWN/PARTIAL_FAILED/CANCELLED。
状態ごとの主体、前提、CAS、retry、terminal性をfeature設計で表にする。

- tenant/subject/action単位のfenceをhold開始・解除、claim、実行直前検査が共有する。
  durable holdが先に確定すれば処分を拒否する。処分が先に確定した事実はhold後も捏造して巻き戻さない。
- target version/hash、policy revision、hold revision、case approval、権限をclaimとcommit直前に再検査する。
  変更は再preview/再承認へ戻す。lease token/generationが古いworkerはcommitできない。
- DB内のaction/receipt/auditは短transactionで原子的に処理する。外部I/Oをlock保持transactionに含めない。
- 外部actionはoutbox＋安定idempotency key＋確認receiptで扱い、HTTP200だけでCONFIRMEDにしない。
  未知結果はUNKNOWN、部分結果は各対象別に保持し、成功済み外部処分を逆操作で勝手に復元しない。
- 本人不明、第三者情報、未知provider、active business blocker、immutable audit、法定原本は拒否する。
- emergency stopは新規claimと未開始実行を止める。in-flight結果の記録・安全確認を放棄しない。

### SF-03a 外部dispatchとholdの線形化

独立journalをhold/dispatch認可の正本とし、application DBはそのrevisionを投影する。
journalはtenant/subject単位の単調sequenceとexpected-revision CASを持ち、holdとdispatch認可を
同一のdurable transactionで排他制御できるadapterを必要とする。単なる非同期log writerでは不足する。

| 操作 | 正本のatomic遷移 | 結果 |
|---|---|---|
| hold開始 | 現在revisionを検証しHOLD_STARTEDをdurable append | ACK後に成立。後続DISPATCH_AUTHORIZEDを拒否 |
| dispatch認可 | policy/case/対象hash/現在hold/期限/fenceを検証し、intentとDISPATCH_AUTHORIZEDを同時append | ACKと認可receiptがある対象のみ外部実行可能 |
| 認可後のhold | HOLD_STARTEDをappendし、既存認可action IDをin-flightとして列挙 | 後続認可を拒否。既認可操作の未実行/取消完了を偽らない |
| hold解除 | 明示権限/SoD/expected revisionを検証しHOLD_RELEASEDをappend | 古いDB snapshotや未確認releaseで解除しない |

外部I/O中にDB lockを保持しない。dispatch認可commitが本synthetic protocolの線形化点であり、
認可後に成立したholdは、既認可actionがHTTP開始前でも取り消せるという保証を持たない。
providerがfencing/cancellationを提供する場合のみ別の取消receiptを確認する。
実処分時点までの取消を必要とするpolicyに対し、その機能のないproviderを有効化しない。
実policyのこの整合モデルへの適合はPrivacy/Legalとprovider検証の人工gateに残す。

workerは認可receiptのaction/対象version/hash/有効期限を検証する。期限切れ未開始操作は
再認可が必要であり、holdを迂回して古いreceiptを再利用しない。
認可commit〜HTTP送信をbarrierで停止し、その間にholdを入れる試験で、前後のsequence、
in-flight表示、後続禁止、取消保証の限界を観測する。

## SF-04 独立journalと復元後の公開gate

- disposition receiptとholdの追加/解除journalを、復元するDB/object snapshotとは別の保護領域へ追記する。
- journalはtenant/opaque subject reference、policy/case/action hash、対象version、sequence、
  previous hash、receipt hash、時刻、署名checkpointを保持する。削除した原文PIIや原本文書を再保存しない。
  opaque reference/hashも無条件に匿名情報とは扱わず、対応表・アクセス・保持の別policy対象とする。
- 信頼watermarkは古いsnapshot内の値だけから決めない。独立authorityの署名checkpointと突合する。
- restore環境はread-only/quarantineで開始し、snapshot以降のhold/dispositionを冪等再適用する。
  journal欠落/破損/sequence gap/遅延/署名不正/未知provider確認があれば公開とwrite-enableを拒否する。
- 二回再適用、旧snapshot復元、復元中の新hold、partial external resultを検証し、
  誤ってPIIを再公開しないことをendpointとstorageの双方から確認する。
- 実journalの耐久性、署名鍵、WORM/アクセス分離、RPO/RTO、当番はOperations/Securityの別gate。

### SF-04a intent先行とcrash recovery

- 不可逆操作の前に、action ID、安定idempotency key、target version/hash、policy/case revisionを含む
  intentを独立journalへdurable保存し、SF-03aの認可receiptを取得する。未ACKなら外部呼出0件を要求する。
- provider結果の後にreceiptを追加する。外部成功〜receipt前crashではintentが残るため、
  restore後はUNKNOWN/quarantineを維持し、provider照会または同keyの安全な冪等確認で解決する。
  intentのみを未実行・成功のどちらにも自動分類しない。
- holdはjournalのdurable ACKを先に取り、application DBのhold projectionを後で更新する。
  DB更新失敗でもjournalのholdは有効であり、全認可入口は古いDB projectionで許可しない。
  journal timeout/不明ACKはfail-closed。同command IDで照会・再送し、二重holdを作らない。
- local holdだけが存在しjournal ACKのない中断状態を注入した場合も、不一致を検出して公開/dispatchを
  拒否し、権限付きreconciliationまでquarantineとする。local DB更新を先に成功応答する実装は禁止する。
- 復元前snapshotにないintent、receipt欠落、journalとDBのrevision不一致、復元中の新holdを検査し、
  信頼watermarkまで照合できない間はwrite-enableも再公開も許可しない。

## SF-05 必須試験と依存

1. prod/prod,test、synthetic authority混入、未知provider、実URL/path注入の拒否。
2. tenant/subject/第三者混在、未署名/偽署名/失効/期限切れ、SoD、対象変更の拒否。
3. MySQL実接続によるhold/claim/execute競合、stale worker、transaction rollback。
4. worker crash、送信前後crash、再送、UNKNOWN、partial failure、emergency stop。
5. old snapshot＋独立journal、欠落/改竄/遅延/watermark、冪等再適用、公開拒否。
6. DSAR exportの固定対象版、第三者redaction、期限/交付を未承認のまま実配布できないこと。
7. NF03 CERTIFICATION_PII、NF08 summary/logへpolicy interfaceを接続し、未承認policyの
   retention/disposition/外送を安全な拒否へ維持する。既存期間を法的期間へ昇格しない。

coverageの構造欠落とpolicy未決定は別counterとする。現在のsource baselineは
`2026-10-01-snf-production-privacy-coverage.md`を参照し、73未分類table等を0と偽らない。
各波は実装→対象限定試験→Astra固定Head Reviewを要求し、NF06/NF07未実装のflag OFFを全機能PASSへ変換しない。
