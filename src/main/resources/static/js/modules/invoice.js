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
            const todayStr = getLocalDateString();
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
                        ${['送付済', '一部入金', '入金済'].includes(inv.status) ? `<button class="btn btn-sm ${inv.status === '入金済' ? 'btn-outline-success' : 'btn-success'}" onclick="openPaymentModal(${inv.id}, '${SES.escapeHtml(inv.invoiceNo)}', ${inv.total})">${inv.status === '入金済' ? SES.i18n.t('invoice.btn.paymentHistory', '入金履歴') : SES.i18n.t('invoice.btn.payment', '入金')}</button>` : ''}
                        ${overdue && ['送付済', '一部入金'].includes(inv.status) ? `<button class="btn btn-sm btn-outline-danger" onclick="openReminderModal(${inv.id}, '${SES.escapeHtml(inv.invoiceNo)}')">${SES.i18n.t('invoice.btn.reminder', '督促')}</button>` : ''}
                        ${['未送付', '送付済'].includes(inv.status) ? `<button class="btn btn-sm btn-danger" onclick="voidInvoice(${inv.id}, '${SES.escapeHtml(inv.invoiceNo)}')">${SES.i18n.t('invoice.btn.void')}</button>` : ''}
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
        paidDate = prompt(SES.i18n.t('invoice.prompt.paidDate'), getLocalDateString());
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
                let indentStyle = '';
                let prefix = '';
                if (bp.layerOrder && bp.layerOrder > 1) {
                    indentStyle = `padding-left: ${(bp.layerOrder - 1) * 20}px; border-left: 3px solid #ccc;`;
                    prefix = '└ ';
                }
                tr.innerHTML = `
                    <td style="${indentStyle}">${prefix}${bp.workMonth}</td>
                    <td>${SES.escapeHtml(bp.engineerName)}<br><small class="text-muted">${bp.payeeCompanyName ? SES.escapeHtml(bp.payeeCompanyName) : ''}</small></td>
                    <td>${SES.escapeHtml(bp.projectName)}</td>
                    <td class="text-right">￥${bp.amount.toLocaleString()}</td>
                    <td>${SES.i18n.t('invoice.status.' + bp.status, bp.status)}</td>
                    <td>${bp.paidDate || ''}</td>
                    <td>
                        ${bp.status === '未払' ? `<button class="btn btn-sm btn-success" onclick="updateBpPaymentStatus(${bp.id}, '支払済')">${SES.i18n.t('invoice.btn.markPaid')}</button>` : ''}
                        ${bp.status === '支払済' ? `<button class="btn btn-sm btn-warning" onclick="updateBpPaymentStatus(${bp.id}, '未払')">${SES.i18n.t('invoice.btn.revertToUnpaid')}</button>` : ''}
                        <button class="btn btn-sm btn-info" onclick="openBpPaymentLayerModal(${bp.workRecordId}, ${bp.id}, ${bp.layerOrder ? bp.layerOrder + 1 : 2})">階層追加</button>
                    </td>
                `;
                tbody.appendChild(tr);
            });
        }
    });
}

function openBpPaymentLayerModal(workRecordId, parentPaymentId, nextLayerOrder) {
    document.querySelector('#bpPaymentLayerForm [name="workRecordId"]').value = workRecordId;
    document.querySelector('#bpPaymentLayerForm [name="parentPaymentId"]').value = parentPaymentId;
    document.querySelector('#bpPaymentLayerForm [name="layerOrder"]').value = nextLayerOrder;
    document.querySelector('#bpPaymentLayerForm [name="payeeCompanyName"]').value = '';
    document.querySelector('#bpPaymentLayerForm [name="amount"]').value = '';
    document.querySelector('#bpPaymentLayerForm [name="remarks"]').value = '';
    new bootstrap.Modal(document.getElementById('bpPaymentLayerModal')).show();
}

document.addEventListener('DOMContentLoaded', () => {
    // ... existing init ...
    const btnSubmitLayer = document.getElementById('btnSubmitBpPaymentLayer');
    if (btnSubmitLayer) {
        btnSubmitLayer.addEventListener('click', () => {
            const form = document.getElementById('bpPaymentLayerForm');
            const workRecordId = form.querySelector('[name="workRecordId"]').value;
            const data = {
                parentPaymentId: form.querySelector('[name="parentPaymentId"]').value,
                layerOrder: parseInt(form.querySelector('[name="layerOrder"]').value, 10),
                payeeCompanyName: form.querySelector('[name="payeeCompanyName"]').value,
                amount: parseFloat(form.querySelector('[name="amount"]').value),
                remarks: form.querySelector('[name="remarks"]').value,
                status: '未払'
            };

            fetch(`/api/work-records/${workRecordId}/bp-payments`, {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
                body: JSON.stringify(data)
            }).then(res => res.json()).then(resData => {
                if (resData.code === 200) {
                    bootstrap.Modal.getInstance(document.getElementById('bpPaymentLayerModal')).hide();
                    loadBpPayments();
                } else {
                    alert(resData.message || 'Error occurred');
                }
            });
        });
    }
});

