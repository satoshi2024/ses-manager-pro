// 駅名 -> 紐づく pref（「都道府県 路線」形式）の一覧。同名駅が複数路線に存在するため配列で保持する。
window.stationIndex = {};

// 候補者管理からの「エンジニアとして登録」導線で渡される変換元候補者ID
// (保存完了後にPUT /api/candidates/{id}/converted-engineerで紐付けるために保持する)
let prefillCandidateId = null;

$(document).ready(function() {
    // Load engineers on page load
    loadEngineers();

    // Load station names lazily when the modal is opened
    $('#engineerModal').on('show.bs.modal', function () {
        if (!window.stationsLoaded) {
            loadAllStations();
        }
    });

    // Load skills for search filter
    loadSearchSkills();

    // Load sales users for search filter
    loadSalesUsers();

    // 最寄り駅を入力/選択したら、その駅の路線候補を絞り込む
    $('#eng-nearestStation').on('input change', function() {
        populateRailwayLines($(this).val(), null);
    });
    // 路線（鉄道会社）を選択したら、都道府県と鉄道会社を自動設定する
    $('#eng-railwayLine').on('change', applyRailwaySelection);

    // 候補者「入社」→エンジニア変換連携: クエリパラメータで初期値が渡された場合、
    // 新規登録モーダルを開いて氏名・経歴サマリを補完する(自動保存はしない。要件3.2)。
    applyCandidateConversionPrefill();
});

function applyCandidateConversionPrefill() {
    const params = new URLSearchParams(window.location.search);
    const candidateId = params.get('prefillCandidateId');
    if (!candidateId) return;

    prefillCandidateId = candidateId;
    $('#eng-fullName').val(params.get('prefillName') || '');
    $('#eng-resumeSummary').val(params.get('prefillSkillSummary') || '');
    bootstrap.Modal.getOrCreateInstance(document.getElementById('engineerModal')).show();
}

function loadSearchSkills() {
    $.ajax({
        url: '/api/skill-tags',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const select = $('#searchSkill');
                select.empty();
                select.append('<option value="">' + SES.i18n.t('common.all') + '</option>');
                // group by category
                const groups = {};
                res.data.forEach(skill => {
                    if (!groups[skill.category]) groups[skill.category] = [];
                    groups[skill.category].push(skill);
                });
                for (const cat in groups) {
                    const optgroup = $('<optgroup>').attr('label', cat);
                    groups[cat].forEach(skill => {
                        optgroup.append($('<option>').val(skill.id).text(skill.skillName));
                    });
                    select.append(optgroup);
                }
            }
        }
    });
}

function loadSalesUsers() {
    $.ajax({
        url: '/api/engineers/sales-user-options',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const select = $('#searchSalesUser');
                select.empty();
                select.append('<option value="">' + SES.i18n.t('common.all') + '</option>');
                res.data.forEach(user => {
                    select.append($('<option>').val(user.id).text(user.realName));
                });
            }
        }
    });
}

function loadAllStations() {
    window.stationsLoaded = true;
    $.ajax({
        url: '/data/station_names.json',
        method: 'GET',
        dataType: 'json',
        success: function(res) {
            if (res && res.length > 0) {
                const datalist = $('#station-list');
                datalist.empty();
                // To prevent browser lag with 10k elements, modern browsers handle datalists very well,
                // but we can just append them as strings.
                let html = '';
                const seenNames = {};
                window.stationIndex = {};
                res.forEach(item => {
                    // 駅名 -> pref のインデックスを構築（重複 pref は除外）
                    if (!window.stationIndex[item.name]) window.stationIndex[item.name] = [];
                    if (window.stationIndex[item.name].indexOf(item.pref) === -1) {
                        window.stationIndex[item.name].push(item.pref);
                    }
                    // datalist は駅名の重複を避けて 1 件だけ出す
                    if (!seenNames[item.name]) {
                        seenNames[item.name] = true;
                        // Setting text content of <option> shows as lighter text on the right side in Chrome
                        html += `<option value="${item.name}">${item.pref}</option>`;
                    }
                });
                datalist.html(html);
            }
        },
        error: function(err) {
            console.error("Failed to load station names", err);
        }
    });
}

