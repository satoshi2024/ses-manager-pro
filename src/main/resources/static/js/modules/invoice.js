document.addEventListener('DOMContentLoaded', () => {
    loadInvoices();
    
    document.getElementById('btnGenerateInvoice').addEventListener('click', () => {
        new bootstrap.Modal(document.getElementById('generateModal')).show();
    });

    document.getElementById('btnSubmitGenerate').addEventListener('click', () => {
        const customerId = document.querySelector('#generateForm [name="customerId"]').value;
        const billingMonth = document.querySelector('#generateForm [name="billingMonth"]').value;

        if (!customerId || !billingMonth) {
            alert(SES.i18n.t('invoice.alert.inputRequired'));
            return;
        }

        fetch('/api/invoices/generate', {
            method: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
            body: JSON.stringify({ customerId, billingMonth })
        }).then(res => res.json()).then(data => {
            if (data.code === 200) {
                alert(SES.i18n.t('invoice.alert.generateSuccess'));
                bootstrap.Modal.getInstance(document.getElementById('generateModal')).hide();
                loadInvoices();
            } else {
                alert(data.message || SES.i18n.t('invoice.alert.generateFail'));
            }
        });
    });

    document.getElementById('billingMonth').addEventListener('change', loadInvoices);
    document.getElementById('filterOverdue').addEventListener('change', loadInvoices);

    document.getElementById('btnSearchBpPayment').addEventListener('click', loadBpPayments);
    document.getElementById('bpWorkMonth').addEventListener('change', loadBpPayments);
});

function loadInvoices() {
    const month = document.getElementById('billingMonth').value;
    const overdueEl = document.getElementById('filterOverdue');
    let url = '/api/invoices?current=1&size=100';
    if (month) url += `&month=${month}`;
    if (overdueEl && overdueEl.checked) url += '&overdue=true';

    fetch(url).then(res => res.json()).then(data => {
        if (data.code === 200) {
            const tbody = document.querySelector('#invoiceTable tbody');
            tbody.innerHTML = '';
            const todayStr = new Date().toISOString().split('T')[0];
            data.data.records.forEach(inv => {
                const tr = document.createElement('tr');
                // 未入金かつ支払期限を過ぎている場合は期限を赤字で強調する
                const overdue = inv.status !== '入金済' && inv.dueDate && inv.dueDate < todayStr;
                const dueCell = inv.dueDate
                    ? `<td class="${overdue ? 'text-danger fw-bold' : ''}">${SES.escapeHtml(inv.dueDate)}</td>`
                    : '<td>-</td>';
                tr.innerHTML = `
                    <td>${SES.escapeHtml(inv.invoiceNo)}</td>
                    <td>${SES.escapeHtml(inv.billingMonth)}</td>
                    <td class="text-right">￥${inv.subtotal.toLocaleString()}</td>
                    <td class="text-right">￥${inv.tax.toLocaleString()}</td>
                    <td class="text-right">￥${inv.total.toLocaleString()}</td>
                    <td>${SES.escapeHtml(SES.i18n.t('invoice.status.' + inv.status, inv.status))}</td>
                    <td>${inv.issuedDate || ''}</td>
                    ${dueCell}
                    <td>${inv.paidDate || ''}</td>
                    <td>
                        <a href="/invoice/${inv.id}/print" target="_blank" class="btn btn-sm btn-info">${SES.i18n.t('common.btn.print')}</a>
                        ${inv.status === '未送付' ? `<button class="btn btn-sm btn-primary" onclick="updateInvoiceStatus(${inv.id}, '送付済')">${SES.i18n.t('invoice.btn.markSent')}</button>` : ''}
                        ${inv.status === '送付済' ? `<button class="btn btn-sm btn-success" onclick="updateInvoiceStatus(${inv.id}, '入金済', true)">${SES.i18n.t('invoice.btn.markPaid')}</button>` : ''}
                        ${['未送付', '送付済'].includes(inv.status) ? `<button class="btn btn-sm btn-danger" onclick="voidInvoice(${inv.id}, '${SES.escapeHtml(inv.invoiceNo)}')">${SES.i18n.t('invoice.btn.void')}</button>` : ''}
                        ${inv.status === '入金済' ? `<button class="btn btn-sm btn-warning" onclick="updateInvoiceStatus(${inv.id}, '送付済', false)">${SES.i18n.t('invoice.btn.revertToSent')}</button>` : ''}
                    </td>
                `;
                tbody.appendChild(tr);
            });
        }
    });
}

