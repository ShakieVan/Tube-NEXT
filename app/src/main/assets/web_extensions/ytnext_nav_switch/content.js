(function () {
  "use strict";

  var NATIVE_APP = "ytnext_nav_switch";
  var MODE_NAV = "MODE_NAV";
  var OPEN_NEW_TAB = "OPEN_NEW_TAB";
  var LANDSCAPE_STYLE_ID = "ytnext_landscape_watch_style";

  function isYouTubeHost(host) {
    var normalized = (host || "").toLowerCase();
    return normalized === "youtube.com" ||
      normalized === "www.youtube.com" ||
      normalized === "m.youtube.com" ||
      normalized === "youtu.be";
  }

  function shouldUseDesktop(urlString) {
    try {
      var url = new URL(urlString, window.location.href);
      if (!isYouTubeHost(url.hostname)) {
        return false;
      }
      return url.hostname.toLowerCase() === "youtu.be" || url.pathname.startsWith("/watch");
    } catch (_) {
      return false;
    }
  }

  function isLandscapeWatch() {
    return shouldUseDesktop(window.location.href) && window.innerWidth > window.innerHeight;
  }

  function ensureLandscapeWatchStyle() {
    if (document.getElementById(LANDSCAPE_STYLE_ID)) {
      return;
    }
    var style = document.createElement("style");
    style.id = LANDSCAPE_STYLE_ID;
    style.textContent = [
      "html.ytnext-landscape-watch, html.ytnext-landscape-watch body {",
      "  margin: 0 !important;",
      "  padding: 0 !important;",
      "  width: 100vw !important;",
      "  height: 100vh !important;",
      "  overflow: hidden !important;",
      "  background: #000 !important;",
      "}",
      "html.ytnext-landscape-watch ytd-masthead,",
      "html.ytnext-landscape-watch #masthead,",
      "html.ytnext-landscape-watch #masthead-container,",
      "html.ytnext-landscape-watch ytd-mini-guide-renderer,",
      "html.ytnext-landscape-watch tp-yt-app-drawer,",
      "html.ytnext-landscape-watch ytd-popup-container,",
      "html.ytnext-landscape-watch ytd-watch-flexy #below,",
      "html.ytnext-landscape-watch ytd-watch-flexy #secondary,",
      "html.ytnext-landscape-watch ytd-watch-flexy #comments,",
      "html.ytnext-landscape-watch ytd-watch-flexy #related,",
      "html.ytnext-landscape-watch ytd-watch-flexy #meta,",
      "html.ytnext-landscape-watch ytd-watch-flexy #chat,",
      "html.ytnext-landscape-watch ytd-watch-flexy #panels {",
      "  display: none !important;",
      "}",
      "html.ytnext-landscape-watch ytd-app,",
      "html.ytnext-landscape-watch ytd-page-manager,",
      "html.ytnext-landscape-watch ytd-watch-flexy,",
      "html.ytnext-landscape-watch #content,",
      "html.ytnext-landscape-watch #primary {",
      "  margin: 0 !important;",
      "  padding: 0 !important;",
      "  width: 100vw !important;",
      "  height: 100vh !important;",
      "  max-width: none !important;",
      "  min-width: 0 !important;",
      "  overflow: hidden !important;",
      "}",
      "html.ytnext-landscape-watch #player,",
      "html.ytnext-landscape-watch #player-container,",
      "html.ytnext-landscape-watch #player-container-outer,",
      "html.ytnext-landscape-watch #player-container-inner,",
      "html.ytnext-landscape-watch #player-full-bleed-container,",
      "html.ytnext-landscape-watch #movie_player,",
      "html.ytnext-landscape-watch .html5-video-player,",
      "html.ytnext-landscape-watch .html5-video-container {",
      "  position: fixed !important;",
      "  inset: 0 !important;",
      "  margin: 0 !important;",
      "  width: 100vw !important;",
      "  height: 100vh !important;",
      "  max-width: none !important;",
      "  max-height: none !important;",
      "  transform: none !important;",
      "  background: #000 !important;",
      "  z-index: 2147483646 !important;",
      "}",
      "html.ytnext-landscape-watch video {",
      "  position: fixed !important;",
      "  inset: 0 !important;",
      "  margin: 0 !important;",
      "  width: 100vw !important;",
      "  height: 100vh !important;",
      "  object-fit: contain !important;",
      "  transform: none !important;",
      "  background: #000 !important;",
      "}"
    ].join("\n");
    (document.documentElement || document.head || document.body).appendChild(style);
  }

  function removeLandscapeWatchStyle() {
    var style = document.getElementById(LANDSCAPE_STYLE_ID);
    if (style) {
      style.remove();
    }
  }

  function applyLandscapeWatchMode() {
    if (!document.documentElement) {
      return;
    }
    if (isLandscapeWatch()) {
      ensureLandscapeWatchStyle();
      document.documentElement.classList.add("ytnext-landscape-watch");
      window.scrollTo(0, 0);
      return;
    }
    document.documentElement.classList.remove("ytnext-landscape-watch");
    removeLandscapeWatchStyle();
  }

  function scheduleLandscapeWatchMode() {
    window.setTimeout(applyLandscapeWatchMode, 0);
    window.setTimeout(applyLandscapeWatchMode, 150);
    window.setTimeout(applyLandscapeWatchMode, 700);
  }

  function extractAnchor(target) {
    if (!target || typeof target.closest !== "function") {
      return null;
    }
    return target.closest("a[href]");
  }

  function shouldIgnoreClick(event) {
    return event.defaultPrevented ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey;
  }

  function handleDocumentClick(event) {
    if (shouldIgnoreClick(event)) {
      return;
    }

    var anchor = extractAnchor(event.target);
    if (!anchor) {
      return;
    }
    if (anchor.target && anchor.target !== "_self") {
      return;
    }
    if (anchor.hasAttribute("download")) {
      return;
    }

    var href = anchor.getAttribute("href");
    if (!href || href.startsWith("javascript:")) {
      return;
    }

    var targetUrl;
    try {
      targetUrl = new URL(anchor.href, window.location.href);
    } catch (_) {
      return;
    }

    if (targetUrl.protocol !== "http:" && targetUrl.protocol !== "https:") {
      return;
    }
    if (!isYouTubeHost(targetUrl.hostname)) {
      return;
    }

    var currentDesktop = shouldUseDesktop(window.location.href);
    var targetDesktop = shouldUseDesktop(targetUrl.toString());
    if (currentDesktop === targetDesktop) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();

    browser.runtime.sendNativeMessage(NATIVE_APP, {
      type: MODE_NAV,
      url: targetUrl.toString()
    }).catch(function () {
      window.location.href = targetUrl.toString();
    });
  }

  function handleDocumentContextMenu(event) {
    var anchor = extractAnchor(event.target);
    if (!anchor) {
      return;
    }

    var href = anchor.getAttribute("href");
    if (!href || href.startsWith("javascript:")) {
      return;
    }

    var targetUrl;
    try {
      targetUrl = new URL(anchor.href, window.location.href);
    } catch (_) {
      return;
    }

    if (targetUrl.protocol !== "http:" && targetUrl.protocol !== "https:") {
      return;
    }
    if (!isYouTubeHost(targetUrl.hostname)) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();

    browser.runtime.sendNativeMessage(NATIVE_APP, {
      type: OPEN_NEW_TAB,
      url: targetUrl.toString()
    }).catch(function () {});
  }

  document.addEventListener("click", handleDocumentClick, true);
  document.addEventListener("contextmenu", handleDocumentContextMenu, true);
  window.addEventListener("resize", scheduleLandscapeWatchMode, true);
  window.addEventListener("orientationchange", scheduleLandscapeWatchMode, true);
  window.addEventListener("yt-navigate-finish", scheduleLandscapeWatchMode, true);
  window.addEventListener("popstate", scheduleLandscapeWatchMode, true);
  scheduleLandscapeWatchMode();
})();
