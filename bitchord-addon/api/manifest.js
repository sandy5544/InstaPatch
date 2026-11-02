module.exports = function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") return res.status(204).end();
  if (req.method !== "GET") return res.status(405).json({ error: "Method not allowed" });
  return res.status(200).json({
    id: "com.sandy5544.bitchord.qobuz",
    name: "Independent Qobuz Bridge",
    version: "1.0.0",
    description: "BitChord addon bridge for genuine Qobuz lossless streams",
    allowDownloads: 1,
    settings: [
      { key: "quality", name: "Audio quality", default: "LOSSLESS", options: [
        { value: "LOW", label: "Standard" },
        { value: "HIGH", label: "High" },
        { value: "LOSSLESS", label: "Lossless / Hi-Res" }
      ] }
    ]
  });
};
