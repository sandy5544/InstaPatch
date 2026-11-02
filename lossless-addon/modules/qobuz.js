(function () {
'use strict';
var BASE = "https://qobuz-addon.vercel.app";

function setting(context, key, fallback) {
  var value = context && context.settings && context.settings[key];
  if (value && typeof value === 'object') value = value.value;
  return value == null || value === '' ? fallback : value;
}
function query(url, values) {
  var pairs = [];
  Object.keys(values).forEach(function (key) {
    if (values[key] != null) pairs.push(encodeURIComponent(key) + '=' + encodeURIComponent(values[key]));
  });
  return url + (url.indexOf('?') < 0 ? '?' : '&') + pairs.join('&');
}
async function json(url) {
  var response = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error('HTTP ' + response.status);
  return response.json();
}
var manifestPromise;
async function getManifest() {
  if (!manifestPromise) manifestPromise = json(BASE + '/manifest.json').catch(function (e) {
    manifestPromise = null; throw e;
  });
  return manifestPromise;
}
async function params(context, quality) {
  var m = await getManifest(), out = {};
  (m.settings || []).forEach(function (s) {
    if (s && s.key && ['string','number','boolean'].indexOf(typeof s.default) >= 0) out[s.key] = s.default;
  });
  var requested = String(quality || setting(context, 'quality', 'LOSSLESS')).toUpperCase();
  var desired = requested === 'LOW' ? 'low' : requested === 'HIGH' ? 'high' : 'lossless';
  var schema = (m.settings || []).filter(function (s) { return s.key === 'quality'; })[0];
  var options = schema && schema.options || [];
  var choices = desired === 'lossless'
    ? ['hi_res_lossless','HI_RES_LOSSLESS','hi_res','lossless','LOSSLESS']
    : [desired, desired.toUpperCase()];
  out.quality = desired;
  for (var i=0;i<choices.length;i++) {
    if (options.some(function (o) { return o.value === choices[i]; })) { out.quality=choices[i]; break; }
  }
  out.atmos = 'stereo';
  return out;
}
function mapTrack(t) {
  var artist = t.artistNames ? t.artistNames.join(', ') : t.artist;
  if (artist && typeof artist === 'object') artist = artist.name;
  if (!artist && t.artists) artist = t.artists.map(function(a){return a.name;}).join(', ');
  var album = t.album && typeof t.album === 'object' ? t.album.title : t.album;
  return {
    id: encodeURIComponent(String(t.id == null ? t.trackId : t.id)),
    title: String(t.title || t.name || ''),
    artist: String(artist || ''),
    album: String(album || ''),
    albumArtworkURL: t.artworkURL || t.albumArtworkURL || t.artwork || null,
    duration: Math.max(0, Math.round(Number(t.duration) || 0)),
    audioQuality: String(t.audioQuality || ''),
    format: String(t.format || '')
  };
}
async function searchTracks(text, limit, context) {
  try {
    var p = await params(context, null);
    p.q = text;
    var data = await json(query(BASE + '/search', p));
    var rows = data.tracks || (data.data && data.data.tracks) || [];
    if (!Array.isArray(rows)) rows = [];
    var tracks = rows.filter(function(t){return t && t.id != null && (t.title || t.name);})
      .slice(0, Math.max(1, Math.min(30, limit || 15))).map(mapTrack);
    return { tracks: tracks, total: tracks.length };
  } catch (_) { return { tracks: [], total: 0 }; }
}
async function getTrackStreamUrl(id, quality, context) {
  try {
    var p = await params(context, quality);
    var data = await json(query(BASE + '/stream/' + encodeURIComponent(decodeURIComponent(String(id))), p));
    var s = data.stream || data.data || data;
    var url = s.url || s.streamUrl || s.directUrl;
    if (typeof url !== 'string' || url.indexOf('https://') !== 0 || s.encrypted === true) return { streamUrl: null };
    var codec = String(s.codec || s.format || '').toLowerCase();
    var mime = s.mimeType || ({flac:'audio/flac',alac:'audio/alac'}[codec]);
    var track = { id:String(id), audioQuality:String(s.audioQuality || s.quality || ''), audioModes:[] };
    if (mime) track.mimeType = mime;
    if (Number(s.bitDepth)>0) track.bitDepth = Number(s.bitDepth);
    if (Number(s.sampleRate)>0) track.sampleRate = Number(s.sampleRate);
    if (Number(s.bitrate)>0) track.bitrate = Number(s.bitrate);
    return { streamUrl:url, track:track };
  } catch (_) { return { streamUrl:null }; }
}
module.exports = { searchTracks:searchTracks, getTrackStreamUrl:getTrackStreamUrl };
})();