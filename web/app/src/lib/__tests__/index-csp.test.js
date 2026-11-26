import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const html = readFileSync(new URL("../../../index.html", import.meta.url), "utf8");

test("indexCsp_allowsSameOriginWebAssemblyForZxingFallback", () => {
  const meta = html.match(
    /<meta\s+http-equiv="Content-Security-Policy"[\s\S]*?content="([^"]+)"/
  );
  assert.ok(meta);
  const scriptSrc = meta[1]
    .split(";")
    .map((directive) => directive.trim())
    .find((directive) => directive.startsWith("script-src "));
  assert.ok(scriptSrc);
  assert.match(scriptSrc, /'self'/);
  assert.match(scriptSrc, /'wasm-unsafe-eval'/);
  assert.doesNotMatch(scriptSrc, /'unsafe-eval'/);
});
