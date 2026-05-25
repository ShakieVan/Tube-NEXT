(function () {
  "use strict";

  var NATIVE_APP = "tubenext_nav_switch";
  var MODE_NAV = "MODE_NAV";
  var OPEN_NEW_TAB = "OPEN_NEW_TAB";
  var DARK_MODE_STYLE_ID = "tubenext_dark_mode_style";
  var WATCH_FIT_STYLE_ID = "tubenext_watch_fit_style";
  var HOME_FEED_FILTER_STYLE_ID = "tubenext_home_feed_filter_style";
  var WATCH_PAGE_STYLE_ID = "tubenext_watch_page_style";
  var SCROLL_TOP_BUTTON_ID = "tubenext_scroll_top_button";
  var COMMENTS_BUTTON_ID = "tubenext_comments_button";
  var LANDSCAPE_STYLE_ID = "tubenext_landscape_watch_style";
  var HOME_FEED_READY = "HOME_FEED_READY";
  var HOME_FEED_SETTINGS = "HOME_FEED_SETTINGS";
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
  var commentsButton = null;
  var commentsScrollTimer = null;
  var suppressPlayerTapUntil = 0;
  var homeFeedFilterTimer = null;
  var homeFeedObserver = null;
  var watchPageTimer = null;
  var nativePort = null;
  var homeFeedSettings = {
    showShorts: true,
    showCommunityPosts: true,
    showWatchHistory: true,
    hideWatchBranding: false
  };

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

  function isHomePage() {
    if (!isYouTubeHost(window.location.hostname)) {
      return false;
    }
    var path = window.location.pathname || "/";
    return path === "/" || path === "";
  }

  function ensureHomeFeedFilterStyle() {
    if (document.getElementById(HOME_FEED_FILTER_STYLE_ID)) {
      return;
    }
    var style = document.createElement("style");
    style.id = HOME_FEED_FILTER_STYLE_ID;
    style.textContent = [
      "html.tubenext-home-feed-filter .tubenext-home-feed-hidden {",
      "  display: none !important;",
      "}"
    ].join("\n");
    (document.documentElement || document.head || document.body).appendChild(style);
  }

  function textOf(node) {
    return ((node && node.textContent) || "").replace(/\s+/g, " ").trim().toLowerCase();
  }

  function hasAnyText(node, words) {
    var text = textOf(node);
    return words.some(function (word) {
      return text.indexOf(word) >= 0;
    });
  }

  function nearestHomeFeedCard(node) {
    if (!node || typeof node.closest !== "function") {
      return null;
    }
    return node.closest([
      "ytd-rich-section-renderer",
      "ytd-rich-item-renderer",
      "ytd-shelf-renderer",
      "ytd-reel-shelf-renderer",
      "ytd-backstage-post-thread-renderer",
      "ytd-post-renderer",
      "ytm-rich-section-renderer",
      "ytm-rich-item-renderer",
      "ytm-item-section-renderer",
      "ytm-reel-shelf-renderer",
      "ytm-shorts-shelf-renderer",
      "ytm-backstage-post-thread-renderer",
      "ytm-post-renderer",
      "ytm-community-post-renderer"
    ].join(","));
  }

  function isShortsCard(card) {
    if (!card || homeFeedSettings.showShorts) {
      return false;
    }
    if (card.matches && card.matches([
      "ytd-reel-shelf-renderer",
      "ytm-reel-shelf-renderer",
      "ytm-shorts-shelf-renderer"
    ].join(","))) {
      return true;
    }
    if (card.querySelector("a[href*='/shorts/'], a[href^='/shorts/']")) {
      return true;
    }
    return card.matches &&
      card.matches([
        "ytd-rich-section-renderer",
        "ytd-shelf-renderer",
        "ytm-rich-section-renderer",
        "ytm-item-section-renderer"
      ].join(",")) &&
      hasAnyText(card, ["shorts"]);
  }

  function isCommunityCard(card) {
    if (!card || homeFeedSettings.showCommunityPosts) {
      return false;
    }
    if (card.matches && card.matches([
      "ytd-backstage-post-thread-renderer",
      "ytd-post-renderer",
      "ytm-backstage-post-thread-renderer",
      "ytm-post-renderer",
      "ytm-community-post-renderer"
    ].join(","))) {
      return true;
    }
    if (card.querySelector([
      "ytd-backstage-post-thread-renderer",
      "ytd-post-renderer",
      "ytm-backstage-post-thread-renderer",
      "ytm-post-renderer",
      "ytm-community-post-renderer"
    ].join(","))) {
      return true;
    }
    return card.matches &&
      card.matches([
        "ytd-rich-section-renderer",
        "ytd-shelf-renderer",
        "ytm-rich-section-renderer",
        "ytm-rich-item-renderer",
        "ytm-item-section-renderer"
      ].join(",")) &&
      hasAnyText(card, ["community", "community-post", "community post", "community-beitrag"]);
  }

  function isWatchHistoryCard(card) {
    if (!card || homeFeedSettings.showWatchHistory) {
      return false;
    }
    return card.matches &&
      card.matches([
        "ytd-rich-section-renderer",
        "ytd-shelf-renderer",
        "ytm-rich-section-renderer",
        "ytm-item-section-renderer"
      ].join(",")) &&
      hasAnyText(card, [
      "zuletzt gesehen",
      "zuletzt angesehen",
      "noch einmal ansehen",
      "weiterschauen",
      "recently watched",
      "watch again",
      "continue watching"
    ]);
  }

  function updateHomeFeedCard(card) {
    if (!card || !card.classList) {
      return;
    }
    var hidden = isHomePage() && (
      isShortsCard(card) ||
      isCommunityCard(card) ||
      isWatchHistoryCard(card)
    );
    card.classList.toggle("tubenext-home-feed-hidden", hidden);
  }

  function applyHomeFeedFilters() {
    if (!document.documentElement) {
      return;
    }
    ensureHomeFeedFilterStyle();
    var enabled = isHomePage() && (
      !homeFeedSettings.showShorts ||
      !homeFeedSettings.showCommunityPosts ||
      !homeFeedSettings.showWatchHistory
    );
    document.documentElement.classList.toggle("tubenext-home-feed-filter", enabled);
    document.querySelectorAll(".tubenext-home-feed-hidden").forEach(function (node) {
      if (!enabled) {
        node.classList.remove("tubenext-home-feed-hidden");
      }
    });
    if (!enabled) {
      return;
    }
    document.querySelectorAll([
      "ytd-rich-section-renderer",
      "ytd-rich-item-renderer",
      "ytd-shelf-renderer",
      "ytd-reel-shelf-renderer",
      "ytd-backstage-post-thread-renderer",
      "ytd-post-renderer",
      "ytm-rich-section-renderer",
      "ytm-rich-item-renderer",
      "ytm-item-section-renderer",
      "ytm-reel-shelf-renderer",
      "ytm-shorts-shelf-renderer",
      "ytm-backstage-post-thread-renderer",
      "ytm-post-renderer",
      "ytm-community-post-renderer"
    ].join(",")).forEach(updateHomeFeedCard);
  }

  function scheduleHomeFeedFilters() {
    if (homeFeedFilterTimer) {
      window.clearTimeout(homeFeedFilterTimer);
    }
    homeFeedFilterTimer = window.setTimeout(function () {
      homeFeedFilterTimer = null;
      applyHomeFeedFilters();
    }, 120);
  }

  function ensureWatchPageStyle() {
    if (document.getElementById(WATCH_PAGE_STYLE_ID)) {
      return;
    }
    var style = document.createElement("style");
    style.id = WATCH_PAGE_STYLE_ID;
    style.textContent = [
      "html.tubenext-hide-watch-branding .annotation.annotation-type-custom.iv-branding,",
      "html.tubenext-hide-watch-branding .ytp-ce-channel,",
      "html.tubenext-hide-watch-branding .ytp-watermark,",
      "html.tubenext-hide-watch-branding .branding-img-container {",
      "  display: none !important;",
      "  pointer-events: none !important;",
      "}"
    ].join("\n");
    (document.documentElement || document.head || document.body).appendChild(style);
  }

  function applyWatchPageTweaks() {
    if (!document.documentElement) {
      return;
    }
    ensureWatchPageStyle();
    var watch = isWatchPage();
    document.documentElement.classList.toggle(
      "tubenext-hide-watch-branding",
      watch && homeFeedSettings.hideWatchBranding
    );
  }

  function scheduleWatchPageTweaks() {
    if (watchPageTimer) {
      window.clearTimeout(watchPageTimer);
    }
    watchPageTimer = window.setTimeout(function () {
      watchPageTimer = null;
      applyWatchPageTweaks();
    }, 160);
  }

  function scheduleWatchPageTweaksBurst() {
    scheduleWatchPageTweaks();
    window.setTimeout(scheduleWatchPageTweaks, 700);
  }

  function ensureHomeFeedObserver() {
    if (homeFeedObserver || typeof MutationObserver !== "function") {
      return;
    }
    homeFeedObserver = new MutationObserver(function (mutations) {
      if (!isHomePage()) {
        scheduleHomeFeedFilters();
        return;
      }
      var shouldScan = mutations.some(function (mutation) {
        return mutation.addedNodes && mutation.addedNodes.length > 0;
      });
      if (shouldScan) {
        scheduleHomeFeedFilters();
      }
    });
    homeFeedObserver.observe(document.documentElement || document, {
      childList: true,
      subtree: true
    });
  }

  function connectNativeSettingsPort() {
    if (nativePort ||
        typeof browser === "undefined" ||
        !browser.runtime ||
        !browser.runtime.connectNative) {
      return;
    }
    try {
      nativePort = browser.runtime.connectNative(NATIVE_APP);
      nativePort.onMessage.addListener(function (message) {
        if (!message || message.type !== HOME_FEED_SETTINGS) {
          return;
        }
        homeFeedSettings = {
          showShorts: message.showShorts !== false,
          showCommunityPosts: message.showCommunityPosts !== false,
          showWatchHistory: message.showWatchHistory !== false,
          hideWatchBranding: message.hideWatchBranding === true
        };
        scheduleHomeFeedFilters();
        scheduleWatchPageTweaksBurst();
      });
      nativePort.onDisconnect.addListener(function () {
        nativePort = null;
      });
      nativePort.postMessage({ type: HOME_FEED_READY });
    } catch (_) {
      nativePort = null;
    }
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
  connectNativeSettingsPort();
  ensureHomeFeedObserver();

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
      "#" + SCROLL_TOP_BUTTON_ID + ",",
      "#" + COMMENTS_BUTTON_ID + " {",
      "  position: fixed !important;",
      "  right: 16px !important;",
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
      "  display: flex !important;",
      "  align-items: center !important;",
      "  justify-content: center !important;",
      "  padding: 0 !important;",
      "  opacity: 0 !important;",
      "  pointer-events: none !important;",
      "  touch-action: manipulation !important;",
      "  transition: opacity 160ms ease !important;",
      "}",
      "#" + SCROLL_TOP_BUTTON_ID + " {",
      "  bottom: 88px !important;",
      "}",
      "#" + COMMENTS_BUTTON_ID + " {",
      "  bottom: 32px !important;",
      "}",
      "#" + COMMENTS_BUTTON_ID + " svg {",
      "  width: 24px !important;",
      "  height: 24px !important;",
      "  fill: none !important;",
      "  stroke: currentColor !important;",
      "  stroke-width: 2.4 !important;",
      "  stroke-linecap: round !important;",
      "  stroke-linejoin: round !important;",
      "  pointer-events: none !important;",
      "}",
      "html.tubenext-show-scroll-top:not(.tubenext-landscape-watch) #" + SCROLL_TOP_BUTTON_ID + " {",
      "  opacity: 1 !important;",
      "  pointer-events: auto !important;",
      "}",
      "html.tubenext-show-comments-button:not(.tubenext-landscape-watch) #" + COMMENTS_BUTTON_ID + " {",
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
    window.setTimeout(updateScrollTopButton, 0);
    window.setTimeout(applyLandscapeWatchMode, 150);
    window.setTimeout(updateScrollTopButton, 150);
    window.setTimeout(applyLandscapeWatchMode, 700);
    window.setTimeout(updateScrollTopButton, 700);
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

  function clearCommentsScrollTimer() {
    if (commentsScrollTimer) {
      window.clearInterval(commentsScrollTimer);
      commentsScrollTimer = null;
    }
  }

  function isUsableCommentsTarget(element) {
    if (!element || !document.documentElement.contains(element)) {
      return false;
    }
    var rect = element.getBoundingClientRect();
    if (rect.width > 1 || rect.height > 1) {
      return true;
    }
    return !!element.querySelector([
      "ytd-comments-header-renderer",
      "ytd-comment-thread-renderer",
      "ytd-comments",
      "#header",
      "#sections"
    ].join(","));
  }

  function findCommentsTarget() {
    var selectors = [
      "#comments",
      "ytd-comments",
      "ytd-comments-header-renderer",
      "ytd-engagement-panel-section-list-renderer[target-id='engagement-panel-comments-section']"
    ];
    for (var i = 0; i < selectors.length; i += 1) {
      var target = document.querySelector(selectors[i]);
      if (isUsableCommentsTarget(target)) {
        return target;
      }
    }
    return null;
  }

  function findCommentsFallbackTarget() {
    return findCommentsTarget() || document.querySelector([
      "ytd-watch-flexy #below",
      "#below",
      "ytd-watch-flexy #primary-inner",
      "#primary-inner"
    ].join(","));
  }

  function isCommentsTargetInView() {
    var target = findCommentsTarget();
    if (!target || typeof target.getBoundingClientRect !== "function") {
      return false;
    }
    var rect = target.getBoundingClientRect();
    return rect.top < window.innerHeight * 0.9 && rect.bottom > 72;
  }

  function scrollToCommentsTarget(target, behavior) {
    if (!target || typeof target.getBoundingClientRect !== "function") {
      return;
    }
    var top = Math.max(0, window.scrollY + target.getBoundingClientRect().top - 8);
    window.scrollTo({ top: top, left: 0, behavior: behavior || "smooth" });
    window.setTimeout(function () {
      if (!document.documentElement.contains(target)) {
        return;
      }
      var adjustedTop = Math.max(0, window.scrollY + target.getBoundingClientRect().top - 8);
      if (Math.abs(adjustedTop - window.scrollY) > 24) {
        window.scrollTo({ top: adjustedTop, left: 0, behavior: "smooth" });
      }
    }, 260);
  }

  function scrollDownTowardComments() {
    var body = document.body || document.documentElement;
    var root = document.documentElement || body;
    var maxScrollY = Math.max(
      body.scrollHeight || 0,
      root.scrollHeight || 0,
      body.offsetHeight || 0,
      root.offsetHeight || 0
    ) - window.innerHeight;
    var currentY = window.scrollY || window.pageYOffset || 0;
    var nextY = Math.min(maxScrollY, currentY + Math.max(360, Math.floor(window.innerHeight * 0.78)));
    if (nextY <= currentY + 4) {
      return false;
    }
    window.scrollTo({ top: nextY, left: 0, behavior: "smooth" });
    return true;
  }

  function scrollToComments() {
    if (!isWatchPage() || isLandscapeWatch()) {
      return;
    }
    clearCommentsScrollTimer();
    var target = findCommentsTarget();
    if (target) {
      scrollToCommentsTarget(target, "smooth");
      return;
    }

    var startedAt = Date.now();
    var stalledTicks = 0;
    var tick = function () {
      if (!isWatchPage() || isLandscapeWatch()) {
        clearCommentsScrollTimer();
        return;
      }
      target = findCommentsTarget();
      if (target) {
        clearCommentsScrollTimer();
        scrollToCommentsTarget(target, "smooth");
        return;
      }
      if (Date.now() - startedAt >= 8500) {
        clearCommentsScrollTimer();
        scrollToCommentsTarget(findCommentsFallbackTarget(), "smooth");
        return;
      }
      if (!scrollDownTowardComments()) {
        stalledTicks += 1;
        if (stalledTicks >= 2) {
          clearCommentsScrollTimer();
          scrollToCommentsTarget(findCommentsFallbackTarget(), "smooth");
        }
      } else {
        stalledTicks = 0;
      }
    };

    tick();
    commentsScrollTimer = window.setInterval(tick, 480);
  }

  function ensureCommentsButton() {
    if (commentsButton && document.documentElement.contains(commentsButton)) {
      return commentsButton;
    }
    commentsButton = document.createElement("button");
    commentsButton.id = COMMENTS_BUTTON_ID;
    commentsButton.type = "button";
    commentsButton.setAttribute("aria-label", "Kommentare");
    commentsButton.innerHTML = [
      "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\" focusable=\"false\">",
      "<path d=\"M5 5.5h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-7l-5 3.5v-3.5H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2Z\"></path>",
      "</svg>"
    ].join("");
    ["click", "pointerup", "touchend"].forEach(function (type) {
      commentsButton.addEventListener(type, function (event) {
        stopGenericOverlayEvent(event, true);
        scrollToComments();
      }, true);
    });
    ["pointerdown", "touchstart", "mousedown", "contextmenu"].forEach(function (type) {
      commentsButton.addEventListener(type, function (event) {
        stopGenericOverlayEvent(event, true);
      }, true);
    });
    (document.body || document.documentElement).appendChild(commentsButton);
    return commentsButton;
  }

  function updateScrollTopButton() {
    if (!document.documentElement) {
      return;
    }
    ensureWatchFitStyle();
    ensureScrollTopButton();
    ensureCommentsButton();
    var isWatch = isWatchPage();
    var isLandscape = isLandscapeWatch();
    var showScrollTop = !isLandscape && window.scrollY > 480;
    var showComments = isWatch && !isLandscape && !isCommentsTargetInView();
    document.documentElement.classList.toggle("tubenext-show-scroll-top", showScrollTop);
    document.documentElement.classList.toggle("tubenext-show-comments-button", showComments);
    if (isLandscape || !isWatch) {
      clearCommentsScrollTimer();
    }
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

  function getMoviePlayer() {
    return document.querySelector("#movie_player, .html5-video-player");
  }

  function isPlayerOverlayVisible() {
    var player = getMoviePlayer();
    if (!player) {
      return true;
    }
    if (player.classList.contains("ytp-autohide")) {
      return false;
    }
    var chrome = player.querySelector(".ytp-chrome-bottom");
    if (!chrome) {
      return true;
    }
    var style = window.getComputedStyle(chrome);
    return style.display !== "none" &&
      style.visibility !== "hidden" &&
      Number(style.opacity || "1") > 0.05;
  }

  function showPlayerOverlay() {
    var player = getMoviePlayer();
    if (!player) {
      return;
    }
    if (typeof player.showControls === "function") {
      try {
        player.showControls();
      } catch (_) {}
    }
    ["mousemove", "mouseover"].forEach(function (type) {
      try {
        player.dispatchEvent(new MouseEvent(type, {
          bubbles: true,
          cancelable: true,
          view: window
        }));
      } catch (_) {}
    });
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
    showPlayerOverlay();
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
    if (!isPlayerOverlayVisible()) {
      showPlayerOverlay();
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
    if (!isPlayerOverlayVisible()) {
      showPlayerOverlay();
      suppressPlayerTapUntil = Date.now() + TAP_CONFIRM_DELAY_MS;
      lastTouchTapAt = 0;
      lastTouchTapZone = null;
      return;
    }
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
    if (!isPlayerOverlayVisible()) {
      showPlayerOverlay();
      lastTouchTapAt = 0;
      lastTouchTapZone = null;
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
    scheduleHomeFeedFilters();
    scheduleWatchPageTweaksBurst();
  }, true);
  window.addEventListener("popstate", function () {
    scheduleLandscapeWatchMode();
    scheduleHomeFeedFilters();
    scheduleWatchPageTweaksBurst();
  }, true);
  scheduleLandscapeWatchMode();
  scheduleHomeFeedFilters();
  scheduleWatchPageTweaksBurst();
  updateScrollTopButton();
})();
