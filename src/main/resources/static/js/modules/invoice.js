document.addEventListener('DOMContentLoaded', () => {
    loadInvoices();
    
    document.getElementById('btnGenerateInvoice').addEventListener('click', () => {
        new bootstrap.Modal(document.getElementById('generateModal')).show();
    });

    document.getElementById('btnSubmitGenerate').addEventListener('click', () => {
        const customerId = document.querySelector('#generateForm [name="customerId"]').value;
        const billingMonth = document.querySelector('#generateForm [name="billingMonth"]').value;

        if (!customerId || !billingMonth) {
            alert('顧客IDと対象月を入力してください。');
            return;
        }

        fetch('/api/invoices/generate', {
            method: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
            body: JSON.stringify({ customerId, billingMonth })
        }).then(res => res.json()).then(data => {
            if (data.code === 200) {
                alert('請求書を生成しました。');
                bootstrap.Modal.getInstance(document.getElementById('generateModal')).hide();
                loadInvoices();
            } else {
                alert(data.message || '生成に失敗しました。');
            }
        });
    });

    document.getElementById('billingMonth').addEventListener('change', loadInvoices);
    
    document.getElementById('btnSearchBpPayment').addEventListener('click', loadBpPayments);
    document.getElementById('bpWorkMonth').addEventListener('change', loadBpPayments);
});

function loadInvoices() {
    const month = document.getElementById('billingMonth').value;
    let url = '/api/invoices?current=1&size=100';
    if (month) url += `&month=${month}`;

    fetch(url).then(res => res.json()).then(data => {
        if (data.code === 200) {
            const tbody = document.querySelector('#invoiceTable tbody');
            tbody.innerHTML = '';
            data.data.records.forEach(inv => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${inv.invoiceNo}</td>
                    <td>${inv.billingMonth}</td>
                    <td class="text-right">￥${inv.subtotal.toLocaleString()}</td>
                    <td class="text-right">￥${inv.tax.toLocaleString()}</td>
                    <td class="text-right">￥${inv.total.toLocaleString()}</td>
                    <td>${inv.status}</td>
                    <td>${inv.issuedDate || ''}</td>
                    <td>${inv.paidDate || ''}</td>
                    <td>
                        <a href="/invoice/${inv.id}/print" target="_blank" class="btn btn-sm btn-info">印刷</a>
                        ${inv.status === '未送付' ? `<button class="btn btn-sm btn-primary" onclick="updateInvoiceStatus(${inv.id}, '送付済')">送付済にする</button>` : ''}
                        ${inv.status === '送付済' ? `<button class="btn btn-sm btn-success" onclick="updateInvoiceStatus(${inv.id}, '入金済', true)">入金済にする</button>` : ''}
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
        paidDate = prompt('入金日を入力してください(YYYY-MM-DD)', new Date().toISOString().split('T')[0]);
        if (!paidDate) return;
    } else {
        if (!confirm(`ステータスを「${status}」に変更しますか？`)) return;
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
                    <td>${bp.engineerName}</td>
                    <td>${bp.projectName}</td>
                    <td class="text-right">￥${bp.amount.toLocaleString()}</td>
                    <td>${bp.status}</td>
                    <td>${bp.paidDate || ''}</td>
                    <td>
                        ${bp.status === '未払' ? `<button class="btn btn-sm btn-success" onclick="updateBpPaymentStatus(${bp.id}, '支払済')">支払済にする</button>` : ''}
                    </td>
                `;
                tbody.appendChild(tr);
            });
        }
    });
}

function updateBpPaymentStatus(id, status) {
    const paidDate = prompt('支払日を入力してください(YYYY-MM-DD)', new Date().toISOString().split('T')[0]);
    if (!paidDate) return;

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
