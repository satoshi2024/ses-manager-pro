# Sample snapshot spec（承認済み要件を反映した実装契約例）

この文書は承認済みNF-10/DG-10を実装契約へ写像するsampleであり、数値、ID、hashはすべて例示である。DB schemaの細部はF1で固定するが、実装時は既存正本service/DTOの値をadapterで取り込み、snapshot後に現在DBを再読してはならない。

## 1. 契約の目的

1. UI、既存 export、report document が同じ metric value を読む。
2. 実績/予測と確定/速報を別フィールドで表示する。
3. period の両端、cutoff、tenant timezone、data freshness、scope owner、canonical source を保存する。
4. section ごとの部分失敗を隠さず、generation retry と区別する。
5. recipient preview と scope check を delivery 前に通し、期限切れ link は再認可する。

## 2. 仮 JSON

```json
{
  "snapshotId": "rs_20260831_000042",
  "runId": "run_20260831_000042",
  "templateVersionId": "rtv_000007",
  "snapshotSchemaVersion": "report-1.0",
  "tenantId": "tenant-example",
  "generatedAt": "2026-09-01T00:15:22Z",
  "businessTimezone": "Asia/Tokyo",
  "period": {
    "from": "2026-08-01",
    "to": "2026-08-31",
    "boundary": "INCLUSIVE"
  },
  "cutoff": {
    "kind": "MONTH_END",
    "asOf": "2026-08-31T23:59:59+09:00",
    "closedThrough": "2026-07",
    "closingState": "OPEN_FOR_AUGUST",
    "description": "8月は速報、7月までを月次締め済み確定として表示する"
  },
  "freshness": {
    "dataAsOf": "2026-08-31T23:59:59+09:00",
    "observedAt": "2026-09-01T00:15:18+09:00",
    "staleAfter": "2026-09-01T06:00:00+09:00",
    "status": "FRESH"
  },
  "retention": {
    "years": 7,
    "legalHoldAware": true
  },
  "scope": {
    "ownerType": "SYSTEM_PRINCIPAL",
    "ownerId": "report-scheduler",
    "policyVersion": "scope-policy-approved-1",
    "organizationIds": ["org-example"],
    "allowedSetHash": "sha256:example-scope-hash",
    "sessionIndependent": true
  },
  "sections": [
    {
      "sectionKey": "revenue.actual",
      "status": "SUCCEEDED",
      "classification": {
        "factType": "実績",
        "confirmation": "確定"
      },
      "period": {
        "from": "2026-07-01",
        "to": "2026-07-31",
        "boundary": "INCLUSIVE"
      },
      "cutoff": {
        "kind": "MONTHLY_CLOSING",
        "asOf": "2026-07-31T23:59:59+09:00"
      },
      "freshness": {
        "dataAsOf": "2026-07-31T23:59:59+09:00",
        "status": "FRESH"
      },
      "canonical": {
        "service": "MonthlyRevenueCalcService",
        "dto": "MonthlyAmount",
        "adapterVersion": "revenue-adapter-sample-1"
      },
      "source": {
        "rowCount": 12,
        "sourceHash": "sha256:example-revenue-source-hash"
      },
      "value": {
        "salesYen": 12000000,
        "grossProfitYen": 3600000,
        "hasActual": true
      }
    },
    {
      "sectionKey": "revenue.forecast",
      "status": "SUCCEEDED",
      "classification": {
        "factType": "予測",
        "confirmation": "速報"
      },
      "period": {
        "from": "2026-08-01",
        "to": "2026-08-31",
        "boundary": "INCLUSIVE"
      },
      "cutoff": {
        "kind": "GENERATED_AT",
        "asOf": "2026-09-01T00:15:18+09:00"
      },
      "freshness": {
        "dataAsOf": "2026-09-01T00:15:18+09:00",
        "status": "FRESH"
      },
      "canonical": {
        "service": "DashboardService",
        "dto": "DashboardSummaryDto",
        "adapterVersion": "dashboard-forecast-adapter-sample-1"
      },
      "source": {
        "rowCount": 4,
        "sourceHash": "sha256:example-forecast-source-hash"
      },
      "value": {
        "pipelineCount": 4,
        "pipelineAmountYen": 4200000,
        "forecastSalesYen": 16200000
      }
    },
    {
      "sectionKey": "utilization",
      "status": "SUCCEEDED",
      "classification": {
        "factType": "予測",
        "confirmation": "速報"
      },
      "period": {
        "from": "2026-08-01",
        "to": "2026-08-31",
        "boundary": "INCLUSIVE"
      },
      "cutoff": {
        "kind": "GENERATED_AT",
        "asOf": "2026-09-01T00:15:18+09:00"
      },
      "freshness": {
        "dataAsOf": "2026-09-01T00:15:18+09:00",
        "status": "FRESH"
      },
      "canonical": {
        "service": "UtilizationCalcService",
        "dto": "UtilizationSummary",
        "adapterVersion": "utilization-adapter-sample-1"
      },
      "source": {
        "rowCount": 20,
        "sourceHash": "sha256:example-utilization-source-hash"
      },
      "value": {
        "workingCount": 16,
        "benchCount": 4,
        "totalCount": 20,
        "utilizationRate": 80.0
      }
    },
    {
      "sectionKey": "serviceDesk.sla",
      "status": "UNAVAILABLE",
      "classification": {
        "factType": "未提供",
        "confirmation": "未定義"
      },
      "canonical": null,
      "error": {
        "code": "DEPENDENCY_NOT_ACCEPTED",
        "message": "NF-02 の ServiceDesk/SLA 正本が未受入のため対象外"
      }
    }
  ],
  "recipientPreview": {
    "status": "APPROVED_SCOPE_CHECKED",
    "evaluatedAt": "2026-09-01T00:15:20+09:00",
    "recipients": [
      {
        "recipientRef": "user-example-in-scope",
        "scopeDecision": "ALLOW",
        "reasonCode": "SCOPE_MATCH"
      },
      {
        "recipientRef": "user-example-out-of-scope",
        "scopeDecision": "DENY",
        "reasonCode": "RECIPIENT_SCOPE_MISMATCH"
      }
    ]
  },
  "artifact": {
    "sourceSnapshotId": "rs_20260831_000042",
    "documentSourceType": "REPORT_SNAPSHOT",
    "contentHash": "sha256:example-document-hash",
    "documentVersion": 1,
    "deliveryMode": "IN_APP_NOTIFICATION_WITH_EXPIRING_LINK",
    "linkExpiresAt": "2026-09-08T00:15:20+09:00",
    "reauthorizeAfterExpiry": true,
    "downloadRequiresReauthentication": true,
    "mailAttachment": false
  }
}
```

