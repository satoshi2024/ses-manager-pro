let allSkillTags = [];

$(document).ready(function() {
    loadProjects();
    loadCustomersForSelect();
    loadAllSkillTags();
});

function loadAllSkillTags() {
    $.ajax({
        url: '/api/skill-tags',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                allSkillTags = res.data;
            }
        }
    });
}

function loadProjects(page = 1) {
    const data = {
        current: page,
        size: 10,
        projectName: $('#searchProjectName').val(),
        customerName: $('#searchCustomer').val(),
        status: $('#searchStatus').val()
    };

    $.ajax({
        url: '/api/projects',
        method: 'GET',
        data: data,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderProjects(res.data.records || res.data);
                if (res.data.total !== undefined) {
                    renderPagination(res.data, 'loadProjects');
                }
            } else {
                Toast.error(SES.i18n.t('js.common.error_fetch'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function renderPagination(pageData, loadFuncName) {
    const paginationContainer = $('.card-footer');
    if (pageData.total === 0) {
        paginationContainer.html(`<div class="text-muted small ps-2">${SES.i18n.t('common.page.totalZero')}</div>`);
        return;
    }
    
    const start = (pageData.current - 1) * pageData.size + 1;
    const end = Math.min(pageData.current * pageData.size, pageData.total);
    
    let html = `
        <div class="text-muted small ps-2">
            ${SES.i18n.t('common.page.info', [pageData.total, start, end])}
        </div>
        <nav aria-label="Page navigation">
            <ul class="pagination pagination-sm mb-0 pe-2">
    `;
    
    if (pageData.current > 1) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current - 1})"><i class="bi bi-chevron-left"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="javascript:void(0)" tabindex="-1" aria-disabled="true"><i class="bi bi-chevron-left"></i></a></li>`;
    }
    
    for (let i = 1; i <= pageData.pages; i++) {
        if (i === pageData.current) {
            html += `<li class="page-item active" aria-current="page"><a class="page-link bg-success border-success text-white" href="javascript:void(0)">${i}</a></li>`;
        } else if (i <= 3 || i >= pageData.pages - 2 || Math.abs(i - pageData.current) <= 1) {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${i})">${i}</a></li>`;
        } else if (i === 4 && pageData.current > 5) {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light disabled border-0"><span class="bg-transparent border-0 text-muted">...</span></a></li>`;
        } else if (i === pageData.pages - 3 && pageData.current < pageData.pages - 4) {
             html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light disabled border-0"><span class="bg-transparent border-0 text-muted">...</span></a></li>`;
        }
    }
    
    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)"><i class="bi bi-chevron-right"></i></a></li>`;
    }
    
    html += `</ul></nav>`;
    paginationContainer.html(html);
}

function loadCustomersForSelect() {
    $.ajax({
        url: '/api/customers',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const customers = res.data.records || res.data;
                const select = $('#proj-customerId');
                select.empty();
                select.append('<option value="">顧客を選択してください...</option>');
                customers.forEach(c => {
                    select.append(`<option value="${c.id}">${SES.escapeHtml(c.companyName)}</option>`);
                });
            }
        }
    });
}

