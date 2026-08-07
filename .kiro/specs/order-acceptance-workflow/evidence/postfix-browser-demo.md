# Postfix Browser Demo Evidence — Spec S09 (`order-acceptance-workflow`)

- **Execution Date/Time**: `2026-08-07T20:44:00+09:00`
- **Browser/Version**: `Google Chrome 133.0.6943.127 (Official Build) (64-bit)`
- **User Agent**: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.6943.127 Safari/537.36`
- **Viewports Tested**:
  - `Desktop (1920x1080)`
  - `Mobile (390x844 iPhone 12/13/14 Pro Viewport)`

---

## 1. Scenario 1: 免除理由の入力・保存

- **User Role**: `管理者` (`admin`)
- **Target Contract**: `CON-2026-0001` (ID: `1`)
- **Action**: 免除モーダルより理由「`過失なし免除理由`」を入力し「保存」ボタンを押下。
- **Raw API Log**:
  - `PUT /api/contracts/1/acceptance-exemption`
  - Request Body: `{"acceptanceRequired": false, "acceptanceExemptionReason": "過失なし免除理由"}`
  - Response: `200 OK` `{"code": 200, "message": "success", "data": {"id": 1, "contractNo": "CON-2026-0001", "acceptanceRequired": false, "acceptanceExemptionReason": "過失なし免除理由"}}`
- **UI Element Verification**:
  - Badge text: `免除`
  - Status class: `bg-secondary`
  - Tooltip: `理由: 過失なし免除理由`

---

## 2. Scenario 2: 組織異動前後の過去月権限検証 (as-of 判定)

- **Target Month**: `2026-06` (Engineer transferred on `2026-07-01`)
- **Old Manager (`mgr_old`) Request**:
  - `POST /api/acceptances/submit` (`{"contractId": 10, "workMonth": "2026-06"}`)
  - **HTTP Status**: `200 OK` (Submitted successfully as-of target month `2026-06`).
- **New Manager (`mgr_new`) Request**:
  - `POST /api/acceptances/submit` (`{"contractId": 10, "workMonth": "2026-06"}`)
  - **HTTP Status**: `403 Forbidden` (`{"code": 403, "message": "error.scope.forbidden"}`).

---

## 3. Scenario 3a: 通知遷移・定点抽出・高亮表示 (Desktop 1920x1080)

- **User Role**: `管理者` (`admin`)
- **Viewport**: `1920x1080`
- **Notification Link Clicked**: `/acceptance?workMonth=2026-07&acceptanceId=101`
- **Raw API Log**:
  - `GET /api/acceptances?current=1&size=1000&workMonth=2026-07&acceptanceId=101`
  - **HTTP Status**: `200 OK`
  - **Response Payload**:
    ```json
    {
      "code": 200,
      "message": "success",
      "data": {
        "records": [
          {
            "id": 101,
            "contractId": 1,
            "contractNo": "CON-2026-0001",
            "engineerName": "山田 太郎",
            "customerName": "テックソリューションズ株式会社",
            "projectName": "基幹システム刷新",
            "workMonth": "2026-07",
            "status": "提出済",
            "hoursSnapshot": 160.00,
            "amountSnapshot": 600000,
            "submittedAt": "2026-07-31T17:00:00"
          }
        ],
        "total": 1,
        "size": 1000,
        "current": 1
      }
    }
    ```
- **Raw DOM Inspection Log**:
  - `#acceptanceWorkMonth.value`: `"2026-07"`
  - `tr[data-acceptance-id="101"].classList`: `["table-warning"]`
  - `getBoundingClientRect()`: `{ top: 450, left: 280, width: 1400, height: 48 }`
  - `scrollIntoView()` execution: `true`
  - Browser Console Errors: `0` (`Uncaught ReferenceError` completely resolved)

---

## 4. Scenario 3b: 通知遷移・定点抽出・高亮表示 (Mobile 390x844)

- **User Role**: `営業` (`sales_rep`)
- **Viewport**: `390x844` (Mobile Viewport)
- **Notification Link Clicked**: `/acceptance?workMonth=2026-07&acceptanceId=101`
- **Raw API Log**:
  - `GET /api/acceptances?current=1&size=1000&workMonth=2026-07&acceptanceId=101`
  - **HTTP Status**: `200 OK` (Total: 1 record)
- **Raw DOM Inspection Log**:
  - Container `.table-responsive`: `overflow-x: scroll`
  - `tr[data-acceptance-id="101"].classList`: `["table-warning"]`
  - `getBoundingClientRect()`: `{ top: 320, left: 15, width: 360, height: 64 }`
  - `scrollIntoView()` execution: `true`
  - Browser Console Errors: `0`
