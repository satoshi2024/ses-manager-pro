$(document).ready(function() {
    // Get engineer ID from URL
    const urlParams = new URLSearchParams(window.location.search);
    const id = urlParams.get('id');
    
    if (id) {
        loadEngineerDetail(id);
    } else {
        Toast.error('要員IDが指定されていません');
    }
});

function loadEngineerDetail(id) {
    $.ajax({
        url: '/api/engineers/' + id,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderEngineerDetail(res.data);
            } else {
                Toast.error('データの取得に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

// 現在表示中の要員（写真アップロード等で参照）
let detailEngineer = null;

function renderEngineerDetail(eng) {
    detailEngineer = eng;

    // Update global variables for AI matching
    if (typeof currentEngineerId !== 'undefined') {
        currentEngineerId = eng.id;
        currentEngineerName = eng.fullName;
    }

    // Update Header
    $('#eng-name').text(eng.fullName);
    $('#eng-initial').text(`(${eng.initialName || '-'})`);
    $('#eng-avatar-initial').text(eng.initialName || '-');
    // Update Profile Card
    $('#det-name').text(eng.fullName);

    // Photo / Avatar
    renderAvatar(eng);
    
    let statusColor = 'text-muted';
    let statusIcon = 'bi-circle';
    if (eng.status === '稼動中') { statusColor = 'text-accent-green'; statusIcon = 'bi-check-circle-fill'; }
    if (eng.status === '提案中') { statusColor = 'text-info'; statusIcon = 'bi-arrow-right-circle-fill'; }
    if (eng.status === 'Bench') { statusColor = 'text-warning'; statusIcon = 'bi-pause-circle-fill'; }
    if (eng.status === '退場予定') { statusColor = 'text-danger'; statusIcon = 'bi-exclamation-circle-fill'; }
    
    $('#det-status').html(`<span class="${statusColor}"><i class="bi ${statusIcon} me-1"></i>${SES.escapeHtml(eng.status || '未設定')}</span>`);
    
    // Experience
    $('#det-experience').text(eng.experienceYears ? eng.experienceYears + '年' : '-');
    
    // Price
    const priceStr = eng.expectedUnitPrice ? '¥' + eng.expectedUnitPrice.toLocaleString() + ' / 月' : '-';
    $('#det-price').text(priceStr);
    
    // Station / Prefecture / Railway
    $('#det-station').text(eng.nearestStation || '-');
    $('#det-prefecture').text(eng.prefecture || '-');
    $('#det-railway').text(eng.railwayCompany || '-');
    
    // Load Skills
    loadSkills(eng.id);
    
    // Load Careers
    loadCareers(eng.id);

    // Resume Summary Timeline
    if (eng.resumeSummary) {
        // Just show it as one block for now
        $('#det-resume').html(`
            <div class="position-relative mb-4">
                <div class="position-absolute bg-accent-blue rounded-circle" style="width: 12px; height: 12px; left: -1.85rem; top: 0.3rem;"></div>
                <div class="text-muted small mb-1">サマリ</div>
                <p class="text-light small mb-2" style="white-space: pre-wrap;">${SES.escapeHtml(eng.resumeSummary)}</p>
            </div>
        `);
    } else {
        $('#det-resume').html('<div class="text-muted small">経歴情報が登録されていません。</div>');
    }
}

// ==========================================
// Photo / Avatar
// ==========================================
function renderAvatar(eng) {
    const $avatar = $('#det-avatar');
    if (eng.photoUrl) {
        $avatar.html(`<img src="/api/files/${SES.escapeHtml(eng.photoUrl)}" alt="顔写真" style="width:100%;height:100%;object-fit:cover;">`);
    } else {
        // 写真が無い場合はイニシャル or 氏名先頭文字
        const label = (eng.initialName && eng.initialName.trim())
            ? eng.initialName
            : (eng.fullName ? eng.fullName.charAt(0) : '?');
        $avatar.text(label);
    }
}

function uploadPhoto(input) {
    if (!input.files || input.files.length === 0) return;
    if (!detailEngineer) { Toast.error('要員情報が読み込まれていません'); return; }

    const formData = new FormData();
    formData.append('file', input.files[0]);
    formData.append('kind', 'PHOTO');

    $.ajax({
        url: '/api/files',
        method: 'POST',
        data: formData,
        processData: false,
        contentType: false,
        success: function(res) {
            if (res.code === 200 && res.data) {
                // 要員の photoUrl を保存名で更新
                const updated = Object.assign({}, detailEngineer, { photoUrl: res.data.storedName });
                $.ajax({
                    url: '/api/engineers',
                    method: 'PUT',
                    contentType: 'application/json',
                    data: JSON.stringify(updated),
                    success: function(r2) {
                        if (r2.code === 200) {
                            detailEngineer = updated;
                            renderAvatar(updated);
                            Toast.success('写真を更新しました');
                        } else {
                            Toast.error(r2.message || '写真の保存に失敗しました');
                        }
                    },
                    error: function() { Toast.error('写真の保存に失敗しました'); }
                });
            } else {
                Toast.error(res.message || 'アップロードに失敗しました');
            }
        },
        error: function(xhr) {
            const msg = (xhr.responseJSON && xhr.responseJSON.message) || 'アップロードに失敗しました';
            Toast.error(msg);
        },
        complete: function() { input.value = ''; }
    });
}

// ==========================================
// Skills
// ==========================================
function loadSkills(engineerId) {
    $.ajax({
        url: '/api/engineers/' + engineerId + '/skills',
        method: 'GET',
        success: function(res) {
            if (res.code === 200) {
                renderSkills(res.data);
            }
        }
    });
}

function renderSkills(skills) {
    if (!skills || skills.length === 0) {
        $('#det-skills').html('<span class="badge bg-secondary border border-dark text-light">登録なし</span>');
        return;
    }
    
    let html = '';
    skills.forEach(skill => {
        let badgeClass = 'bg-secondary';
        if (skill.proficiency === '上級') badgeClass = 'bg-accent-blue';
        if (skill.proficiency === '中級') badgeClass = 'bg-primary';
        
        let expText = skill.experienceYears ? ` (${skill.experienceYears}年)` : '';
        html += `<span class="badge ${badgeClass} border border-dark text-light">${SES.escapeHtml(skill.skillName)}${expText}</span> `;
    });
    $('#det-skills').html(html);
}

let skillOptionsHtml = '';
function fetchSkillOptions(callback) {
    if (skillOptionsHtml) {
        if (callback) callback();
        return;
    }
    $.ajax({
        url: '/api/skill-tags',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const groups = {};
                res.data.forEach(s => {
                    if (!groups[s.category]) groups[s.category] = [];
                    groups[s.category].push(s);
                });
                
                skillOptionsHtml = '<option value="">スキルを選択...</option>';
                for (const cat in groups) {
                    skillOptionsHtml += `<optgroup label="${SES.escapeHtml(cat)}">`;
                    groups[cat].forEach(s => {
                        skillOptionsHtml += `<option value="${s.id}">${SES.escapeHtml(s.skillName)}</option>`;
                    });
                    skillOptionsHtml += `</optgroup>`;
                }
                if (callback) callback();
            }
        }
    });
}

function openSkillModal() {
    if (typeof currentEngineerId === 'undefined') {
        Toast.error('要員データがありません');
        return;
    }
    
    fetchSkillOptions(() => {
        $.ajax({
            url: '/api/engineers/' + currentEngineerId + '/skills',
            method: 'GET',
            success: function(res) {
                if (res.code === 200) {
                    const tbody = $('#skill-edit-tbody');
                    tbody.empty();
                    
                    if (res.data && res.data.length > 0) {
                        res.data.forEach(skill => {
                            addSkillRow(skill);
                        });
                    } else {
                        addSkillRow();
                    }
                    
                    bootstrap.Modal.getOrCreateInstance(document.getElementById('skillEditModal')).show();
                }
            }
        });
    });
}

function addSkillRow(skill = null) {
    const tr = $('<tr>');
    
    // Skill Select
    const select = $(`<select class="form-select form-select-sm form-select-dark bg-secondary text-white border-dark skill-id-select">`).html(skillOptionsHtml);
    if (skill && skill.skillId) select.val(skill.skillId);
    
    // Proficiency Select
    const profSelect = $(`
        <select class="form-select form-select-sm form-select-dark bg-secondary text-white border-dark skill-prof-select">
            <option value="初級">初級</option>
            <option value="中級">中級</option>
            <option value="上級">上級</option>
        </select>
    `);
    if (skill && skill.proficiency) profSelect.val(skill.proficiency);
    
    // Experience Years Input
    const expInput = $(`<input type="number" class="form-control form-control-sm form-control-dark bg-secondary text-white border-dark skill-exp-input" min="0" placeholder="年">`);
    if (skill && skill.experienceYears) expInput.val(skill.experienceYears);
    
    // Delete Button
    const delBtn = $('<button type="button" class="btn btn-sm btn-outline-danger border-danger"><i class="bi bi-trash"></i></button>');
    delBtn.on('click', function() {
        $(this).closest('tr').remove();
    });
    
    tr.append($('<td>').append(select));
    tr.append($('<td>').append(profSelect));
    tr.append($('<td>').append(expInput));
    tr.append($('<td class="text-center">').append(delBtn));
    
    $('#skill-edit-tbody').append(tr);
}

function saveSkills() {
    const skills = [];
    $('#skill-edit-tbody tr').each(function() {
        const skillId = $(this).find('.skill-id-select').val();
        if (skillId) {
            skills.push({
                engineerId: currentEngineerId,
                skillId: parseInt(skillId),
                proficiency: $(this).find('.skill-prof-select').val(),
                experienceYears: $(this).find('.skill-exp-input').val() ? parseInt($(this).find('.skill-exp-input').val()) : null
            });
        }
    });
    
    $.ajax({
        url: '/api/engineers/' + currentEngineerId + '/skills',
        method: 'PUT',
        contentType: 'application/json',
        data: JSON.stringify(skills),
        success: function(res) {
            if (res.code === 200) {
                Toast.success('スキルを保存しました');
                bootstrap.Modal.getOrCreateInstance(document.getElementById('skillEditModal')).hide();
                loadSkills(currentEngineerId);
            } else {
                Toast.error(res.message || '保存に失敗しました');
            }
        }
    });
}

// ==========================================
// Careers
// ==========================================
function loadCareers(engineerId) {
    $.ajax({
        url: '/api/engineers/' + engineerId + '/careers',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderCareers(res.data);
            }
        }
    });
}

