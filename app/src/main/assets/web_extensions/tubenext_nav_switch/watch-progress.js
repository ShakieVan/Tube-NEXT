(function () {
  "use strict";

  // Observe the official main player only. No polling, seeking or URL mutations.
  var bindings = new WeakMap();
  var navigating = false;
  var pendingMetadata = null;
  var lastSentAt = -Infinity;
  var lastSentKey = "";

  function videoId() {
    try {
      var url = new URL(window.location.href);
      if (!/^(www\.|m\.)?youtube\.com$/.test(url.hostname) || url.pathname !== "/watch") {
        return "";
      }
      var id = url.searchParams.get("v") || "";
      var watch = document.querySelector("ytd-watch-flexy[video-id]");
      return /^[A-Za-z0-9_-]{11}$/.test(id) && watch && watch.getAttribute("video-id") === id
        ? id : "";
    } catch (_) {
      return "";
    }
  }

  function mainVideo() {
    return document.querySelector("#movie_player video.html5-main-video");
  }

  function bind(video) {
    var id = videoId();
    if (!video || !id || video.readyState < 1) return;
    bindings.set(video, { videoId: id, source: video.currentSrc, checkpointed: false });
    pendingMetadata = null;
    lastSentKey = "";
    lastSentAt = -Infinity;
  }

  function checkpoint(force) {
    if (navigating) return;
    var video = mainVideo();
    var player = document.querySelector("#movie_player");
    var id = videoId();
    if (!video || !player || !id) return;
    // The watch renderer can receive its video-id after the media metadata event.
    if (pendingMetadata && pendingMetadata.video === video && pendingMetadata.source === video.currentSrc) {
      bind(video);
    }
    var binding = bindings.get(video);
    if (!binding || binding.videoId !== id || binding.source !== video.currentSrc) return;
    // Ads and live/DVR have a different time origin from an on-demand watch URL.
    if (player.classList.contains("ad-showing") || player.classList.contains("ad-interrupting") ||
        player.classList.contains("ytp-live") || video.readyState < 2 || video.seeking ||
        !Number.isFinite(video.currentTime) || !Number.isFinite(video.duration) ||
        video.duration <= 0 || video.currentTime < 0 || video.currentTime > video.duration) return;
    // Initial zero-valued events must not erase a checkpoint before YouTube seeks to t=.
    if (!video.ended && video.currentTime === 0 && !binding.checkpointed && !video.played.length) return;
    var seconds = video.ended ? 0 : Math.floor(video.currentTime);
    var key = id + ":" + seconds;
    var now = Date.now();
    if (key === lastSentKey || (!force && now - lastSentAt < 5000)) return;
    try {
      var sending = browser.runtime.sendNativeMessage("tubenext_nav_switch", {
        type: "WATCH_PROGRESS",
        url: window.location.href,
        position: video.currentTime,
        duration: video.duration,
        ended: video.ended
      });
      lastSentKey = key;
      lastSentAt = now;
      binding.checkpointed = true;
      sending.catch(function () {
        if (lastSentKey === key) lastSentKey = "";
      });
    } catch (_) {
      // The next media event retries when the native bridge becomes available.
    }
  }

  document.addEventListener("loadedmetadata", function (event) {
    if (event.target !== mainVideo()) return;
    bindings.delete(event.target);
    pendingMetadata = { video: event.target, source: event.target.currentSrc };
    if (!navigating) bind(event.target);
  }, true);
  document.addEventListener("emptied", function (event) {
    bindings.delete(event.target);
    if (pendingMetadata && pendingMetadata.video === event.target) pendingMetadata = null;
  }, true);
  ["timeupdate", "pause", "seeked", "ended", "playing"].forEach(function (type) {
    document.addEventListener(type, function (event) {
      if (event.target === mainVideo()) checkpoint(type !== "timeupdate");
    }, true);
  });
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "hidden") checkpoint(true);
  }, true);
  window.addEventListener("pagehide", function () { checkpoint(true); }, true);
  window.addEventListener("yt-navigate-start", function () {
    checkpoint(true);
    navigating = true;
    pendingMetadata = null;
  }, true);
  window.addEventListener("yt-navigate-finish", function () {
    navigating = false;
    if (pendingMetadata && pendingMetadata.video === mainVideo() &&
        pendingMetadata.source === pendingMetadata.video.currentSrc) bind(pendingMetadata.video);
  }, true);
  bind(mainVideo());
})();
