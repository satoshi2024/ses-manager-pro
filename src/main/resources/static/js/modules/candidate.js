// ==========================================
// 候補者管理(採用パイプライン) - 一覧・詳細共通JS
// SalesActivity/Proposalとは意図的にコード共有しない独立実装(design.md参照)
// ==========================================

let candidateId = null;

$(document).ready(function() {
    // 一覧画面
    if ($('#candidate-table-body').length) {
        loadCandidates(1);
    }

    // 詳細画面
    if ($('#timeline-container').length) {
        const urlParams = new URLSearchParams(window.location.search);
        candidateId = urlParams.get('id');
        if (candidateId) {
            loadCandidateDetail();
            loadCandidateActivities();
        } else {
            Toast.error(SES.i18n.t('error.getDataFailed'));
        }
    }

    // ステージ変更モーダル表示時に理由必須表示を初期化
    const stageModal = document.getElementById('stageModal');
    if (stageModal) {
        stageModal.addEventListener('show.bs.modal', function() {
            document.getElementById('stage-form').reset();
            toggleReasonRequired();
        });
    }
});

// ==========================================
// 一覧画面
// ==========================================

function loadCandidates(page = 1) {
    const data = {
        current: page,
        size: 10,
        name: $('#searchName').val(),
        stage: $('#searchStage').val(),
        skillKeyword: $('#searchSkill').val()
    };

    $.ajax({
        url: '/api/candidates',
        method: 'GET',
        data: data,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderCandidates(res.data.records || res.data);
                if (res.data.total !== undefined) {
                    renderCandidatePagination(res.data);
                }
            } else {
                Toast.error(SES.i18n.t('error.getDataFailed'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('error.networkError'));
        }
    });
}

function stageBadgeClass(stage) {
    switch (stage) {
        case '入社': return 'status-success';
        case '内定': return 'status-success';
        case '不採用':
        case '内定辞退': return 'status-danger';
        case '応募受付': return 'status-secondary';
        default: return 'status-warning';
    }
}

function stageLabel(stage) {
    const map = {
        '応募受付': 'candidate.stage.applied',
        '書類選考': 'candidate.stage.documentScreening',
        '一次面談': 'candidate.stage.firstInterview',
        '最終面談': 'candidate.stage.finalInterview',
        '内定': 'candidate.stage.offer',
        '内定辞退': 'candidate.stage.offerDeclined',
        '入社': 'candidate.stage.hired',
        '不採用': 'candidate.stage.rejected'
    };
    return map[stage] ? SES.i18n.t(map[stage]) : (stage || '-');
}

function renderCandidates(records) {
    const tbody = $('#candidate-table-body');
    tbody.empty();

    if (!records || records.length === 0) {
        tbody.append('<tr><td colspan="7" class="text-center text-muted py-4">' + SES.i18n.t('common.noData') + '</td></tr>');
        return;
    }

    const todayStr = new Date().toISOString().split('T')[0];

    records.forEach(c => {
        const isOverdue = c.nextActionDate && c.nextActionDate <= todayStr
            && c.currentStage !== '入社' && c.currentStage !== '不採用' && c.currentStage !== '内定辞退';
        const nextActionHtml = c.nextActionDate
            ? `<span class="${isOverdue ? 'text-danger fw-bold' : 'text-light'}">${SES.escapeHtml(c.nextActionDate)}${isOverdue ? ' <i class="bi bi-exclamation-circle ms-1" title="' + SES.i18n.t('candidate.overdue') + '"></i>' : ''}</span>`
            : '-';

        const tr = `
            <tr>
                <td class="ps-4">
                    <a href="/candidate/detail?id=${c.id}" class="text-light text-decoration-none fw-bold">${SES.escapeHtml(c.name || '-')}</a>
                </td>
                <td><span class="status-badge ${stageBadgeClass(c.currentStage)}">${SES.escapeHtml(stageLabel(c.currentStage))}</span></td>
                <td class="text-truncate" style="max-width: 260px;">${SES.escapeHtml(c.skillSummary || '-')}</td>
                <td>${c.desiredRate ? Number(c.desiredRate).toLocaleString() : '-'}</td>
                <td>${SES.escapeHtml(c.source || '-')}</td>
                <td>${nextActionHtml}</td>
                <td class="text-end pe-4">
                    <div class="btn-group btn-group-sm" role="group">
                        <a href="/candidate/detail?id=${c.id}" class="btn btn-outline-secondary text-light border-secondary"><i class="bi bi-eye"></i></a>
                        <button type="button" class="btn btn-outline-danger text-danger border-danger" onclick="deleteCandidate(${c.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function renderCandidatePagination(pageData) {
    const container = $('#candidate-pagination');
    if (pageData.total === 0) {
        container.html('<div class="text-muted small ps-2">' + SES.i18n.t('common.page.totalZero') + '</div>');
        return;
    }

    let html = `<div class="text-muted small ps-2">` + SES.i18n.t('common.page.totalCount', [pageData.total]) + `</div>
        <nav aria-label="Page navigation"><ul class="pagination pagination-sm mb-0 pe-2">`;

    if (pageData.current > 1) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadCandidates(${pageData.current - 1})"><i class="bi bi-chevron-left"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="javascript:void(0)" tabindex="-1"><i class="bi bi-chevron-left"></i></a></li>`;
    }

    for (let i = 1; i <= pageData.pages; i++) {
        if (i === pageData.current) {
            html += `<li class="page-item active" aria-current="page"><a class="page-link bg-primary border-primary" href="javascript:void(0)">${i}</a></li>`;
        } else {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadCandidates(${i})">${i}</a></li>`;
        }
    }

    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadCandidates(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)"><i class="bi bi-chevron-right"></i></a></li>`;
    }

    html += `</ul></nav>`;
    container.html(html);
}

