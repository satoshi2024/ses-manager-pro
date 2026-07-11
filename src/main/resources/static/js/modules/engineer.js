// 駅名 -> 紐づく pref（「都道府県 路線」形式）の一覧。同名駅が複数路線に存在するため配列で保持する。
window.stationIndex = {};

$(document).ready(function() {
    // Load engineers on page load
    loadEngineers();

    // Load station names for autocomplete
    loadAllStations();

    // 最寄り駅を入力/選択したら、その駅の路線候補を絞り込む
    $('#eng-nearestStation').on('input change', function() {
        populateRailwayLines($(this).val(), null);
    });
    // 路線（鉄道会社）を選択したら、都道府県と鉄道会社を自動設定する
    $('#eng-railwayLine').on('change', applyRailwaySelection);
});

function loadAllStations() {
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
            select.append(`<option value="${selectedPref}" selected>${splitPref(selectedPref).line || selectedPref}</option>`);
            select.prop('disabled', false);
        } else {
            select.append('<option value="">路線情報がありません</option>');
            select.prop('disabled', true);
        }
        applyRailwaySelection();
        return;
    }

    select.append('<option value="">路線を選択...</option>');
    entries.forEach(pref => {
        const line = splitPref(pref).line;
        select.append(`<option value="${pref}">${line}</option>`);
    });

    if (selectedPref) {
        select.val(selectedPref);
        // 候補一覧に無い保存値だった場合は選択肢として補って選択状態にする
        if (!select.val()) {
            select.append(`<option value="${selectedPref}" selected>${splitPref(selectedPref).line || selectedPref}</option>`);
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
    const data = {
        current: page,
        size: 10,
        fullName: $('#searchName').val(),
        status: $('#searchStatus').val(),
        employmentType: $('#searchEmpType').val()
    };

    $.ajax({
        url: '/api/engineers',
        method: 'GET',
        data: data,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderEngineers(res.data.records || res.data); // Handle pagination object if present
                if (res.data.total !== undefined) {
                    renderPagination(res.data, 'loadEngineers');
                }
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

function renderPagination(pageData, loadFuncName) {
    const paginationContainer = $('.card-footer');
    if (pageData.total === 0) {
        paginationContainer.html('<div class="text-muted small ps-2">全 0 件</div>');
        return;
    }
    
    const start = (pageData.current - 1) * pageData.size + 1;
    const end = Math.min(pageData.current * pageData.size, pageData.total);
    
    let html = `
        <div class="text-muted small ps-2">
            全 ${pageData.total} 件中 ${start}-${end} 件を表示
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
        tbody.append('<tr><td colspan="6" class="text-center text-muted py-4">データがありません</td></tr>');
        return;
    }
    
    records.forEach(eng => {
        // Build avatar
        const initial = eng.fullName ? eng.fullName.charAt(0) : '?';
        const kana = eng.fullNameKana || '';
        
        // Status Badge
        let statusBadge = '';
        if (eng.status === '稼動中') statusBadge = '<span class="status-badge status-success">稼動中</span>';
        else if (eng.status === '提案中') statusBadge = '<span class="status-badge status-warning">提案中</span>';
        else if (eng.status === '退場予定') statusBadge = '<span class="status-badge status-danger">退場予定</span>';
        else statusBadge = '<span class="status-badge status-secondary">Bench</span>';

        const priceStr = eng.expectedUnitPrice ? eng.expectedUnitPrice.toLocaleString() + '円' : '-';
        const expStr = eng.experienceYears ? eng.experienceYears + '年' : '-';

        const tr = `
            <tr>
                <td class="ps-4">
                    <div class="d-flex align-items-center py-1">
                        <div class="avatar bg-primary text-white rounded-circle me-3 d-flex align-items-center justify-content-center" style="width: 36px; height: 36px;">${initial}</div>
                        <div>
                            <div class="fw-bold">${eng.fullName || '-'}</div>
                            <div class="text-muted small">${kana}</div>
                        </div>
                    </div>
                </td>
                <td>${statusBadge}</td>
                <td>${eng.employmentType || '-'}</td>
                <td>${expStr}</td>
                <td>${priceStr}</td>
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
                Toast.error('データの取得に失敗しました');
            }
        }
    });
}

function saveEngineer() {
    const fullName = $('#eng-fullName').val();
    if (!fullName) {
        Toast.error('氏名は必須です');
        return;
    }
    
    const id = $('#eng-id').val();
    const nearestStation = $('#eng-nearestStation').val() || '';

    const data = {
        fullName: fullName,
        fullNameKana: $('#eng-fullNameKana').val(),
        employmentType: $('#eng-employmentType').val(),
        status: $('#eng-status').val(),
        experienceYears: $('#eng-experienceYears').val() ? parseInt($('#eng-experienceYears').val()) : null,
        expectedUnitPrice: $('#eng-expectedUnitPrice').val() ? parseInt($('#eng-expectedUnitPrice').val()) : null,
        nearestStation: nearestStation,
        prefecture: $('#eng-prefecture').val() || null,
        railwayCompany: $('#eng-railwayCompany').val() || null
    };

    if (id) {
        data.id = parseInt(id);
    }

    $.ajax({
        url: '/api/engineers',
        method: id ? 'PUT' : 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(id ? '要員を更新しました' : '要員を登録しました');
                // getInstance は未生成時に null を返し .hide() で例外→モーダルが閉じない不具合になるため getOrCreateInstance を使う
                bootstrap.Modal.getOrCreateInstance(document.getElementById('engineerModal')).hide();
                $('#engineer-form')[0].reset();
                $('#eng-id').val('');
                loadEngineers(1);
            } else {
                Toast.error(res.message || '保存に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

function deleteEngineer(id) {
    Swal.fire({
        title: '削除確認',
        text: 'この要員データを削除しますか？この操作は元に戻せません。',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '削除する',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/engineers/' + id,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success('削除しました');
                        loadEngineers();
                    } else {
                        Toast.error(res.message || '削除に失敗しました');
                    }
                },
                error: function(err) {
                    console.error(err);
                    Toast.error('通信エラーが発生しました');
                }
            });
        }
    });
}

