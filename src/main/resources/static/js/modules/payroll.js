/**
 * 給与情報（freee人事労務 給与・賞与参照）画面。
 *
 * <ul>
 *   <li>接続状態の4状態（未設定/接続済み/再認可必要/設定不備）を区別し、管理者だけに接続・再接続・解除を出す</li>
 *   <li>対応付けは内部要員とfreee従業員のselect選択式。生ID入力は行わない</li>
 *   <li>給与・賞与は年・月・種別を指定して取得。二重送信防止とloading表示</li>
 *   <li>金額はnull（計算中・未確定）と0円を区別する。項目明細は区分付きで全件表示（同名も保持）</li>
 *   <li>金額・氏名・外部IDをconsoleへ出力しない</li>
 * </ul>
 */
(() => {
    'use strict';

    const esc = v => $('<div>').text(v == null ? '' : v).html();
    const yen = v => v == null ? '' : Number(v).toLocaleString('ja-JP') + '円';
    /** nullは「—」（計算中/未確定）、0は「0円」として区別する。 */
    const amountCell = v => v == null
        ? '<span class="badge bg-warning-subtle text-warning-emphasis">計算中</span>'
        : yen(v);

    const CATEGORY_LABEL = {
        PAYMENT: '支給',
        DEDUCTION: '控除',
        EMPLOYER_SHARE: '会社負担',
        ALLOWANCE: '手当'
    };

    /** JSON成否で判定する接続解除。opaque 302を成功扱いしない（HFP-01-BUG-02）。 */
    async function disconnectFreee() {
        const confirmed = await Swal.fire({
            title: 'freee接続を解除しますか？',
            text: 'freee側のtokenを失効させ、このシステムの接続情報を削除します。対応付けは削除されません。',
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: '解除する',
            cancelButtonText: 'キャンセル'
        });
        if (!confirmed.value) {
            return;
        }
        try {
            await SES.api.delete('/integrations/freee');
            SES.toast.success('接続を解除しました');
            await load();
        } catch (e) {
            // SES.apiが業務エラーをtoast済み。成功toastは出さない。
        }
    }

    /**
     * 接続ボタンの表示制御。HTML hidden属性は使わず、hiddenプロパティとdisplayを同期する
     * （[hidden]{display:none!important} がjQuery .show()を無効化するため）。
     */
    function setBtnVisible($btn, visible) {
        if (!$btn || !$btn.length) {
            return;
        }
        $btn.prop('hidden', !visible);
        if (visible) {
            $btn.css('display', '');
        } else {
            $btn.css('display', 'none');
        }
    }

    function renderStatus(status) {
        const badge = $('#statusBadge');
        const companyName = $('#companyName');
        const action = $('#statusAction');
        const connectBtn = $('#connectBtn');
        const reconnectBtn = $('#reconnectBtn');
        const disconnectBtn = $('#disconnectBtn');
        const isAdmin = connectBtn.length > 0 || reconnectBtn.length > 0 || disconnectBtn.length > 0;

        setBtnVisible(connectBtn, false);
        setBtnVisible(reconnectBtn, false);
        setBtnVisible(disconnectBtn, false);
        companyName.text(status.companyName || '');
        action.text(status.action || '');

        if (status.status === 'CONNECTED') {
            badge.removeClass('bg-secondary bg-warning bg-danger').addClass('bg-success').text('接続済み');
            if (isAdmin) {
                setBtnVisible(disconnectBtn, true);
            }
        } else if (status.status === 'REAUTH_REQUIRED') {
            badge.removeClass('bg-secondary bg-success bg-danger').addClass('bg-warning').text('再認可が必要');
            if (isAdmin) {
                setBtnVisible(reconnectBtn, true);
            }
        } else if (status.status === 'MISCONFIGURED') {
            badge.removeClass('bg-secondary bg-success bg-warning').addClass('bg-danger').text('設定不備');
            if (isAdmin) {
                setBtnVisible(connectBtn, true);
            }
        } else {
            badge.removeClass('bg-success bg-warning bg-danger').addClass('bg-secondary').text('未接続');
            if (isAdmin) {
                setBtnVisible(connectBtn, true);
            }
        }
    }

    function linkStateBadge(e) {
        if (e.linkState === 'LINKED') {
            return '<span class="badge bg-success-subtle text-success-emphasis">対応済み</span>'
                + (e.linkedEngineerName ? ' ' + esc(e.linkedEngineerName) : '');
        }
        if (e.linkState === 'RECONFIRM_REQUIRED') {
            let html = '<span class="badge bg-warning-subtle text-warning-emphasis">要再確認</span>';
            if (e.linkedEngineerName) {
                html += ' ' + esc(e.linkedEngineerName);
            }
            if (e.linkedEngineerId) {
                html += ' <button type="button" class="btn btn-outline-danger btn-sm py-0"'
                    + ' data-unlink-engineer-id="' + esc(e.linkedEngineerId) + '">解除</button>';
            }
            return html;
        }
        return '<span class="badge bg-secondary-subtle text-secondary-emphasis">未対応</span>';
    }

    function renderEmployees(employees) {
        const container = $('#payrollEmployees');
        if (!employees || employees.length === 0) {
            container.html('<p class="text-muted mb-0">従業員がいません（全期間従業員0件）</p>');
            return;
        }
        const rows = employees.map(e => {
            const retire = e.retireDate
                ? '<span class="badge bg-secondary-subtle text-secondary-emphasis">退職</span>' : '';
            const payrollOut = e.payrollCalculation === false
                ? '<span class="badge bg-secondary-subtle text-secondary-emphasis">給与対象外</span>' : '';
            return '<tr>'
                + '<td>' + esc(e.num) + '</td>'
                + '<td>' + esc(e.displayName) + '</td>'
                + '<td>' + retire + ' ' + payrollOut + '</td>'
                + '<td>' + linkStateBadge(e) + '</td>'
                + '</tr>';
        }).join('');
        container.html('<table class="table table-sm table-striped align-middle mb-0">'
            + '<caption class="visually-hidden">freee従業員一覧</caption>'
            + '<thead><tr><th scope="col">従業員番号</th><th scope="col">表示名</th>'
            + '<th scope="col">在退職・対象</th><th scope="col">対応付け状態</th></tr></thead>'
            + '<tbody>' + rows + '</tbody></table>');
        container.find('button[data-unlink-engineer-id]').on('click', async function () {
            const engineerId = $(this).data('unlink-engineer-id');
            await unlinkEngineer(engineerId);
        });
    }

    async function unlinkEngineer(engineerId) {
        if (!engineerId) {
            SES.toast.warning('解除する内部要員を選択してください');
            return;
        }
        const confirmed = await Swal.fire({
            title: '対応付けを解除しますか？',
            text: '選択した内部要員のfreee従業員対応付けを解除します。',
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: '解除する',
            cancelButtonText: 'キャンセル'
        });
        if (!confirmed.value) {
            return;
        }
        try {
            await SES.api.delete('/api/payroll/links/' + encodeURIComponent(engineerId));
            SES.toast.success('対応付けを解除しました');
            await load();
        } catch (err) {
            // SES.apiがエラー内容をtoast済み
        }
    }

    function fillCandidateSelect(candidates) {
        const sel = $('#linkEngineerId');
        const options = (candidates || []).map(c =>
            '<option value="' + esc(c.id) + '">' + esc(c.fullName) + '</option>').join('');
        sel.html('<option value="">選択してください</option>' + options);
    }

    function fillEmployeeSelect(employees) {
        const sel = $('#linkEmployeeId');
        const options = (employees || []).map(e =>
            '<option value="' + esc(e.id) + '">' + esc(e.num + ' ' + e.displayName) + '</option>').join('');
        sel.html('<option value="">選択してください</option>' + options);
    }

    async function load() {
        let status;
        try {
            status = await SES.api.get('/api/payroll/status');
        } catch (e) {
            renderStatus({ status: 'DISCONNECTED', companyName: '', action: '' });
            return;
        }
        renderStatus(status);

        if (!status.connected) {
            $('#payrollEmployees').html('<p class="text-muted mb-0">接続後に従業員を取得できます</p>');
            fillCandidateSelect([]);
            fillEmployeeSelect([]);
            return;
        }
        try {
            const [employees, candidates] = await Promise.all([
                SES.api.get('/api/payroll/employees'),
                SES.api.get('/api/payroll/engineer-candidates')
            ]);
            renderEmployees(employees);
            fillCandidateSelect(candidates);
            fillEmployeeSelect(employees);
        } catch (e) {
            $('#payrollEmployees').html('<p class="text-danger mb-0">従業員情報の取得に失敗しました。時間を置いて再実行してください</p>');
        }
    }

    function detailRows(items) {
        if (!items || items.length === 0) {
            return '<p class="text-muted mb-0">明細項目がありません（計算中または未設定）</p>';
        }
        return '<table class="table table-sm mb-0">'
            + '<thead><tr><th scope="col">区分</th><th scope="col">項目名</th><th scope="col" class="text-end">金額</th></tr></thead>'
            + '<tbody>' + items.map(it =>
                '<tr><td>' + esc(CATEGORY_LABEL[it.category] || it.category) + '</td>'
                + '<td>' + esc(it.name) + '</td>'
                + '<td class="text-end">' + amountCell(it.amount) + '</td></tr>').join('')
            + '</tbody></table>';
    }

    function openDetail(s) {
        $('#statementDetailModalLabel').text(
            (s.type === 'bonus' ? '賞与明細' : '給与明細') + ' - ' + esc(s.engineerName));
        $('#statementDetailBody').html(detailRows(s.items));
        bootstrap.Modal.getOrCreateInstance(document.getElementById('statementDetailModal')).show();
    }

    function renderStatements(list, year, month, type) {
        const message = $('#statementMessage');
        const container = $('#payrollStatements');
        if (!list || list.length === 0) {
            message.removeClass('text-danger').addClass('text-muted').text('該当する明細がありません（計算中または未確定の月は表示されません）');
            container.html('');
            return;
        }
        message.text('');
        const salaryExtra = type === 'salary'
            ? '<th scope="col" class="text-end">会社負担</th>' : '';
        const rows = list.map(s => {
            const statusBadge = s.calculationStatus === 'calculating'
                ? '<span class="badge bg-warning-subtle text-warning-emphasis">計算中</span>'
                : (s.fixed === false
                    ? '<span class="badge bg-secondary-subtle text-secondary-emphasis">未確定</span>'
                    : '<span class="badge bg-success-subtle text-success-emphasis">確定</span>');
            const payDate = s.payDate || '—';
            const detailBtn = '<button type="button" class="btn btn-outline-secondary btn-sm" data-statement-id="' + esc(s.employeeId) + '">明細</button>';
            return '<tr>'
                + '<td>' + esc(s.engineerName) + '</td>'
                + '<td>' + esc(s.employeeNumber || '') + '</td>'
                + '<td>' + esc(payDate) + '</td>'
                + '<td>' + statusBadge + '</td>'
                + '<td class="text-end">' + amountCell(s.grossAmount) + '</td>'
                + '<td class="text-end">' + amountCell(s.deductionAmount) + '</td>'
                + (type === 'salary' ? '<td class="text-end">' + amountCell(s.employerShareAmount) + '</td>' : '')
                + '<td class="text-end fw-bold">' + amountCell(s.netAmount) + '</td>'
                + '<td>' + detailBtn + '</td>'
                + '</tr>';
        }).join('');
        container.html('<table class="table table-sm table-striped align-middle mb-0">'
            + '<caption class="visually-hidden">' + (type === 'bonus' ? '賞与' : '給与') + '明細一覧（' + year + '年' + month + '月）</caption>'
            + '<thead><tr><th scope="col">内部要員</th><th scope="col">従業員番号</th><th scope="col">支払日</th>'
            + '<th scope="col">状態</th><th scope="col" class="text-end">総支給額</th>'
            + '<th scope="col" class="text-end">控除合計</th>' + salaryExtra
            + '<th scope="col" class="text-end">差引支給額</th><th scope="col">操作</th></tr></thead>'
            + '<tbody>' + rows + '</tbody></table>');

        container.find('button[data-statement-id]').on('click', function () {
            const id = $(this).data('statement-id');
            const found = list.find(x => x.employeeId === String(id));
            if (found) {
                openDetail(found);
            }
        });
    }

    function init() {
        $('#connectBtn, #reconnectBtn').on('click', () => {
            window.location.href = '/integrations/freee/authorize';
        });
        $('#disconnectBtn').on('click', disconnectFreee);

        $('#linkForm').on('submit', async e => {
            e.preventDefault();
            const engineerId = $('#linkEngineerId').val();
            const employeeId = $('#linkEmployeeId').val();
            if (!engineerId || !employeeId) {
                SES.toast.warning('内部要員とfreee従業員を選択してください');
                return;
            }
            try {
                await SES.api.put('/api/payroll/links/' + encodeURIComponent(engineerId)
                    + '?employeeId=' + encodeURIComponent(employeeId));
                SES.toast.success('対応付けしました');
                await load();
            } catch (err) {
                // SES.apiがエラー内容をtoast済み
            }
        });

        $('#unlinkBtn').on('click', async () => {
            await unlinkEngineer($('#linkEngineerId').val());
        });

        const now = new Date();
        $('#statementYear').val(now.getFullYear());
        $('#statementMonth').val(now.getMonth() + 1);

        $('#statementForm').on('submit', async e => {
            e.preventDefault();
            const year = $('#statementYear').val();
            const month = $('#statementMonth').val();
            const type = $('#statementType').val();
            const btn = $('#statementFetchBtn');
            const message = $('#statementMessage');
            btn.prop('disabled', true);
            message.removeClass('text-danger text-muted').text('読み込み中...');
            try {
                const list = await SES.api.get('/api/payroll/statements',
                    { year: year, month: month, type: type });
                renderStatements(list, year, month, type);
            } catch (err) {
                message.addClass('text-danger')
                    .text('明細の取得に失敗しました。接続状態・権限・プランをご確認のうえ、時間を置いて再実行してください');
            } finally {
                btn.prop('disabled', false);
            }
        });

        load();
    }

    $(document).ready(init);
})();