function saveCandidate() {
    const name = $('#cand-name').val();
    if (!name) {
        Toast.error(SES.i18n.t('validation.required', SES.i18n.t('candidate.name')));
        return;
    }

    const data = {
        name: name,
        contactEmail: $('#cand-contactEmail').val() || null,
        contactPhone: $('#cand-contactPhone').val() || null,
        skillSummary: $('#cand-skillSummary').val() || null,
        desiredRate: $('#cand-desiredRate').val() ? parseInt($('#cand-desiredRate').val()) : null,
        source: $('#cand-source').val() || null,
        nextActionDate: $('#cand-nextActionDate').val() || null,
        remarks: $('#cand-remarks').val() || null
    };

    $.ajax({
        url: '/api/candidates',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('success.create'));
                bootstrap.Modal.getOrCreateInstance(document.getElementById('candidateModal')).hide();
                $('#candidate-form')[0].reset();
                loadCandidates(1);
            } else {
                Toast.error(res.message || SES.i18n.t('error.saveFailed'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('error.networkError'));
        }
    });
}

function deleteCandidate(id) {
    Swal.fire({
        title: SES.i18n.t('common.deleteConfirmTitle'),
        text: SES.i18n.t('confirm.deleteCandidate'),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: SES.i18n.t('common.delete'),
        cancelButtonText: SES.i18n.t('common.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/candidates/' + id,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success(SES.i18n.t('success.delete'));
                        loadCandidates(1);
                    } else {
                        Toast.error(res.message || SES.i18n.t('error.deleteFailed'));
                    }
                },
                error: function(err) {
                    console.error(err);
                    Toast.error(SES.i18n.t('error.networkError'));
                }
            });
        }
    });
}

// ==========================================
// 詳細画面
// ==========================================

function loadCandidateDetail() {
    $.ajax({
        url: '/api/candidates/' + candidateId,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderCandidateDetail(res.data);
            } else {
                Toast.error(SES.i18n.t('error.getDataFailed'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('error.networkError'));
        }
    });
}

function renderCandidateDetail(c) {
    $('#header-candidate-name').text(c.name || '-');
    $('#info-name').text(c.name || '-');
    $('#info-currentStage').text(stageLabel(c.currentStage));
    $('#info-contactEmail').text(c.contactEmail || '-');
    $('#info-contactPhone').text(c.contactPhone || '-');
    $('#info-desiredRate').text(c.desiredRate ? Number(c.desiredRate).toLocaleString() : '-');
    $('#info-source').text(c.source || '-');
    $('#info-nextActionDate').text(c.nextActionDate || '-');
    $('#info-skillSummary').text(c.skillSummary || '-');
    $('#info-remarks').text(c.remarks || '-');

    // 「入社」ステージの場合のみ「エンジニアとして登録」ボタンを表示
    // (design.md 2.2/3章: converted済みなら再度の変換は行わない)
    if (c.currentStage === '入社' && !c.convertedEngineerId) {
        $('#btn-convert-engineer').removeClass('d-none');
    } else {
        $('#btn-convert-engineer').addClass('d-none');
    }
}