// pref（「都道府県 路線」形式）を都道府県と路線に分割する
function splitPref(pref) {
    if (!pref) return { prefecture: '', line: '' };
    const m = String(pref).match(/^(\S+)\s+([\s\S]+)$/);
    if (m) return { prefecture: m[1], line: m[2] };
    return { prefecture: pref, line: '' };
}

// 指定した駅名に紐づく路線を路線セレクトに展開する。
// selectedPref があればそれを初期選択にする（編集時の復元用、「都道府県 路線」形式）。
function populateRailwayLines(stationName, selectedPref) {
    const select = $('#eng-railwayLine');
    const entries = (window.stationIndex && window.stationIndex[stationName]) || [];
    select.empty();

    if (entries.length === 0) {
        // JSON に無い駅名。編集時に保存済みの値があれば表示できるようにする。
        if (selectedPref) {
            select.append(`<option value="${SES.escapeHtml(selectedPref)}" selected>${SES.escapeHtml(splitPref(selectedPref).line || selectedPref)}</option>`);
            select.prop('disabled', false);
        } else {
            select.append('<option value="">' + SES.i18n.t('engineer.message.noRailwayInfo') + '</option>');
            select.prop('disabled', true);
        }
        applyRailwaySelection();
        return;
    }

    select.append('<option value="">' + SES.i18n.t('engineer.placeholder.selectRailway') + '</option>');
    entries.forEach(pref => {
        const line = splitPref(pref).line;
        select.append(`<option value="${SES.escapeHtml(pref)}">${SES.escapeHtml(line)}</option>`);
    });

    if (selectedPref) {
        select.val(selectedPref);
        // 候補一覧に無い保存値だった場合は選択肢として補って選択状態にする
        if (!select.val()) {
            select.append(`<option value="${SES.escapeHtml(selectedPref)}" selected>${SES.escapeHtml(splitPref(selectedPref).line || selectedPref)}</option>`);
            select.val(selectedPref);
        }
    } else if (entries.length === 1) {
        // 候補が 1 つだけなら自動選択
        select.val(entries[0]);
    }
    select.prop('disabled', false);
    applyRailwaySelection();
}

// 路線セレクトの選択値から、都道府県と鉄道会社（保存用hidden）と表示を更新する
function applyRailwaySelection() {
    const val = $('#eng-railwayLine').val();
    if (!val) {
        $('#eng-prefecture').val('');
        $('#eng-railwayCompany').val('');
        $('#eng-prefecture-text').text('-');
        return;
    }
    const parts = splitPref(val);
    $('#eng-prefecture').val(parts.prefecture);
    $('#eng-railwayCompany').val(parts.line);
    $('#eng-prefecture-text').text(parts.prefecture || '-');
}

function loadEngineers(page = 1) {
    let skillIdsStr = '';
    let selectedSkills = $('#searchSkill').val();
    if (selectedSkills) {
        if (!Array.isArray(selectedSkills)) {
            selectedSkills = [selectedSkills];
        }
        if (selectedSkills.length > 0) {
            skillIdsStr = selectedSkills.join(','); // We can send them as comma separated or multiple parameters depending on Spring Boot setup. Usually Spring MVC binds multiple params nicely if we send them as an array.
        }
    }

    const data = {
        current: page,
        size: 10,
        fullName: $('#searchName').val(),
        status: $('#searchStatus').val(),
        employmentType: $('#searchEmpType').val(),
        salesUserId: $('#searchSalesUser').val(),
        skillIds: selectedSkills // jQuery ajax will format this as skillIds[]=1&skillIds[]=2 or we can set traditional: true
    };

    $.ajax({
        url: '/api/engineers',
        method: 'GET',
        traditional: true, // so it sends skillIds=1&skillIds=2 instead of skillIds[]=1
        data: data,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderEngineers(res.data.records || res.data); // Handle pagination object if present
                if (res.data.total !== undefined) {
                    renderPagination(res.data, 'loadEngineers');
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

function renderPagination(pageData, loadFuncName) {
    const paginationContainer = $('.card-footer');
    if (pageData.total === 0) {
        paginationContainer.html('<div class="text-muted small ps-2">' + SES.i18n.t('common.page.totalZero') + '</div>');
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
    
    // Prev
    if (pageData.current > 1) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current - 1})"><i class="bi bi-chevron-left"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="javascript:void(0)" tabindex="-1" aria-disabled="true"><i class="bi bi-chevron-left"></i></a></li>`;
    }
    
    // Pages (Simplified)
    for (let i = 1; i <= pageData.pages; i++) {
        if (i === pageData.current) {
            html += `<li class="page-item active" aria-current="page"><a class="page-link bg-primary border-primary" href="javascript:void(0)">${i}</a></li>`;
        } else if (i <= 3 || i >= pageData.pages - 2 || Math.abs(i - pageData.current) <= 1) {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${i})">${i}</a></li>`;
        } else if (i === 4 && pageData.current > 5) {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light disabled border-0"><span class="bg-transparent border-0 text-muted">...</span></a></li>`;
        } else if (i === pageData.pages - 3 && pageData.current < pageData.pages - 4) {
             html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light disabled border-0"><span class="bg-transparent border-0 text-muted">...</span></a></li>`;
        }
    }
    
    // Next
    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)"><i class="bi bi-chevron-right"></i></a></li>`;
    }
    
    html += `</ul></nav>`;
    paginationContainer.html(html);
}

