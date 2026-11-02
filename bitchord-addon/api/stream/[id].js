const { proxy } = require("../_upstream");
module.exports = async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Cache-Control", "no-store");
  if (req.method !== "GET") return res.status(405).json({ error: "Method not allowed" });
  const id = String(req.query.id || "");
  if (!id || id.length > 180) return res.status(400).json({ error: "Invalid track id" });
  try {
    const data = await proxy("/stream/" + encodeURIComponent(id), {
      quality: req.query.quality || "LOSSLESS"
    });
    const s = data.stream || data.data || data;
    if (!s || typeof s.url !== "string" || !/^https:\/\//i.test(s.url)) {
      return res.status(502).json({ error: "Upstream did not return a playable HTTPS stream" });
    }
    if ([true, 1, "true", "1"].includes(s.encrypted)) {
      return res.status(422).json({ error: "Encrypted stream cannot be passed through" });
    }
    const codec = String(s.codec || s.format || "").toLowerCase();
    const mime = s.mimeType || ({ flac: "audio/flac", alac: "audio/alac", mp3: "audio/mpeg", aac: "audio/aac" })[codec];
    const track = { id, audioQuality: String(s.audioQuality || s.quality || "") };
    if (mime) track.mimeType = mime;
    if (Number(s.bitDepth) > 0) track.bitDepth = Math.round(Number(s.bitDepth));
    if (Number(s.sampleRate) > 0) track.sampleRate = Number(s.sampleRate);
    if (Number(s.bitrate) > 0) track.bitrate = Math.round(Number(s.bitrate));
    return res.status(200).json({ streamUrl: s.url, track });
  } catch (e) {
    return res.status(502).json({ error: e.message });
  }
};
