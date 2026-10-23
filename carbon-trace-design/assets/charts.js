/**
 * 报告图表与图示初始化
 * - ECharts：接口分布、链上证书状态、吞吐对比
 * - Mermaid：架构图、时序图、状态机、ER 图
 * 所有颜色取自页面 CSS 变量，保持与主题一致。
 */
(function () {
  var style = getComputedStyle(document.documentElement);
  var accent = style.getPropertyValue('--accent').trim();
  var accent2 = style.getPropertyValue('--accent2').trim();
  var ink = style.getPropertyValue('--ink').trim();
  var muted = style.getPropertyValue('--muted').trim();
  var rule = style.getPropertyValue('--rule').trim();
  var bg2 = style.getPropertyValue('--bg2').trim();
  var soft = style.getPropertyValue('--accent-soft').trim();

  // ---------- 图 5：76 个接口按模块分布 ----------
  var epEl = document.getElementById('chart-endpoints');
  if (epEl) {
    var epChart = echarts.init(epEl, null, { renderer: 'svg' });
    var epData = [
      ['统计', 15], ['用户管理', 8], ['企业管理', 6], ['合规治理', 6],
      ['认证记录', 5], ['公共端', 5], ['审核机构', 4], ['排放因子', 4],
      ['排放记录', 4], ['报告记录', 4], ['监管机构', 4], ['告警', 3],
      ['鉴权', 3], ['链浏览', 3], ['排放源', 1], ['文件', 1]
    ];
    epChart.setOption({
      animation: false,
      grid: { left: 92, right: 46, top: 12, bottom: 12 },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, appendToBody: true },
      xAxis: {
        type: 'value',
        axisLabel: { color: muted, fontSize: 11 },
        splitLine: { lineStyle: { color: rule } },
        axisLine: { lineStyle: { color: rule } }
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: epData.map(function (d) { return d[0]; }),
        axisLabel: { color: ink, fontSize: 11 },
        axisTick: { show: false },
        axisLine: { lineStyle: { color: rule } }
      },
      series: [{
        type: 'bar',
        data: epData.map(function (d) { return d[1]; }),
        barWidth: '58%',
        itemStyle: { color: accent, borderRadius: [0, 3, 3, 0] },
        label: { show: true, position: 'right', color: muted, fontSize: 11 }
      }]
    });
    window.addEventListener('resize', function () { epChart.resize(); });
  }

  // ---------- 图 6：链上证书状态 ----------
  var certEl = document.getElementById('chart-cert');
  if (certEl) {
    var certChart = echarts.init(certEl, null, { renderer: 'svg' });
    certChart.setOption({
      animation: false,
      color: [accent, accent2, muted],
      tooltip: { trigger: 'item', appendToBody: true, formatter: '{b}：{c} 张（{d}%）' },
      title: {
        text: '52',
        subtext: '链上存证',
        left: 'center',
        top: '38%',
        textStyle: { color: ink, fontSize: 30, fontWeight: 700 },
        subtextStyle: { color: muted, fontSize: 12 }
      },
      legend: {
        bottom: 6,
        left: 'center',
        icon: 'roundRect',
        itemWidth: 10,
        itemHeight: 10,
        textStyle: { color: muted, fontSize: 12 }
      },
      series: [{
        type: 'pie',
        radius: ['52%', '74%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        label: { show: false },
        labelLine: { show: false },
        itemStyle: { borderColor: bg2, borderWidth: 2 },
        data: [
          { name: '有效', value: 50 },
          { name: '已撤销', value: 2 },
          { name: '已作废', value: 0 }
        ]
      }]
    });
    window.addEventListener('resize', function () { certChart.resize(); });
  }

  // ---------- 图 7：典型接口吞吐（对数横轴） ----------
  var rpsEl = document.getElementById('chart-rps');
  if (rpsEl) {
    var rpsChart = echarts.init(rpsEl, null, { renderer: 'svg' });
    var rpsData = [
      ['公共企业列表 · 20 并发', 1380],
      ['公共企业列表 · 100 并发', 1324],
      ['监管统计接口', 1234],
      ['认证记录列表 · 50 并发', 1094],
      ['登录接口', 158],
      ['防伪核验 · 回链', 17.3],
      ['链上统计 · 回链', 14.5]
    ];
    rpsChart.setOption({
      animation: false,
      grid: { left: 168, right: 70, top: 14, bottom: 14 },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        appendToBody: true,
        formatter: function (p) {
          var v = p[0].value;
          return p[0].name + '<br>' + (v >= 1 ? v.toFixed(0) : v.toFixed(1)) + ' RPS';
        }
      },
      xAxis: {
        type: 'log',
        min: 1,
        max: 5000,
        axisLabel: { color: muted, fontSize: 10 },
        splitLine: { lineStyle: { color: rule } },
        axisLine: { lineStyle: { color: rule } }
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: rpsData.map(function (d) { return d[0]; }),
        axisLabel: { color: ink, fontSize: 11 },
        axisTick: { show: false },
        axisLine: { lineStyle: { color: rule } }
      },
      series: [{
        type: 'bar',
        barWidth: '55%',
        data: rpsData.map(function (d, i) {
          return {
            value: d[1],
            itemStyle: { color: i < 4 ? accent : accent2, borderRadius: [0, 3, 3, 0] }
          };
        }),
        label: {
          show: true,
          position: 'right',
          color: muted,
          fontSize: 11,
          formatter: function (p) {
            var v = p.value;
            return (v >= 1 ? v.toFixed(0) : v.toFixed(1)) + ' RPS';
          }
        }
      }]
    });
    window.addEventListener('resize', function () { rpsChart.resize(); });
  }

  // ---------- Mermaid 图示 ----------
  if (window.mermaid) {
    mermaid.initialize({
      startOnLoad: false,
      theme: 'neutral',
      securityLevel: 'loose',
      fontFamily: "'Instrument Sans', 'Microsoft YaHei', 'PingFang SC', sans-serif",
      themeVariables: {
        primaryColor: soft,
        primaryBorderColor: accent,
        primaryTextColor: ink,
        lineColor: muted,
        secondaryColor: bg2,
        tertiaryColor: bg2,
        fontSize: '13px'
      },
      flowchart: { curve: 'basis', useMaxWidth: true },
      sequence: { useMaxWidth: true, mirrorActors: false },
      er: { useMaxWidth: true }
    });
    mermaid.run({ querySelector: '.mermaid' });
  }
})();
