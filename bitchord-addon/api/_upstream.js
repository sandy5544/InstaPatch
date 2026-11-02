const ALLOWED_PATHS = new Set(["/search", "/stream"]);
function upstreamBase() {
  const value = process.env.QOBUZ_UPSTREAM_URL;
  if (!value) throw new Error("QOBUZ_UPSTREAM_URL is not configured");
  const url = new URL(value);
  if (url.protocol !== "https:") throw new Error("Upstream must use HTTPS");
  return url.toString().replace(/\/$/, "");
}
async function proxy(path, query) {
  if (!ALLOWED_PATHS.has(path)) throw new Error("Unsupported upstream path");
  const url = new URL(upstreamBase() + path);
  for (const [key, value] of Object.entries(query || {})) {
    if (value != null && key !== "url") url.searchParams.set(key, String(value));
  }
  const response = await fetch(url.toString(), {
    headers: { Accept: "application/json", "User-Agent": "BitChord-Independent-Addon/1.0" }
  });
  const body = await response.text();
  if (!response.ok) throw new Error("Upstream HTTP " + response.status + ": " + body.slice(0, 240));
  let data;
  try { data = JSON.parse(body); } catch (_) { throw new Error("Upstream returned invalid JSON"); }
  return data;
}
module.exports = { proxy };
