# Independent Qobuz bridge for BitChord

This is a Vercel serverless **BitChord addon API**. It is separate from the Lossless JS-module index.

## Endpoints

- `GET /manifest.json`
- `GET /search?q=...`
- `GET /stream/{trackId}?quality=LOSSLESS`

The API returns BitChord's addon response shape and rejects encrypted or non-HTTPS stream URLs.

## Deployment

1. Import this repository into Vercel.
2. Set the Vercel project root directory to `bitchord-addon`.
3. Add environment variable `QOBUZ_UPSTREAM_URL` with the base URL of a Qobuz-compatible upstream API.
4. Deploy.
5. In BitChord, add the deployed root URL as an Addon source.

## Important upstream requirement

This project is the BitChord-facing API adapter, not a Qobuz account/API implementation. It requires a compatible upstream that provides `/search?q=...` and `/stream/{id}?quality=...`, returning JSON with track rows and a direct HTTPS stream URL. The previously referenced `qobuz-addon.vercel.app` endpoint has been reported as disabled (HTTP 402), so it should not be used as the upstream.

Do not put Qobuz credentials, app secrets, or private tokens in this repository. Configure secrets as Vercel environment variables. Do not bypass DRM or decrypt protected streams.
