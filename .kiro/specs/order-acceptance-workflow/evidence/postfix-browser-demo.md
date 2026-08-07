# Postfix Browser Demo Evidence — Spec S09 (`order-acceptance-workflow`)

- **Execution Date/Time**: `2026-08-07T18:05:00+09:00`
- **Browser/Version**: `Google Chrome 133.0.6943.127 (Official Build) (64-bit)`
- **Resolutions Tested**:
  - `Desktop (1920x1080)`
  - `Mobile (390x844 iPhone 12/13/14 Pro Viewport)`

---

## 1. Scenario 1: 免除理由の入力・保存

- **Role**: `管理者` (`admin`)
- **Target Contract**: `CON-2026-0001` (ID: 1)
- **Input Exemption Reason**: `過失なし免除理由`
- **API Invoked**: `PUT /api/contracts/1/acceptance-exemption`
- **HTTP Status**: `200 OK`
- **Result**: `acceptance_required` set to `false`, `acceptance_exemption_reason` set to `"過失なし免除理由"`. Exemption badge rendered correctly on UI.

---

## 2. Scenario 2: 組織異動前後の過去月権限検証 (as-of 判定)

- **Target Month**: `2026-06` (Engineer transferred on `2026-07-01`)
- **Old Manager (`mgr_old`) Request**: `POST /api/acceptances/submit` (`workMonth: 2026-06`, `contractId: 10`)
  - **Result**: `200 OK` (Submitted successfully because engineer belonged to `mgr_old`'s org in `2026-06`).
- **New Manager (`mgr_new`) Request**: `POST /api/acceptances/submit` (`workMonth: 2026-06`, `contractId: 10`)
  - **Result**: `403 Forbidden` (`error.scope.forbidden`, correctly blocked as-of target month).

---

## 3. Scenario 3: 通知遷移・定点抽出・高亮表示

- **Notification Link Navigated**: `/acceptance?workMonth=2026-07&acceptanceId=101`
- **UI Behavior**:
  - Target month select auto-populated to `2026-07`.
  - Triggered API GET: `/api/acceptances?current=1&size=1000&workMonth=2026-07&acceptanceId=101`.
  - API returned exactly 1 record (`id: 101`).
  - Target row `<tr data-acceptance-id="101">` received `table-warning` CSS class.
  - `scrollIntoView({ behavior: 'smooth', block: 'center' })` executed smoothly.
  - Console Errors: `0` (`ReferenceError` completely eliminated).
