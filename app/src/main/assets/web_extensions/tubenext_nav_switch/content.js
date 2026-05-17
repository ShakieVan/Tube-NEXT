(function () {
  "use strict";

  var NATIVE_APP = "tubenext_nav_switch";
  var MODE_NAV = "MODE_NAV";
  var OPEN_NEW_TAB = "OPEN_NEW_TAB";
  var DARK_MODE_STYLE_ID = "tubenext_dark_mode_style";
  var WATCH_FIT_STYLE_ID = "tubenext_watch_fit_style";
  var SCROLL_TOP_BUTTON_ID = "tubenext_scroll_top_button";
  var LANDSCAPE_STYLE_ID = "tubenext_landscape_watch_style";
  var TAP_CONFIRM_DELAY_MS = 320;
  var LONG_PRESS_TRIGGER_MS = 520;
  var LONG_TAP_SUPPRESS_MS = 650;
  var SEEK_STEP_SECONDS = 10;
  var pendingSingleTapTimer = null;
  var activePlayerTouch = null;
  var lastTouchTapAt = 0;
  var lastTouchTapZone = null;
  var cueModeActive = false;
  var cueTime = null;
  var cueOverlay = null;
  var scrollTopButton = null;
  var suppressPlayerTapUntil = 0;

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

  function isWatchPage() {
    return shouldUseDesktop(window.location.href);
  }

  function ensureYouTubeDarkPreference() {
    if (!isYouTubeHost(window.location.hostname)) {
      return;
    }
    var pref = "";
    document.cookie.split(";").forEach(function (part) {
      var trimmed = part.trim();
      if (trimmed.indexOf("PREF=") === 0) {
        pref = trimmed.substring(5);
      }
    });
    var parts = pref ? pref.split("&").filter(Boolean) : [];
    var found = false;
    parts = parts.map(function (part) {
      if (part.indexOf("f6=") === 0) {
        found = true;
        return "f6=400";
      }
      return part;
    });
    if (!found) {
      parts.push("f6=400");
    }
    var cookie = "PREF=" + parts.join("&") + "; path=/; max-age=31536000; SameSite=Lax";
    var host = window.location.hostname.toLowerCase();
    if (host === "youtube.com" || host.endsWith(".youtube.com")) {
      document.cookie = cookie + "; domain=.youtube.com";
    } else {
      document.cookie = cookie;
    }
  }

  function ensureDarkModeStyle() {
    if (document.getElementById(DARK_MODE_STYLE_ID)) {
      return;
    }
    var style = document.createElement("style");
    style.id = DARK_MODE_STYLE_ID;
    style.textContent = [
      ":root {",
      "  color-scheme: dark !important;",
      "  --yt-spec-base-background: #0f0f0f !important;",
      "  --yt-spec-raised-background: #212121 !important;",
      "  --yt-spec-menu-background: #282828 !important;",
      "  --yt-spec-text-primary: #f1f1f1 !important;",
      "  --yt-spec-text-secondary: #aaa !important;",
      "}",
      "html, body, ytd-app, ytm-app {",
      "  background: #0f0f0f !important;",
      "}",
      "html:not([dark]) {",
      "  background: #0f0f0f !important;",
      "}"
    ].join("\n");
    (document.documentElement || document.head || document.body).appendChild(style);
  }

  ensureYouTubeDarkPreference();
  ensureDarkModeStyle();

  function ensureWatchFitStyle() {
    if (document.getElementById(WATCH_FIT_STYLE_ID)) {
      return;
    }
    var style = document.createElement("style");
    style.id = WATCH_FIT_STYLE_ID;
    style.textContent = [
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch),",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) body {",
      "  width: 100% !important;",
      "  max-width: 100vw !important;",
      "  overflow-x: hidden !important;",
      "  box-sizing: border-box !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) * {",
      "  box-sizing: border-box !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) ytd-app,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) ytd-page-manager,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) ytd-watch-flexy,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #content,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #page-manager {",
      "  width: 100% !important;",
      "  max-width: 100vw !important;",
      "  min-width: 0 !important;",
      "  margin-left: 0 !important;",
      "  margin-right: 0 !important;",
      "  padding-left: 0 !important;",
      "  padding-right: 0 !important;",
      "  overflow-x: hidden !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #columns,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #primary,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #primary-inner,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #secondary {",
      "  width: auto !important;",
      "  max-width: 100% !important;",
      "  min-width: 0 !important;",
      "  margin-left: 0 !important;",
      "  margin-right: 0 !important;",
      "  padding-left: 0 !important;",
      "  padding-right: 0 !important;",
      "  box-sizing: border-box !important;",
      "  overflow-x: hidden !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #columns {",
      "  display: block !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #player,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #player-container,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #player-container-outer,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #player-container-inner,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #player-full-bleed-container,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #movie_player,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .html5-video-player,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .html5-video-container {",
      "  left: 0 !important;",
      "  right: auto !important;",
      "  width: 100vw !important;",
      "  max-width: 100vw !important;",
      "  min-width: 0 !important;",
      "  margin-left: 0 !important;",
      "  margin-right: 0 !important;",
      "  transform: none !important;",
      "  box-sizing: border-box !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) video {",
      "  left: 0 !important;",
      "  right: auto !important;",
      "  width: 100vw !important;",
      "  max-width: 100vw !important;",
      "  min-width: 0 !important;",
      "  margin-left: 0 !important;",
      "  margin-right: 0 !important;",
      "  object-fit: contain !important;",
      "  transform: none !important;",
      "  box-sizing: border-box !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) ytd-watch-metadata,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #above-the-fold,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #below,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #comments,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) ytd-comments,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) ytd-item-section-renderer,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) ytd-watch-next-secondary-results-renderer {",
      "  width: 100% !important;",
      "  max-width: 100% !important;",
      "  min-width: 0 !important;",
      "  box-sizing: border-box !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) ytd-watch-metadata,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #below,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #secondary {",
      "  padding-left: 0 !important;",
      "  padding-right: 0 !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) ytd-watch-metadata > *,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #below > *,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) #secondary > * {",
      "  max-width: 100% !important;",
      "  min-width: 0 !important;",
      "  margin-left: 0 !important;",
      "  margin-right: 0 !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-chrome-bottom,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-chrome-top {",
      "  left: 12px !important;",
      "  right: 12px !important;",
      "  width: auto !important;",
      "  max-width: calc(100vw - 24px) !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-chrome-bottom {",
      "  height: 62px !important;",
      "  bottom: 0 !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-chrome-controls {",
      "  height: 56px !important;",
      "  display: flex !important;",
      "  align-items: center !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-left-controls,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-right-controls {",
      "  height: 56px !important;",
      "  display: flex !important;",
      "  align-items: center !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-left-controls {",
      "  flex: 1 1 auto !important;",
      "  min-width: 0 !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-right-controls {",
      "  flex: 0 0 58px !important;",
      "  width: 58px !important;",
      "  min-width: 58px !important;",
      "  justify-content: flex-end !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-button {",
      "  width: 52px !important;",
      "  min-width: 52px !important;",
      "  height: 52px !important;",
      "  padding: 7px !important;",
      "  box-sizing: border-box !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-button svg {",
      "  width: 36px !important;",
      "  height: 36px !important;",
      "  transform: scale(1.2) !important;",
      "  transform-origin: center !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-button .ytp-svg-fill {",
      "  transform: scale(1.25) !important;",
      "  transform-origin: center !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-time-display,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-time-current,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-time-duration {",
      "  font-size: 18px !important;",
      "  line-height: 52px !important;",
      "  height: 52px !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-progress-bar-container {",
      "  height: 18px !important;",
      "  top: -10px !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-progress-bar {",
      "  height: 5px !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-volume-area,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-next-button,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-miniplayer-button,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-size-button,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-subtitles-button,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-fullscreen-button,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-remote-button,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-overflow-button,",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-right-controls .ytp-button:not(.ytp-settings-button) {",
      "  display: none !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-settings-button {",
      "  display: inline-flex !important;",
      "  visibility: visible !important;",
      "  opacity: 1 !important;",
      "}",
      "html.tubenext-watch-fit:not(.tubenext-landscape-watch) .ytp-tooltip {",
      "  display: none !important;",
      "}",
      "#" + SCROLL_TOP_BUTTON_ID + " {",
      "  position: fixed !important;",
      "  right: 16px !important;",
      "  bottom: 88px !important;",
      "  z-index: 2147483647 !important;",
      "  width: 44px !important;",
      "  height: 44px !important;",
      "  border: 0 !important;",
      "  border-radius: 999px !important;",
      "  background: rgba(255, 255, 255, 0.92) !important;",
      "  color: #111 !important;",
      "  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35) !important;",
      "  font: 800 24px/44px sans-serif !important;",
      "  text-align: center !important;",
      "  opacity: 0 !important;",
      "  pointer-events: none !important;",
      "  transition: opacity 160ms ease !important;",
      "}",
      "html.tubenext-show-scroll-top:not(.tubenext-landscape-watch) #" + SCROLL_TOP_BUTTON_ID + " {",
      "  opacity: 1 !important;",
      "  pointer-events: auto !important;",
      "}"
    ].join("\n");
    (document.documentElement || document.head || document.body).appendChild(style);
  }

  function ensureLandscapeWatchStyle() {
    if (document.getElementById(LANDSCAPE_STYLE_ID)) {
      return;
    }
    var style = document.createElement("style");
    style.id = LANDSCAPE_STYLE_ID;
    style.textContent = [
      "html.tubenext-landscape-watch, html.tubenext-landscape-watch body {",
      "  margin: 0 !important;",
      "  padding: 0 !important;",
      "  width: 100vw !important;",
      "  height: 100vh !important;",
      "  overflow: hidden !important;",
      "  background: #000 !important;",
      "}",
      "html.tubenext-landscape-watch ytd-masthead,",
      "html.tubenext-landscape-watch #masthead,",
      "html.tubenext-landscape-watch #masthead-container,",
      "html.tubenext-landscape-watch ytd-mini-guide-renderer,",
      "html.tubenext-landscape-watch tp-yt-app-drawer,",
      "html.tubenext-landscape-watch ytd-popup-container,",
      "html.tubenext-landscape-watch ytd-watch-flexy #below,",
      "html.tubenext-landscape-watch ytd-watch-flexy #secondary,",
      "html.tubenext-landscape-watch ytd-watch-flexy #comments,",
      "html.tubenext-landscape-watch ytd-watch-flexy #related,",
      "html.tubenext-landscape-watch ytd-watch-flexy #meta,",
      "html.tubenext-landscape-watch ytd-watch-flexy #chat,",
      "html.tubenext-landscape-watch ytd-watch-flexy #panels {",
      "  display: none !important;",
      "}",
      "html.tubenext-landscape-watch ytd-app,",
      "html.tubenext-landscape-watch ytd-page-manager,",
      "html.tubenext-landscape-watch ytd-watch-flexy,",
      "html.tubenext-landscape-watch #content,",
      "html.tubenext-landscape-watch #primary {",
      "  margin: 0 !important;",
      "  padding: 0 !important;",
      "  width: 100vw !important;",
      "  height: 100vh !important;",
      "  max-width: none !important;",
      "  min-width: 0 !important;",
      "  overflow: hidden !important;",
      "}",
      "html.tubenext-landscape-watch #player,",
      "html.tubenext-landscape-watch #player-container,",
      "html.tubenext-landscape-watch #player-container-outer,",
      "html.tubenext-landscape-watch #player-container-inner,",
      "html.tubenext-landscape-watch #player-full-bleed-container,",
      "html.tubenext-landscape-watch #movie_player,",
      "html.tubenext-landscape-watch .html5-video-player,",
      "html.tubenext-landscape-watch .html5-video-container {",
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
      "html.tubenext-landscape-watch video {",
      "  position: absolute !important;",
      "  inset: 0 !important;",
      "  margin: 0 !important;",
      "  width: 100vw !important;",
      "  height: 100vh !important;",
      "  object-fit: contain !important;",
      "  transform: none !important;",
      "  background: #000 !important;",
      "  z-index: 0 !important;",
      "}",
      "html.tubenext-landscape-watch .ytp-gradient-top,",
      "html.tubenext-landscape-watch .ytp-gradient-bottom,",
      "html.tubenext-landscape-watch .ytp-chrome-top,",
      "html.tubenext-landscape-watch .ytp-chrome-bottom,",
      "html.tubenext-landscape-watch .ytp-player-content,",
      "html.tubenext-landscape-watch .ytp-cards-teaser,",
      "html.tubenext-landscape-watch .ytp-ce-element,",
      "html.tubenext-landscape-watch .ytp-popup,",
      "html.tubenext-landscape-watch .ytp-settings-menu,",
      "html.tubenext-landscape-watch .ytp-panel,",
      "html.tubenext-landscape-watch .ytp-caption-window-container {",
      "  z-index: 2147483647 !important;",
      "  pointer-events: auto !important;",
      "}",
      "html.tubenext-landscape-watch .ytp-chrome-bottom {",
      "  left: 12px !important;",
      "  right: 12px !important;",
      "  bottom: 0 !important;",
      "  width: auto !important;",
      "}",
      "html.tubenext-landscape-watch .ytp-chrome-top {",
      "  left: 12px !important;",
      "  right: 12px !important;",
      "  width: auto !important;",
      "}",
      "html.tubenext-landscape-watch .ytp-fullscreen .ytp-chrome-bottom,",
      "html.tubenext-landscape-watch .ytp-fullscreen .ytp-chrome-top {",
      "  width: auto !important;",
      "}",
      "html.tubenext-landscape-watch .tubenext-cue-overlay {",
      "  position: fixed !important;",
      "  left: 18px !important;",
      "  top: 50% !important;",
      "  transform: translateY(-50%) !important;",
      "  z-index: 2147483647 !important;",
      "  display: flex !important;",
      "  flex-direction: column !important;",
      "  gap: 8px !important;",
      "  align-items: flex-start !important;",
      "  pointer-events: auto !important;",
      "  font-family: sans-serif !important;",
      "}",
      "html.tubenext-landscape-watch .tubenext-cue-overlay button {",
      "  appearance: none !important;",
      "  border: 0 !important;",
      "  border-radius: 999px !important;",
      "  background: rgba(255, 255, 255, 0.92) !important;",
      "  color: #111 !important;",
      "  font: 700 15px/1 sans-serif !important;",
      "  padding: 12px 16px !important;",
      "  min-width: 68px !important;",
      "  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35) !important;",
      "}",
      "html.tubenext-landscape-watch .tubenext-cue-overlay .tubenext-cue-close {",
      "  min-width: 42px !important;",
      "  padding: 10px 12px !important;",
      "  background: rgba(0, 0, 0, 0.62) !important;",
      "  color: #fff !important;",
      "}",
      "html.tubenext-landscape-watch .tubenext-cue-overlay .tubenext-cue-label {",
      "  border-radius: 999px !important;",
      "  background: rgba(0, 0, 0, 0.62) !important;",
      "  color: #fff !important;",
      "  font: 700 12px/1 sans-serif !important;",
      "  padding: 8px 11px !important;",
      "  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.5) !important;",
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
    if (isWatchPage()) {
      ensureWatchFitStyle();
      document.documentElement.classList.add("tubenext-watch-fit");
    } else {
      document.documentElement.classList.remove("tubenext-watch-fit");
    }
    if (isLandscapeWatch()) {
      ensureLandscapeWatchStyle();
      document.documentElement.classList.add("tubenext-landscape-watch");
      updateCueOverlay();
      window.scrollTo(0, 0);
      return;
    }
    document.documentElement.classList.remove("tubenext-landscape-watch");
    updateCueOverlay();
    removeLandscapeWatchStyle();
  }

  function scheduleLandscapeWatchMode() {
    window.setTimeout(applyLandscapeWatchMode, 0);
    window.setTimeout(applyLandscapeWatchMode, 150);
    window.setTimeout(applyLandscapeWatchMode, 700);
    window.setTimeout(applyLandscapeWatchMode, 1500);
    window.setTimeout(applyLandscapeWatchMode, 2600);
  }

  function stopGenericOverlayEvent(event, preventDefault) {
    if (preventDefault) {
      event.preventDefault();
    }
    event.stopPropagation();
    if (typeof event.stopImmediatePropagation === "function") {
      event.stopImmediatePropagation();
    }
  }

  function ensureScrollTopButton() {
    if (scrollTopButton && document.documentElement.contains(scrollTopButton)) {
      return scrollTopButton;
    }
    scrollTopButton = document.createElement("button");
    scrollTopButton.id = SCROLL_TOP_BUTTON_ID;
    scrollTopButton.type = "button";
    scrollTopButton.textContent = "↑";
    scrollTopButton.setAttribute("aria-label", "Nach oben");
    ["click", "pointerup", "touchend"].forEach(function (type) {
      scrollTopButton.addEventListener(type, function (event) {
        stopGenericOverlayEvent(event, true);
        window.scrollTo({ top: 0, left: 0, behavior: "smooth" });
      }, true);
    });
    ["pointerdown", "touchstart", "mousedown", "contextmenu"].forEach(function (type) {
      scrollTopButton.addEventListener(type, function (event) {
        stopGenericOverlayEvent(event, true);
      }, true);
    });
    (document.body || document.documentElement).appendChild(scrollTopButton);
    return scrollTopButton;
  }

  function updateScrollTopButton() {
    if (!document.documentElement) {
      return;
    }
    ensureWatchFitStyle();
    ensureScrollTopButton();
    var show = !isLandscapeWatch() && window.scrollY > 480;
    document.documentElement.classList.toggle("tubenext-show-scroll-top", show);
  }

  function forceTopAfterLoad() {
    window.setTimeout(function () { window.scrollTo(0, 0); }, 0);
    window.setTimeout(function () { window.scrollTo(0, 0); }, 120);
    window.setTimeout(function () { window.scrollTo(0, 0); }, 450);
  }

  function isPlayerControlTarget(target) {
    if (!target || typeof target.closest !== "function") {
      return false;
    }
    return !!target.closest([
      "a",
      "button",
      "input",
      "textarea",
      "select",
      ".ytp-chrome-bottom",
      ".ytp-chrome-top",
      ".ytp-popup",
      ".ytp-settings-menu",
      ".ytp-panel",
      ".ytp-ce-element",
      ".ytp-cards-teaser",
      ".tubenext-cue-overlay"
    ].join(","));
  }

  function getPrimaryVideo() {
    return document.querySelector("video");
  }

  function formatCueTime(seconds) {
    if (!Number.isFinite(seconds)) {
      return "--:--";
    }
    var total = Math.max(0, Math.floor(seconds));
    var minutes = Math.floor(total / 60);
    var secs = total % 60;
    return String(minutes) + ":" + String(secs).padStart(2, "0");
  }

  function stopOverlayEvent(event, preventDefault) {
    if (preventDefault) {
      event.preventDefault();
    }
    event.stopPropagation();
    if (typeof event.stopImmediatePropagation === "function") {
      event.stopImmediatePropagation();
    }
  }

  function handleCueButtonActivation(event, action) {
    stopOverlayEvent(event, true);
    if (event.type === "click" && event.detail === 0) {
      return;
    }
    action();
  }

  function ensureCueOverlay() {
    if (cueOverlay && document.documentElement.contains(cueOverlay)) {
      return cueOverlay;
    }
    cueOverlay = document.createElement("div");
    cueOverlay.className = "tubenext-cue-overlay";

    var cueButton = document.createElement("button");
    cueButton.type = "button";
    cueButton.className = "tubenext-cue-jump";
    cueButton.textContent = "Cue";
    ["click", "pointerup", "touchend"].forEach(function (type) {
      cueButton.addEventListener(type, function (event) {
        handleCueButtonActivation(event, jumpToCuePoint);
      }, true);
    });

    var label = document.createElement("div");
    label.className = "tubenext-cue-label";

    var closeButton = document.createElement("button");
    closeButton.type = "button";
    closeButton.className = "tubenext-cue-close";
    closeButton.textContent = "X";
    ["click", "pointerup", "touchend"].forEach(function (type) {
      closeButton.addEventListener(type, function (event) {
        handleCueButtonActivation(event, deactivateCueMode);
      }, true);
    });

    ["pointerdown", "pointerup", "touchstart", "touchend", "mousedown", "mouseup"].forEach(function (type) {
      cueOverlay.addEventListener(type, function (event) {
        stopOverlayEvent(event, false);
      }, true);
    });
    cueOverlay.addEventListener("contextmenu", function (event) {
      stopOverlayEvent(event, true);
    });

    cueOverlay.appendChild(cueButton);
    cueOverlay.appendChild(label);
    cueOverlay.appendChild(closeButton);
    (document.body || document.documentElement).appendChild(cueOverlay);
    return cueOverlay;
  }

  function updateCueOverlay() {
    if (!cueOverlay && !cueModeActive) {
      return;
    }
    var overlay = ensureCueOverlay();
    overlay.style.setProperty("display", cueModeActive && isLandscapeWatch() ? "flex" : "none", "important");
    var label = overlay.querySelector(".tubenext-cue-label");
    if (label) {
      label.textContent = "Cue " + formatCueTime(cueTime);
    }
  }

  function setCuePointFromVideo() {
    var video = getPrimaryVideo();
    if (!video || !Number.isFinite(video.currentTime)) {
      return;
    }
    cueTime = video.currentTime;
    cueModeActive = true;
    updateCueOverlay();
  }

  function jumpToCuePoint() {
    var video = getPrimaryVideo();
    if (!video || !Number.isFinite(cueTime)) {
      return;
    }
    video.currentTime = Math.max(0, cueTime);
  }

  function deactivateCueMode() {
    cueModeActive = false;
    cueTime = null;
    updateCueOverlay();
  }

  function togglePrimaryVideo() {
    var video = getPrimaryVideo();
    if (!video) {
      return;
    }
    if (video.paused) {
      video.play().catch(function () {});
      return;
    }
    video.pause();
  }

  function seekPrimaryVideo(deltaSeconds) {
    var video = getPrimaryVideo();
    if (!video || !Number.isFinite(video.duration)) {
      return;
    }
    var nextTime = Math.max(0, Math.min(video.duration, video.currentTime + deltaSeconds));
    video.currentTime = nextTime;
  }

  function screenTapZone(event) {
    var width = Math.max(1, window.innerWidth || document.documentElement.clientWidth || 1);
    var x = Math.max(0, Math.min(width, event.clientX || 0));
    return screenTapZoneForX(x);
  }

  function screenTapZoneForX(x) {
    var width = Math.max(1, window.innerWidth || document.documentElement.clientWidth || 1);
    x = Math.max(0, Math.min(width, x || 0));
    if (x < width / 3) {
      return "left";
    }
    if (x > (width * 2) / 3) {
      return "right";
    }
    return "middle";
  }

  function stopPlayerTapEvent(event) {
    event.preventDefault();
    event.stopPropagation();
    if (typeof event.stopImmediatePropagation === "function") {
      event.stopImmediatePropagation();
    }
  }

  function runLandscapeTapAction(zone, isMultiTap) {
    if (isMultiTap) {
      if (pendingSingleTapTimer) {
        window.clearTimeout(pendingSingleTapTimer);
        pendingSingleTapTimer = null;
      }
      if (zone === "left") {
        seekPrimaryVideo(-SEEK_STEP_SECONDS);
      } else if (zone === "right") {
        seekPrimaryVideo(SEEK_STEP_SECONDS);
      } else {
        togglePrimaryVideo();
      }
      return;
    }
    if (pendingSingleTapTimer) {
      window.clearTimeout(pendingSingleTapTimer);
    }
    pendingSingleTapTimer = window.setTimeout(function () {
      pendingSingleTapTimer = null;
      if (zone === "middle") {
        togglePrimaryVideo();
      }
    }, TAP_CONFIRM_DELAY_MS);
  }

  function handleLandscapePlayerClick(event) {
    if (!isLandscapeWatch()) {
      return;
    }
    if (event.defaultPrevented || isPlayerControlTarget(event.target)) {
      return;
    }
    stopPlayerTapEvent(event);
    if (Date.now() < suppressPlayerTapUntil) {
      return;
    }
    runLandscapeTapAction(screenTapZone(event), event.detail > 1);
  }

  function handleLandscapePlayerDoubleClick(event) {
    if (!isLandscapeWatch()) {
      return;
    }
    if (!event.defaultPrevented && !isPlayerControlTarget(event.target)) {
      stopPlayerTapEvent(event);
    }
    if (pendingSingleTapTimer) {
      window.clearTimeout(pendingSingleTapTimer);
      pendingSingleTapTimer = null;
    }
  }

  function handleLandscapeCueContextMenu(event) {
    if (!isLandscapeWatch()) {
      return;
    }
    if (event.defaultPrevented || isPlayerControlTarget(event.target)) {
      return;
    }
    stopPlayerTapEvent(event);
    suppressPlayerTapUntil = Date.now() + LONG_TAP_SUPPRESS_MS;
    if (pendingSingleTapTimer) {
      window.clearTimeout(pendingSingleTapTimer);
      pendingSingleTapTimer = null;
    }
    setCuePointFromVideo();
  }

  function handleLandscapePlayerTouchStart(event) {
    if (!isLandscapeWatch() || event.defaultPrevented || isPlayerControlTarget(event.target)) {
      return;
    }
    if (!event.touches || event.touches.length !== 1) {
      return;
    }
    var touch = event.touches[0];
    stopPlayerTapEvent(event);
    activePlayerTouch = {
      x: touch.clientX,
      y: touch.clientY,
      moved: false,
      longPressFired: false,
      timer: window.setTimeout(function () {
        if (!activePlayerTouch || activePlayerTouch.moved) {
          return;
        }
        activePlayerTouch.longPressFired = true;
        suppressPlayerTapUntil = Date.now() + LONG_TAP_SUPPRESS_MS;
        if (pendingSingleTapTimer) {
          window.clearTimeout(pendingSingleTapTimer);
          pendingSingleTapTimer = null;
        }
        setCuePointFromVideo();
      }, LONG_PRESS_TRIGGER_MS)
    };
  }

  function handleLandscapePlayerTouchMove(event) {
    if (!activePlayerTouch) {
      return;
    }
    stopPlayerTapEvent(event);
    if (!event.touches || event.touches.length !== 1) {
      activePlayerTouch.moved = true;
      window.clearTimeout(activePlayerTouch.timer);
      return;
    }
    var touch = event.touches[0];
    var dx = touch.clientX - activePlayerTouch.x;
    var dy = touch.clientY - activePlayerTouch.y;
    if (Math.sqrt(dx * dx + dy * dy) > 18) {
      activePlayerTouch.moved = true;
      window.clearTimeout(activePlayerTouch.timer);
    }
  }

  function handleLandscapePlayerTouchEnd(event) {
    if (!activePlayerTouch) {
      return;
    }
    stopPlayerTapEvent(event);
    var touch = activePlayerTouch;
    window.clearTimeout(touch.timer);
    activePlayerTouch = null;
    suppressPlayerTapUntil = Date.now() + LONG_TAP_SUPPRESS_MS;
    if (touch.longPressFired || touch.moved) {
      return;
    }
    var zone = screenTapZoneForX(touch.x);
    var now = Date.now();
    var isMultiTap = lastTouchTapZone === zone && now - lastTouchTapAt <= TAP_CONFIRM_DELAY_MS;
    runLandscapeTapAction(zone, isMultiTap);
    lastTouchTapAt = isMultiTap ? 0 : now;
    lastTouchTapZone = isMultiTap ? null : zone;
  }

  function handleLandscapePlayerTouchCancel(event) {
    if (!activePlayerTouch) {
      return;
    }
    stopPlayerTapEvent(event);
    window.clearTimeout(activePlayerTouch.timer);
    activePlayerTouch = null;
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

  document.addEventListener("touchstart", handleLandscapePlayerTouchStart, true);
  document.addEventListener("touchmove", handleLandscapePlayerTouchMove, true);
  document.addEventListener("touchend", handleLandscapePlayerTouchEnd, true);
  document.addEventListener("touchcancel", handleLandscapePlayerTouchCancel, true);
  document.addEventListener("click", handleLandscapePlayerClick, true);
  document.addEventListener("click", handleDocumentClick, true);
  document.addEventListener("dblclick", handleLandscapePlayerDoubleClick, true);
  document.addEventListener("contextmenu", handleLandscapeCueContextMenu, true);
  document.addEventListener("contextmenu", handleDocumentContextMenu, true);
  window.addEventListener("scroll", updateScrollTopButton, { passive: true });
  window.addEventListener("load", forceTopAfterLoad, true);
  window.addEventListener("pageshow", forceTopAfterLoad, true);
  window.addEventListener("resize", scheduleLandscapeWatchMode, true);
  window.addEventListener("orientationchange", scheduleLandscapeWatchMode, true);
  window.addEventListener("yt-navigate-finish", function () {
    forceTopAfterLoad();
    scheduleLandscapeWatchMode();
  }, true);
  window.addEventListener("popstate", scheduleLandscapeWatchMode, true);
  scheduleLandscapeWatchMode();
  updateScrollTopButton();
})();
