# privacy-retention-dsar 要件

## 0. 承認状態と今回の実装境界

| 項目 | 現在値 | 判定 |
|---|---|---|
| Approved policy/scope | `<APPROVED_SCOPE>`（未置換） | 未承認。処分対象の範囲を確定できない |
| Privacy owner | `<OWNER>`（未置換） | 未定。データ要素ごとの責任者が未確定 |
| Base commit / branch | `<BASE_COMMIT>` / `<BASE_BRANCH>`（未置換） | 入力値は未確定。開始時の作業base/merge-baseは `0333b0a4afadef42639bad27e1ae443758f9804f`。fetch後の現在 `origin/main` は `f131f51c50dbfb68ffc8e71878da52947560c80e` に進んだため、rebaseしない |
| NF-07 | CANDIDATE | 承認済み要求ではない |
| DG-07 | 未完了 | 保持期間、法的根拠、hold権限、二者承認、法務/HR/税務責任者が未確定 |
| 外部専門家 / 社内責任者 gate | 未完了 | 本番処分を許可しない |
| 処分 feature flag | 未導入。将来の既定値は OFF とする | 今回は処分経路を追加しない |

DG-07 および外部専門家/社内責任者 gate が完了していないため、このincrementは以下だけを対象とする。

1. 既存schema、ファイル参照、AI allow-list、監査/保持実装からの read-only PII inventory。
2. 事前に匿名化・マスクした fixture を読む offline dry-run。DB、filesystem、外部providerへ接続しない。
3. 今後のF1以降を停止状態で記録した requirements/design/tasks、completion mapping、Review handoff。

削除、物理削除、論理削除、匿名化、値の上書き、providerへのDSAR検索/通知は今回実装・実行しない。

## 1. 用語と安全原則

- **PII**: 個人を直接または他の要素と組み合わせて識別できる値。氏名、連絡先、住所、顔写真、自由記述、採用/勤怠/給与/健康・相談情報、認証情報、識別子のsnapshotを含む。
- **処分**: 物理削除、論理削除、匿名化、restrict、binary purgeを区別する。処分の可否はシステムが法的判断しない。
- **hold**: legal hold、監査保持、紛争/調査、法定保存、active business blockerを含む停止理由。確認不能もfail-closedとする。
- **本人請求provider**: 本番のDB/table、file store、AI legacy log、バックアップ/replica、外部連携を、認証済みrequest scopeに従って検索する担当adapter。scope外providerは呼び出さない。
- **同姓同名**: name一致を本人識別に使わない。identity verificationが完了し、曖昧性を人がresolutionするまで blocked とする。

## 2. 要件

### PR-R1 PII catalog と retention policy

1. システムは、table/column、file/object、AI payload field の各単位で、`dataElementId`、owner、purpose、collection/trigger、retention、hold、disposition method、DSAR provider、evidence、policy statusを記録できなければならない。
2. retention は「保持期間」と「起算trigger」を分離し、policy version、承認者、承認日時、適用開始を持つ。値が未確定、provisional、または起算日を計算できない場合は、処分候補にしない。
3. 法定文書、監査ログ、認証/セキュリティ証跡、バックアップ/replica、契約・請求・勤怠・税務・採用の保持判断は、各既存specと矛盾してはならない。未確定事項は `UNKNOWN` として残す。
4. AIについては `.kiro/specs/ai-feedback-learning/g10-allowlist.json` を正本とし、allow-list、provider retention、raw prompt 0日、redacted summary 730日、legacy `t_ai_log` 30日という既存値を「技術上の現状」として記録する。ただし法的な承認済み保存期間とは扱わない。

### PR-R2 read-only dry-run

1. dry-run は no-write であり、業務DB、migration、ファイル、backup、replica、外部providerへ接続しない。
2. dry-run は各要素を少なくとも `CANDIDATE`、`BLOCKED`、`UNKNOWN` のいずれかへ分類し、理由、blocker、確認不足、provider呼出しをしなかった事実を説明する。
3. 次のいずれかを検知した要素は `BLOCKED` とする: active legal hold、未完了の法定保存、immutable audit、active business blocker、scope外provider、未解決の本人同定/同姓同名、既存document archiveとの整合不能。
4. owner、purpose、trigger、retention policy、hold状態、audit状態、scopeが未確認の場合は `UNKNOWN` とし、推測で候補化しない。
5. `CANDIDATE` は削除/匿名化の許可ではなく、「承認済みpolicy、本人/対象scope、期限、blockerなしが入力上成立した場合の候補」に限る。人の承認と二者承認が別途必要である。
6. dry-run入力はraw PIIを含まないredacted snapshotに限定する。ログ・出力には氏名、email、電話、住所、自由記述、token、secret、raw promptを出さない。

### PR-R3 DSAR subject resolution と export境界

1. request受付、本人確認、subject resolution、scope確定、検索、第三者redaction、export、delivery、reopen/appealを別状態として管理する。
2. 本人確認が不十分、同姓同名、複数候補、第三者情報混在の場合は、人によるresolution/確認が終わるまで検索結果を確定しない。
3. export は請求者本人のscopeに限定し、第三者の氏名、連絡先、契約相手、他要員、監査actor等をredactする。redaction不能ならexportをfail-closedする。
4. requestのdue date、case owner、decision、reason、delivery、appeal/reopen、承認者と監査証跡を保持する。期限・法的判断は責任者が決定する。
5. scope外の外部providerやAI providerへ、請求の存在またはsubject情報を送信しない。

### PR-R4 処分実装の将来gate

1. F1以降を開始するには、DG-07の承認済みpolicy/owner、legal/HR/tax責任、hold開始/解除権限、二者承認、本人確認、期限、処分方式、provider scopeが文書化されなければならない。
2. B1以降は処分flag既定OFF、approved policy allow-list方式、緊急停止、対象ごとのclaim/idempotency、再送、部分失敗、誤対象取消、backup restore後の再検証を持たなければならない。
3. audit logとlegal document originalは、DSARだけを理由に無条件削除してはならない。処分候補は、既存 `t_document_disposal_request` および監査/復旧証跡との整合を確認してから扱う。
4. システムは「削除してよい」という法的結論を生成しない。人が承認したpolicyとcase decisionを実行条件として受け取るだけとする。

## 3. 受入基準（今回）

- [ ] `<APPROVED_SCOPE>`、`<OWNER>`、`<BASE_COMMIT>`、`<BASE_BRANCH>` の実値が承認記録に置換されている。
- [ ] DG-07、外部専門家、社内責任者 gate がPASSになっている。
- [x] inventoryにDB table/column、file/object、AI payload、owner/purpose/trigger/retention/hold/disposition/provider/evidenceが載っている。未確定はUNKNOWN/PROVISIONALと明示している。
- [x] dry-runがno-writeでcandidate/blocked/unknownを説明し、fixtureでhold、audit、同姓同名、scope外、unknownを扱う。
- [x] 通常checkoutを変更せず、専用worktree/branchで作業した。
- [ ] F1以降のDDL、provider、dashboard、DSAR export、処分batch、restore/evidenceはgate完了後の別incrementとする。
