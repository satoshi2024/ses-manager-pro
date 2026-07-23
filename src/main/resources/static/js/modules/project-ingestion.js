/**
 * 案件メール取込モジュール
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
            url: '/api/project-ingestions',
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
        const tbody = $('#pi-table-body');
        tbody.empty();
        if (!rows || rows.length === 0) {
            tbody.append(`<tr><td colspan="4" class="text-center text-muted py-4">データがありません。</td></tr>`);
            return;
        }
        rows.forEach(function (row) {
            const badgeClass = statusBadge(row.status);
            const nameCol = row.sourceType === 'EML' 
                ? `<i class="bi bi-file-earmark-text me-1"></i>${SES.escapeHtml(row.originalFileName || '-')}`
                : `<i class="bi bi-clipboard me-1"></i>テキスト貼付`;
            tbody.append(`
                <tr>
                    <td class="ps-4">${nameCol}</td>
                    <td><span class="badge ${badgeClass}">${SES.escapeHtml(row.status)}</span></td>
                    <td>${row.createdAt ? row.createdAt.substring(0, 16) : '-'}</td>
                    <td class="text-end pe-4">
                        ${row.status === '要確認' ? `<a href="/project-ingestion/review/${row.id}" class="btn btn-sm btn-primary">レビュー</a>` : ''}
                        ${row.status === '失敗' ? `<button class="btn btn-sm btn-outline-warning" onclick="reparseJob(${row.id})">再解析</button>` : ''}
                        ${row.status === '確定済' && row.convertedProjectId ? `<a href="/project/detail/${row.convertedProjectId}" class="btn btn-sm btn-outline-success">案件詳細</a>` : ''}
                    </td>
                </tr>
            `);
        });
    }

    function renderPagination(pageData, callback) {
        const container = $('#pi-pagination');
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
            '取込待ち': 'bg-secondary',
            '抽出中': 'bg-info text-dark',
            '要確認': 'bg-warning text-dark',
            '確定済': 'bg-success',
            '却下': 'bg-dark',
            '失敗': 'bg-danger'
        };
        return map[status] || 'bg-secondary';
    }

    function reparseJob(id) {
        $.ajax({
            url: '/api/project-ingestions/' + id + '/reparse',
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

    function uploadEml() {
        const file = document.getElementById('emlFile').files[0];
        if (!file) { Toast.error('ファイルを選択してください。'); return; }
        const fd = new FormData();
        fd.append('file', file);

        $('#btn-do-upload').prop('disabled', true).text('処理中...');
        $.ajax({
            url: '/api/project-ingestions/upload',
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

    function pasteText() {
        const text = $('#pasteText').val().trim();
        if (!text) { Toast.error('テキストを入力してください。'); return; }

        $('#btn-do-paste').prop('disabled', true).text('処理中...');
        $.ajax({
            url: '/api/project-ingestions/paste',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ text: text }),
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    bootstrap.Modal.getInstance(document.getElementById('pasteModal')).hide();
                    Toast.success('取り込みました。解析中...');
                    setTimeout(() => loadList(1), 2000);
                } else {
                    Toast.error(res.message || '取込に失敗しました。');
                }
            },
            error: function () { Toast.error('取込に失敗しました。'); },
            complete: function () {
                $('#btn-do-paste').prop('disabled', false).text('取込開始');
            }
        });
    }

    /* ================================================================
     * レビュー画面
     * ================================================================ */
    let currentJob = null;

    function loadJob() {
        $.ajax({
            url: '/api/project-ingestions/' + JOB_ID,
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

        const prj = (parsed && parsed.project) ? parsed.project : {};
        $('#rv-name').val(prj.name || '');
        $('#rv-minUnitPrice').val(prj.minUnitPrice || '');
        $('#rv-maxUnitPrice').val(prj.maxUnitPrice || '');
        $('#rv-location').val(prj.location || '');
        $('#rv-remoteAllowed').val(prj.remoteAllowed || '');
        $('#rv-startDate').val(prj.startDate || '');
        $('#rv-endDate').val(prj.endDate || '');
        $('#rv-commercialFlow').val(prj.commercialFlow || '');
        $('#rv-headCount').val(prj.headCount || '');
        $('#rv-endClientName').val(prj.endClientName || '');
        $('#rv-description').val(prj.description || '');
        $('#rv-reviewNote').val(job.reviewNote || '');
        
        $('#rv-rawText').val(job.rawText || '');
        $('#rv-sourceType').text(job.sourceType);

        if (parsed && parsed.warnings && parsed.warnings.length > 0) {
            $('#pi-warnings').text(parsed.warnings.join(' / ')).removeClass('d-none');
        }

        const skills = (parsed && parsed.skills) ? parsed.skills : [];
        renderSkills(skills);
    }

    function renderSkills(skills) {
        const container = $('#rv-skills-container');
        container.empty();
        skills.forEach(function (sk, i) {
            container.append(`
                <div class="d-flex gap-2 mb-2 skill-row">
                    <input type="text" class="form-control bg-dark border-secondary text-light skill-name" value="${SES.escapeHtml(sk.name || '')}" placeholder="スキル名">
                    <button type="button" class="btn btn-sm btn-outline-danger" onclick="$(this).closest('.skill-row').remove()">删</button>
                </div>
            `);
        });
    }

    function collectReviewData() {
        const skills = [];
        $('.skill-row').each(function () {
            const name = $(this).find('.skill-name').val().trim();
            if (name) {
                skills.push({ name: name });
            }
        });
        return {
            project: {
                name: $('#rv-name').val().trim(),
                minUnitPrice: parseFloat($('#rv-minUnitPrice').val()) || null,
                maxUnitPrice: parseFloat($('#rv-maxUnitPrice').val()) || null,
                location: $('#rv-location').val().trim() || null,
                remoteAllowed: $('#rv-remoteAllowed').val().trim() || null,
                startDate: $('#rv-startDate').val() || null,
                endDate: $('#rv-endDate').val() || null,
                commercialFlow: $('#rv-commercialFlow').val().trim() || null,
                headCount: parseInt($('#rv-headCount').val()) || null,
                endClientName: $('#rv-endClientName').val().trim() || null,
                description: $('#rv-description').val().trim() || null
            },
            skills: skills,
            reviewNote: $('#rv-reviewNote').val().trim() || null
        };
    }

    function showMatchingModal(projectId) {
        // マッチング候補をAPIで取得し、SweetAlertや別モーダルで表示するか、直接提案画面へ飛ばす
        $.ajax({
            url: '/api/ai/matching/project/' + projectId,
            success: function(res) {
                if(res.code === 200 && res.data && res.data.length > 0) {
                    let html = '<div class="list-group text-start mt-3">';
                    res.data.forEach(m => {
                        html += `<div class="list-group-item bg-dark border-secondary text-light">
                            <div class="fw-bold">${SES.escapeHtml(m.engineerName)} <span class="badge bg-success float-end">Score: ${m.matchScore}</span></div>
                            <small class="text-muted">${SES.escapeHtml(m.reason || '')}</small>
                        </div>`;
                    });
                    html += '</div>';
                    
                    Swal.fire({
                        title: '案件作成成功',
                        html: '案件を確定しました。<br>以下の要員がマッチしています:' + html,
                        icon: 'success',
                        showCancelButton: true,
                        confirmButtonText: '提案作成へ',
                        cancelButtonText: '案件詳細へ',
                        width: '600px'
                    }).then((result) => {
                        if (result.isConfirmed) {
                            location.href = '/proposal/kanban'; // 提案作成への導線（既存のカンバン等）
                        } else {
                            location.href = '/project/detail/' + projectId;
                        }
                    });
                } else {
                    Toast.success('確定しました。案件詳細へ移動します。');
                    setTimeout(() => location.href = '/project/detail/' + projectId, 2000);
                }
            },
            error: function() {
                Toast.success('確定しました。案件詳細へ移動します。');
                setTimeout(() => location.href = '/project/detail/' + projectId, 2000);
            }
        });
    }

    /* ================================================================
     * 初期化
     * ================================================================ */
    $(function () {
        if (IS_REVIEW_PAGE) {
            loadJob();

            $('#btn-add-skill').on('click', function () {
                $('#rv-skills-container').append(`
                    <div class="d-flex gap-2 mb-2 skill-row">
                        <input type="text" class="form-control bg-dark border-secondary text-light skill-name" placeholder="スキル名">
                        <button type="button" class="btn btn-sm btn-outline-danger" onclick="$(this).closest('.skill-row').remove()">删</button>
                    </div>
                `);
            });

            $('#btn-save-review').on('click', function () {
                const data = collectReviewData();
                $.ajax({
                    url: '/api/project-ingestions/' + JOB_ID + '/review',
                    method: 'PUT',
                    contentType: 'application/json',
                    data: JSON.stringify(data),
                    headers: SES.csrf.header(),
                    success: function (res) {
                        if (res.code === 200) Toast.success('一時保存しました。');
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
                            url: '/api/project-ingestions/' + JOB_ID + '/reparse',
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
                            url: '/api/project-ingestions/' + JOB_ID + '/reject',
                            method: 'POST',
                            contentType: 'application/json',
                            data: JSON.stringify({ reason: result.value || '' }),
                            headers: SES.csrf.header(),
                            success: function (res) {
                                if (res.code === 200) { Toast.success('却下しました。'); setTimeout(() => location.href = '/project-ingestion', 1500); }
                                else Toast.error(res.message);
                            }
                        });
                    });
            });

            $('#btn-confirm').on('click', function () {
                const data = collectReviewData();
                if (!data.project.name) { Toast.error('案件名は必須です。'); return; }
                Swal.fire({ title: '確定', text: '案件を登録します。よろしいですか？', icon: 'info',
                    showCancelButton: true, confirmButtonText: '確定', cancelButtonText: 'キャンセル' })
                    .then(function (result) {
                        if (!result.isConfirmed) return;
                        $.ajax({
                            url: '/api/project-ingestions/' + JOB_ID + '/confirm',
                            method: 'POST',
                            contentType: 'application/json',
                            data: JSON.stringify(data),
                            headers: SES.csrf.header(),
                            success: function (res) {
                                if (res.code === 200) {
                                    showMatchingModal(res.data);
                                } else {
                                    Toast.error(res.message || '確定に失敗しました。');
                                }
                            }
                        });
                    });
            });

        } else {
            loadList(1);
            $('#filterStatus').on('change', function () { loadList(1); });
            $('#btn-upload').on('click', function () {
                $('#emlFile').val('');
                new bootstrap.Modal(document.getElementById('uploadModal')).show();
            });
            $('#btn-do-upload').on('click', uploadEml);

            $('#btn-paste').on('click', function () {
                $('#pasteText').val('');
                new bootstrap.Modal(document.getElementById('pasteModal')).show();
            });
            $('#btn-do-paste').on('click', pasteText);

            setInterval(function () {
                if ($('.badge.bg-info').length > 0) {
                    loadList(1);
                }
            }, 5000);
        }
    });

    window.loadList = loadList;
    window.reparseJob = reparseJob;

}());
