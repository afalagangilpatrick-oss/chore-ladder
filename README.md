# Chore Ladder

A two-phone Android app for household chore accountability with a reward ladder. One phone
is the **organizer** (writes tasks, sets rewards, flags work, reviews each week, promotes tiers);
the other is the **member** (checks tasks off, fixes flags, submits the week with comments).

- **To-do:** today's tasks: *Basics* (always owed, plus every tier already passed) and the one tier being worked on.
- **Dashboard:** weekly and all-time completed / missed, and a colour calendar (green clean day, yellow
  flagged-and-fixed, pink graced miss, red miss).
- **Rules & rewards:** tiers with organizer-written rewards; enough clean weeks make the member eligible and the organizer promotes; repeated
  bad weeks drop the member one tier, never below Basics.
- **Weekly review:** the member submits the week (auto-submitted at the end of the week if not),
  the organizer reviews it and decides grace before 9am the next morning, then the week locks.
- **Notifications and alarms** for tasks left today, flags and reviews.

## How it works

- **Offline-first, event-sourced.** Every change is an immutable event stamped with a device id and
  a Lamport counter. State is a fold over the log in (counter, device) order, so two phones that were
  apart merge deterministically with no server.
- **Authority is checked at fold time** by device role, not by a UI toggle: organizer-only events
  from the member's phone are ignored.
- **Sync without a server:** phone-to-phone over Bluetooth RFCOMM (`BtSync.java`), or over a
  Tailscale tailnet (`NetSync.java`, accepts only the configured peer inside 100.64.0.0/10).
- **UI** is a single HTML/JS file (`assets/index.html`) in a WebView; the native layer
  (`src/com/household/ladder/`) handles notifications, alarms (restored after reboot) and sync.

## Build

Plain Android SDK command-line tools, no Gradle (build-tools 35, platform android-35, JDK 17):

```bash
ANDROID_SDK=/path/to/android-sdk KEYSTORE=my.keystore KS_ALIAS=release KSPASS=... ./build.sh
adb install app-release.apk
```

License: MIT.
