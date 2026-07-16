document.addEventListener('DOMContentLoaded', function() {
    const monthFilter = document.getElementById('monthFilter');
    const tableBody = document.getElementById('performanceTableBody');
    const ruleNoteText = document.getElementById('ruleNoteText');

    // Default to current month
    const now = new Date();
    const currentMonth = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
    monthFilter.value = currentMonth;

    loadCommissionRule();
    loadPerformance();

    monthFilter.addEventListener('change', loadPerformance);

    function loadCommissionRule() {
        axios.get('/api/sales-performance/commission-rule')
            .then(res => {
                const rule = res.data.data;
                const note = msgRuleNote.replace('{0}', rule.baseType).replace('{1}', rule.rate);
                ruleNoteText.textContent = note;
            })
            .catch(err => console.error('Error loading rule', err));
    }

    function loadPerformance() {
        const month = monthFilter.value;
        if (!month) return;

        axios.get('/api/sales-performance', { params: { month } })
            .then(res => {
                const data = res.data.data;
                renderTable(data);
            })
            .catch(err => {
                console.error('Error loading performance', err);
                Swal.fire('Error', 'データの取得に失敗しました', 'error');
            });
    }

    function renderTable(data) {
        tableBody.innerHTML = '';
        if (!data || data.length === 0) {
            tableBody.innerHTML = `<tr><td colspan="8" class="text-center text-muted py-4">${msgNoData}</td></tr>`;
            return;
        }

        data.forEach(row => {
            const tr = document.createElement('tr');

            if (row.unattributed) {
                // 未帰属(担当営業なし)行: 担当要員数・成約数・成約率・インセンティブは対象外(—)。
                // 契約一覧(担当営業未設定で絞り込み)へのリンクを張り、R1の編集UIで帰属を解消できるようにする。
                tr.className = 'table-active fst-italic';
                tr.innerHTML = `
                    <td class="fw-bold">${escapeHtml(msgUnattributed)}
                        <a href="/contract/list?salesUserId=none" class="ms-2 small">${escapeHtml(msgUnattributedLink)}</a></td>
                    <td class="text-end">—</td>
                    <td class="text-end">—</td>
                    <td class="text-end">—</td>
                    <td class="text-end">${formatNumber(row.activeContractCount)}</td>
                    <td class="text-end">${formatCurrency(row.totalSalesAmount)}</td>
                    <td class="text-end">${formatCurrency(row.totalProfitAmount)}</td>
                    <td class="text-end">${formatCurrency(row.totalCommissionAmount)}</td>
                `;
                tableBody.appendChild(tr);
                return;
            }

            let rateStr = '—';
            if (row.closedRate !== null && row.closedRate !== undefined) {
                rateStr = row.closedRate + '%';
            }

            tr.innerHTML = `
                <td class="fw-bold">${escapeHtml(row.salesUserName)}</td>
                <td class="text-end">${formatNumber(row.activePrimaryCount)}</td>
                <td class="text-end">${formatNumber(row.closedContractCount)}</td>
                <td class="text-end">${rateStr}</td>
                <td class="text-end">${formatNumber(row.activeContractCount)}</td>
                <td class="text-end">${formatCurrency(row.totalSalesAmount)}</td>
                <td class="text-end">${formatCurrency(row.totalProfitAmount)}</td>
                <td class="text-end text-warning fw-bold">${formatCurrency(row.totalCommissionAmount)}</td>
            `;
            tableBody.appendChild(tr);
        });
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/[&<>'"]/g, 
            tag => ({
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                "'": '&#39;',
                '"': '&quot;'
            }[tag] || tag)
        );
    }

    function formatNumber(num) {
        if (num === null || num === undefined) return '0';
        return num.toLocaleString();
    }

    function formatCurrency(num) {
        if (num === null || num === undefined) return '¥0';
        return '¥' + num.toLocaleString();
    }
});