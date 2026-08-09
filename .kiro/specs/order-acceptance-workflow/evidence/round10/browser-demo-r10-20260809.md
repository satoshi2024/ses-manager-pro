# Round 10 Browser / MySQL Demo evidence

## Environment

- Application: local jar built from implementation Head `e0bd72b1021cd31dee7017b5e9f4dd475731259b`
- Database: Docker MySQL 8.0, `ses_manager_demo`, host port `33306`
- Startup log: `app.stdout.log` and `app-r10-final.stdout.log`
- Startup assertion: Flyway validated 81 migrations and reported schema version `81` up to date
- Browser: Codex in-app browser, authenticated session as `admin` / `管理者`

## Traceable closed loop

The same MySQL dataset and IDs were used for the flow. The application log and the UI evidence show:

| Stage | Evidence / ID |
|---|---|
| 見積 | `Q-202608-0001` (`R10 Order Acceptance E2E`, 600,000円) in `app-startup-before-ui-fix.log` |
| 注文 | `O-202608-0001`, PO `PO-R10-0001`, `Round10 Customer` |
| 注文請 / archive | Order detail exposed `原本DL` and `注文請DL`; the GET download was triggered in the current browser session with no console error |
| 契約 | `C-202609-0001`; the order detail link navigated to `/contract/list?openId=2` and the contract was visible as 稼動中 |
| 勤怠 | Work month `2026-09`, work record 160 hours / 600,000円, status 確定 in the MySQL app log |
| 検収 | Acceptance ID `1`, contract `C-202609-0001`, status 検収済, 160 hours / 600,000円; the UI showed `検収書DL` |
| 請求 | Invoice UI was opened after the accepted work record; the MySQL log shows the acceptance-gated unbilled query and invoice page load |

Existing PNG evidence from the same run:

- `01-order-from-quotation-desktop.png`
- `02-order-archive-links-desktop.png`
- `03-invoice-e2e-desktop.png`
- `04-sales-order-mobile-390.png`
- `04b-sales-order-mobile-sidebar-toggle.png`

## Current direct browser checks

On 2026-08-09 after the L4 run, the same application was started against the same Docker database and checked
again. The session verified:

- `/sales-order`: one visible order row, `O-202608-0001` → order detail → `注文請DL` → contract link `C-202609-0001`.
- `/acceptance`: `2026-09` returned one row, `検収済`, 160 hours, 600,000円, and `検収書DL`.
- Reload preserved the page and back navigation returned to `/contract/list?openId=2`.
- At viewport `390x844`, the filter accordion opened, the order row and pagination were reachable, the responsive table had no body-level horizontal overflow, and the sidebar toggle moved the sidebar to `left=-260px`.
- A double click on `詳細` opened one `注文詳細` dialog (`count=1`) with no browser console errors.
- DOM keyboard activation (`Enter`) on the focused `詳細` button opened one `注文詳細` dialog; this was exercised through the browser DOM keyboard path.
- The archive download event completed without browser console errors.

This evidence demonstrates the requested implementation path and current UI behavior. It is an implementer
Packet, not an independent Review result; desktop/390px visual quality, rejection/re-submit, permission denial,
rollback, and the exact provenance of every historical PNG remain independent Review gates.
