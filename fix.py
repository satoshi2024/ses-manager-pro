import os
import re

file_path = r'c:\Users\pc\Documents\ses-manager-pro\src\main\java\com\ses\service\NotificationGenerateService.java'
with open(file_path, 'r', encoding='utf-8') as f:
    text = f.read()

# Replace hardcoded messages with JSON arrays
text = text.replace(
    'String message = "請求書 " + inv.getInvoiceNo() + "（" + customerName + "）が支払期限を" + days + "日超過しています";',
    'String message = "[\\"notification.msg.INVOICE_OVERDUE\\", \\"" + inv.getInvoiceNo() + "\\", \\"" + customerName + "\\", \\"" + days + "\\"]";'
)

text = text.replace(
    'String message = name + "氏の稼動終了が" + days + "日以内に迫っています（終了日：" + c.getEndDate() + "）";',
    'String message = "[\\"notification.msg.CONTRACT_END\\", \\"" + name + "\\", \\"" + days + "\\", \\"" + c.getEndDate() + "\\"]";'
)

text = text.replace(
    'String message = "提案ID " + p.getId() + " のステータスが" + days + "日以上更新されていません（現在：" + p.getStatus() + "）";',
    'String message = "[\\"notification.msg.PROPOSAL_STALE\\", \\"" + p.getId() + "\\", \\"" + days + "\\", \\"" + p.getStatus() + "\\"]";'
)

text = text.replace(
    'String message = name + "氏の待機期間が" + days + "日を超えています";',
    'String message = "[\\"notification.msg.BENCH_LONG\\", \\"" + name + "\\", \\"" + days + "\\"]";'
)

text = text.replace(
    'String message = "急募案件「" + p.getProjectName() + "」が募集中です";',
    'String message = "[\\"notification.msg.PROJECT_URGENT\\", \\"" + p.getProjectName() + "\\"]";'
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(text)
