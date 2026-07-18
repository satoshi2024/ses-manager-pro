import sys

path = 'src/main/resources/static/js/modules/invoice.js'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

find1 = "${['送付済', '一部入金'].includes(inv.status) ? `<button class=\"btn btn-sm btn-success\" onclick=\"openPaymentModal(${inv.id}, '${SES.escapeHtml(inv.invoiceNo)}', ${inv.total})\">${SES.i18n.t('invoice.btn.payment', '入金')}</button>` : ''}"
rep1 = "${['送付済', '一部入金', '入金済'].includes(inv.status) ? `<button class=\"btn btn-sm ${inv.status === '入金済' ? 'btn-outline-success' : 'btn-success'}\" onclick=\"openPaymentModal(${inv.id}, '${SES.escapeHtml(inv.invoiceNo)}', ${inv.total})\">${inv.status === '入金済' ? SES.i18n.t('invoice.btn.paymentHistory', '入金履歴') : SES.i18n.t('invoice.btn.payment', '入金')}</button>` : ''}"

text = text.replace(find1, rep1)

find2 = '''            const balance = currentPaymentInvoiceTotal - paid;
            const balanceEl = document.getElementById('paymentBalance');
            balanceEl.textContent = '￥' + balance.toLocaleString();
            document.getElementById('paymentRemaining').textContent =
                balance > 0 ? SES.i18n.t('invoice.payment.remaining', 'あと {0} 円で入金済').replace('{0}', balance.toLocaleString()) : '';'''

rep2 = '''            const balance = currentPaymentInvoiceTotal - paid;
            const balanceEl = document.getElementById('paymentBalance');
            balanceEl.textContent = '￥' + balance.toLocaleString();
            document.getElementById('paymentRemaining').textContent =
                balance > 0 ? SES.i18n.t('invoice.payment.remaining', 'あと {0} 円で入金済').replace('{0}', balance.toLocaleString()) : '';
            
            const disableForm = balance <= 0;
            document.querySelector('#paymentForm [name="amount"]').disabled = disableForm;
            document.querySelector('#paymentForm [name="fee"]').disabled = disableForm;
            document.querySelector('#paymentForm [name="paidDate"]').disabled = disableForm;
            document.querySelector('#paymentForm [name="remarks"]').disabled = disableForm;
            document.getElementById('btnSubmitPayment').disabled = disableForm;'''

text = text.replace(find2.replace('\r\n', '\n'), rep2)
text = text.replace(find2, rep2)

with open(path, 'w', encoding='utf-8') as f:
    f.write(text)