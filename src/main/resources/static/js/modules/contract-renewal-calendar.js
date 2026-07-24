/**
 * 契約更新カレンダー（月/週）
 * GET  /api/contracts/renewal-calendar?from=&to=
 * PUT  /api/contracts/{id}/renewal-decision  { decision: 'CONTINUE'|'END'|null }
 */
$(document).ready(function() {

    // renewalState -> 表示メタ（凡例・グリッド双方で同じ配色を使う。common.css の status-badge 系を流用）
    const STATE_META = {
        UNHANDLED:     { badgeClass: 'status-danger',    labelKey: 'contract.renewalCalendar.legend.unhandled',    label: '未対応' },
        DRAFT:         { badgeClass: 'status-warning',   labelKey: 'contract.renewalCalendar.legend.draft',        label: 'ドラフト有' },
        CONFIRMED:     { badgeClass: 'status-success',   labelKey: 'contract.renewalCalendar.legend.confirmed',    label: '確定' },
        CONTINUE:      { badgeClass: 'status-primary',   labelKey: 'contract.renewalCalendar.legend.continue',     label: '継続確定' },
        END_SCHEDULED: { badgeClass: 'status-secondary', labelKey: 'contract.renewalCalendar.legend.endScheduled', label: '終了予定' }
    };

    function stateMeta(state) {
        return STATE_META[state] || { badgeClass: 'status-secondary', labelKey: null, label: state || '-' };
    }

    function stateLabel(state) {
        const meta = stateMeta(state);
        return meta.labelKey ? SES.i18n.t(meta.labelKey) : meta.label;
    }

    // ---- モジュール状態 ----
    let viewMode = 'Month';   // 'Month' | 'Week'
    let anchorDate = new Date();
    let currentItemsByDate = {};   // 'YYYY-MM-DD' -> [item, ...]
    let currentDetailItem = null;

    // ---- 日付ユーティリティ（ローカル日付、UTC変換によるズレを避ける） ----
    function fmtDate(d) {
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    }
    function addDays(d, n) {
        return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
    }
    function addMonths(d, n) {
        return new Date(d.getFullYear(), d.getMonth() + n, 1);
    }
    function startOfMonth(d) {
        return new Date(d.getFullYear(), d.getMonth(), 1);
    }
    function startOfWeek(d) {
        return addDays(new Date(d.getFullYear(), d.getMonth(), d.getDate()), -d.getDay());
    }
    function isSameDate(a, b) {
        return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
    }
    function weekdayLabel(d) {
        const locale = (window.SES_LANG || document.documentElement.lang || 'ja-JP');
        return new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(d);
    }

    // 現在の表示モードに応じた [from, to]（グリッドの表示範囲。月表示は6週間分のパディングを含む）
    function currentRange() {
        if (viewMode === 'Week') {
            const start = startOfWeek(anchorDate);
            const end = addDays(start, 6);
            return { start: start, end: end };
        }
        const firstOfMonth = startOfMonth(anchorDate);
        const gridStart = startOfWeek(firstOfMonth);
        const gridEnd = addDays(gridStart, 41); // 6週間 x 7日 - 1
        return { start: gridStart, end: gridEnd };
    }

    function updateCurrentLabel() {
        const locale = (window.SES_LANG || document.documentElement.lang || 'ja-JP');
        let label;
        if (viewMode === 'Week') {
            const range = currentRange();
            label = new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'short', day: '2-digit' }).format(range.start)
                + ' - '
                + new Intl.DateTimeFormat(locale, { month: 'short', day: '2-digit' }).format(range.end);
        } else {
            label = new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'long' }).format(anchorDate);
        }
        $('#renewal-current-label').text(label);
    }

    function loadCalendar() {
        $('#renewal-calendar-loading').show();
        $('#renewal-calendar-target').empty();
        $('#renewal-truncated-banner').addClass('d-none');
        updateCurrentLabel();

        const range = currentRange();
        const from = fmtDate(range.start);
        const to = fmtDate(range.end);

        $.ajax({
            url: '/api/contracts/renewal-calendar',
            method: 'GET',
            data: { from: from, to: to },
            success: function(res) {
                $('#renewal-calendar-loading').hide();
                if (res.code !== 200 || !res.data) {
                    Toast.error(res.message || SES.i18n.t('js.renewalCalendar.error_fetch'));
                    $('#renewal-calendar-target').html('<div class="text-center text-danger p-5">' + SES.i18n.t('js.renewalCalendar.error_fetch') + '</div>');
                    return;
                }

                const data = res.data;
                const items = data.items || [];
                currentItemsByDate = {};
                items.forEach(function(item) {
                    const key = item.renewalDueDate;
                    if (!key) return;
                    if (!currentItemsByDate[key]) currentItemsByDate[key] = [];
                    currentItemsByDate[key].push(item);
                });

                if (data.truncated) {
                    $('#renewal-truncated-banner').removeClass('d-none');
                    Toast.warning(SES.i18n.t('js.renewalCalendar.truncated'));
                }

                if (viewMode === 'Week') {
                    renderWeek(range.start);
                } else {
                    renderMonth(range.start, range.end);
                }
            },
            error: function(err) {
                console.error(err);
                $('#renewal-calendar-loading').hide();
                $('#renewal-calendar-target').html('<div class="text-center text-danger p-5">' + SES.i18n.t('js.renewalCalendar.error_network') + '</div>');
            }
        });
    }

    function buildPillHtml(item) {
        const meta = stateMeta(item.renewalState);
        const label = SES.escapeHtml((item.engineerName || '-') + ' / ' + (item.customerName || '-'));
        return `<button type="button" class="renewal-pill status-badge ${meta.badgeClass}" data-contract-id="${item.contractId}" title="${label}">${label}</button>`;
    }

    function renderDayCellInner(dateStr, dayNum, extraClasses) {
        const items = currentItemsByDate[dateStr] || [];
        let html = `<div class="renewal-day-num text-muted">${dayNum}</div>`;
        if (items.length === 0) {
            html += `<div class="renewal-day-empty text-muted">&nbsp;</div>`;
        } else {
            items.forEach(function(item) {
                html += buildPillHtml(item);
            });
        }
        return html;
    }

    function renderMonth(gridStart, gridEnd) {
        const today = new Date();
        const currentMonth = anchorDate.getMonth();

        let headHtml = '<div class="renewal-calendar-headrow">';
        for (let i = 0; i < 7; i++) {
            const d = addDays(gridStart, i);
            headHtml += `<div class="text-muted small">${SES.escapeHtml(weekdayLabel(d))}</div>`;
        }
        headHtml += '</div>';

        let gridHtml = '<div class="renewal-calendar-grid">';
        let cursor = new Date(gridStart);
        while (cursor.getTime() <= gridEnd.getTime()) {
            const dateStr = fmtDate(cursor);
            const classes = ['renewal-day-cell'];
            if (cursor.getMonth() !== currentMonth) classes.push('other-month');
            if (isSameDate(cursor, today)) classes.push('is-today');
            gridHtml += `<div class="${classes.join(' ')}" data-date="${dateStr}">${renderDayCellInner(dateStr, cursor.getDate())}</div>`;
            cursor = addDays(cursor, 1);
        }
        gridHtml += '</div>';

        $('#renewal-calendar-target').html(headHtml + gridHtml);
        bindPillClicks();
    }

    function renderWeek(weekStart) {
        const today = new Date();

        let headHtml = '<div class="renewal-calendar-headrow">';
        for (let i = 0; i < 7; i++) {
            const d = addDays(weekStart, i);
            headHtml += `<div class="text-muted small">${SES.escapeHtml(weekdayLabel(d))} ${d.getMonth() + 1}/${d.getDate()}</div>`;
        }
        headHtml += '</div>';

        let gridHtml = '<div class="renewal-calendar-grid week-mode">';
        for (let i = 0; i < 7; i++) {
            const d = addDays(weekStart, i);
            const dateStr = fmtDate(d);
            const classes = ['renewal-day-cell'];
            if (isSameDate(d, today)) classes.push('is-today');
            gridHtml += `<div class="${classes.join(' ')}" data-date="${dateStr}" style="min-height:220px;max-height:400px;">${renderDayCellInner(dateStr, d.getDate())}</div>`;
        }
        gridHtml += '</div>';

        $('#renewal-calendar-target').html(headHtml + gridHtml);
        bindPillClicks();
    }

    function bindPillClicks() {
        $('.renewal-pill').on('click', function() {
            const contractId = $(this).data('contract-id');
            openDetail(contractId);
        });
    }

    function findItemByContractId(contractId) {
        for (const key in currentItemsByDate) {
            const found = (currentItemsByDate[key] || []).find(it => String(it.contractId) === String(contractId));
            if (found) return found;
        }
        return null;
    }

    function openDetail(contractId) {
        const item = findItemByContractId(contractId);
        if (!item) return;
        currentDetailItem = item;

        $('#renewalDetailContractNo').text(item.contractNo || ('C-' + item.contractId));
        $('#renewalDetailEngineer').text(item.engineerName || '-');
        $('#renewalDetailCustomer').text(item.customerName || '-');
        $('#renewalDetailSalesUser').text(item.salesUserName || '-');
        $('#renewalDetailEndDate').text(item.endDate || '-');
        $('#renewalDetailDueDate').text(item.renewalDueDate || '-');
        $('#renewalDetailStatus').text(item.status || '-');

        const meta = stateMeta(item.renewalState);
        $('#renewalDetailState').html(`<span class="status-badge ${meta.badgeClass}">${SES.escapeHtml(stateLabel(item.renewalState))}</span>`);

        bootstrap.Modal.getOrCreateInstance(document.getElementById('renewalDetailModal')).show();
    }

    function submitDecision(decision) {
        if (!currentDetailItem) return;
        const contractId = currentDetailItem.contractId;
        $.ajax({
            url: '/api/contracts/' + contractId + '/renewal-decision',
            method: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify({ decision: decision }),
            success: function(res) {
                if (res.code === 200) {
                    Toast.success(SES.i18n.t('js.renewalCalendar.success.decisionUpdated'));
                    bootstrap.Modal.getOrCreateInstance(document.getElementById('renewalDetailModal')).hide();
                    loadCalendar();
                } else {
                    Toast.error(res.message || SES.i18n.t('js.renewalCalendar.error.decisionUpdate'));
                }
            },
            error: function(err) {
                console.error(err);
                const msg = err.responseJSON && err.responseJSON.message ? err.responseJSON.message : SES.i18n.t('js.common.error_network');
                Toast.error(msg);
            }
        });
    }

    // ---- イベントバインド ----
    $('#renewal-nav-prev').on('click', function() {
        anchorDate = viewMode === 'Week' ? addDays(anchorDate, -7) : addMonths(anchorDate, -1);
        loadCalendar();
    });
    $('#renewal-nav-next').on('click', function() {
        anchorDate = viewMode === 'Week' ? addDays(anchorDate, 7) : addMonths(anchorDate, 1);
        loadCalendar();
    });
    $('#renewal-nav-today').on('click', function() {
        anchorDate = new Date();
        loadCalendar();
    });
    $('#renewal-view-mode-buttons button').on('click', function() {
        $('#renewal-view-mode-buttons button').removeClass('active');
        $(this).addClass('active');
        viewMode = $(this).data('mode');
        loadCalendar();
    });

    $('#renewalDetailBtnContinue').on('click', function() { submitDecision('CONTINUE'); });
    $('#renewalDetailBtnEnd').on('click', function() { submitDecision('END'); });
    $('#renewalDetailBtnReset').on('click', function() { submitDecision(null); });

    loadCalendar();
});
