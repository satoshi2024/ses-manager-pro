# G2 gate証跡 記録様式テンプレート（R23-P1-01・証跡3〜5・改訂版）

- **作成**: 2026-08-14 / 実装AI（R10 2026-08-14指摘に対応）
- **目的**: 証跡3（実在資格保有者Review）・official source/manual check記録・Phase A/B screenshot manifest・exact evidence記録の様式。
- **参照**: `reviewer-verification-decision-delta-r23-p1-01.md` §3.2〜§3.4（event順序契約）・§7（人間証跡と停止条件）・§9（fingerprint契約）

## 共通注意

- **AIは実在する資格保有者・本人確認・資格確認・外部Reviewを生成・代替しない**（§7）。
- 確認者とReview作成者は別の人間（混同禁止・accepted v3 §5）。
- 全記録はAPI経由（`/api/compliance-gate/**`）。直接SQLで書き込まない。
- credentialはAES-GCM暗号化（CGC1 envelope）で保存され、full値はAPI/logへ出ない（§3.3）。証跡にはmasked表現のみ記載する。
- fingerprintはtenant-HMAC snapshot（§9）。full fingerprintを証跡へ記載しない（先頭8桁maskedのみ）。

## 証跡3: 実在資格保有者Review＋本人性・資格有効性・作成者確認

### 手順（人間操作・API経由）

1. **管理者**が `/compliance-gate` の External Review tab（または `POST /api/compliance-gate/external-reviews`）で、実在資格保有者本人のReviewをSUBMITTED eventとして登録（mapping・requirement group・reviewer type・氏名・組織・credential・chain採番）。
2. **別の人間確認者**が `POST /api/compliance-gate/verifications` で確認を記録:
   - kind=IDENTITY（本人性確認・常時必須）
   - kind=REVIEW_AUTHORSHIP（Review作成者確認・常時必須）
   - kind=QUALIFICATION（frozen flag=trueのtypeのみ必須）
   - kind=ACTIVE_STATUS（frozen flag=trueのtypeのみ必須）
   - 各確認にofficial source・method code・checked_at・exact evidence（document/version/hash/CLEAN）を結び付ける。
3. **管理者**が `POST /api/compliance-gate/submitted-reviews/{id}/adoptions/approve` でexact CLEAN evidenceと共にadoption（APPROVED）を記録。

### 様式

| 項目 | 値 | 記入者 |
|---|---|---|
| mapping_code / mapping_version / mapping_hash | 承認対象mappingの固定値 | 管理者 |
| review_chain_id | SUBMITTED時に採番されたUUID | 管理者 |
| reviewer_type_code_snapshot | type code（例: LABOR_CONSULTANT・**固定valueでなくdynamic master**） | — |
| reviewer_subject_id | person-stable subject id（§G2-VERIFY-13） | — |
| person_fingerprint_masked | 先頭8桁のみ | — |
| verification_kind | IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP | 確認者 |
| result | VERIFIED / FAILED / INCONCLUSIVE | 確認者 |
| method_code | official verification method（dynamic master） | 確認者 |
| authority_source_code / name | official source（dynamic master） | 確認者 |
| official_url_reference | 公式URL（server-side fetch禁止・§G2-VERIFY-05） | 確認者 |
| registration_identifier_masked | 末尾4桁のみ（暗号化保存） | 確認者 |
| checked_at / source_data_as_of | 確認日時・sourceデータ時点 | 確認者 |
| max_age_days_snapshot | frozen max age（未設定はfail-closed） | — |
| valid_until | authority由来の有効期限（存在時） | 確認者 |
| checked_by | 確認者本人のsys_user.id（セッション由来） | 確認者 |
| evidence_document_id / version_id / version / hash | **exact CLEAN evidence**（pickerで解決・§4-5/6） | 確認者 |
| adoption event id / adopted_at / adopted_by | APPROVED adoptionの応答値 | 管理者 |

## official source / manual check 記録様式

- 初期実装は公的sourceを用いた**手動確認**（§G2-VERIFY-05: 公式API・scraping/server fetch禁止）。
- source/methodはtenant管理のdynamic master（§3.8・Java/DDL変更なしで画面設定可能）。

| 項目 | 値 | 記入者 |
|---|---|---|
| method_code | `MANUAL_PUBLIC_SOURCE`（手動・公的source）等のdynamic method code | 確認者 |
| authority_source_code | official source code（dynamic master・例: 公式登録簿） | 確認者 |
| 確認手順の記録 | 何の公的sourceを・いつ・どのように確認したか（画面/書面の日時・URL） | 確認者 |
| manual checkの画像/書面 | 確認時の証跡（画面キャプチャ・書面写真）をdocument archiveへ登録 | 確認者 |

## Phase A/B screenshot manifest 様式

| 項目 | 値 |
|---|---|
| run-id | 実行識別子 |
| viewport | desktop（例: 1920x1080）・mobile（例: 390x844） |
| role | 操作role（管理者/HR/マネージャー） |
| page | `/compliance-gate`（tab名）・accountable document系ページ |
| URL | 取得URL |
| screenshot file | PNGパス |
| console log | エラー0件の確認 |
| network log | 失敗リクエスト0件の確認 |
| 画面のPDF SHA-256 | 帳票ダウンロード結果のハッシュ |

## exact evidence 記録様式

| 項目 | 値 | 記入者 |
|---|---|---|
| document_id | document archiveのid | 管理者/確認者 |
| version_id | **exact version id**（latestでない・§4-6） | 管理者/確認者 |
| version_no | version番号 | — |
| sha256 | SHA-256（64 hex） | — |
| scan_status | `CLEAN`必須（PENDING/INFECTEDはfail-closed） | — |
| file scope | tenant・document/version対応（cross-tenant参照拒否） | — |
| title / original_name | 表示用 | — |
| createdAt | 登録日時 | — |

## 補足

- 証跡1/2の様式は `g2-gate-evidence-templates.md`（改訂版・UI/API経由）を参照。
- 証跡4（PDF目視）・証跡5（T066-HISTORY可否）は `t066-m-acceptance-checklist.md` を参照。
