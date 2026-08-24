document.addEventListener('DOMContentLoaded', function() {
    const fromMonthInput = document.getElementById('fromMonth');
    const toMonthInput = document.getElementById('toMonth');
    const filterForm = document.getElementById('filterForm');
    const filterEndingSoonBtn = document.getElementById('filterEndingSoonBtn');
    let chartInstance = null;
    let currentEngineers = [];
    let lastFromStr = null;
    let lastToStr = null;

    // Initialize dates (default: current month to next 5 months)
    const today = new Date();
    const fromDate = new Date(today.getFullYear(), today.getMonth(), 1);
    const toDate = new Date(today.getFullYear(), today.getMonth() + 5, 1);
    
    fromMonthInput.value = SES.util.getLocalDateString(fromDate).slice(0, 7);
    toMonthInput.value = SES.util.getLocalDateString(toDate).slice(0, 7);

    document.addEventListener('ses:theme-changed', function() {
        if (chartInstance) {
            SES.theme.applyChartTheme(chartInstance);
        }
    });

    // Fetch initial data for selects
    fetch('/api/skill-tags').then(r => r.json()).then(res => {
        if (res.code === 200) {
            const select = document.getElementById('skillId');
            (res.data.records || res.data).forEach(s => {
                const opt = document.createElement('option');
                opt.value = s.id;
                opt.textContent = s.skillName || s.id;
                select.appendChild(opt);
            });
        }
    });
    fetch('/api/engineers/sales-user-options').then(r => r.json()).then(res => {
        if (res.code === 200) {
            const select = document.getElementById('salesUserId');
            res.data.forEach(u => {
                const opt = document.createElement('option');
                opt.value = u.id;
                opt.textContent = u.name || u.realName;
                select.appendChild(opt);
            });
        }
    });

    // Fetch initial data (defaulting to those ending soon to prevent heavy rendering)
    fetchData(true);

    filterForm.addEventListener('submit', function(e) {
        e.preventDefault();
        fetchData(false);
    });

    filterEndingSoonBtn.addEventListener('click', function() {
        fetchData(true);
    });

    function fetchData(onlyEndingSoon) {
        const from = fromMonthInput.value;
        const to = toMonthInput.value;
        const skillId = document.getElementById('skillId').value;
        const salesUserId = document.getElementById('salesUserId').value;

        const url = new URL('/api/analytics/availability-timeline', window.location.origin);
        url.searchParams.append('from', from);
        url.searchParams.append('to', to);
        if (skillId) url.searchParams.append('skillId', skillId);
        if (salesUserId) url.searchParams.append('salesUserId', salesUserId);

        fetch(url)
            .then(res => res.json())
            .then(response => {
                if (response.code === 200) {
                    let engineers = response.data.engineers;
                    if (onlyEndingSoon) {
                        engineers = engineers.filter(e => e.endingSoon);
                    }
                    currentEngineers = engineers;
                    lastFromStr = from;
                    lastToStr = to;
                    renderChart(engineers, from, to);
                } else {
                    console.error('Failed to load timeline data');
                }
            })
            .catch(err => console.error(err));
    }

    function renderChart(engineers, fromStr, toStr) {
        const canvas = document.getElementById('timelineChart');
        const ctx = canvas.getContext('2d');
        if (chartInstance) {
            chartInstance.destroy();
        }

        // Dynamically adjust height to prevent squished bars
        const minHeightPerRow = 40;
        canvas.style.height = Math.max(400, engineers.length * minHeightPerRow) + 'px';

        const labels = engineers.map(e => (e.endingSoon ? '⚠️ ' : '') + e.name);
        
        // Prepare datasets
        // Since an engineer can have multiple bars, and Chart.js floating bars can take an array of arrays for data,
        // we map each engineer's bars into the dataset. However, Chart.js 3+ floating bar dataset requires structure:
        // data: [ [start, end], [start, end] ] for one series, but multiple bars per row is tricky in standard bar charts without plugins.
        // Actually, with indexAxis: 'y', we can pass objects { x: [start, end], y: label }
        
        const dataContracted = [];
        const dataAvailable = [];

        engineers.forEach((eng, index) => {
            const label = labels[index];
            eng.bars.forEach(bar => {
                // If end is null, set it to end of toMonth
                let endStr = bar.end;
                if (!endStr) {
                    const toDate = new Date(toStr + '-01');
                    const lastDay = new Date(toDate.getFullYear(), toDate.getMonth() + 1, 0);
                    endStr = SES.util.getLocalDateString(lastDay);
                }

                const dataPoint = {
                    x: [new Date(bar.start).getTime(), new Date(endStr).getTime()],
                    y: label,
                    engId: eng.id,
                    contractId: bar.contractId,
                    type: bar.type,
                    isIndefinite: !bar.end // Mark if it has no end date
                };

                if (bar.type === 'contracted') {
                    dataContracted.push(dataPoint);
                } else {
                    dataAvailable.push(dataPoint);
                }
            });
        });

        const chartColors = (window.SES && SES.theme && SES.theme.chartColors)
            ? SES.theme.chartColors()
            : { textColor: '#475569', gridColor: 'rgba(0, 0, 0, 0.1)' };

        chartInstance = new Chart(ctx, {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [
                    {
                        label: SES.i18n.t('availability.chart.contracted'),
                        data: dataContracted,
                        backgroundColor: 'rgba(54, 162, 235, 0.7)',
                        borderColor: 'rgba(54, 162, 235, 1)',
                        borderWidth: 1,
                        borderSkipped: false
                    },
                    {
                        label: SES.i18n.t('availability.chart.available'),
                        data: dataAvailable,
                        backgroundColor: 'rgba(255, 159, 64, 0.5)',
                        borderColor: 'rgba(255, 159, 64, 1)',
                        borderWidth: 1,
                        borderSkipped: false
                    }
                ]
            },
            options: {
                indexAxis: 'y',
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    x: {
                        type: 'time',
                        time: {
                            unit: 'month',
                            displayFormats: {
                                month: 'yyyy-MM'
                            }
                        },
                        min: new Date(fromStr + '-01').getTime(),
                        max: new Date(new Date(toStr + '-01').getFullYear(), new Date(toStr + '-01').getMonth() + 1, 0).getTime(),
                        ticks: { color: chartColors.textColor },
                        grid: { color: chartColors.gridColor }
                    },
                    y: {
                        stacked: true,
                        ticks: { color: chartColors.textColor },
                        grid: { color: chartColors.gridColor }
                    }
                },
                plugins: {
                    tooltip: {
                        callbacks: {
                            label: function(context) {
                                const pt = context.raw;
                                const start = new Date(pt.x[0]).toLocaleDateString();
                                const end = pt.isIndefinite ? SES.i18n.t('availability.chart.ongoing') : new Date(pt.x[1]).toLocaleDateString();
                                return `${context.dataset.label}: ${start} 〜 ${end}`;
                            }
                        }
                    }
                },
                onClick: (event, elements, chart) => {
                    if (elements && elements.length > 0) {
                        const firstElement = elements[0];
                        const datasetIndex = firstElement.datasetIndex;
                        const index = firstElement.index;
                        const dataPoint = chart.data.datasets[datasetIndex].data[index];
                        if (dataPoint && dataPoint.engId) {
                            window.location.href = '/engineer/detail?id=' + dataPoint.engId;
                        }
                    } else {
                        // Clicked on label?
                        const yValue = chart.scales.y.getValueForPixel(event.y);
                        if (yValue !== undefined && yValue >= 0 && yValue < labels.length) {
                            const eng = engineers[yValue];
                            if (eng) {
                                window.location.href = '/engineer/detail?id=' + eng.id;
                            }
                        }
                    }
                }
            }
        });
        if (SES.theme && typeof SES.theme.applyChartTheme === 'function') {
            SES.theme.applyChartTheme(chartInstance);
        }
    }
});