function loadCandidateActivities() {
    $.ajax({
        url: '/api/candidates/' + candidateId + '/activities',
        method: 'GET',
        success: function(res) {
            if (res.code === 200) {
                renderActivities(res.data || []);
            }
        },
        error: function(err) {
            console.error(err);
        }
    });
}

function renderActivities(records) {
    const container = $('#timeline-container');
    container.empty();

    if (!records || records.length === 0) {
        container.append('<div class="text-center text-muted py-4">' + SES.i18n.t('candidate.emptyActivity') + '</div>');
        return;
    }

    let html = '<div class="timeline position-relative ps-4 py-2" style="border-left: 2px solid #343a40;">';

    records.forEach(act => {
        const isReasonStage = act.stage === '不採用' || act.stage === '内定辞退';
        const borderClass = isReasonStage ? 'border-danger' : 'border-secondary';
        const bgClass = isReasonStage ? 'bg-danger bg-opacity-10' : 'bg-dark';

        html += `
            <div class="timeline-item position-relative mb-4">
                <div class="position-absolute bg-dark border border-secondary rounded-circle d-flex align-items-center justify-content-center text-primary" style="width: 32px; height: 32px; left: -41px; top: 0;">
                    <i class="bi bi-arrow-repeat"></i>
                </div>
                <div class="card ${bgClass} ${borderClass} shadow-sm">
                    <div class="card-body p-3">
                        <div class="d-flex justify-content-between align-items-start mb-2">
                            <h6 class="mb-1 text-light">${SES.escapeHtml(stageLabel(act.stage))}</h6>
                            <span class="text-muted small"><i class="bi bi-calendar3 me-1"></i>${SES.escapeHtml((act.changedAt || '').replace('T', ' '))}</span>
                        </div>
                        ${act.reason ? `<div class="text-light small mb-1"><strong>${SES.i18n.t('candidate.activity.reason')}:</strong> ${SES.escapeHtml(act.reason)}</div>` : ''}
                        ${act.remarks ? `<div class="text-muted small">${SES.escapeHtml(act.remarks)}</div>` : ''}
                    </div>
                </div>
            </div>
        `;
    });

    html += '</div>';
    container.html(html);
}

function toggleReasonRequired() {
    const stage = $('#stage-newStage').val();
    const requiresReason = stage === '不採用' || stage === '内定辞退';
    $('#stage-reason-required').toggleClass('d-none', !requiresReason);
}

function saveStageChange() {
    const newStage = $('#stage-newStage').val();
    const reason = $('#stage-reason').val();
    const requiresReason = newStage === '不採用' || newStage === '内定辞退';

    if (requiresReason && !reason) {
        Toast.error(SES.i18n.t('error.candidate.reasonRequired'));
        return;
    }

    const data = {
        stage: newStage,
        reason: reason || null,
        remarks: $('#stage-remarks').val() || null
    };

    $.ajax({
        url: '/api/candidates/' + candidateId + '/activities',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('success.candidate.stageChanged'));
                bootstrap.Modal.getOrCreateInstance(document.getElementById('stageModal')).hide();
                loadCandidateDetail();
                loadCandidateActivities();
            } else {
                Toast.error(res.message || SES.i18n.t('error.saveFailed'));
            }
        },
        error: function(xhr) {
            const msg = (xhr.responseJSON && xhr.responseJSON.message) || SES.i18n.t('error.networkError');
            Toast.error(msg);
        }
    });
}

// ==========================================
// 入社→エンジニア変換連携
// ==========================================

function convertToEngineer() {
    Swal.fire({
        title: SES.i18n.t('candidate.convertToEngineer'),
        text: SES.i18n.t('candidate.convertToEngineer.confirm'),
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: SES.i18n.t('common.confirm'),
        cancelButtonText: SES.i18n.t('common.cancel')
    }).then((result) => {
        if (!result.isConfirmed) return;

        $.ajax({
            url: '/api/candidates/' + candidateId + '/convert-to-engineer',
            method: 'POST',
            success: function(res) {
                if (res.code === 200 && res.data) {
                    const dto = res.data;
                    const params = new URLSearchParams({
                        prefillCandidateId: dto.candidateId,
                        prefillName: dto.fullName || '',
                        prefillSkillSummary: dto.resumeSummary || ''
                    });
                    window.location.href = '/engineer/list?' + params.toString();
                } else {
                    Toast.error(res.message || SES.i18n.t('error.saveFailed'));
                }
            },
            error: function(xhr) {
                const msg = (xhr.responseJSON && xhr.responseJSON.message) || SES.i18n.t('error.networkError');
                Toast.error(msg);
            }
        });
    });
}
