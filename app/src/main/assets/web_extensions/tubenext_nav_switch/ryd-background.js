(function () {
  "use strict";

  // Return YouTube Dislike API integration. The API and protocol are documented at
  // https://returnyoutubedislikeapi.com/swagger/index.html.
  var API_BASE = "https://returnyoutubedislikeapi.com";
  var GET_VOTES = "TUBENEXT_RYD_GET_VOTES";
  var SUBMIT_VOTE = "TUBENEXT_RYD_SUBMIT_VOTE";
  var SET_ENABLED = "TUBENEXT_RYD_SET_ENABLED";
  var VIDEO_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/;
  var DEFAULT_CACHE_TTL_MS = 3 * 60 * 1000;
  var MAX_CACHE_TTL_MS = 60 * 60 * 1000;
  var MAX_CACHE_ENTRIES = 128;
  var MAX_REQUESTS_PER_MINUTE = 30;
  var MAX_REQUESTS_PER_DAY = 2000;
  var MIN_REQUEST_GAP_MS = 350;
  var REQUEST_TIMEOUT_MS = 10000;
  var DEFAULT_RATE_LIMIT_BACKOFF_MS = 15 * 60 * 1000;
  var MAX_VOTE_ATTEMPTS = 6;
  var MAX_PENDING_VOTES = 100;
  var MAX_PUZZLE_DIFFICULTY = 22;
  var STORAGE_PENDING_VOTES = "tubenext_ryd_pending_votes";
  var STORAGE_USER_ID = "tubenext_ryd_user_id";
  var STORAGE_REGISTRATION_CONFIRMED = "tubenext_ryd_registration_confirmed";
  var STORAGE_DAILY_DATE = "tubenext_ryd_daily_date";
  var STORAGE_DAILY_COUNT = "tubenext_ryd_daily_count";
  var STORAGE_MINUTE_REQUEST_TIMES = "tubenext_ryd_minute_request_times";
  var STORAGE_BLOCKED_UNTIL = "tubenext_ryd_blocked_until";

  var featureEnabled = false;
  var voteCache = new Map();
  var inFlightVoteFetches = new Map();
  var pendingVotes = {};
  var rydUserId = "";
  var registrationConfirmed = false;
  var dailyDate = "";
  var dailyCount = 0;
  var blockedUntil = 0;
  var minuteRequestTimes = [];
  var lastRequestAt = 0;
  var requestTail = Promise.resolve();
  var activeRequestControllers = new Set();
  var voteProcessingPromise = null;
  var voteRetryTimer = null;
  var voteRevision = 0;

  var storageReady = browser.storage.local.get([
    STORAGE_PENDING_VOTES,
    STORAGE_USER_ID,
    STORAGE_REGISTRATION_CONFIRMED,
    STORAGE_DAILY_DATE,
    STORAGE_DAILY_COUNT,
    STORAGE_MINUTE_REQUEST_TIMES,
    STORAGE_BLOCKED_UNTIL
  ]).then(function (stored) {
    pendingVotes = sanitizePendingVotes(stored[STORAGE_PENDING_VOTES]);
    rydUserId = validUserId(stored[STORAGE_USER_ID]) ? stored[STORAGE_USER_ID] : "";
    registrationConfirmed = stored[STORAGE_REGISTRATION_CONFIRMED] === true && !!rydUserId;
    dailyDate = typeof stored[STORAGE_DAILY_DATE] === "string"
      ? stored[STORAGE_DAILY_DATE]
      : "";
    dailyCount = nonNegativeInteger(stored[STORAGE_DAILY_COUNT]);
    minuteRequestTimes = sanitizeRequestTimes(stored[STORAGE_MINUTE_REQUEST_TIMES]);
    blockedUntil = nonNegativeInteger(stored[STORAGE_BLOCKED_UNTIL]);
  }).catch(function () {
    pendingVotes = {};
  });

  function nonNegativeInteger(value) {
    var parsed = Number(value);
    return Number.isFinite(parsed) && parsed >= 0 ? Math.floor(parsed) : 0;
  }

  function validUserId(value) {
    return typeof value === "string" && /^[A-Za-z0-9]{36}$/.test(value);
  }

  function validVideoId(value) {
    return typeof value === "string" && VIDEO_ID_PATTERN.test(value);
  }

  function validVote(value) {
    return value === -1 || value === 0 || value === 1;
  }

  function sanitizeRequestTimes(value) {
    if (!Array.isArray(value)) {
      return [];
    }
    var now = Date.now();
    return value.map(nonNegativeInteger).filter(function (timestamp) {
      return timestamp > 0 && now - timestamp < 60 * 1000 && timestamp <= now;
    }).sort(function (left, right) {
      return left - right;
    }).slice(-MAX_REQUESTS_PER_MINUTE);
  }

  function sanitizePendingVotes(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return {};
    }
    var result = {};
    Object.keys(value).forEach(function (videoId) {
      var entry = value[videoId];
      if (!validVideoId(videoId) || !entry || !validVote(entry.value)) {
        return;
      }
      result[videoId] = {
        videoId: videoId,
        value: entry.value,
        attempts: Math.min(MAX_VOTE_ATTEMPTS, nonNegativeInteger(entry.attempts)),
        nextAttemptAt: nonNegativeInteger(entry.nextAttemptAt),
        updatedAt: nonNegativeInteger(entry.updatedAt),
        revision: typeof entry.revision === "string" ? entry.revision : "restored-" + videoId
      };
    });
    return result;
  }

  function youtubeSender(sender) {
    var rawUrl = sender && (sender.url || sender.tab && sender.tab.url);
    try {
      var url = new URL(rawUrl || "");
      var host = url.hostname.toLowerCase();
      return url.protocol === "https:" && (
        host === "youtube.com" ||
        host === "www.youtube.com" ||
        host === "m.youtube.com" ||
        host === "youtu.be"
      );
    } catch (_) {
      return false;
    }
  }

  function rydError(code, message, retryable, retryAt) {
    var error = new Error(message || code);
    error.rydCode = code;
    error.retryable = retryable === true;
    error.retryAt = nonNegativeInteger(retryAt);
    return error;
  }

  function serializeError(error) {
    return {
      code: error && error.rydCode || "unknown",
      retryable: !!(error && error.retryable),
      retryAt: nonNegativeInteger(error && error.retryAt)
    };
  }

  function utcDateKey(now) {
    return new Date(now).toISOString().slice(0, 10);
  }

  function nextUtcDay(now) {
    var date = new Date(now);
    return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate() + 1);
  }

  function wait(milliseconds) {
    return new Promise(function (resolve) {
      setTimeout(resolve, Math.max(0, milliseconds));
    });
  }

  async function reserveRequestSlot() {
    await storageReady;
    var now = Date.now();
    if (blockedUntil > now) {
      throw rydError("rate_limited", "RYD is temporarily rate limited", true, blockedUntil);
    }

    minuteRequestTimes = minuteRequestTimes.filter(function (timestamp) {
      return now - timestamp < 60 * 1000;
    });
    if (minuteRequestTimes.length >= MAX_REQUESTS_PER_MINUTE) {
      throw rydError(
        "local_minute_limit",
        "Local RYD minute limit reached",
        true,
        minuteRequestTimes[0] + 60 * 1000
      );
    }

    var today = utcDateKey(now);
    if (dailyDate !== today) {
      dailyDate = today;
      dailyCount = 0;
    }
    if (dailyCount >= MAX_REQUESTS_PER_DAY) {
      throw rydError(
        "local_daily_limit",
        "Local RYD daily limit reached",
        true,
        nextUtcDay(now)
      );
    }

    var gap = MIN_REQUEST_GAP_MS - (now - lastRequestAt);
    if (gap > 0) {
      await wait(gap);
      now = Date.now();
    }
    minuteRequestTimes.push(now);
    lastRequestAt = now;
    dailyCount += 1;
    await browser.storage.local.set({
      tubenext_ryd_daily_date: dailyDate,
      tubenext_ryd_daily_count: dailyCount,
      tubenext_ryd_minute_request_times: minuteRequestTimes
    });
  }

  function retryAfterTimestamp(response) {
    var rawValue = response.headers.get("Retry-After");
    if (rawValue) {
      var seconds = Number(rawValue);
      if (Number.isFinite(seconds) && seconds >= 0) {
        return Date.now() + seconds * 1000;
      }
      var parsedDate = Date.parse(rawValue);
      if (Number.isFinite(parsedDate) && parsedDate > Date.now()) {
        return parsedDate;
      }
    }
    return Date.now() + DEFAULT_RATE_LIMIT_BACKOFF_MS;
  }

  async function performRequest(path, options) {
    if (!featureEnabled) {
      throw rydError("disabled", "RYD is disabled", false, 0);
    }
    await reserveRequestSlot();
    if (!featureEnabled) {
      throw rydError("disabled", "RYD is disabled", false, 0);
    }
    var controller = new AbortController();
    activeRequestControllers.add(controller);
    var timeout = setTimeout(function () {
      controller.abort();
    }, REQUEST_TIMEOUT_MS);
    var response;
    try {
      response = await fetch(API_BASE + path, Object.assign({}, options || {}, {
        credentials: "omit",
        referrerPolicy: "no-referrer",
        signal: controller.signal
      }));
    } catch (error) {
      if (error && error.name === "AbortError") {
        throw rydError("timeout", "RYD request timed out", true, 0);
      }
      throw rydError("network", "RYD request failed", true, 0);
    } finally {
      clearTimeout(timeout);
      activeRequestControllers.delete(controller);
    }

    if (response.status === 429) {
      blockedUntil = retryAfterTimestamp(response);
      await browser.storage.local.set({ tubenext_ryd_blocked_until: blockedUntil });
      throw rydError("rate_limited", "RYD rate limit reached", true, blockedUntil);
    }
    if (response.status >= 500) {
      throw rydError("server", "RYD server error", true, 0);
    }

    var data = null;
    var text = await response.text();
    if (text) {
      try {
        data = JSON.parse(text);
      } catch (_) {
        throw rydError("invalid_json", "Invalid RYD response", response.ok, 0);
      }
    }
    return {
      ok: response.ok,
      status: response.status,
      data: data,
      cacheControl: response.headers.get("Cache-Control") || ""
    };
  }

  function request(path, options) {
    var run = function () {
      return performRequest(path, options);
    };
    var result = requestTail.then(run, run);
    requestTail = result.catch(function () {});
    return result;
  }

  function cacheTtl(cacheControl) {
    var match = /(?:^|,)\s*max-age=(\d+)/i.exec(cacheControl || "");
    if (!match) {
      return DEFAULT_CACHE_TTL_MS;
    }
    return Math.max(60 * 1000, Math.min(MAX_CACHE_TTL_MS, Number(match[1]) * 1000));
  }

  function trimVoteCache() {
    while (voteCache.size > MAX_CACHE_ENTRIES) {
      voteCache.delete(voteCache.keys().next().value);
    }
  }

  function applyCachedDislikeDelta(videoId, dislikeDelta) {
    if ((dislikeDelta !== -1 && dislikeDelta !== 0 && dislikeDelta !== 1) ||
        !voteCache.has(videoId)) {
      return;
    }
    var cached = voteCache.get(videoId);
    cached.data.dislikes = Math.max(0, cached.data.dislikes + dislikeDelta);
  }

  function normalizedVoteData(data) {
    var dislikes = Number(data && data.dislikes);
    var likes = Number(data && data.likes);
    if (!Number.isFinite(dislikes) || dislikes < 0) {
      throw rydError("invalid_votes", "Invalid RYD vote data", false, 0);
    }
    return {
      dislikes: Math.floor(dislikes),
      likes: Number.isFinite(likes) && likes >= 0 ? Math.floor(likes) : 0,
      deleted: data && data.deleted === true
    };
  }

  async function getVotes(videoId) {
    await storageReady;
    if (!featureEnabled) {
      return { ok: false, error: { code: "disabled" } };
    }
    if (!validVideoId(videoId)) {
      return { ok: false, error: { code: "invalid_video_id" } };
    }

    var now = Date.now();
    var cached = voteCache.get(videoId);
    if (cached && cached.expiresAt > now) {
      return { ok: true, data: cached.data, cached: true };
    }
    if (inFlightVoteFetches.has(videoId)) {
      return inFlightVoteFetches.get(videoId);
    }

    var fetchPromise = request("/votes?videoId=" + encodeURIComponent(videoId), {
      method: "GET",
      headers: { Accept: "application/json" }
    }).then(function (result) {
      if (!result.ok) {
        throw rydError("http_" + result.status, "RYD vote request rejected", false, 0);
      }
      var data = normalizedVoteData(result.data);
      voteCache.delete(videoId);
      voteCache.set(videoId, {
        data: data,
        expiresAt: Date.now() + cacheTtl(result.cacheControl)
      });
      trimVoteCache();
      return { ok: true, data: data, cached: false };
    }).catch(function (error) {
      if (cached) {
        return { ok: true, data: cached.data, cached: true, stale: true };
      }
      return { ok: false, error: serializeError(error) };
    }).finally(function () {
      inFlightVoteFetches.delete(videoId);
    });
    inFlightVoteFetches.set(videoId, fetchPromise);
    return fetchPromise;
  }

  function countLeadingZeroes(bytes, limit) {
    var zeroes = 0;
    for (var index = 0; index < bytes.length; index += 1) {
      var value = bytes[index];
      if (value === 0) {
        zeroes += 8;
      } else {
        var count = 1;
        if (value >>> 4 === 0) {
          count += 4;
          value <<= 4;
        }
        if (value >>> 6 === 0) {
          count += 2;
          value <<= 2;
        }
        zeroes += count - (value >>> 7);
        break;
      }
      if (zeroes >= limit) {
        break;
      }
    }
    return zeroes;
  }

  async function solvePuzzle(puzzle) {
    var difficulty = Number(puzzle && puzzle.difficulty);
    if (!Number.isInteger(difficulty) || difficulty < 0 || difficulty > MAX_PUZZLE_DIFFICULTY) {
      throw rydError("invalid_puzzle", "Unsupported RYD puzzle difficulty", false, 0);
    }
    var challenge;
    try {
      challenge = Uint8Array.from(atob(puzzle.challenge), function (character) {
        return character.charCodeAt(0);
      });
    } catch (_) {
      throw rydError("invalid_puzzle", "Invalid RYD puzzle challenge", false, 0);
    }
    if (challenge.length !== 16) {
      throw rydError("invalid_puzzle", "Invalid RYD puzzle length", false, 0);
    }

    var buffer = new ArrayBuffer(20);
    var byteView = new Uint8Array(buffer);
    var integerView = new Uint32Array(buffer);
    byteView.set(challenge, 4);
    var maxCount = Math.pow(2, difficulty) * 3;
    for (var value = 0; value < maxCount; value += 1) {
      integerView[0] = value;
      var hash = new Uint8Array(await crypto.subtle.digest("SHA-512", buffer));
      if (countLeadingZeroes(hash, difficulty) >= difficulty) {
        return {
          solution: btoa(String.fromCharCode.apply(null, byteView.slice(0, 4)))
        };
      }
    }
    throw rydError("puzzle_unsolved", "RYD puzzle could not be solved", true, 0);
  }

  function generateUserId() {
    var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    var randomValues = new Uint32Array(36);
    crypto.getRandomValues(randomValues);
    var result = "";
    for (var index = 0; index < randomValues.length; index += 1) {
      result += alphabet[randomValues[index] % alphabet.length];
    }
    return result;
  }

  async function clearRegistration() {
    rydUserId = "";
    registrationConfirmed = false;
    await browser.storage.local.remove([
      STORAGE_USER_ID,
      STORAGE_REGISTRATION_CONFIRMED
    ]);
  }

  async function ensureRegistration() {
    await storageReady;
    if (registrationConfirmed && validUserId(rydUserId)) {
      return rydUserId;
    }

    if (!validUserId(rydUserId)) {
      rydUserId = generateUserId();
      registrationConfirmed = false;
      await browser.storage.local.set({
        tubenext_ryd_user_id: rydUserId,
        tubenext_ryd_registration_confirmed: false
      });
    }
    var query = "?userId=" + encodeURIComponent(rydUserId);
    var challenge = await request("/puzzle/registration" + query, {
      method: "GET",
      headers: { Accept: "application/json" }
    });
    if (!challenge.ok) {
      throw rydError("registration_" + challenge.status, "RYD registration rejected", false, 0);
    }
    var solution = await solvePuzzle(challenge.data);
    var confirmation = await request("/puzzle/registration" + query, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(solution)
    });
    if (!confirmation.ok || confirmation.data !== true) {
      throw rydError("registration_failed", "RYD registration failed", false, 0);
    }
    registrationConfirmed = true;
    await browser.storage.local.set({ tubenext_ryd_registration_confirmed: true });
    return rydUserId;
  }

  async function submitVoteToRyd(videoId, value, registrationRetry) {
    var userId = await ensureRegistration();
    var voteResult = await request("/interact/vote", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId: userId, videoId: videoId, value: value })
    });
    if (voteResult.status === 401 && registrationRetry !== false) {
      await clearRegistration();
      return submitVoteToRyd(videoId, value, false);
    }
    if (!voteResult.ok) {
      throw rydError("vote_" + voteResult.status, "RYD vote rejected", false, 0);
    }
    var solution = await solvePuzzle(voteResult.data);
    var confirmation = await request("/interact/confirmVote", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        solution: solution.solution,
        userId: userId,
        videoId: videoId
      })
    });
    if (!confirmation.ok) {
      throw rydError("confirmation_" + confirmation.status, "RYD vote confirmation rejected", false, 0);
    }
  }

  function persistPendingVotes() {
    return browser.storage.local.set({ tubenext_ryd_pending_votes: pendingVotes });
  }

  function pendingEntries() {
    return Object.keys(pendingVotes).map(function (videoId) {
      return pendingVotes[videoId];
    });
  }

  function trimPendingVotes() {
    var entries = pendingEntries().sort(function (left, right) {
      return left.updatedAt - right.updatedAt;
    });
    while (entries.length > MAX_PENDING_VOTES) {
      var removed = entries.shift();
      delete pendingVotes[removed.videoId];
    }
  }

  function retryDelay(attempts) {
    var base = Math.min(6 * 60 * 60 * 1000, 30 * 1000 * Math.pow(2, Math.max(0, attempts - 1)));
    return Math.floor(base * (0.75 + Math.random() * 0.5));
  }

  function scheduleVoteRetry() {
    if (voteRetryTimer) {
      clearTimeout(voteRetryTimer);
      voteRetryTimer = null;
    }
    if (!featureEnabled) {
      return;
    }
    var entries = pendingEntries();
    if (!entries.length) {
      return;
    }
    var nextAttemptAt = Math.min.apply(null, entries.map(function (entry) {
      return entry.nextAttemptAt;
    }));
    voteRetryTimer = setTimeout(function () {
      voteRetryTimer = null;
      processPendingVotes();
    }, Math.max(1000, nextAttemptAt - Date.now()));
  }

  async function runPendingVotes() {
    var hardFailures = {};
    while (featureEnabled) {
      var now = Date.now();
      var due = pendingEntries().filter(function (entry) {
        return entry.nextAttemptAt <= now;
      }).sort(function (left, right) {
        return left.updatedAt - right.updatedAt;
      });
      if (!due.length) {
        break;
      }
      var entry = due[0];
      var revision = entry.revision;
      try {
        await submitVoteToRyd(entry.videoId, entry.value, true);
        if (pendingVotes[entry.videoId] && pendingVotes[entry.videoId].revision === revision) {
          delete pendingVotes[entry.videoId];
        }
      } catch (error) {
        var current = pendingVotes[entry.videoId];
        if (!current || current.revision !== revision) {
          continue;
        }
        current.attempts += 1;
        if (!error.retryable || current.attempts >= MAX_VOTE_ATTEMPTS) {
          hardFailures[entry.videoId] = serializeError(error);
          delete pendingVotes[entry.videoId];
        } else {
          current.nextAttemptAt = Math.max(
            Date.now() + retryDelay(current.attempts),
            nonNegativeInteger(error.retryAt)
          );
        }
      }
      await persistPendingVotes();
    }
    scheduleVoteRetry();
    return hardFailures;
  }

  function processPendingVotes() {
    if (voteProcessingPromise) {
      return voteProcessingPromise;
    }
    voteProcessingPromise = runPendingVotes().finally(function () {
      voteProcessingPromise = null;
    });
    return voteProcessingPromise;
  }

  async function queueVote(videoId, value, dislikeDelta) {
    await storageReady;
    if (!featureEnabled) {
      return { ok: false, error: { code: "disabled" } };
    }
    if (!validVideoId(videoId) || !validVote(value)) {
      return { ok: false, error: { code: "invalid_vote" } };
    }
    applyCachedDislikeDelta(videoId, dislikeDelta);
    voteRevision += 1;
    var now = Date.now();
    pendingVotes[videoId] = {
      videoId: videoId,
      value: value,
      attempts: 0,
      nextAttemptAt: now,
      updatedAt: now,
      revision: now + "-" + voteRevision
    };
    trimPendingVotes();
    await persistPendingVotes();
    var hardFailures = await processPendingVotes();
    if (hardFailures[videoId]) {
      return { ok: false, error: hardFailures[videoId] };
    }
    return {
      ok: true,
      queued: !!pendingVotes[videoId],
      retryAt: pendingVotes[videoId] ? pendingVotes[videoId].nextAttemptAt : 0
    };
  }

  async function setEnabled(enabled) {
    await storageReady;
    featureEnabled = enabled === true;
    if (!featureEnabled) {
      activeRequestControllers.forEach(function (controller) {
        controller.abort();
      });
      activeRequestControllers.clear();
      if (voteRetryTimer) {
        clearTimeout(voteRetryTimer);
        voteRetryTimer = null;
      }
      pendingVotes = {};
      await persistPendingVotes();
      return { ok: true, enabled: false };
    }
    scheduleVoteRetry();
    processPendingVotes();
    return { ok: true, enabled: true };
  }

  browser.runtime.onMessage.addListener(function (message, sender) {
    if (!youtubeSender(sender) || !message || typeof message !== "object") {
      return undefined;
    }
    if (message.type === SET_ENABLED) {
      return setEnabled(message.enabled === true);
    }
    if (message.type === GET_VOTES) {
      return getVotes(message.videoId);
    }
    if (message.type === SUBMIT_VOTE) {
      return queueVote(message.videoId, message.value, message.dislikeDelta);
    }
    return undefined;
  });
})();