function updateBpPaymentStatus(id, status) {
    let paidDate = null;
    if (status === '支払済') {
        paidDate = prompt(SES.i18n.t('invoice.prompt.paymentDate'), getLocalDateString());
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

// ===== 債権管理（ar-management / P2） =====

let currentPaymentInvoiceId = null;
let currentPaymentInvoiceTotal = 0;

function openPaymentModal(invoiceId, invoiceNo, total) {
    currentPaymentInvoiceId = invoiceId;
    currentPaymentInvoiceTotal = total;
    document.getElementById('paymentInvoiceNo').textContent = invoiceNo;
    document.querySelector('#paymentForm [name="paidDate"]').value = getLocalDateString();
    document.querySelector('#paymentForm [name="amount"]').value = '';
    document.querySelector('#paymentForm [name="fee"]').value = '0';
    document.querySelector('#paymentForm [name="remarks"]').value = '';
    loadPayments();
    new bootstrap.Modal(document.getElementById('paymentModal')).show();
}

function loadPayments() {
    fetch(`/api/invoices/${currentPaymentInvoiceId}/payments`)
        .then(res => res.json()).then(data => {
            if (data.code !== 200) return;
            const tbody = document.querySelector('#paymentHistoryTable tbody');
            tbody.innerHTML = '';
            let paid = 0;
            data.data.forEach(p => {
                paid += Number(p.amount) + Number(p.fee || 0);
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${SES.escapeHtml(p.paidDate)}</td>
                    <td class="text-right">￥${Number(p.amount).toLocaleString()}</td>
                    <td class="text-right">￥${Number(p.fee || 0).toLocaleString()}</td>
                    <td>${p.remarks ? SES.escapeHtml(p.remarks) : ''}</td>
                    <td><button class="btn btn-sm btn-outline-danger" onclick="deletePayment(${p.id})">${SES.i18n.t('common.btn.delete', '削除')}</button></td>`;
                tbody.appendChild(tr);
            });
            const balance = currentPaymentInvoiceTotal - paid;
            const balanceEl = document.getElementById('paymentBalance');
            balanceEl.textContent = '￥' + balance.toLocaleString();
            document.getElementById('paymentRemaining').textContent =
                balance > 0 ? SES.i18n.t('invoice.payment.remaining', 'あと {0} 円で入金済').replace('{0}', balance.toLocaleString()) : '';
            
            const disableForm = balance <= 0;
            document.querySelector('#paymentForm [name="amount"]').disabled = disableForm;
            document.querySelector('#paymentForm [name="fee"]').disabled = disableForm;
            document.querySelector('#paymentForm [name="paidDate"]').disabled = disableForm;
            document.querySelector('#paymentForm [name="remarks"]').disabled = disableForm;
            document.getElementById('btnSubmitPayment').disabled = disableForm;
        });
}

function submitPayment() {
    const amount = parseFloat(document.querySelector('#paymentForm [name="amount"]').value);
    const fee = parseFloat(document.querySelector('#paymentForm [name="fee"]').value) || 0;
    const paidDate = document.querySelector('#paymentForm [name="paidDate"]').value;
    const remarks = document.querySelector('#paymentForm [name="remarks"]').value;
    if (!amount || amount <= 0 || !paidDate) {
        alert(SES.i18n.t('invoice.alert.inputRequired', '入力してください'));
        return;
    }
    fetch(`/api/invoices/${currentPaymentInvoiceId}/payments`, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify({ amount, fee, paidDate, remarks })
    }).then(res => res.json()).then(data => {
        if (data.code === 200) {
            document.querySelector('#paymentForm [name="amount"]').value = '';
            document.querySelector('#paymentForm [name="fee"]').value = '0';
            document.querySelector('#paymentForm [name="remarks"]').value = '';
            loadPayments();
            loadInvoices();
        } else {
            alert(data.message);
        }
    });
}

function deletePayment(paymentId) {
    Swal.fire({
        title: SES.i18n.t('common.title.confirm', '確認'),
        text: SES.i18n.t('invoice.payment.deleteConfirm', 'この入金記録を削除しますか？'),
        icon: 'warning',
        showCancelButton: true
    }).then(result => {
        if (!result.isConfirmed) return;
        fetch(`/api/invoices/${currentPaymentInvoiceId}/payments/${paymentId}`, {
            method: 'DELETE',
            headers: SES.csrf.header()
        }).then(res => res.json()).then(data => {
            if (data.code === 200) {
                loadPayments();
                loadInvoices();
            } else {
                alert(data.message);
            }
        });
    });
}

// ----- エイジング -----
function loadAging() {
    const asOf = document.getElementById('agingAsOf').value;
    let url = '/api/invoices/aging';
    if (asOf) url += '?asOf=' + asOf;
    fetch(url).then(res => res.json()).then(data => {
        if (data.code !== 200) return;
        const tbody = document.querySelector('#agingTable tbody');
        tbody.innerHTML = '';
        const cols = ['unsent', 'notDue', 'd1to30', 'd31to60', 'd61to90', 'd91plus', 'noDueDate', 'balance'];
        (data.data.rows || []).forEach(r => {
            const tr = document.createElement('tr');
            let cells = `<td>${SES.escapeHtml(r.customerName || '')}</td>`;
            cols.forEach(c => {
                const val = Number(r[c] || 0);
                const str = `￥${val.toLocaleString()}`;
                if (val > 0 && r.customerId) {
                    cells += `<td class="text-right"><a href="/invoice/list?customerId=${r.customerId}">${str}</a></td>`;
                } else {
                    cells += `<td class="text-right">${str}</td>`;
                }
            });
            tr.innerHTML = cells;
            tbody.appendChild(tr);
        });
        const t = data.data.total || {};
        const tfoot = document.querySelector('#agingTable tfoot');
        let tcells = `<td><strong>${SES.i18n.t('common.total', '合計')}</strong></td>`;
        cols.forEach(c => { tcells += `<td class="text-right"><strong>￥${Number(t[c] || 0).toLocaleString()}</strong></td>`; });
        tfoot.innerHTML = `<tr>${tcells}</tr>`;
    });
}

function exportAging() {
    const asOf = document.getElementById('agingAsOf').value;
    let url = '/api/invoices/aging-export';
    if (asOf) url += '?asOf=' + asOf;
    window.location.href = url;
}

// ----- 督促メール -----
let currentReminderInvoiceId = null;

function openReminderModal(invoiceId, invoiceNo) {
    currentReminderInvoiceId = invoiceId;
    document.getElementById('reminderInvoiceNo').textContent = invoiceNo;
    const sel = document.getElementById('reminderTemplate');
    sel.innerHTML = '';
    fetch('/api/invoices/reminder-templates').then(res => res.json()).then(data => {
        if (data.code === 200) {
            data.data.forEach(t => {
                const opt = document.createElement('option');
                opt.value = t.id;
                opt.textContent = t.templateName;
                sel.appendChild(opt);
            });
        }
    });
    new bootstrap.Modal(document.getElementById('reminderModal')).show();
}

function submitReminder() {
    const templateId = document.getElementById('reminderTemplate').value;
    if (!templateId) return;
    fetch(`/api/invoices/${currentReminderInvoiceId}/reminder`, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify({ templateId: Number(templateId) })
    }).then(res => res.json()).then(data => {
        if (data.code === 200) {
            bootstrap.Modal.getInstance(document.getElementById('reminderModal')).hide();
            if (data.data && data.data.status === 'FAILED') {
                if (window.SES && SES.toast) SES.toast('メール送信に失敗しました', 'error');
                else alert('メール送信に失敗しました');
            } else {
                if (window.SES && SES.toast) SES.toast(SES.i18n.t('invoice.reminder.sent', '督促メールを送信しました'), 'success');
                else alert(SES.i18n.t('invoice.reminder.sent', '督促メールを送信しました'));
            }
        } else {
            alert(data.message);
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    const btnPay = document.getElementById('btnSubmitPayment');
    if (btnPay) btnPay.addEventListener('click', submitPayment);
    const btnAging = document.getElementById('btnLoadAging');
    if (btnAging) btnAging.addEventListener('click', loadAging);
    const btnAgingExport = document.getElementById('btnExportAging');
    if (btnAgingExport) btnAgingExport.addEventListener('click', exportAging);
    const btnReminder = document.getElementById('btnSubmitReminder');
    if (btnReminder) btnReminder.addEventListener('click', submitReminder);
    const agingTab = document.getElementById('aging-tab');
    if (agingTab) agingTab.addEventListener('shown.bs.tab', loadAging);
});

function getLocalDateString() {
    const d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
