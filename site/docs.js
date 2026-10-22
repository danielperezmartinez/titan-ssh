// titan-ssh documentation pages: the language switch. Both languages are in
// the page (data-l="en" / data-l="es") and styles.css hides the other one;
// the choice is shared with the product page through the same storage key.
(function () {
  "use strict";

  var LANGS = ["en", "es"];

  function storageGet(k) { try { return window.localStorage.getItem(k); } catch (e) { return null; } }
  function storageSet(k, v) { try { window.localStorage.setItem(k, v); } catch (e) { /* private mode */ } }

  function initialLang() {
    var fromUrl = new URLSearchParams(location.search).get("lang");
    if (LANGS.indexOf(fromUrl) >= 0) return fromUrl;
    var saved = storageGet("titan-lang");
    if (LANGS.indexOf(saved) >= 0) return saved;
    var langs = navigator.languages || [navigator.language || "en"];
    for (var i = 0; i < langs.length; i++) {
      var l = String(langs[i]).slice(0, 2).toLowerCase();
      if (LANGS.indexOf(l) >= 0) return l;
    }
    return "en";
  }

  function setLang(next) {
    document.documentElement.lang = next;
    document.documentElement.setAttribute("data-lang", next);
    document.querySelectorAll("[data-set-lang]").forEach(function (b) {
      b.setAttribute("aria-pressed", String(b.getAttribute("data-set-lang") === next));
    });
    var title = document.documentElement.getAttribute("data-title-" + next);
    if (title) document.title = title;
  }

  document.querySelectorAll("[data-set-lang]").forEach(function (b) {
    b.addEventListener("click", function () {
      var next = b.getAttribute("data-set-lang");
      storageSet("titan-lang", next);
      setLang(next);
    });
  });

  setLang(initialLang());
})();