function renderEngineers(records) {
    const tbody = $('#engineer-table-body');
    tbody.empty();
    
    if (!records || records.length === 0) {
        tbody.append('<tr><td colspan="7" class="text-center text-muted py-4">' + SES.i18n.t('common.noData') + '</td></tr>');
        return;
    }
    
    records.forEach(eng => {
        // Build avatar
        const initial = SES.escapeHtml(eng.initialName || (eng.fullName ? eng.fullName.charAt(0) : '?'));
        const kana = SES.escapeHtml(eng.fullNameKana || '');
        
        // Status Badge
        let statusBadge = '';
        if (eng.status === '稼動中') statusBadge = '<span class="status-badge status-success">稼動中</span>';
        else if (eng.status === '提案中') statusBadge = '<span class="status-badge status-warning">提案中</span>';
        else if (eng.status === '退場予定') statusBadge = '<span class="status-badge status-danger">退場予定</span>';
        else statusBadge = '<span class="status-badge status-secondary">Bench</span>';

        const priceStr = eng.expectedUnitPrice ? eng.expectedUnitPrice.toLocaleString() + SES.i18n.t('engineer.expectedPrice.currency') : '-';
        const expStr = eng.experienceYears ? eng.experienceYears + SES.i18n.t('engineer.experience.unit') : '-';

        const tr = `
            <tr>
                <td class="ps-4">
                    <div class="d-flex align-items-center py-1">
                        <div class="avatar bg-primary text-white rounded-circle me-3 d-flex align-items-center justify-content-center" style="width: 36px; height: 36px;">${initial}</div>
                        <div>
                            <div class="fw-bold">${SES.escapeHtml(eng.fullName || '-')}</div>
                            <div class="text-muted small">${kana}</div>
                        </div>
                    </div>
                </td>
                <td>${statusBadge}</td>
                <td>${SES.escapeHtml(eng.employmentType || '-')}</td>
                <td>${expStr}</td>
                <td>${priceStr}</td>
                <td class="text-light">${SES.escapeHtml(eng.primarySalesUserName || '-')}</td>
                <td class="text-end pe-4">
                    <div class="btn-group btn-group-sm" role="group">
                        <a href="/engineer/detail?id=${eng.id}" class="btn btn-outline-secondary text-light border-secondary"><i class="bi bi-eye"></i></a>
                        <button type="button" class="btn btn-outline-info text-info border-info" onclick="editEngineer(${eng.id})"><i class="bi bi-pencil"></i></button>
                        <button type="button" class="btn btn-outline-danger text-danger border-danger" onclick="deleteEngineer(${eng.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function editEngineer(id) {
    $.ajax({
        url: '/api/engineers/' + id,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const eng = res.data;
                $('#eng-id').val(eng.id);
                $('#eng-fullName').val(eng.fullName);
                $('#eng-fullNameKana').val(eng.fullNameKana);
                $('#eng-employmentType').val(eng.employmentType);
                $('#eng-status').val(eng.status);
                $('#eng-experienceYears').val(eng.experienceYears);
                $('#eng-expectedUnitPrice').val(eng.expectedUnitPrice);
                $('#eng-resumeSummary').val(eng.resumeSummary || '');
                
                // 最寄り駅・都道府県・鉄道会社を復元
                $('#eng-nearestStation').val(eng.nearestStation || '');
                $('#eng-prefecture').val(eng.prefecture || '');
                $('#eng-railwayCompany').val(eng.railwayCompany || '');
                // 保存済みの「都道府県 路線」を再構築して路線セレクトを初期選択にする
                const selectedPref = eng.prefecture
                    ? (eng.prefecture + (eng.railwayCompany ? ' ' + eng.railwayCompany : ''))
                    : (eng.railwayCompany || '');
                populateRailwayLines(eng.nearestStation || '', selectedPref);
                $('#eng-prefecture-text').text(eng.prefecture || '-');

                // モーダル表示（既存インスタンスを再利用し、二重生成・バックドロップ残りを防ぐ）
                bootstrap.Modal.getOrCreateInstance(document.getElementById('engineerModal')).show();
            } else {
                Toast.error(SES.i18n.t('error.getDataFailed'));
            }
        }
    });
}

const KANA_TO_ROMAJI = {
    'ア':'A', 'イ':'I', 'ウ':'U', 'エ':'E', 'オ':'O',
    'カ':'K', 'キ':'K', 'ク':'K', 'ケ':'K', 'コ':'K',
    'ガ':'G', 'ギ':'G', 'グ':'G', 'ゲ':'G', 'ゴ':'G',
    'サ':'S', 'シ':'S', 'ス':'S', 'セ':'S', 'ソ':'S',
    'ザ':'Z', 'ジ':'Z', 'ズ':'Z', 'ゼ':'Z', 'ゾ':'Z',
    'タ':'T', 'チ':'T', 'ツ':'T', 'テ':'T', 'ト':'T',
    'ダ':'D', 'ヂ':'D', 'ヅ':'D', 'デ':'D', 'ド':'D',
    'ナ':'N', 'ニ':'N', 'ヌ':'N', 'ネ':'N', 'ノ':'N',
    'ハ':'H', 'ヒ':'H', 'フ':'H', 'ヘ':'H', 'ホ':'H',
    'バ':'B', 'ビ':'B', 'ブ':'B', 'ベ':'B', 'ボ':'B',
    'パ':'P', 'ピ':'P', 'プ':'P', 'ペ':'P', 'ポ':'P',
    'マ':'M', 'ミ':'M', 'ム':'M', 'メ':'M', 'モ':'M',
    'ヤ':'Y', 'ユ':'Y', 'ヨ':'Y',
    'ラ':'R', 'リ':'R', 'ル':'R', 'レ':'R', 'ロ':'R',
    'ワ':'W', 'ヲ':'W', 'ン':'N'
};

function extractInitials(fullName, kanaName) {
    let nameToProcess = kanaName || fullName;
    if (!nameToProcess) return '?';
    
    // Convert half-width kana to full-width or deal with it (simplification: assume full-width or Kanji)
    // Split by spaces (half or full width)
    const parts = nameToProcess.trim().split(/[\s　]+/);
    let initials = [];
    
    for (let p of parts) {
        if (p.length > 0) {
            const firstChar = p.charAt(0);
            const romaji = KANA_TO_ROMAJI[firstChar];
            if (romaji) {
                initials.push(romaji);
            } else {
                initials.push(firstChar.toUpperCase()); // Fallback to Kanji or English letter
            }
        }
    }
    
    return initials.length > 0 ? initials.join('.') : '?';
}

function saveEngineer() {
    const fullName = $('#eng-fullName').val();
    if (!fullName) {
        Toast.error(SES.i18n.t('validation.required', [SES.i18n.t('engineer.name')]));
        return;
    }
    
    const id = $('#eng-id').val();
    const nearestStation = $('#eng-nearestStation').val() || '';
    const fullNameKana = $('#eng-fullNameKana').val();
    const computedInitial = extractInitials(fullName, fullNameKana);

    const data = {
        fullName: fullName,
        fullNameKana: fullNameKana,
        initialName: computedInitial,
        employmentType: $('#eng-employmentType').val(),
        status: $('#eng-status').val(),
        experienceYears: $('#eng-experienceYears').val() ? parseInt($('#eng-experienceYears').val()) : null,
        expectedUnitPrice: $('#eng-expectedUnitPrice').val() ? parseInt($('#eng-expectedUnitPrice').val()) : null,
        nearestStation: nearestStation,
        prefecture: $('#eng-prefecture').val() || null,
        railwayCompany: $('#eng-railwayCompany').val() || null,
        resumeSummary: $('#eng-resumeSummary').val() || null
    };

    if (id) {
        data.id = parseInt(id);
    }

    $.ajax({
        url: id ? '/api/engineers/' + id : '/api/engineers',
        method: id ? 'PUT' : 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(id ? SES.i18n.t('success.update') : SES.i18n.t('success.create'));
                // getInstance は未生成時に null を返し .hide() で例外→モーダルが閉じない不具合になるため getOrCreateInstance を使う
                bootstrap.Modal.getOrCreateInstance(document.getElementById('engineerModal')).hide();

                // 候補者からの変換導線: 新規登録が完了したら、候補者側にconvertedEngineerIdを紐付ける
                if (!id && prefillCandidateId && res.data && res.data.id) {
                    $.ajax({
                        url: '/api/candidates/' + prefillCandidateId + '/converted-engineer',
                        method: 'PUT',
                        contentType: 'application/json',
                        data: JSON.stringify({ engineerId: res.data.id })
                    });
                    prefillCandidateId = null;
                }

                $('#engineer-form')[0].reset();
                $('#eng-id').val('');
                loadEngineers(1);
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



// 現在の検索条件を反映してExcel出力する。
// バイナリレスポンスのため $.ajax ではなく window.location.href で直接ダウンロードさせる
// (common.js の ajaxSetup complete ハンドラが非JSONレスポンスをセッション切れと誤検知するのを避けるため)
function exportEngineers() {
    const selectedSkills = $('#searchSkill').val();
    const params = {
        fullName: $('#searchName').val(),
        status: $('#searchStatus').val(),
        employmentType: $('#searchEmpType').val(),
        salesUserId: $('#searchSalesUser').val(),
        skillIds: selectedSkills
    };
    window.location.href = '/api/engineers/export?' + $.param(params, true);
}

function exportEngineersCsv() {
    const params = {
        fullName: $('#searchName').val(),
        status: $('#searchStatus').val(),
        employmentType: $('#searchEmpType').val(),
        salesUserId: $('#searchSalesUser').val(),
        skillIds: $('#searchSkill').val()
    };
    window.location.href = '/api/engineers/export-csv?' + $.param(params, true);
}

function importEngineersCsv(input) {
    if (!input.files || input.files.length === 0) return;
    const formData = new FormData();
    formData.append('file', input.files[0]);

    $.ajax({
        url: '/api/engineers/import-csv',
        method: 'POST',
        data: formData,
        processData: false,
        contentType: false,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderCsvResult(res.data);
            } else {
                Toast.error(res.message || SES.i18n.t('error.importFailed'));
            }
        },
        error: function(xhr) {
            const msg = (xhr.responseJSON && xhr.responseJSON.message) || 'インポートに失敗しました';
            Toast.error(msg || SES.i18n.t('error.importFailed'));
        },
        complete: function() { input.value = ''; }
    });
}

function renderCsvResult(result) {
    $('#csvSuccessCount').text(result.successCount || 0);
    const errors = result.errors || [];
    if (errors.length === 0) {
        $('#csvErrorArea').html('<div class="text-muted small">' + SES.i18n.t('engineer.csv.noError') + '</div>');
    } else {
        let $container = $('<div></div>');
        $container.append($('<div class="text-danger small mb-1"></div>').text(SES.i18n.t('engineer.csv.errorLines')));
        let $ul = $('<ul class="list-group list-group-flush"></ul>');
        errors.forEach(function(e) {
            let $li = $('<li class="list-group-item bg-transparent text-light small border-dark py-1"></li>');
            $li.text(`${e.line} ${SES.i18n.t('engineer.csv.line')}: ${e.message}`);
            $ul.append($li);
        });
        $container.append($ul);
        $('#csvErrorArea').empty().append($container);
    }
    const modal = new bootstrap.Modal(document.getElementById('csvResultModal'));
    modal.show();
}

function deleteEngineer(id) {
    Swal.fire({
        title: SES.i18n.t('common.deleteConfirmTitle'),
        text: SES.i18n.t('confirm.deleteEngineer'),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: SES.i18n.t('common.delete'),
        cancelButtonText: SES.i18n.t('common.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/engineers/' + id,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success(SES.i18n.t('success.delete'));
                        loadEngineers();
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

