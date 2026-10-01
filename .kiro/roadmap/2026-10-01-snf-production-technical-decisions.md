# S-NF Production Readiness 技術決定案

## 権限と状態

依頼者の2026-10-01対話による技術判断の委任を入力とする。総括agentは以下を開発用の技術基線として選択する。
状態は`TECHNICAL_SCOPE_SELECTED / PLAN_REVIEW_PENDING`。法的承認、実source適合承認、provider契約、Privacy Owner、production enablementは承認していない。
Base: `1443fb68943849a3260d7ebe005259f3eb609d60`。
旧Decisionの署名者・日時・対象を改変せず、新しい開発incrementとして扱う。

## TD-01 staging

- 既存Docker/MySQL/backup検証を再利用し、productionから分離したsynthetic環境を作る。
- データ・network・credentials・uploads・backup repositoryはproductionと共有しない。
- Java 21、MySQL 8、Asia/Tokyo、UTF-8。正確なimage digestと容量profileはrehearsal packetで固定する。
- ローカルsynthetic stagingは実production代表性の証明ではない。production profile/署名未提供のGateは残す。
- 既存port/containerを停止せず、新規環境の識別子・port衝突を検査する。

## TD-02 NF06 技術scope

- 対象adapter: CUSTOMER、ENGINEER、PROJECT、CONTRACT、PROPOSAL。依存順をmappingへ明示し、既存正本のdomain validationを経由する。
- 形式: CSV UTF-8(BOM有無)/Shift_JISを明示選択。XLSXは初期正式scope外として拒否し、未実装を隠さない。
- source schema: `ses-import-v1`の公開canonical schemaとsynthetic golden fixtureを開発する。実legacy sourceの承認済みschemaと偽らない。
- identity: tenant + source-system + entity + external-key。氏名・会社名だけで既存行を同一視しない。既存行への初回bindingは候補と根拠を表示し、人の承認を要求する。
- create/update: default insert-only。既存更新は明示mapping version・target binding・現在version・別承認者が揃ったときだけ。子collectionの暗黙全置換は禁止。
- 金額: JPY、scale=0、UNNECESSARY。丸めや欠損の0円化をしない。
- validate: source再parseとlookupのみ。業務table/sequenceへwriteしない。job metadataの記録範囲とno-write検証対象を仕様で分離する。
- apply: chunkは有限件数の計画・進捗単位。target変更、id-map、row result、監査はrow transactionで原子的にcommitする。checkpointはcommit済みprefixから復元し、再起動時の二重作成を防ぐ。
- 検証前後のcounter定義を分離する。validate段階で未適用のaccepted行をappliedと数えない。完了時は`source=accepted+rejected`、`accepted=applied+updated+skipped+apply_failed`、失敗・差異0を要求する。
- rollback: downstream/version/holdを再検証し、domainの補償操作だけを許す。安全な補償方法のない対象はmanual remediationとして明示し、成功を偽らない。hard deleteなし。
- 全操作はadmin +明示action permission + tenant/job/domain/file scope、CSRF、監査。UI非表示は認可にしない。
- 全adapter、10,000行、encoding/quoted newline/formula、同hash別mapping、crash/restart、rollback/partial failure、MySQL実並行、real Browserまで実装対象。
- 実データ取り込みには実source schema、mapping、target binding、データowner承認が別途必要。synthetic acceptanceだけでproduction移行を許可しない。

## TD-03 NF07 安全基盤の技術scope案

- Policyは版付きの外部承認入力。owner/purpose/trigger/期限/hold/処分方式/provider/署名証拠の未確定値を補完しない。
- NF03 `CERTIFICATION_PII`、NF08 summary/log、採用/契約/請求/勤怠/監査/backupの現状値はinventoryとして記録し、法律上の期間とは宣言しない。
- policy/hold/request/subject-resolution/DSAR export/claim/disposition/tombstoneの安全基盤を、syntheticデータで検証できる構造として設計する。
- 法的期間をコード定数にせず、未承認policyではcandidate/execute/exportを拒否する。dispositionはdefault OFFで、個別policy allow-list、case approval、二者分離、対象version/hashとhold再検証が必要。
- 誤対象、第三者情報、未知provider、本人同定不明、audit/法定原本、未完了business blockerをfail-closedにする。
- DB+binary restore後にtombstone/holdを再適用し、再公開前に検証する。外部providerの削除完了をHTTP成功だけで推測しない。
- この案はDG-07を閉じない。独立Plan Reviewでは、既存specの停止条件と今回の技術委任の境界を明示し、policy未確定で安全に実装できる部分と人の入力待ち部分を分離する。
- 正式Privacy Owner、Legal/HR/税務判断、本人確認・期限・交付、実providerの契約とproduction処分許可はHUMAN_REQUIRED。

## TD-04 外部依存

- NF05は公開APIとsigned webhookの契約を維持する。実transportはHTTPS、明示approved destination、redirect拒否、DNS/peer検証、secret-store注入、有限timeout、lease/CAS、emergency stopが必要。一般的なHTTP200を業務完了と同一視しない。
- NF09の最初の実adapter候補は、既存OIDC境界と組み合わせられるself-hosted Keycloakとする。ローカルsynthetic環境で実RESTを検証できる。実企業が別IdPを使う場合はそのproviderの適合確認を別途必要とする。
- Keycloak adapterはuser無効化、session logout、現在状態確認の契約を独立に検証する。offline token、既発行access token、下流SaaS sessionの残存を単純な`enabled=false`で解決済みにしない。必要な完了条件が証明できなければPENDING/UNKNOWNで退社blockerを維持する。
- NF08は既存registryの`gemini`実装との互換を優先し、provider追加を先行しない。model version、DPA、region、料金上限は現行公式仕様と契約に基づく別packetを作る。free-tierやmockの結果を企業データ外送の許可にしない。
- 本選択は発注、課金、アカウント作成、外部送信、credential読取/使用の実行ではない。

参考（2026-10-01確認、実装時はpinしたversionで再確認）:
- Keycloak Admin REST API: https://www.keycloak.org/docs-api/26.7.4/rest-api/index.html
- Gemini API terms: https://ai.google.dev/gemini-api/terms

## 独立Reviewとhandoff

- Astraは各決定の実装可能性、既存scopeとの整合、no-write/transaction/counter、provider確認、policy未承認時の拒否をReviewする。
- FAIL findingを修正してからLunaへ各waveを渡す。CONDITIONALを無条件PASSへ変換しない。
- 新migrationは総括が一括採番する。現在最大V182であり、この文書はV183をどのfeatureにも予約しない。
- 書換範囲を限定し、全Gate合格前にproduction-readyを宣言しない。
