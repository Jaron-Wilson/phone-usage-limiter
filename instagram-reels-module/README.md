# Reels Gone

An Xposed / LSPatch module that removes short-form video from Instagram and
YouTube from the inside. Our own code, no third-party app mod.

## What it hides

Instagram (`com.instagram.android`):
- The **Reels tab** in the bottom bar (`clips_tab`).
- The **reels tray** in the home feed (`reels_tray_container`).
- The **Explore grid**, but only on the Explore screen, so the search bar still
  works. It hides `recycler_view` when `explore_action_bar` is present.

YouTube (`com.google.android.youtube`):
- The **Shorts tab** in the bottom bar, matched by its "Shorts" label because it
  carries no resource id.

It is scoped to those two packages and touches nothing else. It works by
finding each app's own view ids and labels and setting those views gone on every
screen change, so it needs no understanding of the apps' obfuscated code. If
either app renames something, edit the strings in `ReelsGone.kt`.

Short videos inside a chat or a search result still play; only the push
surfaces, the tabs and shelves, are removed.

## Why a module, not a patched APK

A patched Instagram is a copy of Meta's proprietary app and cannot be
redistributed, and it goes stale on every Instagram update. This module plus
LSPatch re-creates the result on any device, at any Instagram version, and is
ours to publish.

## Install (no root)

1. Build the module, or use the prebuilt `ReelsGone.apk` here.
2. Install the **LSPatch manager** app (JingMatrix/LSPatch releases).
3. Install `ReelsGone.apk` on the phone so LSPatch can see it.
4. In LSPatch: New Patch, pick Instagram, Integrated mode, tick Reels Gone,
   patch. Install the result. Instagram is replaced, so you log in again.
5. On an Instagram update, re-run the LSPatch step on the new version.

With root and LSPosed the module loads the same way; enable it scoped to
Instagram.

## Build

```
./gradlew :app:assembleRelease
```

Depends only on the compile-only Xposed API. The output is unsigned; sign it
with any key (`apksigner`) before installing.

## Caveat

A re-signed Instagram can be flagged by Meta. That risk is yours to weigh; it is
your device and your account.
