// @ts-check
import { test } from "node:test";
import assert from "node:assert/strict";

// The component defines its custom element at import time; node tests run
// without a DOM, so stub the three globals it touches and drive
// reportInvalidFields with plain fake objects (Codeberg #181 regression:
// the novalidate form must open collapsed <details> around invalid
// controls before the browser reports them).
globalThis.HTMLElement = class HTMLElement {};
globalThis.customElements = { define() {} };
globalThis.HTMLDetailsElement = class HTMLDetailsElement {};

const { EntryForm } = await import("../../components/entry-form.js");

// An instance of the stubbed class so the implementation's
// `instanceof HTMLDetailsElement` check accepts it.
function details(parent = null) {
  const el = new HTMLDetailsElement();
  el.open = false;
  el.parentElement = parent;
  return el;
}

function control({ valid = true, willValidate = true, parent = null } = {}) {
  return { willValidate, validity: { valid }, parentElement: parent };
}

function fakeForm(controls, reportValidityResult) {
  const state = { reportValidityCalls: 0 };
  return {
    state,
    querySelectorAll: () => controls,
    reportValidity: () => {
      state.reportValidityCalls += 1;
      return reportValidityResult;
    },
  };
}

test("reportInvalidFields_opensDetailsAroundAnInvalidControl", () => {
  const outer = details();
  const inner = details(outer);
  const bad = control({ valid: false, parent: inner });
  const form = fakeForm([bad], false);

  const result = EntryForm.prototype.reportInvalidFields.call({ querySelector: () => form });

  assert.equal(inner.open, true);
  assert.equal(outer.open, true);
  assert.equal(form.state.reportValidityCalls, 1);
  assert.equal(result, false);
});

test("reportInvalidFields_leavesClosedDetailsWhenControlsValidateOrSkipValidation", () => {
  const validDetails = details();
  const nonValidatingDetails = details();
  const fine = control({ valid: true, parent: validDetails });
  const skipped = control({ valid: false, willValidate: false, parent: nonValidatingDetails });
  const form = fakeForm([fine, skipped], true);

  const result = EntryForm.prototype.reportInvalidFields.call({ querySelector: () => form });

  assert.equal(validDetails.open, false);
  assert.equal(nonValidatingDetails.open, false);
  assert.equal(form.state.reportValidityCalls, 1);
  assert.equal(result, true);
});

test("reportInvalidFields_passesWhenThereIsNoForm", () => {
  const result = EntryForm.prototype.reportInvalidFields.call({ querySelector: () => null });

  assert.equal(result, true);
});