function updateInvoiceStatus(id, status, requireDate = false) {
    let paidDate = null;
    if (requireDate) {
        paidDate = prompt(SES.i18n.t('invoice.prompt.paidDate'), new Date().toISOString().split('T')[0]);
        if (!paidDate) return;
    } else {
        if (!confirm(SES.i18n.t('invoice.confirm.changeStatus', { status: SES.i18n.t('invoice.status.' + status, status) }))) return;
    }

    fetch(`/api/invoices/${id}/status`, {
        method: 'PUT',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify({ status, paidDate })
    }).then(res => res.json()).then(data => {
        if (data.code === 200) {
            loadInvoices();
        } else {
            alert(data.message);
        }
    });
}

function voidInvoice(id, invoiceNo) {
    Swal.fire({
        title: SES.i18n.t('common.title.confirm'),
        text: SES.i18n.t('invoice.confirm.void', { invoiceNo }),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: SES.i18n.t('invoice.btn.void'),
        cancelButtonText: SES.i18n.t('common.btn.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            fetch(`/api/invoices/${id}/void`, {
                method: 'PUT',
                headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header())
            }).then(res => res.json()).then(data => {
                if (data.code === 200) {
                    if (window.SES && SES.toast) {
                        SES.toast(SES.i18n.t('invoice.toast.voidSuccess'), 'success');
                    } else {
                        alert(SES.i18n.t('invoice.toast.voidSuccess'));
                    }
                    loadInvoices();
                } else {
                    if (window.SES && SES.toast) {
                        SES.toast(data.message, 'error');
                    } else {
                        alert(data.message);
                    }
                }
            });
        }
    });
}

function loadBpPayments() {
    const month = document.getElementById('bpWorkMonth').value;
    let url = '/api/invoices/bp-payments?month=' + month;

    fetch(url).then(res => res.json()).then(data => {
        if (data.code === 200) {
            const tbody = document.querySelector('#bpPaymentTable tbody');
            tbody.innerHTML = '';
            data.data.forEach(bp => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${bp.workMonth}</td>
                    <td>${SES.escapeHtml(bp.engineerName)}</td>
                    <td>${SES.escapeHtml(bp.projectName)}</td>
                    <td class="text-right">￥${bp.amount.toLocaleString()}</td>
                    <td>${SES.i18n.t('invoice.status.' + bp.status, bp.status)}</td>
                    <td>${bp.paidDate || ''}</td>
                    <td>
                        ${bp.status === '未払' ? `<button class="btn btn-sm btn-success" onclick="updateBpPaymentStatus(${bp.id}, '支払済')">${SES.i18n.t('invoice.btn.markPaid')}</button>` : ''}
                        ${bp.status === '支払済' ? `<button class="btn btn-sm btn-warning" onclick="updateBpPaymentStatus(${bp.id}, '未払')">${SES.i18n.t('invoice.btn.revertToUnpaid')}</button>` : ''}
                    </td>
                `;
                tbody.appendChild(tr);
            });
        }
    });
}

function updateBpPaymentStatus(id, status) {
    let paidDate = null;
    if (status === '支払済') {
        paidDate = prompt(SES.i18n.t('invoice.prompt.paymentDate'), new Date().toISOString().split('T')[0]);
        if (!paidDate) return;
    } else {
        if (!confirm(SES.i18n.t('invoice.confirm.changeStatus', { status: SES.i18n.t('invoice.status.' + status, status) }))) return;
    }

    fetch(`/api/invoices/bp-payments/${id}`, {
        method: 'PUT',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify({ status, paidDate })
    }).then(res => res.json()).then(data => {
        if (data.code === 200) {
            loadBpPayments();
        } else {
            alert(data.message);
        }
    });
}