## 3. 不変性ルール（仮）

- `snapshotId` は一度作成した section value、canonical 識別子、scope、cutoff、freshness、source hash を更新しない。snapshot/documentは7年間保持する。
- template/version、現在 DB 値、現在の recipient 権限を変更しても過去 run の JSON・document は変化しない。
- 明示的な再生成は旧 snapshot の上書きではなく、新しい run/version として旧 snapshot への関係を持つ。generation retryは同一runの同一snapshotを再利用し、重複snapshotを作らない。
- `FAILED` section は値を配布可能な値として扱わず、失敗理由をsanitized codeで監査する。sectionが1つでも失敗したrunは`PARTIAL`/`FAILED`として配布停止する。
- document はこの snapshot を唯一の入力とし、render 時に正本 DB を再集計しない。PDF/XLSX/CSV の金額セルは同一 value contract から作る。
- recipient previewはgeneration前に実施し、scope mismatchをrejectする。generation時とdownload/open時にも`DocumentService`のscope/access checkを再実施する。権限喪失、組織異動、link期限切れは拒否し、download時は再認証を要求する。

## 4. contract test の入力境界（仮）

画面 DTO、既存 export DTO、snapshot section value を同じ fixture の正本 service 出力から比較する。比較対象は sales、gross profit、utilization、AR balance などの指標値とし、表示用のラベル・通貨・速報/確定フラグは別の presentation assertion にする。fixture は tenant timezone、月末、確定 record、予測 pipeline、scope 外 record を含む。

## 5. 実装固定項目

`recipientPreview.status`はscope check後の承認状態、partial sectionは配布停止、retentionは7年、deliveryは站内通知＋期限付きlink、downloadは再認証、generation retryは同一run・同一snapshotとする。ServiceDesk/SLAはNF-02 PASSまで対象外である。
