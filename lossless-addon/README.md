# Independent Lossless addon for Lossless

This is a GitHub-hosted Lossless **JavaScript module index**. It does not use a LastWave token, account, quota, or signing key.

## Install

In Lossless, add this module index URL:

`https://raw.githubusercontent.com/sandy5544/InstaPatch/main/lossless-addon/manifest.json`

The initial module uses the public Qobuz-compatible addon endpoint at `https://qobuz-addon.vercel.app`. Search and stream availability depend on that independent upstream service and its terms/uptime. Stream URLs may expire.

## Important

- The module passes through upstream stream URLs; it does not transcode lossy audio into FLAC.
- It reports codec/sample-rate/bit-depth only when the upstream returns those fields.
- Downloads are disabled in the manifest.
- This is a client-side module index, not a server-side proxy. GitHub Pages/raw hosting cannot itself proxy or guarantee audio streams.
