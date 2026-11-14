# NF07 SF-W2 追加技術Plan Review

Reviewer: `astra_review_baseline` / gpt-6-astra、read-only。
固定Head: `3e05b32f31626056347f67c5ad015e3b1bdf43ec`。
判定: **PLAN CONDITIONAL**、P0なし。実装・production・Privacy/Legalの承認ではない。

## 必須補完

1. **P1: 歴史receiptの信頼境界**。approval/lease期限切れは新dispatchを禁止するが既存結果を消さない。
   一方、receipt署名者のscope、署名、compromise/revocationの効力発生時点は引き続き検証する。
   自己申告の署名時刻だけを信じない。信頼できる歴史時刻がない、漏洩範囲不明、provider照会不能の場合は
   observation hashと固定reasonだけを追記しUNKNOWNを維持する。
   一度trustedとした結果の鍵漏洩が判明した場合も、trust-change eventを追記し、
   原記録の静かな削除や無条件な成功扱いを禁止する。
2. **P2: revision単位**。元案の二event appendと一revision進行が矛盾している。
   総括は単一の複合`DISPATCH_AUTHORIZED` eventを選定した。
   immutable intentを同一payload/transactionへ含めてr→r+1とし、command replay、hash chain、
   projection、watermarkもevent単位に統一する。途中失敗は半分の認可やintentを公開しない。
3. **W2b前のcheckpoint freshness**。独立attestorが示す最新・後退不可checkpointを取得する。
   journal中の旧署名を検証するだけでは不可。正しい旧watermarkと完全な旧prefixの組合せを
   replayしても復元公開/write-enableを拒否する回帰を要求する。

## 最小追加検証と着手条件

- 正常な鍵rotation後のlate receipt、鍵漏洩後のbackdated偽receipt、歴史妥当性UNKNOWN、
  authoritative provider queryによる確認、対象binding不一致。
- 認可transaction中途失敗、同command replay/別payload conflict、旧watermark replay。
- mainは汎用interface/安全機構に限定し、connection生成、test schema、秘密鍵、attestor/provider fixtureは
  test-only harnessが所有する。任意DataSourceによるprod/default-prod/mixed-prod拒否の迂回を防ぐ。

Astraは補完済み範囲からW2a port/harness/hold/replay/lease/hash-chainを進める選択肢を認めたが、
総括は先に上記契約を一つの固定Planへ反映し、追加Reviewを受ける方針とした。
現在W2のworker/DDL/実処置は未開始。migration番号も未割当。

## 再Review結果

固定Head `e5530c01b7697e125f3b4b7f2db168bfc385bd97`をAstraが再審査し、限定**技術PLAN PASS**。
歴史receiptのexact digest/signatureと独立trusted time、単一compound event revision、
独立latest witnessによる旧有効prefix拒否を契約へ反映した。production/policy承認ではない。

次はsynthetic W2aのtyped port、test schema、Journal CAS/chain/command replay/hold/leaseを実装する。
P1–P5/P9/P13に加え、**P14のtransaction途中失敗とP15の接続前profile/capability拒否をW2aで検証**する。
W2aの固定CODE Review後にW2bへ進み、W3 worker/receipt、実処置、production adapterはこの着手範囲へ含めない。
既存migrationの変更・番号予約なし。MySQL試験の実行時間は総括が割り当てる。
