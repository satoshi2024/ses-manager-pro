import sys
import re

try:
    with open('src/main/java/com/ses/service/impl/InvoiceServiceImpl.java', 'r', encoding='utf-8') as f:
        content = f.read()

    part1_replace = '''        checkClosing(invoice.getBillingMonth());
        List<InvoicePaymentResponse> payments = listPayments(id);
        if (!payments.isEmpty()) {'''
    
    # regex 1
    content = re.sub(r'<<<<<<< HEAD\s+List<InvoicePaymentResponse> payments = listPayments\(id\);\s+if \(!payments\.isEmpty\(\)\) \{\s+=======\s+checkClosing\(invoice\.getBillingMonth\(\)\);\s+if \("[^"]+"\.equals\(invoice\.getStatus\(\)\)\) \{\s+>>>>>>> fix-bug-hunt-p3', part1_replace, content)

    # regex 2
    content = re.sub(r'<<<<<<< HEAD(\s+@Override\s+public java\.util\.List<MailDispatchResult> sendReminders[\s\S]*?)=======\s+\}\s+>>>>>>> fix-bug-hunt-p3', r'\1', content)

    with open('src/main/java/com/ses/service/impl/InvoiceServiceImpl.java', 'w', encoding='utf-8') as f:
        f.write(content)
    print("Resolved successfully")
except Exception as e:
    print(e)
