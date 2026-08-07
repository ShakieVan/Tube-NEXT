(function () {
  "use strict";

  var NATIVE_APP = "tubenext_nav_switch";
  var MODE_NAV = "MODE_NAV";
  var OPEN_NEW_TAB = "OPEN_NEW_TAB";
  var SHOW_LINK_MENU = "SHOW_LINK_MENU";
  var LINK_SLIDER_STYLE_ID = "tubenext_link_slider_style";
  var LINK_SLIDER_ID = "tubenext_link_slider";
  var DARK_MODE_STYLE_ID = "tubenext_dark_mode_style";
  var WATCH_FIT_STYLE_ID = "tubenext_watch_fit_style";
  var HOME_FEED_FILTER_STYLE_ID = "tubenext_home_feed_filter_style";
  var WATCH_PAGE_STYLE_ID = "tubenext_watch_page_style";
  var SCROLL_TOP_BUTTON_ID = "tubenext_scroll_top_button";
  var COMMENTS_BUTTON_ID = "tubenext_comments_button";
  var LANDSCAPE_STYLE_ID = "tubenext_landscape_watch_style";
  var HOME_FEED_READY = "HOME_FEED_READY";
  var HOME_FEED_SETTINGS = "HOME_FEED_SETTINGS";
  var VIDEO_TRANSFORM = "VIDEO_TRANSFORM";
  var PAGE_PREVIEW_READY = "PAGE_PREVIEW_READY";
  var PROGRESS_DIAGNOSTICS_SETTINGS = "PROGRESS_DIAGNOSTICS_SETTINGS";
  var PROGRESS_LAYOUT_ANOMALY = "PROGRESS_LAYOUT_ANOMALY";
  var PROGRESS_DIAGNOSTIC_INTERVAL_MS = 2500;
  var PROGRESS_DIAGNOSTIC_COOLDOWN_MS = 5 * 60 * 1000;
  var TAP_CONFIRM_DELAY_MS = 320;
  var LONG_PRESS_TRIGGER_MS = 520;
  var LONG_TAP_SUPPRESS_MS = 650;
  var LINK_HOLD_TRIGGER_MS = 450;
  var LINK_HOLD_MOVE_TOLERANCE_PX = 24;
  var LINK_SLIDER_SELECTION_PX = 56;
  var LINK_ACTIVATION_SUPPRESS_MS = 1200;
  var MENU_TOUCH_THROUGH_GUARD_MS = 1000;
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
  var previewReadyGeneration = 0;
  var nativePort = null;
  var activeLinkHold = null;
  var linkSlider = null;
  var suppressLinkActivationUntil = 0;
  var suppressedLinkActivationUrl = "";
  var menuTouchThroughGuardUntil = 0;
  var progressDiagnosticsEnabled = false;
  var progressDiagnosticTimer = null;
  var progressDiagnosticCandidate = "";
  var progressDiagnosticCandidateCount = 0;
  var progressDiagnosticEpisodeActive = false;
  var lastProgressDiagnosticAt = 0;
  var homeFeedSettings = {
    showShorts: true,
    showCommunityPosts: true,
    showWatchHistory: true,
    hideWatchBranding: false
  };
  var videoTransform = {
    scale: 1,
    translationXFraction: 0,
    translationYFraction: 0
  };

  function applyVideoTransform() {
    if (!document.documentElement) {
      return;
    }
    document.documentElement.style.setProperty(
      "--tubenext-video-scale",
      String(videoTransform.scale)
    );
    document.documentElement.style.setProperty(
      "--tubenext-video-translate-x",
      String(videoTransform.translationXFraction * 100) + "vw"
    );
    document.documentElement.style.setProperty(
      "--tubenext-video-translate-y",
      String(videoTransform.translationYFraction * 100) + "vh"
    );
  }

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
    var enabled = isHomePage() && isHomeFeedFilteringEnabled();
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

  function isHomeFeedFilteringEnabled() {
    return (
      !homeFeedSettings.showShorts ||
      !homeFeedSettings.showCommunityPosts ||
      !homeFeedSettings.showWatchHistory
    );
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

  function updateHomeFeedObserver() {
    if (homeFeedObserver && !isHomeFeedFilteringEnabled()) {
      homeFeedObserver.disconnect();
      homeFeedObserver = null;
      return;
    }
    if (homeFeedObserver || !isHomeFeedFilteringEnabled() || typeof MutationObserver !== "function") {
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
        if (!message) {
          return;
        }
        if (message.type === VIDEO_TRANSFORM) {
          var scale = Number(message.scale);
          var translationXFraction = Number(message.translationXFraction);
          var translationYFraction = Number(message.translationYFraction);
          videoTransform = {
            scale: Number.isFinite(scale) ? Math.max(1, Math.min(3, scale)) : 1,
            translationXFraction: Number.isFinite(translationXFraction)
              ? Math.max(-1, Math.min(1, translationXFraction))
              : 0,
            translationYFraction: Number.isFinite(translationYFraction)
              ? Math.max(-1, Math.min(1, translationYFraction))
              : 0
          };
          applyVideoTransform();
          return;
        }
        if (message.type === PROGRESS_DIAGNOSTICS_SETTINGS) {
          progressDiagnosticsEnabled = message.enabled === true;
          scheduleProgressLayoutDiagnosticCheck();
          return;
        }
        if (message.type !== HOME_FEED_SETTINGS) {
          return;
        }
        homeFeedSettings = {
          showShorts: message.showShorts !== false,
          showCommunityPosts: message.showCommunityPosts !== false,
          showWatchHistory: message.showWatchHistory !== false,
          hideWatchBranding: message.hideWatchBranding === true
        };
        updateHomeFeedObserver();
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

  function commentManagementEndpoint(rawRenderer) {
    var data = rawRenderer && (
      rawRenderer.data ||
      rawRenderer.__data && rawRenderer.__data.data
    );
    var endpoint = data && data.navigationEndpoint;
    if (!endpoint) {
      return null;
    }
    if (endpoint.updateCommentDialogEndpoint ||
        endpoint.updateCommentReplyDialogEndpoint) {
      return endpoint;
    }
    var confirmDialog = endpoint.confirmDialogEndpoint &&
      endpoint.confirmDialogEndpoint.content &&
      endpoint.confirmDialogEndpoint.content.confirmDialogRenderer;
    var confirmButton = confirmDialog && confirmDialog.confirmButton;
    var serviceEndpoint = confirmButton &&
      confirmButton.buttonRenderer &&
      confirmButton.buttonRenderer.serviceEndpoint;
    return serviceEndpoint && serviceEndpoint.performCommentActionEndpoint
      ? endpoint
      : null;
  }

  function handleCommentManagementTap(event) {
    if (typeof event.composedPath !== "function") {
      return;
    }
    var eventPath = event.composedPath();
    var renderer = eventPath.find(function (node) {
      return node &&
        node.tagName === "YTD-MENU-NAVIGATION-ITEM-RENDERER";
    });
    if (!renderer) {
      return;
    }
    var rawRenderer = renderer.wrappedJSObject || renderer;
    if (!commentManagementEndpoint(rawRenderer) ||
        typeof rawRenderer.onEndpointTap_ !== "function") {
      return;
    }
    var dropdown = eventPath.find(function (node) {
      return node && node.tagName === "TP-YT-IRON-DROPDOWN";
    });
    var rawDropdown = dropdown &&
      (dropdown.wrappedJSObject || dropdown);
    try {
      rawRenderer.onEndpointTap_(event.wrappedJSObject || event);
      if (rawDropdown && typeof rawDropdown.close === "function") {
        rawDropdown.close();
      }
    } catch (_) {}
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
  updateHomeFeedObserver();

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
      "  transform: translate(",
      "    var(--tubenext-video-translate-x, 0),",
      "    var(--tubenext-video-translate-y, 0)",
      "  ) scale(var(--tubenext-video-scale, 1)) !important;",
      "  transform-origin: center center !important;",
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
      "html.tubenext-landscape-watch .ytp-contextmenu {",
      "  display: none !important;",
      "  pointer-events: none !important;",
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
      "html.tubenext-landscape-watch .ytp-chapters-container {",
      "  display: flex !important;",
      "  flex-wrap: nowrap !important;",
      "}",
      "html.tubenext-landscape-watch .ytp-chapters-container > .ytp-chapter-hover-container {",
      "  float: none !important;",
      "  flex: 0 0 auto !important;",
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
      applyVideoTransform();
      updateCueOverlay();
      window.scrollTo(0, 0);
      return;
    }
    document.documentElement.classList.remove("tubenext-landscape-watch");
    updateCueOverlay();
    removeLandscapeWatchStyle();
  }

  function scheduleLandscapeWatchMode() {
    previewReadyGeneration += 1;
    var generation = previewReadyGeneration;
    window.setTimeout(applyLandscapeWatchMode, 0);
    window.setTimeout(updateScrollTopButton, 0);
    window.setTimeout(applyLandscapeWatchMode, 150);
    window.setTimeout(updateScrollTopButton, 150);
    window.setTimeout(applyLandscapeWatchMode, 700);
    window.setTimeout(updateScrollTopButton, 700);
    window.setTimeout(applyLandscapeWatchMode, 1500);
    window.setTimeout(function () {
      applyLandscapeWatchMode();
      if (generation === previewReadyGeneration && nativePort) {
        nativePort.postMessage({ type: PAGE_PREVIEW_READY });
      }
    }, 2600);
  }

  function rounded(value) {
    return Math.round(Number(value || 0) * 10) / 10;
  }

  function rectangleSnapshot(rect) {
    return {
      left: rounded(rect.left),
      top: rounded(rect.top),
      right: rounded(rect.right),
      bottom: rounded(rect.bottom),
      width: rounded(rect.width),
      height: rounded(rect.height)
    };
  }

  function elementDiagnosticSnapshot(element) {
    if (!element) {
      return null;
    }
    var style = window.getComputedStyle(element);
    return {
      className: String(element.className || "").slice(0, 180),
      rect: rectangleSnapshot(element.getBoundingClientRect()),
      clientWidth: element.clientWidth,
      clientHeight: element.clientHeight,
      scrollWidth: element.scrollWidth,
      scrollHeight: element.scrollHeight,
      style: {
        display: style.display,
        position: style.position,
        width: style.width,
        height: style.height,
        left: style.left,
        right: style.right,
        top: style.top,
        bottom: style.bottom,
        flexWrap: style.flexWrap,
        overflow: style.overflow,
        boxSizing: style.boxSizing,
        transform: style.transform
      }
    };
  }

  function distinctRows(elements) {
    var rows = [];
    elements.forEach(function (element) {
      var rect = element.getBoundingClientRect();
      if (rect.width <= 1 || rect.height <= 0) {
        return;
      }
      var existing = rows.some(function (top) {
        return Math.abs(top - rect.top) <= 4;
      });
      if (!existing) {
        rows.push(rect.top);
      }
    });
    return rows.sort(function (left, right) { return left - right; });
  }

  function safeVideoId() {
    try {
      var value = new URL(window.location.href).searchParams.get("v") || "";
      return /^[A-Za-z0-9_-]{1,32}$/.test(value) ? value : "";
    } catch (_) {
      return "";
    }
  }

  function inspectProgressLayout() {
    if (!progressDiagnosticsEnabled || !isLandscapeWatch()) {
      return null;
    }
    var player = document.querySelector("#movie_player");
    var progressList = player && player.querySelector(".ytp-progress-list");
    var progressBar = player && player.querySelector(".ytp-progress-bar");
    if (!player || !progressList || !progressBar) {
      return null;
    }
    var barRect = progressBar.getBoundingClientRect();
    if (barRect.width < 100 || barRect.height <= 0) {
      return null;
    }

    var playSegments = Array.prototype.slice.call(
      player.querySelectorAll(".ytp-play-progress")
    );
    var loadSegments = Array.prototype.slice.call(
      player.querySelectorAll(".ytp-load-progress")
    );
    var playRows = distinctRows(playSegments);
    var loadRows = distinctRows(loadSegments);
    var reasons = [];
    if (playRows.length > 1) {
      reasons.push("play-progress-multiple-rows");
    }
    if (loadRows.length > 1) {
      reasons.push("load-progress-multiple-rows");
    }
    if (progressList.scrollHeight > Math.max(progressList.clientHeight + 12, 24)) {
      reasons.push("progress-list-vertical-overflow");
    }
    if (reasons.length === 0) {
      return null;
    }

    var video = player.querySelector("video");
    var visualViewport = window.visualViewport;
    var selectors = [
      "#movie_player",
      ".ytp-chrome-bottom",
      ".ytp-progress-bar-container",
      ".ytp-progress-bar",
      ".ytp-chapters-container",
      ".ytp-chapter-hover-container",
      ".ytp-progress-list"
    ];
    var elements = {};
    selectors.forEach(function (selector) {
      elements[selector] = elementDiagnosticSnapshot(player.matches(selector)
        ? player
        : player.querySelector(selector));
    });
    var segments = playSegments.concat(loadSegments).slice(0, 24).map(function (element) {
      return elementDiagnosticSnapshot(element);
    });
    var capturedAtEpochMs = Date.now();
    return {
      type: PROGRESS_LAYOUT_ANOMALY,
      schemaVersion: 2,
      capturedAtEpochMs: capturedAtEpochMs,
      capturedAtIso8601: new Date(capturedAtEpochMs).toISOString(),
      reason: reasons,
      page: {
        path: String(window.location.pathname || "").slice(0, 120),
        videoId: safeVideoId()
      },
      media: {
        currentTime: rounded(video && video.currentTime),
        duration: rounded(video && video.duration),
        paused: video ? video.paused : null
      },
      viewport: {
        innerWidth: window.innerWidth,
        innerHeight: window.innerHeight,
        devicePixelRatio: rounded(window.devicePixelRatio),
        visualWidth: rounded(visualViewport && visualViewport.width),
        visualHeight: rounded(visualViewport && visualViewport.height),
        visualScale: rounded(visualViewport && visualViewport.scale),
        visualOffsetLeft: rounded(visualViewport && visualViewport.offsetLeft),
        visualOffsetTop: rounded(visualViewport && visualViewport.offsetTop)
      },
      videoTransform: videoTransform,
      playerOverlayVisible: isPlayerOverlayVisible(),
      playerClassName: String(player.className || "").slice(0, 500),
      playProgressRows: playRows.map(rounded),
      loadProgressRows: loadRows.map(rounded),
      elements: elements,
      segments: segments
    };
  }

  function resetProgressDiagnosticEpisode() {
    progressDiagnosticCandidate = "";
    progressDiagnosticCandidateCount = 0;
    progressDiagnosticEpisodeActive = false;
  }

  function runProgressLayoutDiagnosticCheck() {
    var snapshot = inspectProgressLayout();
    if (!snapshot) {
      resetProgressDiagnosticEpisode();
      return;
    }
    var signature = snapshot.page.videoId + ":" + snapshot.reason.join(",");
    if (signature !== progressDiagnosticCandidate) {
      progressDiagnosticCandidate = signature;
      progressDiagnosticCandidateCount = 1;
      return;
    }
    progressDiagnosticCandidateCount += 1;
    if (progressDiagnosticCandidateCount < 2 || progressDiagnosticEpisodeActive) {
      return;
    }
    if (Date.now() - lastProgressDiagnosticAt < PROGRESS_DIAGNOSTIC_COOLDOWN_MS) {
      return;
    }
    if (nativePort) {
      showPlayerOverlay();
      nativePort.postMessage(snapshot);
      progressDiagnosticEpisodeActive = true;
      lastProgressDiagnosticAt = Date.now();
    }
  }

  function scheduleProgressLayoutDiagnosticCheck() {
    if (progressDiagnosticTimer || !progressDiagnosticsEnabled) {
      return;
    }
    progressDiagnosticTimer = window.setTimeout(function () {
      progressDiagnosticTimer = null;
      try {
        runProgressLayoutDiagnosticCheck();
      } finally {
        scheduleProgressLayoutDiagnosticCheck();
      }
    }, PROGRESS_DIAGNOSTIC_INTERVAL_MS);
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

  function isFullscreenPlayerMode() {
    return isLandscapeWatch() ||
      !!document.fullscreenElement ||
      !!(document.documentElement &&
        document.documentElement.classList.contains("tubenext-landscape-watch")) ||
      !!document.querySelector("#movie_player.ytp-fullscreen, .html5-video-player.ytp-fullscreen");
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

  function screenTapZone(point) {
    var viewport = window.visualViewport;
    var width = Math.max(
      1,
      viewport && viewport.width || document.documentElement.clientWidth || window.innerWidth || 1
    );
    var left = viewport && Number.isFinite(viewport.offsetLeft) ? viewport.offsetLeft : 0;
    var x = (point && Number.isFinite(point.clientX) ? point.clientX : 0) - left;
    return screenTapZoneForX(x, width);
  }

  function screenTapZoneForX(x, width) {
    width = Math.max(1, width || window.innerWidth || document.documentElement.clientWidth || 1);
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
    var landscape = isLandscapeWatch();
    var controlTarget = isPlayerControlTarget(event.target);
    if (!landscape) {
      return;
    }
    if (event.defaultPrevented || controlTarget) {
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
    if (!isFullscreenPlayerMode()) {
      return;
    }
    stopPlayerTapEvent(event);
    suppressPlayerTapUntil = Date.now() + LONG_TAP_SUPPRESS_MS;
    if (pendingSingleTapTimer) {
      window.clearTimeout(pendingSingleTapTimer);
      pendingSingleTapTimer = null;
    }
    if (isPlayerControlTarget(event.target)) {
      return;
    }
    setCuePointFromVideo();
  }

  function handleLandscapePlayerTouchStart(event) {
    var landscape = isLandscapeWatch();
    var controlTarget = isPlayerControlTarget(event.target);
    if (!landscape || event.defaultPrevented || controlTarget) {
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
      clientX: touch.clientX,
      clientY: touch.clientY,
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
    var dx = touch.clientX - activePlayerTouch.clientX;
    var dy = touch.clientY - activePlayerTouch.clientY;
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
    var zone = screenTapZone(touch);
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

  function eventPathHasYouTubeMenu(event) {
    if (typeof event.composedPath !== "function") {
      return false;
    }
    return event.composedPath().some(function (node) {
      if (!node || node.nodeType !== 1) {
        return false;
      }
      var tagName = node.tagName || "";
      var role = typeof node.getAttribute === "function"
        ? (node.getAttribute("role") || "").toLowerCase()
        : "";
      return role === "menu" || role === "menuitem" ||
        tagName === "TP-YT-IRON-DROPDOWN" ||
        tagName === "TP-YT-PAPER-LISTBOX" ||
        tagName === "YTD-MENU-POPUP-RENDERER" ||
        tagName === "YTD-MENU-SERVICE-ITEM-RENDERER" ||
        tagName === "YTD-MENU-NAVIGATION-ITEM-RENDERER";
    });
  }

  function armMenuTouchThroughGuard(event) {
    if (!isWatchPage() || !eventPathHasYouTubeMenu(event)) {
      return;
    }
    menuTouchThroughGuardUntil = Date.now() + MENU_TOUCH_THROUGH_GUARD_MS;
  }

  function handleMenuTouchThroughActivation(event) {
    if (Date.now() >= menuTouchThroughGuardUntil ||
        eventPathHasYouTubeMenu(event) ||
        !youtubeLinkUrlForEvent(event)) {
      return;
    }
    menuTouchThroughGuardUntil = 0;
    event.preventDefault();
    event.stopImmediatePropagation();
  }

  function youtubeLinkUrlForEvent(event) {
    var anchor = extractAnchor(event.target);
    if (!anchor || anchor.hasAttribute("download")) {
      return null;
    }
    var href = (anchor.getAttribute("href") || "").trim();
    if (!href || href.charAt(0) === "#" || href.toLowerCase().startsWith("javascript:")) {
      return null;
    }

    var targetUrl;
    try {
      targetUrl = new URL(anchor.href, window.location.href);
    } catch (_) {
      return null;
    }
    if ((targetUrl.protocol !== "http:" && targetUrl.protocol !== "https:") ||
        !isYouTubeHost(targetUrl.hostname)) {
      return null;
    }
    return targetUrl.toString();
  }

  function ensureLinkSliderStyle() {
    if (document.getElementById(LINK_SLIDER_STYLE_ID)) {
      return;
    }
    var style = document.createElement("style");
    style.id = LINK_SLIDER_STYLE_ID;
    style.textContent = [
      "#" + LINK_SLIDER_ID + " {",
      "  position: fixed;",
      "  width: min(292px, calc(100vw - 32px));",
      "  height: 78px;",
      "  z-index: 2147483647;",
      "  transform: translate(-50%, -50%) scale(.92);",
      "  opacity: 0;",
      "  pointer-events: none;",
      "  transition: opacity 120ms ease, transform 180ms cubic-bezier(.2,.9,.2,1);",
      "  filter: drop-shadow(0 10px 24px rgba(0,0,0,.55));",
      "}",
      "#" + LINK_SLIDER_ID + ".tubenext-visible { opacity: 1; transform: translate(-50%, -50%) scale(1); }",
      "#" + LINK_SLIDER_ID + " .tubenext-slider-track {",
      "  position: absolute; inset: 9px 0; border-radius: 34px;",
      "  background: linear-gradient(100deg, rgba(24,24,28,.96), rgba(45,31,31,.97));",
      "  border: 1px solid rgba(255,151,91,.5);",
      "  box-shadow: inset 0 0 0 1px rgba(255,255,255,.04);",
      "}",
      "#" + LINK_SLIDER_ID + " .tubenext-slider-end {",
      "  position: absolute; top: 0; width: 78px; height: 78px; border-radius: 50%;",
      "  display: grid; place-items: center; color: #ffe8da;",
      "  background: radial-gradient(circle at 35% 25%, #ff9f63, #d94b36 62%, #76251f);",
      "  border: 2px solid rgba(255,221,198,.72);",
      "  box-shadow: 0 7px 18px rgba(0,0,0,.5), inset 0 1px 5px rgba(255,255,255,.3);",
      "  transition: transform 110ms ease, filter 110ms ease;",
      "}",
      "#" + LINK_SLIDER_ID + " .tubenext-slider-left { left: 0; }",
      "#" + LINK_SLIDER_ID + " .tubenext-slider-right { right: 0; }",
      "#" + LINK_SLIDER_ID + ".tubenext-select-left .tubenext-slider-left,",
      "#" + LINK_SLIDER_ID + ".tubenext-select-right .tubenext-slider-right {",
      "  transform: scale(1.12); filter: brightness(1.25);",
      "}",
      "#" + LINK_SLIDER_ID + " .tubenext-slider-knob {",
      "  position: absolute; left: 50%; top: 50%; width: 44px; height: 44px;",
      "  margin: -22px 0 0 -22px; border-radius: 50%;",
      "  background: linear-gradient(145deg, #fff1e6, #ff8a50 58%, #d13a31);",
      "  border: 2px solid rgba(255,255,255,.82);",
      "  box-shadow: 0 4px 13px rgba(0,0,0,.5);",
      "  transition: transform 55ms linear;",
      "}",
      "#" + LINK_SLIDER_ID + " .tubenext-slider-chevrons {",
      "  position: absolute; top: 22px; color: #ffb081; font: 700 27px/1 sans-serif;",
      "  letter-spacing: -6px; opacity: .8;",
      "}",
      "#" + LINK_SLIDER_ID + " .tubenext-chevrons-left { left: 81px; animation: tubenext-pulse-left 950ms ease-in-out infinite; }",
      "#" + LINK_SLIDER_ID + " .tubenext-chevrons-right { right: 87px; animation: tubenext-pulse-right 950ms ease-in-out infinite; }",
      "#" + LINK_SLIDER_ID + " .tubenext-menu-icon { width: 29px; display: grid; gap: 5px; }",
      "#" + LINK_SLIDER_ID + " .tubenext-menu-icon i { height: 3px; border-radius: 2px; background: currentColor; }",
      "#" + LINK_SLIDER_ID + " .tubenext-tab-icon { position: relative; width: 29px; height: 25px; border: 3px solid currentColor; border-radius: 4px; }",
      "#" + LINK_SLIDER_ID + " .tubenext-tab-icon::before { content: ''; position: absolute; left: -8px; top: -8px; width: 23px; height: 19px; border: 2px solid currentColor; border-radius: 3px; opacity: .72; }",
      "#" + LINK_SLIDER_ID + " .tubenext-tab-icon::after { content: '+'; position: absolute; right: -7px; bottom: -12px; font: 800 25px/1 sans-serif; text-shadow: 0 1px 3px #7a211d; }",
      "@keyframes tubenext-pulse-left { 0%,100% { transform: translateX(4px); opacity:.35; } 50% { transform: translateX(-5px); opacity:1; } }",
      "@keyframes tubenext-pulse-right { 0%,100% { transform: translateX(-4px); opacity:.35; } 50% { transform: translateX(5px); opacity:1; } }"
    ].join("\n");
    (document.head || document.documentElement).appendChild(style);
  }

  function ensureLinkSlider() {
    if (linkSlider && linkSlider.isConnected) {
      return linkSlider;
    }
    ensureLinkSliderStyle();
    linkSlider = document.createElement("div");
    linkSlider.id = LINK_SLIDER_ID;
    linkSlider.setAttribute("aria-hidden", "true");
    linkSlider.innerHTML = [
      "<div class='tubenext-slider-track'></div>",
      "<div class='tubenext-slider-end tubenext-slider-left'><span class='tubenext-menu-icon'><i></i><i></i><i></i></span></div>",
      "<div class='tubenext-slider-chevrons tubenext-chevrons-left'>&lsaquo;&lsaquo;</div>",
      "<div class='tubenext-slider-knob'></div>",
      "<div class='tubenext-slider-chevrons tubenext-chevrons-right'>&rsaquo;&rsaquo;</div>",
      "<div class='tubenext-slider-end tubenext-slider-right'><span class='tubenext-tab-icon'></span></div>"
    ].join("");
    (document.body || document.documentElement).appendChild(linkSlider);
    return linkSlider;
  }

  function hideLinkSlider() {
    if (!linkSlider) {
      return;
    }
    linkSlider.classList.remove("tubenext-visible", "tubenext-select-left", "tubenext-select-right");
    var knob = linkSlider.querySelector(".tubenext-slider-knob");
    if (knob) {
      knob.style.transform = "translateX(0)";
    }
  }

  function clearActiveLinkHold() {
    if (activeLinkHold && activeLinkHold.timer) {
      window.clearTimeout(activeLinkHold.timer);
    }
    activeLinkHold = null;
    hideLinkSlider();
  }

  function suppressFollowUpLinkActivation(targetUrl) {
    suppressLinkActivationUntil = Date.now() + LINK_ACTIVATION_SUPPRESS_MS;
    suppressedLinkActivationUrl = targetUrl;
  }

  function activateLinkHold(hold) {
    if (activeLinkHold !== hold || hold.cancelled || hold.activated) {
      return;
    }
    hold.activated = true;
    hold.timer = null;
    suppressFollowUpLinkActivation(hold.targetUrl);
    var slider = ensureLinkSlider();
    var halfWidth = Math.min(146, Math.max(100, (window.innerWidth - 32) / 2));
    var centerX = Math.max(halfWidth + 8, Math.min(window.innerWidth - halfWidth - 8, hold.x));
    var centerY = Math.max(70, Math.min(window.innerHeight - 70, hold.y - 96));
    slider.style.left = centerX + "px";
    slider.style.top = centerY + "px";
    slider.classList.add("tubenext-visible");
    updateLinkHoldSelection(hold.lastX);
  }

  function updateLinkHoldSelection(currentX) {
    var hold = activeLinkHold;
    if (!hold || !hold.activated) {
      return;
    }
    hold.lastX = currentX;
    var deltaX = currentX - hold.x;
    hold.selection = deltaX <= -LINK_SLIDER_SELECTION_PX ? "left" :
      (deltaX >= LINK_SLIDER_SELECTION_PX ? "right" : null);
    var slider = ensureLinkSlider();
    slider.classList.toggle("tubenext-select-left", hold.selection === "left");
    slider.classList.toggle("tubenext-select-right", hold.selection === "right");
    var knob = slider.querySelector(".tubenext-slider-knob");
    if (knob) {
      var knobOffset = Math.max(-92, Math.min(92, deltaX));
      knob.style.transform = "translateX(" + knobOffset + "px)";
    }
  }

  function beginLinkHold(event, inputType, identifier, x, y) {
    clearActiveLinkHold();
    suppressLinkActivationUntil = 0;
    suppressedLinkActivationUrl = "";
    var targetUrl = youtubeLinkUrlForEvent(event);
    if (!targetUrl) {
      return;
    }
    var hold = {
      inputType: inputType,
      identifier: identifier,
      x: x,
      y: y,
      lastX: x,
      lastY: y,
      targetUrl: targetUrl,
      activated: false,
      cancelled: false,
      selection: null,
      timer: null
    };
    hold.timer = window.setTimeout(function () {
      activateLinkHold(hold);
    }, LINK_HOLD_TRIGGER_MS);
    activeLinkHold = hold;
  }

  function moveLinkHold(event, inputType, identifier, x, y) {
    var hold = activeLinkHold;
    if (!hold || hold.inputType !== inputType || hold.identifier !== identifier) {
      return;
    }
    hold.lastX = x;
    hold.lastY = y;
    if (!hold.activated) {
      if (Math.abs(x - hold.x) > LINK_HOLD_MOVE_TOLERANCE_PX ||
          Math.abs(y - hold.y) > LINK_HOLD_MOVE_TOLERANCE_PX) {
        hold.cancelled = true;
        clearActiveLinkHold();
      }
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
    updateLinkHoldSelection(x);
  }

  function finishLinkHold(event, inputType, identifier) {
    var hold = activeLinkHold;
    if (!hold || hold.inputType !== inputType || hold.identifier !== identifier) {
      return;
    }
    if (!hold.activated) {
      clearActiveLinkHold();
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
    var selection = hold.selection;
    var targetUrl = hold.targetUrl;
    suppressFollowUpLinkActivation(targetUrl);
    clearActiveLinkHold();
    if (selection === "left") {
      browser.runtime.sendNativeMessage(NATIVE_APP, {
        type: SHOW_LINK_MENU,
        url: targetUrl
      }).catch(function () {});
    } else if (selection === "right") {
      browser.runtime.sendNativeMessage(NATIVE_APP, {
        type: OPEN_NEW_TAB,
        url: targetUrl
      }).catch(function () {});
    }
  }

  function touchByIdentifier(touchList, identifier) {
    if (!touchList) {
      return null;
    }
    for (var i = 0; i < touchList.length; i += 1) {
      if (touchList[i].identifier === identifier) {
        return touchList[i];
      }
    }
    return null;
  }

  function handleLinkTouchStart(event) {
    if (!event.touches || event.touches.length !== 1) {
      clearActiveLinkHold();
      return;
    }
    var touch = event.touches[0];
    beginLinkHold(event, "touch", touch.identifier, touch.clientX, touch.clientY);
  }

  function handleLinkTouchMove(event) {
    var hold = activeLinkHold;
    if (!hold || hold.inputType !== "touch") {
      return;
    }
    var touch = touchByIdentifier(event.touches, hold.identifier);
    if (!touch) {
      clearActiveLinkHold();
      return;
    }
    moveLinkHold(event, "touch", hold.identifier, touch.clientX, touch.clientY);
  }

  function handleLinkTouchEnd(event) {
    var hold = activeLinkHold;
    if (!hold || hold.inputType !== "touch") {
      return;
    }
    if (touchByIdentifier(event.changedTouches, hold.identifier)) {
      finishLinkHold(event, "touch", hold.identifier);
    }
  }

  function handleLinkPointerDown(event) {
    if (event.pointerType === "touch" || event.button !== 0 || event.isPrimary === false) {
      return;
    }
    beginLinkHold(event, "pointer", event.pointerId, event.clientX, event.clientY);
  }

  function handleLinkPointerMove(event) {
    if (event.pointerType !== "touch") {
      moveLinkHold(event, "pointer", event.pointerId, event.clientX, event.clientY);
    }
  }

  function handleLinkPointerUp(event) {
    if (event.pointerType !== "touch") {
      finishLinkHold(event, "pointer", event.pointerId);
    }
  }

  function handleLinkPointerCancel(event) {
    if (event.pointerType !== "touch" && activeLinkHold &&
        activeLinkHold.inputType === "pointer" &&
        activeLinkHold.identifier === event.pointerId) {
      clearActiveLinkHold();
    }
  }

  function handleSuppressedLinkActivation(event) {
    if (Date.now() >= suppressLinkActivationUntil) {
      return;
    }
    var targetUrl = youtubeLinkUrlForEvent(event);
    if (targetUrl !== suppressedLinkActivationUrl) {
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
  }

  function handleDocumentContextMenu(event) {
    if (event.defaultPrevented) {
      return;
    }
    var targetUrl = youtubeLinkUrlForEvent(event);
    if (!targetUrl) return;

    event.preventDefault();
    event.stopImmediatePropagation();

    if (activeLinkHold && activeLinkHold.targetUrl === targetUrl) {
      activateLinkHold(activeLinkHold);
      return;
    }

    // Gecko normally delivered touchstart first. Keep the proven direct path
    // as a fallback for devices that expose only contextmenu for a long press.
    suppressFollowUpLinkActivation(targetUrl);
    browser.runtime.sendNativeMessage(NATIVE_APP, {
      type: OPEN_NEW_TAB,
      url: targetUrl
    }).catch(function () {});
  }

  document.addEventListener("touchstart", armMenuTouchThroughGuard, true);
  document.addEventListener("touchend", armMenuTouchThroughGuard, true);
  document.addEventListener("pointerdown", function (event) {
    if (event.pointerType === "touch") {
      armMenuTouchThroughGuard(event);
    }
  }, true);
  document.addEventListener("tap", handleMenuTouchThroughActivation, true);
  document.addEventListener("click", handleMenuTouchThroughActivation, true);
  document.addEventListener("touchstart", handleLandscapePlayerTouchStart, true);
  document.addEventListener("touchstart", handleLinkTouchStart, { capture: true, passive: true });
  document.addEventListener("touchmove", handleLinkTouchMove, { capture: true, passive: false });
  document.addEventListener("touchend", handleLinkTouchEnd, { capture: true, passive: false });
  document.addEventListener("touchcancel", clearActiveLinkHold, true);
  document.addEventListener("pointerdown", handleLinkPointerDown, true);
  document.addEventListener("pointermove", handleLinkPointerMove, true);
  document.addEventListener("pointerup", handleLinkPointerUp, true);
  document.addEventListener("pointercancel", handleLinkPointerCancel, true);
  document.addEventListener("tap", handleSuppressedLinkActivation, true);
  document.addEventListener("click", handleSuppressedLinkActivation, true);
  document.addEventListener("touchmove", handleLandscapePlayerTouchMove, true);
  document.addEventListener("touchend", handleLandscapePlayerTouchEnd, true);
  document.addEventListener("touchcancel", handleLandscapePlayerTouchCancel, true);
  document.addEventListener("click", handleLandscapePlayerClick, true);
  document.addEventListener("tap", handleCommentManagementTap, true);
  document.addEventListener("click", handleDocumentClick, true);
  document.addEventListener("dblclick", handleLandscapePlayerDoubleClick, true);
  window.addEventListener("contextmenu", handleLandscapeCueContextMenu, true);
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
    updateHomeFeedObserver();
    scheduleHomeFeedFilters();
    scheduleWatchPageTweaksBurst();
  }, true);
  window.addEventListener("popstate", function () {
    scheduleLandscapeWatchMode();
    updateHomeFeedObserver();
    scheduleHomeFeedFilters();
    scheduleWatchPageTweaksBurst();
  }, true);
  scheduleLandscapeWatchMode();
  updateHomeFeedObserver();
  scheduleHomeFeedFilters();
  scheduleWatchPageTweaksBurst();
  updateScrollTopButton();
})();
