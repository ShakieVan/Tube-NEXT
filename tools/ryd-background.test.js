"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const { webcrypto } = require("node:crypto");

const BACKGROUND_SCRIPT = fs.readFileSync(
  path.join(
    __dirname,
    "..",
    "app",
    "src",
    "main",
    "assets",
    "web_extensions",
    "tubenext_nav_switch",
    "ryd-background.js"
  ),
  "utf8"
);
const YOUTUBE_SENDER = { url: "https://www.youtube.com/watch?v=dQw4w9WgXcQ" };

function createHarness(fetchImplementation, initialStorage = {}) {
  const stored = { ...initialStorage };
  let messageListener = null;
  const browser = {
    runtime: {
      onMessage: {
        addListener(listener) {
          messageListener = listener;
        }
      }
    },
    storage: {
      local: {
        async get(keys) {
          return keys.reduce((result, key) => {
            if (Object.hasOwn(stored, key)) {
              result[key] = stored[key];
            }
            return result;
          }, {});
        },
        async set(values) {
          Object.assign(stored, values);
        },
        async remove(keys) {
          keys.forEach((key) => delete stored[key]);
        }
      }
    }
  };
  const context = vm.createContext({
    AbortController,
    ArrayBuffer,
    Date,
    Headers,
    Map,
    Math,
    Promise,
    Response,
    URL,
    Uint8Array,
    Uint32Array,
    atob,
    browser,
    btoa,
    clearTimeout,
    crypto: webcrypto,
    encodeURIComponent,
    fetch: fetchImplementation,
    setTimeout
  });
  vm.runInContext(BACKGROUND_SCRIPT, context, { filename: "ryd-background.js" });
  assert.equal(typeof messageListener, "function");
  return {
    send(message, sender = YOUTUBE_SENDER) {
      return Promise.resolve(messageListener(message, sender));
    },
    stored
  };
}

test("vote counts stay disabled until opt-in and concurrent requests are coalesced", async () => {
  let fetchCount = 0;
  const harness = createHarness(async () => {
    fetchCount += 1;
    return new Response(JSON.stringify({ dislikes: 517123, likes: 19000000 }), {
      status: 200,
      headers: { "Cache-Control": "public, max-age=600" }
    });
  });

  const disabled = await harness.send({
    type: "TUBENEXT_RYD_GET_VOTES",
    videoId: "dQw4w9WgXcQ"
  });
  assert.equal(disabled.ok, false);
  assert.equal(disabled.error.code, "disabled");
  assert.equal(fetchCount, 0);

  await harness.send({ type: "TUBENEXT_RYD_SET_ENABLED", enabled: true });
  const first = harness.send({
    type: "TUBENEXT_RYD_GET_VOTES",
    videoId: "dQw4w9WgXcQ"
  });
  const second = harness.send({
    type: "TUBENEXT_RYD_GET_VOTES",
    videoId: "dQw4w9WgXcQ"
  });
  const results = await Promise.all([first, second]);

  assert.equal(fetchCount, 1);
  assert.equal(results[0].data.dislikes, 517123);
  assert.equal(results[1].data.dislikes, 517123);

  const cached = await harness.send({
    type: "TUBENEXT_RYD_GET_VOTES",
    videoId: "dQw4w9WgXcQ"
  });
  assert.equal(cached.cached, true);
  assert.equal(fetchCount, 1);
  assert.equal(Object.hasOwn(harness.stored, "tubenext_ryd_user_id"), false);
});

test("a 429 response blocks further requests until Retry-After", async () => {
  let fetchCount = 0;
  const harness = createHarness(async () => {
    fetchCount += 1;
    return new Response("", {
      status: 429,
      headers: { "Retry-After": "60" }
    });
  });
  await harness.send({ type: "TUBENEXT_RYD_SET_ENABLED", enabled: true });

  const first = await harness.send({
    type: "TUBENEXT_RYD_GET_VOTES",
    videoId: "dQw4w9WgXcQ"
  });
  const second = await harness.send({
    type: "TUBENEXT_RYD_GET_VOTES",
    videoId: "kxOuG8jMIgI"
  });

  assert.equal(first.ok, false);
  assert.equal(first.error.code, "rate_limited");
  assert.equal(second.ok, false);
  assert.equal(second.error.code, "rate_limited");
  assert.equal(fetchCount, 1);
  assert.ok(harness.stored.tubenext_ryd_blocked_until > Date.now());
});

test("disabling aborts an active request and prevents follow-up traffic", async () => {
  let fetchCount = 0;
  let aborted = false;
  const harness = createHarness((_, options) => {
    fetchCount += 1;
    return new Promise((_, reject) => {
      options.signal.addEventListener("abort", () => {
        aborted = true;
        reject(new DOMException("Aborted", "AbortError"));
      });
    });
  });
  await harness.send({ type: "TUBENEXT_RYD_SET_ENABLED", enabled: true });
  const activeRequest = harness.send({
    type: "TUBENEXT_RYD_GET_VOTES",
    videoId: "dQw4w9WgXcQ"
  });
  await new Promise((resolve) => setTimeout(resolve, 0));

  await harness.send({ type: "TUBENEXT_RYD_SET_ENABLED", enabled: false });
  const result = await activeRequest;
  const afterDisable = await harness.send({
    type: "TUBENEXT_RYD_GET_VOTES",
    videoId: "kxOuG8jMIgI"
  });

  assert.equal(aborted, true);
  assert.equal(result.ok, false);
  assert.equal(afterDisable.error.code, "disabled");
  assert.equal(fetchCount, 1);
});

test("a confirmed vote uses one persistent pseudonymous ID and proof-of-work", async () => {
  const requests = [];
  const challenge = {
    difficulty: 0,
    challenge: btoa(String.fromCharCode(...new Uint8Array(16)))
  };
  const harness = createHarness(async (rawUrl, options) => {
    const url = new URL(rawUrl);
    requests.push({
      method: options.method,
      path: url.pathname,
      queryUserId: url.searchParams.get("userId"),
      body: options.body ? JSON.parse(options.body) : null
    });
    if (url.pathname === "/puzzle/registration" && options.method === "GET") {
      return Response.json(challenge);
    }
    if (url.pathname === "/interact/vote") {
      return Response.json(challenge);
    }
    return Response.json(true);
  });
  await harness.send({ type: "TUBENEXT_RYD_SET_ENABLED", enabled: true });

  const result = await harness.send({
    type: "TUBENEXT_RYD_SUBMIT_VOTE",
    videoId: "dQw4w9WgXcQ",
    value: -1,
    dislikeDelta: 1
  });

  const userId = harness.stored.tubenext_ryd_user_id;
  assert.equal(result.ok, true);
  assert.match(userId, /^[A-Za-z0-9]{36}$/);
  assert.equal(harness.stored.tubenext_ryd_registration_confirmed, true);
  assert.deepEqual(
    requests.map((request) => [request.method, request.path]),
    [
      ["GET", "/puzzle/registration"],
      ["POST", "/puzzle/registration"],
      ["POST", "/interact/vote"],
      ["POST", "/interact/confirmVote"]
    ]
  );
  assert.equal(requests[0].queryUserId, userId);
  assert.deepEqual(requests[2].body, {
    userId,
    videoId: "dQw4w9WgXcQ",
    value: -1
  });
  assert.equal(requests[3].body.userId, userId);
  assert.equal(requests[3].body.videoId, "dQw4w9WgXcQ");
  assert.equal(typeof requests[3].body.solution, "string");
});
