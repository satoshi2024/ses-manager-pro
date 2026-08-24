document.addEventListener('DOMContentLoaded', function() {
    const monthFilter = document.getElementById('monthFilter');
    const tableBody = document.getElementById('performanceTableBody');
    const ruleNoteText = document.getElementById('ruleNoteText');

    let allRows = [];
    let unattributedRow = null;
    const pageState = { current: 1, size: 10 };

    // Default to current month
    const now = new Date();
    const currentMonth = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
    monthFilter.value = currentMonth;

    loadCommissionRule();
    loadPerformance();

    monthFilter.addEventListener('change', loadPerformance);

    function loadCommissionRule() {
        $.ajax({
            url: '/api/sales-performance/commission-rule',
            type: 'GET',
            success: function(res) {
                if (res && res.code === 200) {
                    const rule = res.data;
                    const note = msgRuleNote.replace('{0}', rule.baseType).replace('{1}', rule.rate);
                    ruleNoteText.textContent = note;
                }
            },
            error: function(err) {
                console.error('Error loading rule', err);
            }
        });
    }

    function loadPerformance() {
        const month = monthFilter.value;
        if (!month) return;

        $.ajax({
            url: '/api/sales-performance',
            type: 'GET',
            data: { month: month },
            success: function(res) {
                if (res && res.code === 200) {
                    const data = res.data || [];
                    unattributedRow = data.find(row => row.unattributed) || null;
                    allRows = data.filter(row => !row.unattributed);
                    showPage(1);
                } else {
                    Swal.fire('Error', res.message || 'データの取得に失敗しました', 'error');
                }
            },
            error: function(err) {
                console.error('Error loading performance', err);
                Swal.fire('Error', 'データの取得に失敗しました', 'error');
            }
        });
    }

    function showPage(page) {
        const total = allRows.length;
        const pages = Math.max(1, Math.ceil(total / pageState.size) || 1);
        pageState.current = Math.min(Math.max(1, page || 1), pages);

        const startIdx = (pageState.current - 1) * pageState.size;
        const pageRows = allRows.slice(startIdx, startIdx + pageState.size);
        // 未帰属行は全社突合用のため、ページ切替後も常に末尾へ出す
        renderTable(pageRows, unattributedRow);
        renderPagination(total, pages);
    }

    function renderPagination(total, pages) {
        const info = document.getElementById('sales-perf-page-info');
        if (info) {
            if (total === 0) {
                info.textContent = (typeof SES !== 'undefined' && SES.i18n)
                    ? SES.i18n.t('common.page.totalZero')
                    : '全 0 件';
            } else {
                const start = (pageState.current - 1) * pageState.size + 1;
                const end = Math.min(pageState.current * pageState.size, total);
                info.textContent = (typeof SES !== 'undefined' && SES.i18n)
                    ? SES.i18n.t('common.page.info', [total, start, end])
                    : (total + '件中 ' + start + '～' + end + '件目を表示');
            }
        }
        if (typeof SES !== 'undefined' && SES.pagination) {
            SES.pagination.render('sales-perf-pagination', pageState.current, pages, showPage);
        }
    }

    function renderTable(pageRows, unattributed) {
        tableBody.innerHTML = '';
        if ((!pageRows || pageRows.length === 0) && !unattributed) {
            tableBody.innerHTML = `<tr><td colspan="8" class="text-center text-muted py-4">${msgNoData}</td></tr>`;
            return;
        }

        (pageRows || []).forEach(row => {
            tableBody.appendChild(buildSalesRow(row));
        });

        if (unattributed) {
            tableBody.appendChild(buildUnattributedRow(unattributed));
        }
    }

    function buildSalesRow(row) {
        const tr = document.createElement('tr');
        let rateStr = '—';
        if (row.closedRate !== null && row.closedRate !== undefined) {
            rateStr = row.closedRate + '%';
        }
        tr.innerHTML = `
            <td class="px-3 fw-bold">${escapeHtml(row.salesUserName)}</td>
            <td class="text-end">${formatNumber(row.activePrimaryCount)}</td>
            <td class="text-end">${formatNumber(row.closedContractCount)}</td>
            <td class="text-end">${rateStr}</td>
            <td class="text-end">${formatNumber(row.activeContractCount)}</td>
            <td class="text-end">${formatCurrency(row.totalSalesAmount)}</td>
            <td class="text-end">${formatCurrency(row.totalProfitAmount)}</td>
            <td class="px-3 text-end text-warning fw-bold">${formatCurrency(row.totalCommissionAmount)}</td>
        `;
        return tr;
    }

    function buildUnattributedRow(row) {
        // 未帰属(担当営業なし)行: 担当要員数・成約数・成約率・インセンティブは対象外(—)。
        // 契約一覧(担当営業未設定で絞り込み)へのリンクを張り、R1の編集UIで帰属を解消できるようにする。
        const tr = document.createElement('tr');
        tr.className = 'table-active fst-italic';
        tr.innerHTML = `
            <td class="px-3 fw-bold">${escapeHtml(msgUnattributed)}
                <a href="/contract/list?salesUserId=none" class="ms-2 small">${escapeHtml(msgUnattributedLink)}</a></td>
            <td class="text-end">—</td>
            <td class="text-end">—</td>
            <td class="text-end">—</td>
            <td class="text-end">${formatNumber(row.activeContractCount)}</td>
            <td class="text-end">${formatCurrency(row.totalSalesAmount)}</td>
            <td class="text-end">${formatCurrency(row.totalProfitAmount)}</td>
            <td class="px-3 text-end">${formatCurrency(row.totalCommissionAmount)}</td>
        `;
        return tr;
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
