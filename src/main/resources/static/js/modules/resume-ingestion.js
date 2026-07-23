/**
 * スキルシート取込モジュール
 * 一覧画面 (/resume-ingestion) とレビュー画面 (/resume-ingestion/review/:id) 共用
 */
(function () {
    'use strict';

    const IS_REVIEW_PAGE = typeof JOB_ID !== 'undefined' && JOB_ID != null;

    /* ================================================================
     * 一覧画面
     * ================================================================ */
    function loadList(page) {
        const status = $('#filterStatus').val();
        $.ajax({
            url: '/api/resume-ingestions',
            data: { current: page || 1, size: 10, status: status },
            success: function (res) {
                if (res.code !== 200) {
                    Toast.error(res.message || '取得に失敗しました。');
                    return;
                }
                renderTable(res.data.records);
                renderPagination(res.data, loadList);
            }
        });
    }

    function renderTable(rows) {
        const tbody = $('#ri-table-body');
        tbody.empty();
        if (!rows || rows.length === 0) {
            tbody.append(`<tr><td colspan="4" class="text-center text-muted py-4">データがありません。</td></tr>`);
            return;
        }
        rows.forEach(function (row) {
            const badgeClass = statusBadge(row.status);
            tbody.append(`
                <tr>
                    <td class="ps-4">${SES.escapeHtml(row.originalFileName || '-')}</td>
                    <td><span class="badge ${badgeClass}">${SES.escapeHtml(row.status)}</span></td>
                    <td>${row.createdAt ? row.createdAt.substring(0, 16) : '-'}</td>
                    <td class="text-end pe-4">
                        ${row.status === '\u8981\u78ba\u8a8d' ? `<a href="/resume-ingestion/review/${row.id}" class="btn btn-sm btn-primary">レビュー</a>` : ''}
                        ${row.status === '\u5931\u6557' ? `<button class="btn btn-sm btn-outline-warning" onclick="reparseJob(${row.id})">再解析</button>` : ''}
                        ${row.status === '\u78ba\u5b9a\u6e08' && row.convertedEngineerId ? `<a href="/engineer/detail/${row.convertedEngineerId}" class="btn btn-sm btn-outline-success">要員詳細</a>` : ''}
                    </td>
                </tr>
            `);
        });
    }

    function renderPagination(pageData, callback) {
        const container = $('#ri-pagination');
        container.empty();
        const total = pageData.total || 0;
        const size = pageData.size || 10;
        const current = pageData.current || 1;
        const pages = Math.ceil(total / size);
        if (pages <= 1) { container.append(`<small class="text-muted">全${total}件</small>`); return; }
        let nav = `<small class="text-muted">全${total}件</small><nav><ul class="pagination pagination-sm mb-0">`;
        for (let i = 1; i <= Math.min(pages, 10); i++) {
            nav += `<li class="page-item${i === current ? ' active' : ''}"><a class="page-link" href="#" onclick="${callback.name}(${i});return false;">${i}</a></li>`;
        }
        nav += '</ul></nav>';
        container.html(nav);
    }

    function statusBadge(status) {
        const map = {
            '\u53d6\u8fbc\u5f85\u3061': 'bg-secondary',
            '\u62bd\u51fa\u4e2d': 'bg-info text-dark',
            '\u8981\u78ba\u8a8d': 'bg-warning text-dark',
            '\u78ba\u5b9a\u6e08': 'bg-success',
            '\u5374\u4e0b': 'bg-dark',
            '\u5931\u6557': 'bg-danger'
        };
        return map[status] || 'bg-secondary';
    }

    function reparseJob(id) {
        $.ajax({
            url: '/api/resume-ingestions/' + id + '/reparse',
            method: 'POST',
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    Toast.success('再解析を開始しました。');
                    setTimeout(() => loadList(1), 1500);
                } else {
                    Toast.error(res.message || '失敗しました。');
                }
            }
        });
    }

    function uploadFile() {
        const file = document.getElementById('resumeFile').files[0];
        if (!file) { Toast.error('ファイルを選択してください。'); return; }
        const fd = new FormData();
        fd.append('file', file);
        
        const urlParams = new URLSearchParams(window.location.search);
        const candidateId = urlParams.get('candidateId');
        if (candidateId) {
            fd.append('candidateId', candidateId);
        }

        $('#btn-do-upload').prop('disabled', true).text('アップロード中...');
        $.ajax({
            url: '/api/resume-ingestions',
            method: 'POST',
            data: fd,
            processData: false,
            contentType: false,
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    bootstrap.Modal.getInstance(document.getElementById('uploadModal')).hide();
                    Toast.success('アップロードしました。解析中...');
                    setTimeout(() => loadList(1), 2000);
                } else {
                    Toast.error(res.message || 'アップロードに失敗しました。');
                }
            },
            error: function () { Toast.error('アップロードに失敗しました。'); },
            complete: function () {
                $('#btn-do-upload').prop('disabled', false).text('アップロード');
            }
        });
    }

    /* ================================================================
     * レビュー画面
     * ================================================================ */
    let currentJob = null;

    function loadJob() {
        $.ajax({
            url: '/api/resume-ingestions/' + JOB_ID,
            success: function (res) {
                if (res.code !== 200) { Toast.error(res.message); return; }
                currentJob = res.data;
                populateForm(currentJob);
            }
        });
    }

    function populateForm(job) {
        let parsed = null;
        if (job.parsedJson) {
            try { parsed = JSON.parse(job.parsedJson); } catch (e) {}
        }

        const eng = (parsed && parsed.engineer) ? parsed.engineer : {};
        $('#rv-fullName').val(eng.fullName || '');
        $('#rv-fullNameKana').val(eng.fullNameKana || '');
        $('#rv-gender').val(eng.gender || '');
        $('#rv-japaneseLevel').val(eng.japaneseLevel || '');
        $('#rv-employmentType').val(eng.employmentType || 'BP');
        $('#rv-experienceYears').val(eng.experienceYears || '');
        $('#rv-expectedUnitPrice').val(eng.expectedUnitPrice || '');
        $('#rv-resumeSummary').val(eng.resumeSummary || '');
        $('#rv-reviewNote').val(job.reviewNote || '');

        // ウォーニング表示
        if (parsed && parsed.warnings && parsed.warnings.length > 0) {
            $('#ri-warnings').text(parsed.warnings.join(' / ')).removeClass('d-none');
        }

        // スキルリスト
        const skills = (parsed && parsed.skills) ? parsed.skills : [];
        renderSkills(skills);

        // 経歴リスト
        const careers = (parsed && parsed.careers) ? parsed.careers : [];
        renderCareers(careers);
    }

    function renderSkills(skills) {
        const container = $('#rv-skills-container');
        container.empty();
        skills.forEach(function (sk, i) {
            container.append(`
                <div class="d-flex gap-2 mb-2 skill-row" data-index="${i}">
                    <input type="text" class="form-control bg-dark border-secondary text-light skill-name" value="${SES.escapeHtml(sk.name || '')}" placeholder="\u30b9\u30ad\u30eb\u540d">
                    <select class="form-select bg-dark border-secondary text-light skill-prof" style="max-width:130px;">
                        <option value="">レベル</option>
                        <option value="\u521d\u7d1a"${sk.proficiency === '\u521d\u7d1a' ? ' selected' : ''}>初級</option>
                        <option value="\u4e2d\u7d1a"${sk.proficiency === '\u4e2d\u7d1a' ? ' selected' : ''}>中級</option>
                        <option value="\u4e0a\u7d1a"${sk.proficiency === '\u4e0a\u7d1a' ? ' selected' : ''}>上級</option>
                    </select>
                    <input type="number" class="form-control bg-dark border-secondary text-light skill-years" value="${sk.experienceYears || ''}" min="0" style="max-width:90px;" placeholder="\u5e74\u6570">
                    <button type="button" class="btn btn-sm btn-outline-danger" onclick="$(this).closest('.skill-row').remove()">删</button>
                </div>
            `);
        });
    }

    function renderCareers(careers) {
        const container = $('#rv-careers-container');
        container.empty();
        careers.forEach(function (c, i) {
            container.append(`
                <div class="card bg-dark border-secondary mb-3 career-row">
                    <div class="card-body p-3">
                        <div class="row g-2 mb-2">
                            <div class="col-6"><label class="small text-muted">開始</label><input type="date" class="form-control form-control-sm bg-dark border-secondary text-light career-from" value="${c.periodFrom || ''}"></div>
                            <div class="col-6"><label class="small text-muted">終了</label><input type="date" class="form-control form-control-sm bg-dark border-secondary text-light career-to" value="${c.periodTo || ''}"></div>
                        </div>
                        <div class="mb-2"><label class="small text-muted">プロジェクト名</label><input type="text" class="form-control form-control-sm bg-dark border-secondary text-light career-project" value="${SES.escapeHtml(c.projectName || '')}"></div>
                        <div class="mb-2"><label class="small text-muted">業為・役割</label><input type="text" class="form-control form-control-sm bg-dark border-secondary text-light career-role" value="${SES.escapeHtml(c.role || '')}"></div>
                        <div class="mb-2"><label class="small text-muted">技術スタック</label><input type="text" class="form-control form-control-sm bg-dark border-secondary text-light career-tech" value="${SES.escapeHtml(c.techStack || '')}"></div>
                        <div class="mb-2"><label class="small text-muted">業務内容</label><textarea class="form-control form-control-sm bg-dark border-secondary text-light career-desc" rows="2">${SES.escapeHtml(c.description || '')}</textarea></div>
                        <button type="button" class="btn btn-sm btn-outline-danger" onclick="$(this).closest('.career-row').remove()">経歴删除</button>
                    </div>
                </div>
            `);
        });
    }

    function collectReviewData() {
        const skills = [];
        $('.skill-row').each(function () {
            const name = $(this).find('.skill-name').val().trim();
            if (name) {
                skills.push({
                    name: name,
                    proficiency: $(this).find('.skill-prof').val() || null,
                    experienceYears: parseInt($(this).find('.skill-years').val()) || null
                });
            }
        });
        const careers = [];
        $('.career-row').each(function () {
            careers.push({
                periodFrom: $(this).find('.career-from').val() || null,
                periodTo: $(this).find('.career-to').val() || null,
                projectName: $(this).find('.career-project').val().trim() || null,
                role: $(this).find('.career-role').val().trim() || null,
                techStack: $(this).find('.career-tech').val().trim() || null,
                description: $(this).find('.career-desc').val().trim() || null
            });
        });
        return {
            engineer: {
                fullName: $('#rv-fullName').val().trim(),
                fullNameKana: $('#rv-fullNameKana').val().trim() || null,
                gender: $('#rv-gender').val() || null,
                japaneseLevel: $('#rv-japaneseLevel').val() || null,
                employmentType: $('#rv-employmentType').val() || 'BP',
                experienceYears: parseInt($('#rv-experienceYears').val()) || null,
                expectedUnitPrice: parseFloat($('#rv-expectedUnitPrice').val()) || null,
                resumeSummary: $('#rv-resumeSummary').val().trim() || null
            },
            skills: skills,
            careers: careers,
            reviewNote: $('#rv-reviewNote').val().trim() || null
        };
    }

    /* ================================================================
     * 初期化
     * ================================================================ */
    $(function () {
        if (IS_REVIEW_PAGE) {
            // レビュー画面の初期化
            loadJob();

            $('#btn-add-skill').on('click', function () {
                const count = $('.skill-row').length;
                $('#rv-skills-container').append(`
                    <div class="d-flex gap-2 mb-2 skill-row">
                        <input type="text" class="form-control bg-dark border-secondary text-light skill-name" placeholder="\u30b9\u30ad\u30eb\u540d">
                        <select class="form-select bg-dark border-secondary text-light skill-prof" style="max-width:130px;">
                            <option value="">レベル</option>
                            <option value="\u521d\u7d1a">初級</option>
                            <option value="\u4e2d\u7d1a">中級</option>
                            <option value="\u4e0a\u7d1a">上級</option>
                        </select>
                        <input type="number" class="form-control bg-dark border-secondary text-light skill-years" min="0" style="max-width:90px;" placeholder="\u5e74\u6570">
                        <button type="button" class="btn btn-sm btn-outline-danger" onclick="$(this).closest('.skill-row').remove()">删</button>
                    </div>
                `);
            });

            $('#btn-add-career').on('click', function () {
                $('#rv-careers-container').append(`
                    <div class="card bg-dark border-secondary mb-3 career-row">
                        <div class="card-body p-3">
                            <div class="row g-2 mb-2">
                                <div class="col-6"><label class="small text-muted">開始</label><input type="date" class="form-control form-control-sm bg-dark border-secondary text-light career-from"></div>
                                <div class="col-6"><label class="small text-muted">終了</label><input type="date" class="form-control form-control-sm bg-dark border-secondary text-light career-to"></div>
                            </div>
                            <div class="mb-2"><label class="small text-muted">プロジェクト名</label><input type="text" class="form-control form-control-sm bg-dark border-secondary text-light career-project"></div>
                            <div class="mb-2"><label class="small text-muted">業為・役割</label><input type="text" class="form-control form-control-sm bg-dark border-secondary text-light career-role"></div>
                            <div class="mb-2"><label class="small text-muted">技術スタック</label><input type="text" class="form-control form-control-sm bg-dark border-secondary text-light career-tech"></div>
                            <div class="mb-2"><label class="small text-muted">業務内容</label><textarea class="form-control form-control-sm bg-dark border-secondary text-light career-desc" rows="2"></textarea></div>
                            <button type="button" class="btn btn-sm btn-outline-danger" onclick="$(this).closest('.career-row').remove()">経歴删除</button>
                        </div>
                    </div>
                `);
            });

            $('#btn-save-review').on('click', function () {
                const data = collectReviewData();
                $.ajax({
                    url: '/api/resume-ingestions/' + JOB_ID + '/review',
                    method: 'PUT',
                    contentType: 'application/json',
                    data: JSON.stringify(data),
                    headers: SES.csrf.header(),
                    success: function (res) {
                        if (res.code === 200) Toast.success('中間保存しました。');
                        else Toast.error(res.message);
                    }
                });
            });

            $('#btn-reparse').on('click', function () {
                Swal.fire({ title: '再解析', text: 'AIで再解析します。編集中の内容は上書きされます。', icon: 'question',
                    showCancelButton: true, confirmButtonText: '再解析', cancelButtonText: 'キャンセル' })
                    .then(function (result) {
                        if (!result.isConfirmed) return;
                        $.ajax({
                            url: '/api/resume-ingestions/' + JOB_ID + '/reparse',
                            method: 'POST',
                            headers: SES.csrf.header(),
                            success: function (res) {
                                if (res.code === 200) { Toast.success('再解析を開始しました。'); setTimeout(() => location.reload(), 3000); }
                                else Toast.error(res.message);
                            }
                        });
                    });
            });

            $('#btn-reject').on('click', function () {
                Swal.fire({ title: '却下', input: 'text', inputLabel: '却下理由', showCancelButton: true,
                    confirmButtonText: '却下', cancelButtonText: 'キャンセル', confirmButtonColor: '#dc3545' })
                    .then(function (result) {
                        if (!result.isConfirmed) return;
                        $.ajax({
                            url: '/api/resume-ingestions/' + JOB_ID + '/reject',
                            method: 'POST',
                            contentType: 'application/json',
                            data: JSON.stringify({ reason: result.value || '' }),
                            headers: SES.csrf.header(),
                            success: function (res) {
                                if (res.code === 200) { Toast.success('却下しました。'); setTimeout(() => location.href = '/resume-ingestion', 1500); }
                                else Toast.error(res.message);
                            }
                        });
                    });
            });

            $('#btn-confirm').on('click', function () {
                const data = collectReviewData();
                if (!data.engineer.fullName) { Toast.error('氏名は必須です。'); return; }
                Swal.fire({ title: '確定', text: '要員・スキル・経歴を登録します。よろしいですか？', icon: 'info',
                    showCancelButton: true, confirmButtonText: '確定', cancelButtonText: 'キャンセル' })
                    .then(function (result) {
                        if (!result.isConfirmed) return;
                        $.ajax({
                            url: '/api/resume-ingestions/' + JOB_ID + '/confirm',
                            method: 'POST',
                            contentType: 'application/json',
                            data: JSON.stringify(data),
                            headers: SES.csrf.header(),
                            success: function (res) {
                                if (res.code === 200) {
                                    Toast.success('確定しました。要員画面へ移動します。');
                                    setTimeout(() => location.href = '/engineer/detail/' + res.data, 2000);
                                } else {
                                    Toast.error(res.message || '確定に失敗しました。');
                                }
                            }
                        });
                    });
            });

        } else {
            // 一覧画面の初期化
            loadList(1);
            $('#filterStatus').on('change', function () { loadList(1); });
            $('#btn-upload').on('click', function () {
                $('#resumeFile').val('');
                new bootstrap.Modal(document.getElementById('uploadModal')).show();
            });
            $('#btn-do-upload').on('click', uploadFile);

            // 30秒ごとに一覧を自動更新（解析中ステータスのリアルタイム更新）
            setInterval(function () {
                // 抽出中の行がある場合のみ自動更新
                if ($('.badge.bg-info').length > 0) {
                    loadList(1);
                }
            }, 30000);
        }
    });

    // インスタンス共有のためグローバルに公開
    window.loadList = loadList;
    window.reparseJob = reparseJob;

}());