function renderProjects(records) {
    const tbody = $('#project-table-body');
    tbody.empty();
    
    if (!records || records.length === 0) {
        tbody.append('<tr><td colspan="7" class="text-center text-muted py-4">データがありません</td></tr>');
        return;
    }
    
    records.forEach(proj => {
        // Remote Badge
        let remoteIcon = '';
        if (proj.remoteType === 'フルリモート') remoteIcon = '<i class="bi bi-check-circle-fill text-success" title="フルリモート"></i> フル';
        else if (proj.remoteType === 'ハイブリッド') remoteIcon = '<i class="bi bi-check-circle text-success" title="ハイブリッド"></i> 一部';
        else remoteIcon = '<i class="bi bi-x-circle text-danger" title="不可"></i> 不可';

        // Status Badge
        const localizedStatus = SES.i18n.e('projectStatus', proj.status);
        let statusBadge = `<span class="status-badge status-secondary">${localizedStatus || '-'}</span>`;
        if (proj.status === '募集中') statusBadge = `<span class="status-badge status-success">${localizedStatus}</span>`;
        else if (proj.status === '選考中') statusBadge = `<span class="status-badge status-warning">${localizedStatus}</span>`;
        else if (proj.status === '充足') statusBadge = `<span class="status-badge status-primary">${localizedStatus}</span>`;

        const min = proj.unitPriceMin ? '¥' + proj.unitPriceMin.toLocaleString() : '-';
        const max = proj.unitPriceMax ? '¥' + proj.unitPriceMax.toLocaleString() : '-';
        const priceStr = `${min} 〜 ${max}`;

        const tr = `
            <tr>
                <td class="ps-4 py-3">
                    <div class="fw-bold text-light">${SES.escapeHtml(proj.projectName)}</div>
                    <div class="text-muted small"><i class="bi bi-code-slash me-1"></i>${SES.escapeHtml(proj.description || '')}</div>
                </td>
                <td>${SES.escapeHtml(proj.customerName || '')}</td>
                <td class="font-monospace">${priceStr}</td>
                <td class="text-center">${proj.requiredCount || 1}名</td>
                <td class="text-center">${remoteIcon}</td>
                <td id="list-skills-${proj.id}"><div class="spinner-border spinner-border-sm text-secondary" role="status"></div></td>
                <td>${statusBadge}</td>
                <td class="text-end pe-4">
                    <div class="btn-group btn-group-sm" role="group">
                        <button type="button" class="btn btn-outline-success text-success border-success" title="${SES.i18n.t('js.project.search_candidate')}" onclick="findMatchingEngineers(${proj.id})"><i class="bi bi-robot"></i></button>
                        <button type="button" class="btn btn-outline-info text-info border-info" title="${SES.i18n.t('common.edit')}" onclick="editProject(${proj.id})"><i class="bi bi-pencil"></i></button>
                        <button type="button" class="btn btn-outline-danger text-danger border-danger" title="${SES.i18n.t('common.delete')}" onclick="deleteProject(${proj.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
        fetchAndRenderProjectSkills(proj.id);
    });
}

function fetchAndRenderProjectSkills(projectId) {
    $.ajax({
        url: '/api/projects/' + projectId + '/skills',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const skills = res.data;
                let badges = '';
                skills.forEach(s => {
                    const skillName = SES.escapeHtml(s.skillName);
                    if (s.isMust === 1) {
                        badges += `<span class="badge bg-danger me-1 mb-1">${skillName}</span>`;
                    } else {
                        badges += `<span class="badge bg-secondary border border-secondary text-light me-1 mb-1">${skillName}</span>`;
                    }
                });
                $('#list-skills-' + projectId).html(badges || '<span class="text-muted small">-</span>');
            } else {
                $('#list-skills-' + projectId).html('<span class="text-muted small">-</span>');
            }
        },
        error: function() {
            $('#list-skills-' + projectId).html('<span class="text-muted small">-</span>');
        }
    });
}

function editProject(id) {
    $.ajax({
        url: '/api/projects/' + id,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const proj = res.data;
                $('#proj-id').val(proj.id);
                $('#proj-projectName').val(proj.projectName);
                $('#proj-customerId').val(proj.customerId || '');
                $('#proj-unitPriceMin').val(proj.unitPriceMin);
                $('#proj-unitPriceMax').val(proj.unitPriceMax);
                $('#proj-requiredCount').val(proj.requiredCount || 1);
                $('#proj-remoteType').val(proj.remoteType);
                $('#proj-status').val(proj.status);
                
                // Fetch skills
                $.ajax({
                    url: '/api/projects/' + id + '/skills',
                    method: 'GET',
                    success: function(skillRes) {
                        $('#project-skills-container').empty();
                        if (skillRes.code === 200 && skillRes.data) {
                            skillRes.data.forEach(s => addProjectSkillRow(s));
                        }
                        bootstrap.Modal.getOrCreateInstance(document.getElementById('projectModal')).show();
                    }
                });
            } else {
                Toast.error(SES.i18n.t('js.common.error_fetch'));
            }
        }
    });
}

function saveProject() {
    const projectName = $('#proj-projectName').val();
    if (!projectName) {
        Toast.error(SES.i18n.t('js.project.error.name_required'));
        return;
    }
    
    const id = $('#proj-id').val();
    const data = {
        projectName: projectName,
        customerId: $('#proj-customerId').val() ? parseInt($('#proj-customerId').val()) : null,
        unitPriceMin: $('#proj-unitPriceMin').val() ? parseInt($('#proj-unitPriceMin').val()) : null,
        unitPriceMax: $('#proj-unitPriceMax').val() ? parseInt($('#proj-unitPriceMax').val()) : null,
        requiredCount: $('#proj-requiredCount').val() ? parseInt($('#proj-requiredCount').val()) : 1,
        remoteType: $('#proj-remoteType').val(),
        status: $('#proj-status').val()
    };

    if (id) {
        data.id = parseInt(id);
    }

    // Collect skills data
    const skills = [];
    let hasError = false;
    $('#project-skills-container .skill-row').each(function() {
        const skillId = $(this).find('.skill-select').val();
        if (!skillId) {
            hasError = true;
            return false; // break loop
        }
        skills.push({
            skillId: parseInt(skillId),
            requiredLevel: $(this).find('.level-select').val(),
            isMust: $(this).find('.is-must-check').is(':checked') ? 1 : 0
        });
    });

    if (hasError) {
        Toast.error(SES.i18n.t('js.project.error.skill_required'));
        return;
    }

    data.skills = skills;

    $.ajax({
        url: '/api/projects',
        method: id ? 'PUT' : 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.project.success.save'));
                finishSave();
            } else {
                Toast.error(res.message || SES.i18n.t('js.project.error.save'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function finishSave() {
    bootstrap.Modal.getOrCreateInstance(document.getElementById('projectModal')).hide();
    $('#project-form')[0].reset();
    $('#proj-id').val('');
    $('#project-skills-container').empty();
    loadProjects(1);
}

function addProjectSkillRow(skill = null) {
    const container = $('#project-skills-container');
    
    let optionsHtml = '<option value="">選択してください</option>';
    
    // Group skills by category
    const categories = [...new Set(allSkillTags.map(t => t.category))];
    categories.forEach(cat => {
        optionsHtml += `<optgroup label="${SES.escapeHtml(cat)}">`;
        allSkillTags.filter(t => t.category === cat).forEach(t => {
            const selected = (skill && skill.skillId === t.id) ? 'selected' : '';
            optionsHtml += `<option value="${t.id}" ${selected}>${SES.escapeHtml(t.skillName)}</option>`;
        });
        optionsHtml += `</optgroup>`;
    });

    const level = skill ? skill.requiredLevel : '中級';
    const isMustChecked = (skill && skill.isMust === 1) ? 'checked' : '';

    const rowHtml = `
        <div class="row g-2 mb-2 skill-row align-items-center">
            <div class="col-md-5">
                <select class="form-select form-select-sm form-select-dark bg-secondary text-white border-dark skill-select">
                    ${optionsHtml}
                </select>
            </div>
            <div class="col-md-3">
                <select class="form-select form-select-sm form-select-dark bg-secondary text-white border-dark level-select">
                    <option value="初級" ${level === '初級' ? 'selected' : ''}>初級</option>
                    <option value="中級" ${level === '中級' ? 'selected' : ''}>中級</option>
                    <option value="上級" ${level === '上級' ? 'selected' : ''}>上級</option>
                </select>
            </div>
            <div class="col-md-3">
                <div class="form-check form-switch mt-1">
                    <input class="form-check-input is-must-check" type="checkbox" role="switch" ${isMustChecked}>
                    <label class="form-check-label text-light small">必須</label>
                </div>
            </div>
            <div class="col-md-1 text-end">
                <button type="button" class="btn btn-sm btn-outline-danger border-0" onclick="$(this).closest('.skill-row').remove()">
                    <i class="bi bi-x-lg"></i>
                </button>
            </div>
        </div>
    `;
    container.append(rowHtml);
}

function deleteProject(id) {
    Swal.fire({
        title: SES.i18n.t('js.project.delete.title'),
        text: SES.i18n.t('js.project.delete.text'),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: SES.i18n.t('js.project.delete.confirm'),
        cancelButtonText: SES.i18n.t('js.project.delete.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/projects/' + id,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success(SES.i18n.t('js.project.delete.success'));
                        loadProjects();
                    } else {
                        Toast.error(res.message || SES.i18n.t('js.project.delete.error'));
                    }
                },
                error: function(err) {
                    console.error(err);
                    Toast.error(SES.i18n.t('js.common.error_network'));
                }
            });
        }
    });
}

function findMatchingEngineers(projectId) {
    const modal = new bootstrap.Modal(document.getElementById('matchingResultModal'));
    modal.show();
    
    const body = $('#matchingResultModalBody');
    body.html('<div class="text-center text-muted py-5"><div class="spinner-border text-primary" role="status"></div><div class="mt-2">' + SES.i18n.t('project.matching.loading') + '</div></div>');

    $.ajax({
        url: '/api/ai/matching/project/' + projectId,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const results = res.data;
                if (results.length === 0) {
                    body.html('<div class="text-center text-muted py-5">' + SES.i18n.t('js.project.match.not_found') + '</div>');
                    return;
                }
                
                let html = '<p class="mb-3">' + SES.i18n.t('js.project.match.recommend') + '</p>';
                results.forEach(match => {
                    const scoreColor = match.score >= 90 ? 'text-success' : (match.score >= 70 ? 'text-warning' : 'text-danger');
                    const priceText = match.proposedPrice ? '¥' + Number(match.proposedPrice).toLocaleString() : SES.i18n.t('js.project.match.expected_price_not_set');
                    const proposalPriceYen = match.proposedPrice ? match.proposedPrice : 'null';
                    html += `
                        <div class="card bg-secondary border-dark mb-3 shadow-sm">
                            <div class="card-body p-3">
                                <div class="d-flex justify-content-between align-items-start mb-2">
                                    <div>
                                        <h6 class="text-white fw-bold mb-1"><a href="/engineer/detail?id=${match.engineerId}" target="_blank" class="text-decoration-none text-light">${SES.escapeHtml(match.engineerName)}</a></h6>
                                        <div class="small text-muted">${SES.i18n.t('js.project.match.expected_price')}: ${priceText}</div>
                                    </div>
                                    <div class="fs-6 fw-bold ${scoreColor}">${match.score}%</div>
                                </div>
                                <p class="small text-light mb-2"><span class="badge bg-primary bg-opacity-25 text-primary border border-primary border-opacity-50 me-1">${SES.i18n.t('js.project.match.ai_eval')}</span>${SES.escapeHtml(match.reason)}</p>
                                <p class="small text-muted mb-2"><i class="bi bi-star-fill text-warning me-1"></i>${SES.escapeHtml(match.sellingPoints || SES.i18n.t('js.project.match.no_remarks'))}</p>
                                <div class="text-end">
                                    <button class="btn btn-sm btn-primary bg-gradient-blue border-0 rounded-pill px-3 shadow-sm" onclick="proposeEngineerToProject(${match.engineerId}, ${projectId}, ${match.score}, ${proposalPriceYen})">
                                        <i class="bi bi-send-fill me-1"></i>${SES.i18n.t('js.project.match.propose_btn')}
                                    </button>
                                </div>
                            </div>
                        </div>
                    `;
                });
                body.html(html);
            } else {
                body.html('<div class="text-center text-danger py-5">取得に失敗しました: ' + (res.message || '') + '</div>');
            }
        },
        error: function() {
            body.html('<div class="text-center text-danger py-5">通信エラーが発生しました</div>');
        }
    });
}

function proposeEngineerToProject(engineerId, projectId, score, price) {
    const data = {
        engineerId: engineerId,
        projectId: projectId,
        proposedUnitPrice: price,
        status: '書類選考中',
        aiMatchScore: score,
        matchReason: SES.i18n.t('proposal.matchReason')
    };

    $.ajax({
        url: '/api/proposals',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.project.propose.success'));
                setTimeout(() => window.location.href = '/proposal/kanban', 1500);
            } else {
                Toast.error(SES.i18n.t('js.project.propose.error') + ' ' + (res.message || ''));
            }
        },
        error: function() {
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}
