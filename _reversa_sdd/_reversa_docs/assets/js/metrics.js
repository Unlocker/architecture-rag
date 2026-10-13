(function () {
  "use strict";
  var M = (window.RV_DATA || {}).metrics;
  if (!M) return;
  var ok = !!window.Highcharts;
  var PAL = ["#3d4a5c", "#4f6b5d", "#a06b4a", "#7c8a99", "#bdb4a4", "#1e2937"];
  var GROUP = { contracts: "#3d4a5c", adapters: "#4f6b5d", ingestion: "#a06b4a", access: "#7c8a99", platform: "#bdb4a4" };
  function missing(id) {
    var el = document.getElementById(id);
    if (el) el.innerHTML = "<p class='md-note'>Библиотека Highcharts недоступна: файлы assets/vendor/highcharts*.js не загружены.</p>";
  }
  if (!ok) { ["chart-treemap", "chart-complexity", "chart-histogram", "chart-sankey", "chart-tests", "chart-conf", "chart-lang"].forEach(missing); return; }
  Highcharts.setOptions({ credits: { enabled: false }, exporting: { enabled: false, fallbackToExportServer: false },
    colors: PAL, chart: { backgroundColor: "transparent", style: { fontFamily: "inherit" } }, title: { text: null } });
  function short(p) { return p.split("/").pop(); }
  if (Highcharts.seriesTypes.treemap) {
    Highcharts.chart("chart-treemap", { series: [{ type: "treemap", layoutAlgorithm: "squarified",
      data: M.treemap_loc_by_folder.map(function (t) { return { name: short(t.folder), value: t.loc, color: GROUP[t.group] }; }),
      dataLabels: { style: { textOutline: "none" } } }],
      tooltip: { pointFormat: "{point.name}: <b>{point.value}</b> LOC (с тестами)" } });
  } else missing("chart-treemap");
  Highcharts.chart("chart-complexity", { chart: { type: "bar", height: 520 },
    xAxis: { categories: M.top_complexity.map(function (t) { return t.name; }), title: { text: null } },
    yAxis: { title: { text: "Оценка числа ветвлений (грубая)" } }, legend: { enabled: false },
    series: [{ name: "Ветвлений", data: M.top_complexity.map(function (t) { return t.complexity; }) }] });
  var b = M.loc_histogram.bins, cats = M.loc_histogram.counts.map(function (c, i) { return b[i] + (i === b.length - 2 ? "+" : "-" + (b[i + 1] - 1)); });
  Highcharts.chart("chart-histogram", { chart: { type: "column" }, xAxis: { categories: cats, title: { text: "LOC в файле" } },
    yAxis: { title: { text: "Файлов" } }, legend: { enabled: false }, series: [{ name: "Файлов", data: M.loc_histogram.counts }] });
  if (Highcharts.seriesTypes.sankey) {
    Highcharts.chart("chart-sankey", { chart: { height: 620 },
      series: [{ type: "sankey", keys: ["from", "to", "weight"],
        data: M.dependency_sankey.links.map(function (l) { return [short(l.source), short(l.target), l.value]; }),
        nodes: M.dependency_sankey.nodes.map(function (n) { return { id: short(n.id), color: GROUP[n.id.split("/")[0]] }; }) }],
      tooltip: { pointFormat: "{point.fromNode.name} зависит от {point.toNode.name}: <b>{point.weight}</b>" } });
  } else missing("chart-sankey");
  var mv = M.main_vs_test;
  Highcharts.chart("chart-tests", { chart: { type: "column", height: 420 }, xAxis: { categories: mv.map(function (m) { return short(m.module); }), labels: { rotation: -50 } },
    yAxis: { title: { text: "LOC" } }, plotOptions: { column: { stacked: true } },
    series: [{ name: "Код", data: mv.map(function (m) { return m.main; }), stacking: "normal" },
             { name: "Тесты", data: mv.map(function (m) { return m.test; }), stacking: "normal" }] });
  var cf = M.confidence_by_unit.slice().sort(function (a, b) { return a.pct - b.pct; });
  Highcharts.chart("chart-conf", { chart: { type: "bar", height: 560 }, xAxis: { categories: cf.map(function (c) { return c.unit; }) },
    yAxis: { title: { text: "Вхождений меток" } },
    series: [{ name: "Пробел (красная метка)", data: cf.map(function (c) { return c.r; }), color: "#c0392b", stacking: "normal" },
             { name: "Выведено (жёлтая метка)", data: cf.map(function (c) { return c.y; }), color: "#d3a032", stacking: "normal" },
             { name: "Подтверждено кодом (зелёная метка)", data: cf.map(function (c) { return c.g; }), color: "#4f6b5d", stacking: "normal" }],
    tooltip: { shared: true } });
  Highcharts.chart("chart-lang", { chart: { type: "pie", height: 360 }, series: [{ name: "LOC", data: M.language_distribution.map(function (l) { return { name: l.language + " (" + l.modules + " файлов)", y: l.loc }; }) }],
    plotOptions: { pie: { dataLabels: { format: "{point.name}: {point.percentage:.0f}%" } } } });
})();
