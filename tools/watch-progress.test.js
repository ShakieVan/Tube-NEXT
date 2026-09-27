"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const script = fs.readFileSync(path.join(__dirname,
  "../app/src/main/assets/web_extensions/tubenext_nav_switch/watch-progress.js"), "utf8");

function harness() {
  const events = new Map();
  const sent = [];
  const classes = new Set();
  let now = 10000;
  let renderedId = "abcdefghijk";
  let video = { currentTime: 0, duration: 600, readyState: 4, currentSrc: "blob:main",
    ended: false, seeking: false, played: { length: 0 } };
  const player = { classList: { contains: value => classes.has(value) } };
  const listen = (name, callback) => events.set(name, [...(events.get(name) || []), callback]);
  const window = { location: { href: "https://www.youtube.com/watch?v=abcdefghijk" }, addEventListener: listen };
  const document = {
    visibilityState: "visible",
    addEventListener: listen,
    querySelector(selector) {
      if (selector === "#movie_player video.html5-main-video") return video;
      if (selector === "#movie_player") return player;
      if (selector === "ytd-watch-flexy[video-id]") return { getAttribute: () => renderedId };
      throw new Error(selector);
    }
  };
  vm.runInNewContext(script, { window, document, URL, Date: { now: () => now },
    browser: { runtime: { sendNativeMessage(app, message) {
      assert.equal(app, "tubenext_nav_switch");
      sent.push(JSON.parse(JSON.stringify(message)));
      return Promise.resolve();
    } } }
  });
  return {
    sent, classes, window, document,
    get video() { return video; },
    emit(name, target = video) { (events.get(name) || []).forEach(fn => fn({ target })); },
    tick(ms) { now += ms; },
    navigate(id) { window.location.href = "https://www.youtube.com/watch?v=" + id; renderedId = id; },
    rendererId(id) { renderedId = id; },
    replaceVideo() { video = { ...video, currentSrc: "blob:new", played: { length: 0 } }; }
  };
}

test("pause captures the exact latest time without navigation or playback changes", () => {
  const h = harness();
  h.video.currentTime = 135.8;
  const originalUrl = h.window.location.href;
  h.emit("pause");
  assert.equal(h.sent.length, 1);
  assert.equal(h.sent[0].position, 135.8);
  assert.equal(h.sent[0].url, originalUrl);
  assert.equal(h.window.location.href, originalUrl);
  assert.equal(h.video.currentTime, 135.8);
});

test("time updates are throttled but pause seek and hiding flush immediately", () => {
  const h = harness();
  h.video.currentTime = 30;
  h.emit("timeupdate");
  h.video.currentTime = 31;
  h.tick(1000);
  h.emit("timeupdate");
  assert.equal(h.sent.length, 1);
  h.tick(4000);
  h.emit("timeupdate");
  h.video.currentTime = 32;
  h.emit("pause");
  h.video.currentTime = 12;
  h.emit("seeked");
  h.video.currentTime = 14;
  h.document.visibilityState = "hidden";
  h.emit("visibilitychange");
  h.video.currentTime = 15;
  h.emit("pagehide");
  assert.deepEqual(h.sent.map(x => x.position), [30, 31, 32, 12, 14, 15]);
});

test("initial zero and teardown cannot overwrite a restored timestamp", () => {
  const h = harness();
  h.emit("pause");
  h.emit("timeupdate");
  assert.equal(h.sent.length, 0);
  h.video.currentTime = 135;
  h.emit("seeked");
  h.video.currentTime = 0;
  h.video.readyState = 0;
  h.emit("emptied");
  h.emit("pause");
  assert.deepEqual(h.sent.map(x => x.position), [135]);
});

test("seeking back to zero and finishing are recorded", () => {
  const h = harness();
  h.video.currentTime = 135;
  h.emit("seeked");
  h.video.currentTime = 0;
  h.emit("seeked");
  h.video.currentTime = 590;
  h.emit("seeked");
  h.video.currentTime = 600;
  h.video.ended = true;
  h.emit("ended");
  assert.deepEqual(h.sent.map(x => [x.position, x.ended]), [[135, false], [0, false], [590, false], [600, true]]);
});

test("ads live DVR invalid metadata seeking and preview videos are ignored", () => {
  const h = harness();
  h.video.currentTime = 135;
  for (const flag of ["ad-showing", "ad-interrupting", "ytp-live"]) {
    h.classes.add(flag);
    h.emit("pause");
    h.classes.delete(flag);
  }
  h.video.duration = Infinity;
  h.emit("pause");
  h.video.duration = NaN;
  h.emit("pause");
  h.video.duration = 600;
  h.video.seeking = true;
  h.emit("pause");
  h.video.seeking = false;
  h.emit("pause", { ...h.video });
  assert.equal(h.sent.length, 0);
  h.emit("pause");
  assert.equal(h.sent.length, 1);
});

test("SPA cannot label the old video position with the new video ID", () => {
  const h = harness();
  h.video.currentTime = 135;
  h.emit("yt-navigate-start");
  h.navigate("lmnopqrstuv");
  h.emit("pause");
  h.emit("yt-navigate-finish");
  h.emit("timeupdate");
  h.emit("playing");
  assert.equal(h.sent.length, 1);
  h.video.currentSrc = "blob:next";
  h.video.currentTime = 20;
  h.emit("loadedmetadata");
  h.emit("pause");
  assert.equal(h.sent.length, 2);
  assert.equal(h.sent[1].url, "https://www.youtube.com/watch?v=lmnopqrstuv");
  assert.equal(h.sent[1].position, 20);
});

test("metadata during SPA navigation binds only after the navigation finishes", () => {
  const h = harness();
  h.emit("yt-navigate-start");
  h.navigate("lmnopqrstuv");
  h.replaceVideo();
  h.video.currentTime = 50;
  h.emit("loadedmetadata");
  h.emit("timeupdate");
  assert.equal(h.sent.length, 0);
  h.emit("yt-navigate-finish");
  h.emit("pause");
  assert.equal(h.sent[0].position, 50);
});

test("a late main player replacement works without a permanent DOM observer", () => {
  const h = harness();
  h.replaceVideo();
  h.video.currentTime = 90;
  h.emit("timeupdate");
  assert.equal(h.sent.length, 0);
  h.emit("loadedmetadata");
  h.emit("pause");
  assert.equal(h.sent[0].position, 90);
});

test("metadata arriving before the watch renderer ID can be bound on the next event", () => {
  const h = harness();
  h.emit("yt-navigate-start");
  h.navigate("lmnopqrstuv");
  h.rendererId("abcdefghijk");
  h.replaceVideo();
  h.video.currentTime = 42;
  h.emit("loadedmetadata");
  h.emit("yt-navigate-finish");
  h.emit("pause");
  assert.equal(h.sent.length, 0);
  h.rendererId("lmnopqrstuv");
  h.emit("pause");
  assert.equal(h.sent[0].position, 42);
});

test("separate tabs keep independent checkpoints and non-watch pages do not report", () => {
  const a = harness();
  const b = harness();
  a.video.currentTime = 135;
  b.video.currentTime = 270;
  a.emit("pause");
  b.emit("pause");
  assert.equal(a.sent[0].position, 135);
  assert.equal(b.sent[0].position, 270);
  a.window.location.href = "https://www.youtube.com/";
  a.video.currentTime = 200;
  a.emit("pause");
  assert.equal(a.sent.length, 1);
});
