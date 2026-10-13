(function () {
  "use strict";
  var D = window.RV_DATA || {};
  var files = (D.modules && D.modules.modules) || [];
  var units = (D.modules && D.modules.units) || [];
  var host = document.getElementById("three-canvas");
  var loader = document.querySelector(".reversa-doc-3d-loader");
  var info = document.getElementById("city-info");
  if (!host) return;
  if (!window.THREE || !THREE.OrbitControls) {
    if (loader) loader.textContent = "Библиотека three.js недоступна: файл assets/vendor/three.min.js не загружен.";
    return;
  }
  var W = host.clientWidth || 800, H = 560;
  var renderer;
  try { renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true }); }
  catch (e) { if (loader) loader.textContent = "WebGL недоступен в этом браузере: 3D-вид не может быть показан. Смотрите страницу «Модули»."; return; }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(W, H);
  host.appendChild(renderer.domElement);
  var scene = new THREE.Scene();
  scene.background = new THREE.Color(0xf5f3ee);
  var camera = new THREE.PerspectiveCamera(50, W / H, 0.1, 2000);
  var controls = new THREE.OrbitControls(camera, renderer.domElement);
  var amb = new THREE.AmbientLight(0xffffff, 0.7);
  var dir = new THREE.DirectionalLight(0xffffff, 0.8);
  dir.position.set(40, 80, 30);
  scene.add(amb); scene.add(dir);

  var GROUP_COLOR = { contracts: 0x3d4a5c, adapters: 0x4f6b5d, ingestion: 0xa06b4a, access: 0x7c8a99, platform: 0xbdb4a4 };
  var byUnit = {};
  files.forEach(function (f) { (byUnit[f.module] = byUnit[f.module] || []).push(f); });
  var maxBr = Math.max.apply(null, files.map(function (f) { return f.branches; }).concat([1]));
  var state = { vscale: 1, light: 0.8, palette: "complexity" };
  var buildings = [], pitch = 1.4, shelfW = 64, x = 0, z = 0, rowD = 0, gap = 3;
  var districts = [];
  units.forEach(function (u) {
    var list = (byUnit[u.path] || []).slice().sort(function (a, b) { return b.loc - a.loc; });
    if (!list.length) return;
    var cols = Math.ceil(Math.sqrt(list.length)), rows = Math.ceil(list.length / cols);
    var w = cols * pitch, d = rows * pitch;
    if (x + w > shelfW && x > 0) { x = 0; z += rowD + gap; rowD = 0; }
    districts.push({ u: u, list: list, x: x, z: z, w: w, d: d, cols: cols });
    x += w + gap; rowD = Math.max(rowD, d);
  });
  var totalD = z + rowD;
  var group = new THREE.Group(); scene.add(group);
  var geo = new THREE.BoxGeometry(1, 1, 1);
  function lerp(a, b, t) { return a + (b - a) * t; }
  function colorOf(f, u) {
    if (state.palette === "group") return new THREE.Color(GROUP_COLOR[u.group] || 0x888888);
    if (state.palette === "tests") return new THREE.Color(f.test ? 0x4f6b5d : 0xa06b4a);
    var t = Math.min(1, f.branches / (maxBr * 0.5));
    var c1 = new THREE.Color(0x9fb0c0), c2 = new THREE.Color(0xa0432a);
    return c1.lerp(c2, t);
  }
  districts.forEach(function (ds) {
    var floor = new THREE.Mesh(new THREE.BoxGeometry(ds.w + 0.4, 0.1, ds.d + 0.4),
      new THREE.MeshLambertMaterial({ color: GROUP_COLOR[ds.u.group] || 0x999999, transparent: true, opacity: 0.35 }));
    floor.position.set(ds.x + ds.w / 2 - 0.35, 0, ds.z + ds.d / 2 - 0.35);
    group.add(floor);
    var cv = document.createElement("canvas"); cv.width = 256; cv.height = 48;
    var cx = cv.getContext("2d"); cx.font = "bold 26px sans-serif"; cx.fillStyle = "#1e2937"; cx.textAlign = "center";
    cx.fillText(ds.u.id, 128, 32);
    var spr = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(cv), transparent: true, depthTest: false }));
    spr.scale.set(8, 1.5, 1); spr.position.set(ds.x + ds.w / 2, 0.4, ds.z + ds.d + 1.2);
    group.add(spr);
    ds.list.forEach(function (f, i) {
      var mesh = new THREE.Mesh(geo, new THREE.MeshLambertMaterial({ color: colorOf(f, ds.u) }));
      var cxx = ds.x + (i % ds.cols) * pitch, czz = ds.z + Math.floor(i / ds.cols) * pitch;
      mesh.position.x = cxx; mesh.position.z = czz;
      mesh.userData = { f: f, u: ds.u };
      group.add(mesh); buildings.push(mesh);
    });
  });
  function applyHeights() {
    buildings.forEach(function (m) {
      var h = Math.max(0.15, Math.sqrt(m.userData.f.loc) * 0.2 * state.vscale);
      m.scale.set(0.9, h, 0.9); m.position.y = h / 2;
      m.material.color.copy(colorOf(m.userData.f, m.userData.u));
    });
    amb.intensity = 0.4 + state.light * 0.4; dir.intensity = state.light;
  }
  applyHeights();
  group.position.set(-shelfW / 2, 0, -totalD / 2);
  camera.position.set(0, 38, totalD * 0.45 + 22);
  controls.target.set(0, 0, 0); controls.update();
  if (loader) loader.style.display = "none";

  var ray = new THREE.Raycaster(), mouse = new THREE.Vector2();
  renderer.domElement.addEventListener("mousemove", function (ev) {
    var r = renderer.domElement.getBoundingClientRect();
    mouse.x = ((ev.clientX - r.left) / r.width) * 2 - 1; mouse.y = -((ev.clientY - r.top) / r.height) * 2 + 1;
    ray.setFromCamera(mouse, camera);
    var hit = ray.intersectObjects(buildings)[0];
    if (hit && info) {
      var f = hit.object.userData.f;
      info.textContent = f.id + " | LOC: " + f.loc + " | ветвлений (оценка): " + f.branches + (f.test ? " | тест" : "");
    }
  });
  document.addEventListener("reversa:param-change", function (e) {
    var p = e.detail.param, v = e.detail.value;
    if (p === "vscale") state.vscale = v; else if (p === "light") state.light = v; else if (p === "palette") state.palette = v;
    applyHeights();
  });
  window.addEventListener("resize", function () {
    var w = host.clientWidth || W; renderer.setSize(w, H); camera.aspect = w / H; camera.updateProjectionMatrix();
  });
  (function loop() { requestAnimationFrame(loop); controls.update(); renderer.render(scene, camera); })();
})();
