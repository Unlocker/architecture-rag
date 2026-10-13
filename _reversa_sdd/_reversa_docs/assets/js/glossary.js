(function () {
  "use strict";
  var G = ((window.RV_DATA || {}).glossary || {}).concepts || [];
  var grid = document.getElementById("glossary-grid");
  var input = document.getElementById("glossary-search");
  var count = document.getElementById("glossary-count");
  if (!grid) return;
  function slug(s) { return s.toLowerCase().replace(/[^a-z0-9а-яё]+/gi, "-").replace(/^-|-$/g, ""); }
  var cards = G.map(function (c) {
    var a = document.createElement("article"); a.className = "reversa-card glossary-card"; a.id = "concept-" + slug(c.term);
    var h = document.createElement("h3"); h.textContent = c.term; a.appendChild(h);
    var k = document.createElement("span"); k.className = "reversa-pill is-info"; k.textContent = c.kind; a.appendChild(k);
    var p = document.createElement("p"); p.textContent = c.definition; a.appendChild(p);
    var s = document.createElement("small"); s.textContent = "Источник: " + c.source; a.appendChild(s);
    grid.appendChild(a);
    return { el: a, text: (c.term + " " + c.definition).toLowerCase() };
  });
  function filter() {
    var q = (input.value || "").toLowerCase().trim(), n = 0;
    cards.forEach(function (c) { var show = !q || c.text.indexOf(q) >= 0; c.el.hidden = !show; if (show) n++; });
    if (count) count.textContent = "Показано: " + n + " из " + cards.length;
  }
  input.addEventListener("input", filter); filter();
  if (location.hash) { var t = document.getElementById(location.hash.slice(1)); if (t) t.scrollIntoView(); }
})();
