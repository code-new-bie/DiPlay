# Audio channel separation and Simplified Chinese localization — 2026-09-26

- Keyed audio streams by (stream type, CarPlay audio type) end to end: CarPlayMediaEngine and
  AndroidMediaSink now keep concurrent type-100 streams (media, guidance alerts, telephony,
  speech recognition) separate instead of letting a second SETUP replace the previous stream.
- Automotive audio-bus mode routes "default" (navigation guidance) to
  USAGE_ASSISTANCE_NAVIGATION_GUIDANCE instead of merging it into the media bus.
- Added optional audio focus handling (off by default; "Audio focus" switch in settings):
  media claims AUDIOFOCUS_GAIN but never self-mutes — it only ducks to 20% while something
  else holds transient may-duck focus and restores on gain. Navigation guidance takes no
  focus and overlays media at full volume; Siri requests transient may-duck focus and calls
  request transient exclusive focus. Focus stays opt-in because several head units mute
  streams that touch their focus stack.
- Added "Media audio channel" and "Navigation audio channel" settings: either stream can be
  pinned to a head-unit-defined channel (1-40, always offered in full because vendor channels
  do not answer device probes) via AudioAttributes.setLegacyStreamType(), letting the
  vehicle's own audio policy route it; 0 keeps usage-based routing.
- Localized the app interface into Simplified Chinese (values-zh-rCN); shared-layer error
  messages stay English as stable matching keys for friendlyStage.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
