# schussel

**Bluetooth-triggered checklists that remind you of what you forgot — before you walk away.**

*Schussel* is German for a scatterbrained, forgetful person: the one who drives off with the
lights still on. This app is the antidote. You build a checklist, tie it to a Bluetooth device
(typically your car), and when your phone connects, schussel nags you **only if you left something
unticked**.

![Screenshot](screenshots/main_screen.png)

## What it does

You keep one or more checklists. Each list is linked to the Bluetooth device(s) that should trigger
it. When your phone connects to a linked device:

- if everything on that list is ticked → **silence**;
- if anything is still unticked → a **reminder** naming exactly what you forgot.

It works even when the app is closed, and optionally sounds an alarm that repeats until you dismiss
it.

## Features

- **Optional daily reset** (per list) — automatically untick everything at a time you choose, with a
  live “resets in HH:MM” countdown. Lists without a reset time keep their state.
- All data stays **on your device** — no network, no accounts, no analytics.

## How it works

1. Tap **Add list** and add the items you tend to forget.
2. Open the list's **⋮ → Settings** and choose the Bluetooth device(s) that should trigger it.
   Optionally name the list, set a daily reset time, and enable alarm mode.
3. That's it. Next time your phone connects to that device with items left unticked, you get a
   reminder.

## Requirements

- Android 10 (API 29) or newer.
- A phone with Bluetooth, paired with the device you want to trigger on.

## Permissions & privacy

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` (Android 12+) | Detect device connections and read paired device names |
| `BLUETOOTH` (Android ≤ 11) | Same, on older versions |
| `POST_NOTIFICATIONS` (Android 13+) | Show the reminder |
| `VIBRATE` | The optional alarm mode |

schussel stores everything locally (in `SharedPreferences`) and never sends data anywhere.

## Architecture

- Single-activity **Jetpack Compose** UI.
- Checklists are persisted as JSON in `SharedPreferences` (`Checklists`).
- A manifest-registered `BroadcastReceiver` listens for `ACTION_ACL_CONNECTED`, so it fires even
  when the app isn't running.
- The daily reset is **lazy**: it's applied whenever the app opens, a device connects, or on a
  periodic check while the app is open — rather than via a scheduled alarm, so there's no background
  service to be killed.

## Known limitations

- **Background reliability** depends on your phone. Manufacturer battery optimizers (Samsung,
  Xiaomi, Oppo, Huawei, …) can delay or drop the connect broadcast; exempting schussel from battery
  optimization helps. Works fine on my S26 without any changes though.

## License

Copyright © 2026 Florian Mayer.

Licensed under the [Apache License 2.0](LICENSE.txt).
