// 契約単価改定履歴（contract-price-history / P6）
$(function () {
    // 編集モーダル表示時: 既存契約なら改定エリアを表示し、履歴があれば単価欄を読み取り専用にする。
    $('#contractModal').on('shown.bs.modal', function () {
        const id = $('#cont-id').val();
        if (id) {
            $('#priceRevisionArea').show();
            refreshPriceRevisionState(id);
        } else {
            $('#priceRevisionArea').hide();
            $('#cont-sellingPrice, #cont-costPrice').prop('readonly', false);
        }
    });
});

function refreshPriceRevisionState(contractId) {
    fetch(`/api/contracts/${contractId}/price-revisions`)
        .then(res => res.json()).then(data => {
            if (data.code !== 200) return;
            const list = data.data || [];
            const hasHistory = list.length > 0;
            // 履歴がある契約は単価直接編集を禁止し「単価改定」経由に一本化する。
            $('#cont-sellingPrice, #cont-costPrice').prop('readonly', hasHistory);
            const d = new Date();
            const nowMonth = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
            const hasReserved = list.some(h => h.applyFromMonth > nowMonth);
            $('#reservedBadge').toggle(hasReserved);
        });
}

// 遡及改定警告を初期化する（別契約のmodalへ旧warningが残らないようにする / R3R-30）。
function hidePriceRevWarning() {
    const el = document.getElementById('priceRevWarning');
    if (el) el.classList.add('d-none');
}

function openPriceRevisionModal() {
    const id = $('#cont-id').val();
    if (!id) return;
    // modal open時に必ず警告を初期化する。
    hidePriceRevWarning();
    loadPriceRevisions(id);
    new bootstrap.Modal(document.getElementById('priceRevisionModal')).show();
}

function loadPriceRevisions(contractId) {
    // 履歴load開始時に警告を初期化する。
    hidePriceRevWarning();
    fetch(`/api/contracts/${contractId}/price-revisions`)
        .then(res => res.json()).then(data => {
            if (data.code !== 200) { hidePriceRevWarning(); return; }
            const tbody = document.querySelector('#priceRevTable tbody');
            tbody.innerHTML = '';
            const d = new Date();
            const nowMonth = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
            (data.data || []).forEach(h => {
                const future = h.applyFromMonth > nowMonth;
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${SES.escapeHtml(h.applyFromMonth)}</td>
                    <td class="text-end">￥${Number(h.sellingPrice).toLocaleString()}</td>
                    <td class="text-end">￥${Number(h.costPrice).toLocaleString()}</td>
                    <td>${h.reason ? SES.escapeHtml(h.reason) : ''}</td>
                    <td>${future ? `<button class="btn btn-sm btn-outline-danger" onclick="deletePriceRevision(${contractId}, '${h.applyFromMonth}')">×</button>` : ''}</td>`;
                tbody.appendChild(tr);
            });
        }).catch(() => hidePriceRevWarning());
}

function submitPriceRevision() {
    const contractId = $('#cont-id').val();
    const form = document.getElementById('priceRevForm');
    const body = {
        applyFromMonth: form.applyFromMonth.value,
        sellingPrice: form.sellingPrice.value ? Number(form.sellingPrice.value) : null,
        costPrice: form.costPrice.value ? Number(form.costPrice.value) : null,
        reason: form.reason.value || null
    };
    if (!body.applyFromMonth) return;
    fetch(`/api/contracts/${contractId}/price-revisions`, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify(body)
    }).then(res => res.json()).then(data => {
        if (data.code !== 200) { alert(data.message); return; }
        const warning = data.data && data.data.warning;
        document.getElementById('priceRevWarning').classList.toggle('d-none', !warning);
        form.reset();
        loadPriceRevisions(contractId);
        refreshPriceRevisionState(contractId);
    });
}

function deletePriceRevision(contractId, month) {
    fetch(`/api/contracts/${contractId}/price-revisions/${month}`, {
        method: 'DELETE', headers: SES.csrf.header()
    }).then(res => res.json()).then(data => {
        if (data.code === 200) { loadPriceRevisions(contractId); refreshPriceRevisionState(contractId); }
        else alert(data.message);
    });
}
