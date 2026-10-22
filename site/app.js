// titan-ssh product page: language switch, the micro-cut demo and the
// download links, which come from the latest stable GitHub Release.
(function () {
  "use strict";

  var REPO = "danielperezmartinez/titan-ssh";
  var RELEASES = "https://github.com/" + REPO + "/releases/latest";

  var STRINGS = {
    en: {
      "skip": "Skip to content",
      "nav.why": "Why", "nav.features": "Features", "nav.download": "Download", "nav.docs": "Docs",
      "hero.title": "SSH sessions that survive the network.",
      "hero.lede": "A free, open-source SSH client for Linux, Windows and Android. When the Wi-Fi drops or you change networks, your session comes back where it was, with your programs still running.",
      "hero.cta": "Download titan-ssh",
      "hero.ctaFor": "Download for {os}",
      "hero.other": "Other platforms",
      "hero.meta": "Linux, Windows and Android. GPL-3.0-or-later.",
      "hero.metaVersion": "Version {v}, {file} package.",
      "hero.metaElse": "titan-ssh runs on Linux, Windows and Android.",
      "demo.connected": "connected",
      "demo.reconnecting": "reconnecting, attempt {n}",
      "demo.back": "reconnected, {n} lines you missed",
      "demo.caption": "The connection dropped for a few seconds. The tests kept running on the server, and the tab shows what you missed.",
      "demo.replay": "Drop the connection again",
      "why.title": "Why titan-ssh exists",
      "why.a.title": "A network hiccup shouldn't cost you your work.",
      "why.a.body": "Most clients close the session at the first micro-cut. You reconnect, go back to the project folder and restart whatever was running. titan-ssh reconnects on its own, and with its agent on the server your shell and its processes never stop.",
      "why.b.title": "Startup scripts shouldn't be a paid feature.",
      "why.b.body": "Open a session straight in a project folder, start your tools or a service, run any chain of commands. Each saved session has its own steps, and they run again after a reconnect if you want them to.",
      "layers.title": "Three layers, used automatically",
      "layers.1.title": "Reconnect",
      "layers.1.body": "The tab keeps its screen and scrollback, retries until the network is back and runs your startup steps again.",
      "layers.2.title": "tmux or screen",
      "layers.2.body": "If the server has tmux or screen, the shell lives there and outlasts the connection.",
      "layers.3.title": "titan-agent",
      "layers.3.body": "A small agent installed in your user account on the server keeps a real terminal alive and replays what you missed. No root needed. Runs on Linux, macOS, FreeBSD and Windows servers.",
      "features.title": "What else it does",
      "f.tabs.t": "Tabs", "f.tabs.d": "Several sessions at once, including parallel copies of the same saved session. Rename them and drag them into order.",
      "f.hosts.t": "Hosts and sessions", "f.hosts.d": "Save hosts and sessions, sort them into folders, and reuse the same scripts and tunnels across sessions.",
      "f.tunnels.t": "Tunnels", "f.tunnels.d": "Local, remote and SOCKS forwards that open with the session and come back after a reconnect.",
      "f.files.t": "Files", "f.files.d": "Browse the server over SFTP from an open session, and download or upload files.",
      "f.secrets.t": "Keys and passwords", "f.secrets.d": "Kept in your system's secure store: Android Keystore, Windows Credential Manager or Secret Service on Linux. Never in plain text. On Android, keys can live in the hardware keystore and never leave it.",
      "f.touch.t": "On a phone", "f.touch.d": "A key bar you arrange by dragging, and select, copy and paste in the terminal.",
      "f.updates.t": "Updates", "f.updates.d": "Updates itself on every platform, and only installs a package listed in a manifest signed with the project's key.",
      "f.privacy.t": "Privacy", "f.privacy.d": "No account and no tracking. The only request it makes on its own is the update check to GitHub.",
      "dl.title": "Download",
      "dl.loading": "Looking up the latest version…",
      "dl.release": "Version {v}, released {date}.",
      "dl.failed": "Couldn't reach GitHub to look up the latest version. The links open the release page instead.",
      "dl.yours": "Your system",
      "dl.deb": "Debian, Ubuntu and derivatives", "dl.rpm": "Fedora and derivatives", "dl.tar": "Any distribution",
      "dl.linux.note": "The tar.gz needs no install: unpack it and run titan-ssh/bin/titan-ssh.",
      "dl.msi": "Windows 10 and 11, 64-bit",
      "dl.win.note": "Installs for your user, without administrator rights. The installer isn't signed yet, so SmartScreen warns you: choose More info, then Run anyway.",
      "dl.apk": "Android 8 or later",
      "dl.obtainium": "Add the repository URL as a new app",
      "dl.android.note": "Every published APK is signed with the same key. SHA-256 of the certificate:",
      "dl.after": "After the first install, titan-ssh updates itself. You can switch to asking first in Settings.",
      "dl.sums": "SHA256SUMS",
      "dl.all": "All files of this release on GitHub",
      "foot.license": "titan-ssh is free software under the GNU GPL, version 3 or later.",
      "foot.source": "Source code", "foot.security": "Report a vulnerability", "foot.notices": "Third-party notices"
    },
    es: {
      "skip": "Ir al contenido",
      "nav.why": "Por qué", "nav.features": "Funciones", "nav.download": "Descargar", "nav.docs": "Documentación",
      "hero.title": "Sesiones SSH que sobreviven a la red.",
      "hero.lede": "Un cliente SSH libre y gratuito para Linux, Windows y Android. Si se cae la Wi-Fi o cambias de red, la sesión vuelve donde estaba, con tus programas todavía en marcha.",
      "hero.cta": "Descargar titan-ssh",
      "hero.ctaFor": "Descargar para {os}",
      "hero.other": "Otras plataformas",
      "hero.meta": "Linux, Windows y Android. GPL-3.0-or-later.",
      "hero.metaVersion": "Versión {v}, paquete {file}.",
      "hero.metaElse": "titan-ssh funciona en Linux, Windows y Android.",
      "demo.connected": "conectado",
      "demo.reconnecting": "reconectando, intento {n}",
      "demo.back": "reconectado, {n} líneas que te perdiste",
      "demo.caption": "La conexión se cortó unos segundos. Los tests siguieron en el servidor y la pestaña te enseña lo que te perdiste.",
      "demo.replay": "Cortar la conexión otra vez",
      "why.title": "Por qué existe titan-ssh",
      "why.a.title": "Un microcorte no debería costarte el trabajo.",
      "why.a.body": "La mayoría de clientes cierran la sesión al primer microcorte. Reconectas, vuelves a la carpeta del proyecto y arrancas otra vez lo que tenías en marcha. titan-ssh reconecta solo y, con su agente en el servidor, tu shell y sus procesos no se paran nunca.",
      "why.b.title": "Los scripts de inicio no deberían ser de pago.",
      "why.b.body": "Abre una sesión directamente en la carpeta de un proyecto, arranca tus herramientas o un servicio, ejecuta la cadena de comandos que quieras. Cada sesión guardada tiene sus propios pasos y, si quieres, se repiten al reconectar.",
      "layers.title": "Tres capas, que se usan solas",
      "layers.1.title": "Reconexión",
      "layers.1.body": "La pestaña conserva la pantalla y el historial, reintenta hasta que vuelve la red y repite tus pasos de inicio.",
      "layers.2.title": "tmux o screen",
      "layers.2.body": "Si el servidor tiene tmux o screen, la shell vive allí y aguanta más que la conexión.",
      "layers.3.title": "titan-agent",
      "layers.3.body": "Un agente pequeño, instalado en tu cuenta de usuario del servidor, mantiene vivo un terminal real y te repite lo que te perdiste. No necesita root. Funciona en servidores Linux, macOS, FreeBSD y Windows.",
      "features.title": "Qué más hace",
      "f.tabs.t": "Pestañas", "f.tabs.d": "Varias sesiones a la vez, también copias en paralelo de la misma sesión guardada. Cámbiales el nombre y ordénalas arrastrando.",
      "f.hosts.t": "Hosts y sesiones", "f.hosts.d": "Guarda hosts y sesiones, ordénalos en carpetas y reutiliza los mismos scripts y túneles en varias sesiones.",
      "f.tunnels.t": "Túneles", "f.tunnels.d": "Reenvíos locales, remotos y SOCKS que se abren con la sesión y vuelven tras reconectar.",
      "f.files.t": "Archivos", "f.files.d": "Navega por el servidor con SFTP desde una sesión abierta, y descarga o sube archivos.",
      "f.secrets.t": "Claves y contraseñas", "f.secrets.d": "Se guardan en el almacén seguro del sistema: Android Keystore, el Administrador de credenciales de Windows o Secret Service en Linux. Nunca en texto plano. En Android, las claves pueden vivir en el almacén de hardware y no salir nunca de él.",
      "f.touch.t": "En el móvil", "f.touch.d": "Una barra de teclas que ordenas arrastrando, y seleccionar, copiar y pegar en el terminal.",
      "f.updates.t": "Actualizaciones", "f.updates.d": "Se actualiza sola en todas las plataformas, y solo instala un paquete que aparezca en un manifiesto firmado con la clave del proyecto.",
      "f.privacy.t": "Privacidad", "f.privacy.d": "Sin cuenta y sin rastreo. La única petición que hace por su cuenta es la comprobación de actualizaciones a GitHub.",
      "dl.title": "Descargar",
      "dl.loading": "Buscando la última versión…",
      "dl.release": "Versión {v}, publicada el {date}.",
      "dl.failed": "No se ha podido consultar GitHub para buscar la última versión. Los enlaces abren la página del Release.",
      "dl.yours": "Tu sistema",
      "dl.deb": "Debian, Ubuntu y derivadas", "dl.rpm": "Fedora y derivadas", "dl.tar": "Cualquier distribución",
      "dl.linux.note": "El tar.gz no necesita instalación: descomprímelo y ejecuta titan-ssh/bin/titan-ssh.",
      "dl.msi": "Windows 10 y 11, 64 bits",
      "dl.win.note": "Se instala para tu usuario, sin permisos de administrador. El instalador aún no está firmado, así que SmartScreen te avisa: elige Más información y después Ejecutar de todas formas.",
      "dl.apk": "Android 8 o posterior",
      "dl.obtainium": "Añade la URL del repositorio como app nueva",
      "dl.android.note": "Todas las APK publicadas están firmadas con la misma clave. SHA-256 del certificado:",
      "dl.after": "Después de la primera instalación, titan-ssh se actualiza sola. En Ajustes puedes hacer que pregunte antes.",
      "dl.sums": "SHA256SUMS",
      "dl.all": "Todos los ficheros de esta versión en GitHub",
      "foot.license": "titan-ssh es software libre bajo la GNU GPL, versión 3 o posterior.",
      "foot.source": "Código fuente", "foot.security": "Avisar de una vulnerabilidad", "foot.notices": "Avisos de terceros"
    }
  };

  var OS_NAMES = { linux: "Linux", windows: "Windows", android: "Android" };
  var PRIMARY_ASSET = { linux: "deb", windows: "msi", android: "apk" };
  var ASSET_PATTERNS = {
    deb: /_amd64\.deb$/, rpm: /\.x86_64\.rpm$/, tar: /-linux-x64\.tar\.gz$/,
    msi: /-windows-x64\.msi$/, apk: /\.apk$/, sums: /^SHA256SUMS$/
  };

  var lang = "en";
  var release = null;   // { version, date, assets: { key: { url, name, size } } } once fetched
  var releaseFailed = false;
  var os = detectOs();
  var stripState = { key: "demo.connected", n: 0, mark: "[+]", cls: "ok" };

  function t(key, vars) {
    var s = STRINGS[lang][key] || STRINGS.en[key] || "";
    if (vars) s = s.replace(/\{(\w+)\}/g, function (_, k) { return vars[k]; });
    return s;
  }

  function storageGet(k) { try { return window.localStorage.getItem(k); } catch (e) { return null; } }
  function storageSet(k, v) { try { window.localStorage.setItem(k, v); } catch (e) { /* private mode */ } }

  function initialLang() {
    var fromUrl = new URLSearchParams(location.search).get("lang");
    if (fromUrl && STRINGS[fromUrl]) return fromUrl;
    var saved = storageGet("titan-lang");
    if (saved && STRINGS[saved]) return saved;
    var langs = navigator.languages || [navigator.language || "en"];
    for (var i = 0; i < langs.length; i++) {
      var l = String(langs[i]).slice(0, 2).toLowerCase();
      if (STRINGS[l]) return l;
    }
    return "en";
  }

  function detectOs() {
    var ua = navigator.userAgent || "";
    if (/Android/i.test(ua)) return "android";
    if (/Windows/i.test(ua)) return "windows";
    if (/iPhone|iPad|iPod|Macintosh/i.test(ua)) return null;
    if (/Linux|X11|CrOS/i.test(ua)) return "linux";
    return null;
  }

  function setLang(next) {
    lang = next;
    document.documentElement.lang = next;
    document.documentElement.setAttribute("data-lang", next);
    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      el.textContent = t(el.getAttribute("data-i18n"));
    });
    document.querySelectorAll("[data-set-lang]").forEach(function (b) {
      b.setAttribute("aria-pressed", String(b.getAttribute("data-set-lang") === next));
    });
    document.title = next === "es" ? "titan-ssh: sesiones SSH que sobreviven a la red" : "titan-ssh: SSH sessions that survive the network";
    renderStrip();
    renderRelease();
  }

  // ---- Download links -------------------------------------------------

  function formatSize(bytes) {
    var mb = bytes / (1024 * 1024);
    return new Intl.NumberFormat(lang, { maximumFractionDigits: mb < 10 ? 1 : 0 }).format(mb) + " MB";
  }

  function renderRelease() {
    var line = document.getElementById("release-line");
    var heroBtn = document.getElementById("hero-download");
    var heroMeta = document.getElementById("hero-meta");

    if (os) {
      heroBtn.textContent = t("hero.ctaFor", { os: OS_NAMES[os] });
    } else {
      heroBtn.textContent = t("hero.cta");
      heroBtn.href = "#download";
      heroMeta.textContent = t("hero.metaElse");
    }

    if (releaseFailed) { line.textContent = t("dl.failed"); return; }
    if (!release) return;

    var date = new Intl.DateTimeFormat(lang, { year: "numeric", month: "long", day: "numeric" }).format(new Date(release.date));
    line.textContent = t("dl.release", { v: release.version, date: date });

    document.querySelectorAll("[data-asset]").forEach(function (a) {
      var asset = release.assets[a.getAttribute("data-asset")];
      if (!asset) return;
      a.href = asset.url;
      a.querySelector(".fsize").textContent = formatSize(asset.size);
      a.title = asset.name;
    });
    document.querySelectorAll(".cmd .v").forEach(function (v) { v.textContent = release.version; });
    if (release.assets.sums) document.getElementById("sums").href = release.assets.sums.url;
    document.getElementById("all-files").href = release.page;

    if (os) {
      var primary = release.assets[PRIMARY_ASSET[os]];
      if (primary) {
        heroBtn.href = primary.url;
        heroMeta.textContent = t("hero.metaVersion", { v: release.version, file: primary.name.replace(/^.*?(\.[a-z.]+)$/, "$1") });
      }
    }
  }

  function markDetectedPlatform() {
    if (!os) return;
    var card = document.querySelector('.platform[data-os="' + os + '"]');
    card.classList.add("detected");
    card.style.order = "-1";
    card.querySelector(".yours").hidden = false;
  }

  function loadRelease() {
    var cached = null;
    try { cached = JSON.parse(sessionStorage.getItem("titan-release") || "null"); } catch (e) { cached = null; }
    if (cached && Date.now() - cached.at < 10 * 60 * 1000) { release = cached.release; renderRelease(); return; }

    fetch("https://api.github.com/repos/" + REPO + "/releases/latest", { headers: { Accept: "application/vnd.github+json" } })
      .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
      .then(function (json) {
        var assets = {};
        (json.assets || []).forEach(function (a) {
          Object.keys(ASSET_PATTERNS).forEach(function (key) {
            if (ASSET_PATTERNS[key].test(a.name)) assets[key] = { url: a.browser_download_url, name: a.name, size: a.size };
          });
        });
        release = {
          version: String(json.tag_name || "").replace(/^v/, ""),
          date: json.published_at,
          page: json.html_url || RELEASES,
          assets: assets
        };
        try { sessionStorage.setItem("titan-release", JSON.stringify({ at: Date.now(), release: release })); } catch (e) { /* ignore */ }
        renderRelease();
      })
      .catch(function () { releaseFailed = true; renderRelease(); });
  }

  // ---- Micro-cut demo -------------------------------------------------

  var PROMPT = '<span class="p">you@build-box</span>:<span class="d">~/projects/api</span>$ ';
  var BEFORE = [
    PROMPT + "make test-all",
    '<span class="dim">go vet ./...</span>',
    "running 412 tests",
    '  <span class="ok">ok</span>  pkg/auth        1.8s',
    '  <span class="ok">ok</span>  pkg/billing     3.1s',
    '  <span class="ok">ok</span>  pkg/config      0.4s'
  ];
  var MISSED = [
    '  <span class="ok">ok</span>  pkg/http        2.2s',
    '  <span class="ok">ok</span>  pkg/queue       5.0s',
    '  <span class="ok">ok</span>  pkg/storage     6.4s'
  ];
  var AFTER = [
    '  <span class="ok">ok</span>  pkg/worker      1.1s',
    "412 passed, 0 failed (24.6s)",
    PROMPT + '<span class="caret"></span>'
  ];

  var term = document.getElementById("term");
  var screen = document.getElementById("screen");
  var tabMark = document.getElementById("tab-mark");
  var timers = [];
  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function renderStrip() {
    var strip = document.getElementById("strip");
    strip.innerHTML = '<span class="mark ' + stripState.cls + '">' + stripState.mark + "</span> ";
    var span = document.createElement("span");
    span.textContent = t(stripState.key, { n: stripState.n });
    strip.appendChild(span);
    tabMark.textContent = stripState.mark;
    tabMark.className = "mark " + stripState.cls;
  }

  function setStrip(key, n, connected) {
    stripState = { key: key, n: n, mark: connected ? "[+]" : "[-]", cls: connected ? "ok" : "wait" };
    renderStrip();
  }

  function line(html, cls) {
    var div = document.createElement("div");
    if (cls) div.className = cls;
    div.innerHTML = html;
    screen.appendChild(div);
  }

  function at(ms, fn) { timers.push(setTimeout(fn, ms)); }

  function playDemo() {
    timers.forEach(clearTimeout);
    timers = [];
    screen.innerHTML = "";
    term.classList.remove("down");

    if (reduceMotion) {
      BEFORE.forEach(function (h) { line(h); });
      MISSED.forEach(function (h) { line(h, "replayed"); });
      AFTER.forEach(function (h) { line(h); });
      setStrip("demo.back", MISSED.length, true);
      return;
    }

    setStrip("demo.connected", 0, true);
    var tms = 300;
    BEFORE.forEach(function (h, i) { at(tms + i * 420, function () { line(h); }); });
    tms += BEFORE.length * 420 + 600;
    at(tms, function () { term.classList.add("down"); setStrip("demo.reconnecting", 1, false); });
    at(tms + 1400, function () { setStrip("demo.reconnecting", 2, false); });
    tms += 2900;
    at(tms, function () {
      term.classList.remove("down");
      MISSED.forEach(function (h) { line(h, "replayed"); });
      setStrip("demo.back", MISSED.length, true);
    });
    AFTER.forEach(function (h, i) { at(tms + 700 + i * 500, function () { line(h); }); });
  }

  // ---- Wire up --------------------------------------------------------

  document.querySelectorAll("[data-set-lang]").forEach(function (b) {
    b.addEventListener("click", function () {
      var next = b.getAttribute("data-set-lang");
      storageSet("titan-lang", next);
      setLang(next);
    });
  });
  document.getElementById("replay").addEventListener("click", playDemo);

  markDetectedPlatform();
  setLang(initialLang());
  playDemo();
  loadRelease();
})();
