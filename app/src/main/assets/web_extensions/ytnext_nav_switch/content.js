(function () {
  "use strict";

  var NATIVE_APP = "ytnext_nav_switch";
  var MODE_NAV = "MODE_NAV";
  var OPEN_NEW_TAB = "OPEN_NEW_TAB";
  var LANDSCAPE_STYLE_ID = "ytnext_landscape_watch_style";
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
      "html.ytnext-landscape-watch .ytp-gradient-top,",
      "html.ytnext-landscape-watch .ytp-gradient-bottom,",
      "html.ytnext-landscape-watch .ytp-chrome-top,",
      "html.ytnext-landscape-watch .ytp-chrome-bottom,",
      "html.ytnext-landscape-watch .ytp-player-content,",
      "html.ytnext-landscape-watch .ytp-cards-teaser,",
      "html.ytnext-landscape-watch .ytp-ce-element,",
      "html.ytnext-landscape-watch .ytp-popup,",
      "html.ytnext-landscape-watch .ytp-settings-menu,",
      "html.ytnext-landscape-watch .ytp-panel,",
      "html.ytnext-landscape-watch .ytp-caption-window-container {",
      "  z-index: 2147483647 !important;",
      "  pointer-events: auto !important;",
      "}",
      "html.ytnext-landscape-watch .ytp-chrome-bottom {",
      "  left: 12px !important;",
      "  right: 12px !important;",
      "  bottom: 0 !important;",
      "  width: auto !important;",
      "}",
      "html.ytnext-landscape-watch .ytp-chrome-top {",
      "  left: 12px !important;",
      "  right: 12px !important;",
      "  width: auto !important;",
      "}",
      "html.ytnext-landscape-watch .ytp-fullscreen .ytp-chrome-bottom,",
      "html.ytnext-landscape-watch .ytp-fullscreen .ytp-chrome-top {",
      "  width: auto !important;",
      "}",
      "html.ytnext-landscape-watch .ytnext-cue-overlay {",
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
      "html.ytnext-landscape-watch .ytnext-cue-overlay button {",
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
      "html.ytnext-landscape-watch .ytnext-cue-overlay .ytnext-cue-close {",
      "  min-width: 42px !important;",
      "  padding: 10px 12px !important;",
      "  background: rgba(0, 0, 0, 0.62) !important;",
      "  color: #fff !important;",
      "}",
      "html.ytnext-landscape-watch .ytnext-cue-overlay .ytnext-cue-label {",
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
    if (isLandscapeWatch()) {
      ensureLandscapeWatchStyle();
      document.documentElement.classList.add("ytnext-landscape-watch");
      updateCueOverlay();
      window.scrollTo(0, 0);
      return;
    }
    document.documentElement.classList.remove("ytnext-landscape-watch");
    updateCueOverlay();
    removeLandscapeWatchStyle();
  }

  function scheduleLandscapeWatchMode() {
    window.setTimeout(applyLandscapeWatchMode, 0);
    window.setTimeout(applyLandscapeWatchMode, 150);
    window.setTimeout(applyLandscapeWatchMode, 700);
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
      ".ytnext-cue-overlay"
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
    cueOverlay.className = "ytnext-cue-overlay";

    var cueButton = document.createElement("button");
    cueButton.type = "button";
    cueButton.className = "ytnext-cue-jump";
    cueButton.textContent = "Cue";
    ["click", "pointerup", "touchend"].forEach(function (type) {
      cueButton.addEventListener(type, function (event) {
        handleCueButtonActivation(event, jumpToCuePoint);
      }, true);
    });

    var label = document.createElement("div");
    label.className = "ytnext-cue-label";

    var closeButton = document.createElement("button");
    closeButton.type = "button";
    closeButton.className = "ytnext-cue-close";
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
    var label = overlay.querySelector(".ytnext-cue-label");
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
  window.addEventListener("resize", scheduleLandscapeWatchMode, true);
  window.addEventListener("orientationchange", scheduleLandscapeWatchMode, true);
  window.addEventListener("yt-navigate-finish", scheduleLandscapeWatchMode, true);
  window.addEventListener("popstate", scheduleLandscapeWatchMode, true);
  scheduleLandscapeWatchMode();
})();