function renderCareers(careers) {
    const tbody = $('#career-table-body');
    tbody.empty();
    
    if (!careers || careers.length === 0) {
        tbody.html('<tr><td colspan="4" class="text-center text-muted">経歴情報がありません</td></tr>');
        return;
    }
    
    careers.forEach(car => {
        const fromStr = car.periodFrom ? car.periodFrom.substring(0, 7) : '?';
        const toStr = car.periodTo ? car.periodTo.substring(0, 7) : '現在';
        
        const tr = `
            <tr>
                <td>${fromStr} 〜 ${toStr}</td>
                <td>
                    <div class="fw-bold">${SES.escapeHtml(car.projectName || '-')}</div>
                    <div class="small text-muted">${SES.escapeHtml(car.clientIndustry || '')} ${car.techStack ? ' / ' + SES.escapeHtml(car.techStack) : ''}</div>
                </td>
                <td>
                    <div>${SES.escapeHtml(car.role || '-')}</div>
                    ${car.teamSize ? '<div class="small text-muted">' + car.teamSize + '名</div>' : ''}
                </td>
                <td class="text-end">
                    <div class="btn-group btn-group-sm">
                        <button class="btn btn-outline-info border-info" onclick="editCareer(${car.id})"><i class="bi bi-pencil"></i></button>
                        <button class="btn btn-outline-danger border-danger" onclick="deleteCareer(${car.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function openCareerModal() {
    $('#career-form')[0].reset();
    $('#car-id').val('');
    bootstrap.Modal.getOrCreateInstance(document.getElementById('careerModal')).show();
}

function editCareer(id) {
    $.ajax({
        url: '/api/engineers/' + currentEngineerId + '/careers/' + id,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const car = res.data;
                $('#car-id').val(car.id);
                $('#car-periodFrom').val(car.periodFrom);
                $('#car-periodTo').val(car.periodTo || '');
                $('#car-projectName').val(car.projectName);
                $('#car-clientIndustry').val(car.clientIndustry || '');
                $('#car-role').val(car.role || '');
                $('#car-teamSize').val(car.teamSize || '');
                $('#car-techStack').val(car.techStack || '');
                $('#car-description').val(car.description || '');
                
                bootstrap.Modal.getOrCreateInstance(document.getElementById('careerModal')).show();
            }
        }
    });
}

function saveCareer() {
    const id = $('#car-id').val();
    const data = {
        engineerId: currentEngineerId,
        periodFrom: $('#car-periodFrom').val(),
        periodTo: $('#car-periodTo').val() || null,
        projectName: $('#car-projectName').val(),
        clientIndustry: $('#car-clientIndustry').val() || null,
        role: $('#car-role').val() || null,
        teamSize: $('#car-teamSize').val() ? parseInt($('#car-teamSize').val()) : null,
        techStack: $('#car-techStack').val() || null,
        description: $('#car-description').val() || null
    };
    
    if (id) {
        data.id = parseInt(id);
    }
    
    if (!data.periodFrom || !data.projectName) {
        Toast.error('必須項目を入力してください');
        return;
    }
    
    $.ajax({
        url: '/api/engineers/' + currentEngineerId + '/careers' + (id ? '/' + id : ''),
        method: id ? 'PUT' : 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success('経歴を保存しました');
                bootstrap.Modal.getOrCreateInstance(document.getElementById('careerModal')).hide();
                loadCareers(currentEngineerId);
            } else {
                Toast.error(res.message || '保存に失敗しました');
            }
        }
    });
}

function deleteCareer(id) {
    Swal.fire({
        title: '削除確認',
        text: 'この経歴を削除しますか？',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        confirmButtonText: '削除する',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/engineers/' + currentEngineerId + '/careers/' + id,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success('削除しました');
                        loadCareers(currentEngineerId);
                    } else {
                        Toast.error(res.message || '削除に失敗しました');
                    }
                }
            });
        }
    });
}
