// @ts-check
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { trackerDailyAverage } from "../tracker-averages.js";

describe("trackerDailyAverage", () => {
  it("averages positive totals over days with a log", () => {
    const result = trackerDailyAverage(
      [
        { date: "2026-11-18T08:00:00.000Z", amount: 250 },
        { date: "2026-11-18T12:00:00.000Z", amount: 250 },
        { date: "2026-11-19T08:00:00.000Z", amount: 500 },
      ],
      "2026-11-15",
      "2026-11-21",
    );
    assert.equal(result.average, 500);
    assert.equal(result.loggedDays, 2);
  });

  it("skips days outside the range, untracked days, and non-positive amounts", () => {
    const result = trackerDailyAverage(
      [
        { date: "2026-11-14", amount: 900 },
        { date: "2026-11-16", amount: 200 },
        { date: "2026-11-17", amount: 400 },
        { date: "2026-11-18", amount: -50 },
        { date: "2026-11-22", amount: 900 },
      ],
      "2026-11-15",
      "2026-11-21",
      new Set(["2026-11-17"]),
    );
    assert.equal(result.average, 200);
    assert.equal(result.loggedDays, 1);
  });

  it("returns no logged days when nothing is in range", () => {
    assert.deepEqual(trackerDailyAverage([], "2026-11-15", "2026-11-21"), {
      average: 0,
      loggedDays: 0,
    });
  });
});
