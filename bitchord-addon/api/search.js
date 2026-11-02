const { proxy } = require("./_upstream");
module.exports = async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Cache-Control", "public, s-maxage=30, stale-while-revalidate=120");
  if (req.method !== "GET") return res.status(405).json({ error: "Method not allowed" });
  const q = String(req.query.q || "").trim();
  if (!q) return res.status(400).json({ error: "Missing q parameter" });
  if (q.length > 200) return res.status(400).json({ error: "Query too long" });
  try {
    const data = await proxy("/search", { q, quality: req.query.quality || "LOSSLESS" });
    const rows = Array.isArray(data.tracks) ? data.tracks :
      Array.isArray(data.data && data.data.tracks) ? data.data.tracks : [];
    const tracks = rows.filter(t => t && t.id != null && (t.title || t.name)).map(t => ({
      id: String(t.id),
      title: String(t.title || t.name),
      artist: typeof t.artist === "string" ? t.artist :
        (t.artists || []).map(a => a.name || "").filter(Boolean).join(", "),
      album: typeof t.album === "string" ? t.album : (t.album && t.album.title) || "",
      albumCover: t.albumCover || t.artworkURL || t.cover || null,
      duration: Math.max(0, Math.round(Number(t.duration) || 0)),
      audioQuality: String(t.audioQuality || ""),
      format: String(t.format || "")
    }));
    return res.status(200).json({ tracks, total: tracks.length });
  } catch (e) {
    return res.status(502).json({ error: e.message });
  }
};
