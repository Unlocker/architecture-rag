(function () {
  "use strict";
  var D = window.RV_DATA || {};
  var deps = D.deps || { nodes: [], edges: [], cycles: [] };
  var units = (D.modules && D.modules.units) || [];
  var totals = {};
  ((D.metrics && D.metrics.treemap_loc_by_folder) || []).forEach(function (t) { totals[t.folder] = t; });
  var svgEl = document.getElementById("d3-canvas");
  var panel = document.getElementById("graph-info");
  if (!svgEl) return;
  if (!window.d3) {
    svgEl.outerHTML = "<p class='md-note'>Библиотека d3 недоступна: файл assets/vendor/d3.v7.min.js не загружен.</p>";
    return;
  }
  var W = svgEl.parentNode.clientWidth || 900, H = 620;
  var svg = d3.select(svgEl).attr("width", W).attr("height", H).attr("viewBox", [0, 0, W, H]);
  var COLORS = { contracts: "#3d4a5c", adapters: "#4f6b5d", ingestion: "#a06b4a", access: "#7c8a99", platform: "#bdb4a4" };
  var inCycle = {};
  (deps.cycles || []).forEach(function (c) { c.forEach(function (n) { inCycle[n] = true; }); });
  svg.append("defs").append("marker").attr("id", "arrow").attr("viewBox", "0 -4 8 8").attr("refX", 16).attr("refY", 0)
    .attr("markerWidth", 6).attr("markerHeight", 6).attr("orient", "auto").append("path").attr("d", "M0,-4L8,0L0,4").attr("fill", "#7c8a99");
  var zoomG = svg.append("g");
  svg.call(d3.zoom().scaleExtent([0.3, 4]).on("zoom", function (e) { zoomG.attr("transform", e.transform); }));
  var state = { group: "all", charge: -700, dist: 150, test: true };
  var nodes = deps.nodes.map(function (n) {
    var t = totals[n.id] || { loc: 0 };
    var u = units.filter(function (x) { return x.path === n.id; })[0] || {};
    return { id: n.id, label: n.label, group: n.group, loc: t.loc, files: t.modules || 0, purpose: u.purpose || "" };
  });
  var links = deps.edges.map(function (e) { return { source: e.source, target: e.target, value: e.value, scope: e.scope }; });
  var linkSel, nodeSel, labelSel, sim;
  function draw() {
    zoomG.selectAll("*").remove();
    var vis = nodes.filter(function (n) { return state.group === "all" || n.group === state.group; });
    var ids = {}; vis.forEach(function (n) { ids[n.id] = true; });
    var vl = links.filter(function (l) {
      var s = l.source.id || l.source, t = l.target.id || l.target;
      return ids[s] && ids[t] && (state.test || l.scope !== "test");
    });
    linkSel = zoomG.append("g").selectAll("line").data(vl).join("line")
      .attr("stroke", "#7c8a99").attr("stroke-opacity", 0.55).attr("stroke-width", function (l) { return 1 + Math.min(4, Math.log2(l.value + 1) / 1.6); })
      .attr("stroke-dasharray", function (l) { return l.scope === "test" ? "4 3" : null; }).attr("marker-end", "url(#arrow)");
    nodeSel = zoomG.append("g").selectAll("circle").data(vis).join("circle")
      .attr("r", function (n) { return 6 + Math.sqrt(n.loc) * 0.32; })
      .attr("fill", function (n) { return inCycle[n.id] ? "#c0392b" : COLORS[n.group]; })
      .attr("stroke", "#1e2937").attr("stroke-width", 1).style("cursor", "pointer")
      .on("click", function (ev, n) { select(n); });
    nodeSel.append("title").text(function (n) { return n.id + ", LOC: " + n.loc; });
    labelSel = zoomG.append("g").selectAll("text").data(vis).join("text").text(function (n) { return n.label; })
      .attr("font-size", 11).attr("fill", "#1e2937").attr("dx", 12).attr("dy", 4).style("pointer-events", "none");
    sim = d3.forceSimulation(vis).force("link", d3.forceLink(vl).id(function (n) { return n.id; }).distance(state.dist))
      .force("charge", d3.forceManyBody().strength(state.charge)).force("center", d3.forceCenter(W / 2, H / 2))
      .force("collide", d3.forceCollide(20));
    nodeSel.call(d3.drag().on("start", function (ev, n) { if (!ev.active) sim.alphaTarget(0.3).restart(); n.fx = n.x; n.fy = n.y; })
      .on("drag", function (ev, n) { n.fx = ev.x; n.fy = ev.y; })
      .on("end", function (ev, n) { if (!ev.active) sim.alphaTarget(0); n.fx = null; n.fy = null; }));
    sim.on("tick", function () {
      linkSel.attr("x1", function (l) { return l.source.x; }).attr("y1", function (l) { return l.source.y; })
        .attr("x2", function (l) { return l.target.x; }).attr("y2", function (l) { return l.target.y; });
      nodeSel.attr("cx", function (n) { return n.x; }).attr("cy", function (n) { return n.y; });
      labelSel.attr("x", function (n) { return n.x; }).attr("y", function (n) { return n.y; });
    });
  }
  function select(n) {
    var out = deps.edges.filter(function (e) { return e.source === n.id; }).map(function (e) { return e.target.split("/").pop(); });
    var inn = deps.edges.filter(function (e) { return e.target === n.id; }).map(function (e) { return e.source.split("/").pop(); });
    nodeSel.attr("stroke-width", function (m) { return m.id === n.id ? 3 : 1; });
    if (panel) {
      panel.innerHTML = "";
      var h = document.createElement("h3"); h.textContent = n.id; panel.appendChild(h);
      function line(t) { var p = document.createElement("p"); p.textContent = t; panel.appendChild(p); }
      line(n.purpose);
      line("Файлов кода: " + n.files + ", LOC (с тестами): " + n.loc);
      line("Зависит от: " + (out.join(", ") || "ничего проектного"));
      line("Используется в: " + (inn.join(", ") || "никем"));
      var a = document.createElement("a"); a.href = "features/" + n.label + ".html"; a.textContent = "Страница единицы"; panel.appendChild(a);
    }
  }
  draw();
  document.addEventListener("reversa:param-change", function (e) {
    var p = e.detail.param, v = e.detail.value;
    if (p === "group") state.group = v; else if (p === "charge") state.charge = v; else if (p === "dist") state.dist = v; else if (p === "test") state.test = v;
    if (sim) sim.stop();
    draw();
  });
})();
