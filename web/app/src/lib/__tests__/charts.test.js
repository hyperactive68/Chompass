// @ts-check
// Codeberg #190: the weight chart's goal line and its effect on the scale.
import { test } from "node:test";
import assert from "node:assert/strict";

import { lineChartSvg } from "../charts.js";

const POINTS = [
  { label: "Mon", value: 97.2 },
  { label: "Sun", value: 97.6 },
];

test("lineChartSvg_goalLineAndScaleFollowTheGoalByDefault", () => {
  const svg = lineChartSvg(POINTS, { goal: 85, unit: "kg" });
  assert.ok(svg.includes('stroke-dasharray="4 4"'), "goal rule drawn");
  assert.ok(svg.includes(">85kg<"), "goal label rendered");
});

test("lineChartSvg_hiddenGoalLineScalesToTheData", () => {
  const svg = lineChartSvg(POINTS, { goal: 85, unit: "kg", showGoalLine: false });
  assert.ok(!svg.includes('stroke-dasharray="4 4"'), "no goal rule");
  assert.ok(!svg.includes(">85kg<"), "no goal label");
  assert.ok(svg.includes(">97.2kg<"), "scale top follows the data");
  assert.ok(svg.includes(">97.6kg<"), "scale bottom follows the data");
});

test("lineChartSvg_noGoalDrawsNoGoalLine", () => {
  const svg = lineChartSvg(POINTS, {});
  assert.ok(!svg.includes('stroke-dasharray="4 4"'), "no goal rule without a goal");
});
