(function () {
  "use strict";
  var slides = Array.prototype.slice.call(document.querySelectorAll("#deck-slides > li"));
  var counter = document.querySelector(".reversa-doc-deck-counter");
  var cur = 0;
  function show(i) {
    cur = Math.max(0, Math.min(slides.length - 1, i));
    slides.forEach(function (s, k) { s.classList.toggle("is-current", k === cur); });
    if (counter) counter.textContent = (cur + 1) + " / " + slides.length;
    try { history.replaceState(null, "", "#s" + (cur + 1)); } catch (e) { /* file:// без history */ }
  }
  function fs() {
    var el = document.querySelector(".reversa-doc-deck");
    if (document.fullscreenElement) document.exitFullscreen(); else if (el.requestFullscreen) el.requestFullscreen();
  }
  document.addEventListener("keydown", function (e) {
    if (e.key === "ArrowRight") show(cur + 1); else if (e.key === "ArrowLeft") show(cur - 1);
    else if (e.key === "f" || e.key === "F") fs();
  });
  document.querySelectorAll(".reversa-doc-deck-nav button").forEach(function (b) {
    b.addEventListener("click", function () {
      var a = b.getAttribute("data-action");
      if (a === "prev") show(cur - 1); else if (a === "next") show(cur + 1); else if (a === "fullscreen") fs();
    });
  });
  var m = /^#s(\d+)$/.exec(location.hash);
  show(m ? parseInt(m[1], 10) - 1 : 0);
})();
