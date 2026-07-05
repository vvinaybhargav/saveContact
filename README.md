# saveContact — Job Call Tracker + Default Phone App

A native **Android (Java)** app that is **both a full default phone/dialer app and a recruiter/job-call tracker**. Built for a job seeker who wants incoming recruiter calls to show the company, stage, tags, and past notes right on the call screen — and to log/track every call, note, and pipeline stage in one place.

- **Package:** `com.example.callsaver`
- **App name:** "Job Call Tracker"
- **Repo:** https://github.com/vvinaybhargav/saveContact
- **Language:** Java · **UI:** Android XML + Material Components (no Jetpack Compose)
- **Storage:** local SQLite (no Room, no server, no cloud) — fully offline
- **minSdk 23 · targetSdk 34 · compileSdk 34**

> This README is intended to be complete enough that another developer or AI can understand and extend the app without prior context.

---

## What it does

Two bottom-nav tabs plus a full-screen call experience:

1. **Recents tab** — the device call log, a slide-up dialer keypad (places calls through the app), and search by name/number.
2. **Tracker tab** — job-call entries (company, phone, stage, tags, notes), a stats bar, a search (phone/company/tag/notes), status filter chips, swipe-to-edit/delete, a FAB to add manually, and an add/edit dialog that shows a **merged timeline of calls + notes**.
3. **Default phone app** — when set as the default dialer, the app shows its own **full-screen incoming / in-call / post-call screens**, enriched with the caller's tracker info and latest note.
4. **Analytics** — a funnel dashboard (conversion rates, pipeline funnel, calls-per-week, top tags).

### Feature highlights
- Full-screen **incoming call** UI (Answer / Decline) shown over the lock screen, with company · stage · tags and the **latest note** (shown like a tag), plus a color avatar initial or the saved contact name.
- **In-call controls:** Mute, **Keypad (DTMF tones for IVR menus)**, Speaker, **Note**, End, and a live duration timer.
- **Notes timeline:** each note is its own dated, individually-deletable row (not a single blob).
- **Call history per entry:** every Incoming / Outgoing / Missed call (+ duration) is logged against the tracked company and merged into its timeline.
- **Post-call panel:** after an answered call, one screen handles everything — for a tracked recruiter it captures a note + lets you advance the stage; for an unknown caller it captures name + note and saves to **both** the tracker and phone contacts in one tap.
- **Save-caller notification** fallback for answered unknown calls when the app is *not* the default dialer.
- **Collapsing header** on both tabs (title fades out on scroll, returns near the top).
- **Charcoal-grey dark theme** with an indigo accent; adapts to light/dark.

---

## Tech stack

- Java, Android SDK, `com.android.application` Gradle plugin.
- `androidx.appcompat`, `com.google.android.material:material:1.11.0`, `androidx.constraintlayout`, `androidx.coordinatorlayout` (transitive via material).
- Android **Telecom** framework (`InCallService`, `Call`, `TelecomManager`, `RoleManager`) for being the default dialer.
- **SQLite** via `SQLiteOpenHelper` (no ORM).
- Build/APK via **GitHub Actions** (see CI section).

---

## Architecture overview

```
                 ┌────────────── MainActivity ──────────────┐
                 │  bottom nav · requests default-dialer     │
                 │  role + runtime permissions               │
                 └───────┬───────────────────────┬───────────┘
                         │                        │
                 RecentsFragment           TrackerFragment ──► AnalyticsActivity
                 (call log, dialer)        (entries, search,        (funnel,
                         │                  chips, add/edit          conversion,
                 RecentsAdapter             dialog w/ timeline)      weekly, tags)
                                                  │
                                            JobCallAdapter

  Telecom (default dialer path):
     CallReceiver (PHONE_STATE)          CallService (InCallService)
        tracks answered state              ├─ launches CallActivity via full-screen intent
        posts save-notification            ├─ captures call direction
        only when NOT default dialer       └─ logs call history on remove
                                                  │
                                            OngoingCall (static bridge to the active Call:
                                            answer/reject/hangup/DTMF, state listener)
                                                  │
                                            CallActivity (full-screen incoming / ongoing /
                                            post-call UI, DTMF dialpad, in-call notes)

  Data:  DatabaseHelper (SQLite "JobTracker.db")
         tables: job_calls · call_notes · call_history
         models: JobCall · CallNote · CallHistory
```

---

## Project structure (file by file)

### Java (`app/src/main/java/com/example/callsaver/`)
| File | Responsibility |
|------|----------------|
| `MainActivity.java` | Bottom-nav host (Recents/Tracker). Requests the default-dialer role (`RoleManager` on API 29+, `TelecomManager.ACTION_CHANGE_DEFAULT_DIALER` on 23–28) and runtime permissions. `openTrackerWithNumber()` deep-links to the add dialog prefilled. |
| `RecentsFragment.java` | Reads the device call log, shows it, hosts the slide-up dialer keypad, searches by name/number, and places calls via `TelecomManager.placeCall` (falls back to `ACTION_CALL`/`ACTION_DIAL`). Adds the collapsing-header fade listener. |
| `RecentsAdapter.java` | Recents list rows. Call-type accent colors: Incoming=indigo, Outgoing=violet, Missed=rose (from `call_*` color resources). |
| `TrackerFragment.java` | The tracker: loads entries, stats bar, search + filter chips, swipe edit/delete, the add/edit dialog, and `populateTimeline()` which merges call history + notes into one dated list. Requests `POST_NOTIFICATIONS` on 33+. Opens `AnalyticsActivity`. Collapsing-header fade listener. |
| `JobCallAdapter.java` | Tracker card rows: avatar initial (hashed color), company, phone, tags, notes preview, status badge, call-back button. |
| `AnalyticsActivity.java` | Read-only dashboard computed from SQLite: conversion rates, pipeline funnel, calls-per-week (last 6 weeks), top tags. Uses lightweight rounded `ProgressBar`s (no chart library). |
| `CallService.java` | The `InCallService` the OS binds to when this is the default phone app. Launches `CallActivity` via a **full-screen-intent notification** (reliable from background). Captures call **direction** on add and **logs call history** on remove. Exposes static mute/speaker helpers. Fully guarded so it can never crash the call. |
| `OngoingCall.java` | Static bridge between `CallService` and `CallActivity`: holds the single active `Call`, forwards state changes to a listener, and exposes `answer/reject/hangup/playDtmf/stopDtmf` and the call direction. |
| `CallActivity.java` | Full-screen call UI. Switches between **incoming** (Answer/Decline), **ongoing** (Mute/Keypad/Speaker/Note/End + DTMF dialpad + duration), and **post-call** (note + stage for tracked; save name+note+contact for unknown). Shows company/stage/tags, latest note, avatar. Resolves names via tracker DB then `ContactsContract.PhoneLookup`. |
| `CallReceiver.java` | `PHONE_STATE` broadcast receiver. Tracks whether an incoming call was answered (RINGING→OFFHOOK→IDLE). Posts the "save this caller?" notification for **answered unknown** calls **only when the app is not the default dialer** (otherwise `CallActivity`'s post-call panel handles it). |
| `SaveContactActivity.java` | Standalone transparent "save caller" popup opened by the save-notification (the non-default-dialer path). Saves to tracker + phone contacts, with company/tags/notes/stage fields. |
| `CallerIdService.java` | **Legacy** overlay banner service — **retired** (no longer started; starting a service from the background receiver crashed on Android 8+). Kept for reference. |
| `DatabaseHelper.java` | `SQLiteOpenHelper` for `JobTracker.db`. Owns all three tables, migrations, and CRUD. |
| `JobCall.java` / `CallNote.java` / `CallHistory.java` | Data models (see Data model). |

### Layouts (`app/src/main/res/layout/`)
`activity_main.xml` (nav host), `activity_call.xml` (full-screen call UI), `activity_analytics.xml`, `activity_save_contact.xml`, `fragment_recents.xml` / `fragment_tracker.xml` (CoordinatorLayout + AppBarLayout collapsing header), `dialog_add_call.xml` (add/edit with timeline, wrapped in a ScrollView), `item_job_call.xml`, `item_recent_call.xml`, `item_note_row.xml` (timeline row), `item_analytics_bar.xml`, `item_spinner_white.xml` (white spinner item for the dark call screen), `layout_caller_id_banner.xml` (legacy overlay).

### Resources (`app/src/main/res/values/` and `values-night/`)
- `colors.xml` — light palette + `call_incoming/outgoing/missed(_bg)` accents. `values-night/colors.xml` — charcoal-grey dark overrides. Note: `white` is deliberately overridden to a dark card color in night mode; use `white_constant` when you need real white (e.g., text on the dark header/call screen).
- `themes.xml` — `Theme.CallSaver` (Material DayNight, NoActionBar) + styles: `TrackerChipStyle`, `KeypadButtonStyle`, `CallActionColumn/Button*`, `CallActionIcon/Label`, `CallDtmfKey`, `Analytics*`.
- `strings.xml` — strings + the `round_statuses` string-array (pipeline stages).

---

## Data model (SQLite: `JobTracker.db`, schema version 5)

**`job_calls`** — one row per tracked company/number
`id, phone_number, company_name, round_status, tags, notes, duration, timestamp`
- `round_status` ∈ pipeline stages: Screening, 1st Round, 2nd Round, Final Round, HR / Salary, Offered, Rejected.
- `notes` is kept in sync with the **latest** timeline note (for the card preview).

**`call_notes`** (added v4) — the notes timeline, one row per note
`id, job_call_id, note_text, note_time`
- Old accumulated `notes` blobs were migrated in on upgrade.

**`call_history`** (added v5) — one row per logged call against an entry
`id, job_call_id, call_type ("Incoming"/"Outgoing"/"Missed"), duration (sec), call_time`

Number matching uses `PhoneNumberUtils.compare` (`DatabaseHelper.getJobCallByNumber`) so formatting/country-code differences still match.

---

## Call flow (default-dialer path)

1. OS binds `CallService` (we hold `ROLE_DIALER`). `onCallAdded` records direction (RINGING⇒incoming, else outgoing) and posts a high-priority **full-screen-intent notification** → launches `CallActivity`.
2. `CallActivity` reads state from `OngoingCall` and shows the incoming or ongoing UI, enriched from the tracker DB / contacts, including the **latest note**.
3. During a call: Mute/Speaker route audio via `CallService`; **Keypad** sends DTMF via `Call.playDtmfTone`; **Note** appends to the timeline live.
4. On disconnect: `CallService.onCallRemoved` **logs a `call_history` row** for tracked numbers. `CallActivity` shows the **post-call panel** if the call was connected and the caller is tracked or unknown (ordinary saved contacts just close).
5. `CallReceiver` also observes `PHONE_STATE`; its save-notification only fires when we are **not** the default dialer.

---

## Permissions (declared in `AndroidManifest.xml`)
`READ_PHONE_STATE`, `READ_CALL_LOG`, `READ_CONTACTS`, `WRITE_CONTACTS`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `SYSTEM_ALERT_WINDOW` (legacy overlay), `POST_NOTIFICATIONS` (13+), `USE_FULL_SCREEN_INTENT`. The `InCallService` is declared with `BIND_INCALL_SERVICE` + `IN_CALL_SERVICE_UI` meta-data, and `MainActivity` declares the DIAL/VIEW `tel:` intent filters required to be an eligible default dialer.

---

## Build, CI, signing, versioning

- **CI:** `.github/workflows/android.yml` runs on push/PR to `main`: sets up JDK 17, generates the Gradle wrapper (8.2), runs `./gradlew assembleDebug`, and uploads `app-debug.apk` as an artifact. Grab the APK from the **Actions** tab.
- **Signing:** a **fixed keystore** is committed at `app/keystore/callsaver.jks` and applied to both debug and release builds in `app/build.gradle` (`signingConfigs.stable`). This gives every build the **same signature** so APKs **install as in-place updates** instead of forcing an uninstall. (Trade-off accepted for a personal, sideloaded app that will never be on the Play Store — the key is public in this repo.)
- **Versioning:** bump `versionCode`/`versionName` in `app/build.gradle` on every change so builds are distinguishable and register as updates. (Currently `13` / `2.2`.)
- **No local Android SDK is assumed** during development here — changes are validated by the CI build.

---

## Gotchas & lessons (read before changing call code)
- **Show incoming UI via a full-screen-intent notification**, not a background `startActivity` — the latter is blocked/crashes for incoming calls and hands the call to the stock phone app.
- **Never call `Context.startService()` from `CallReceiver`** (background) — it throws `IllegalStateException` on Android 8+. This crash (from the old caller-ID overlay) took down the whole call; the overlay is retired.
- **`AppBarLayout.addOnOffsetChangedListener(lambda)` is ambiguous** across the two overloads — use an explicit `new AppBarLayout.OnOffsetChangedListener() {…}`.
- Keep `<style>` names in sync with layout references (an earlier `DialerKeyStyle` vs `KeypadButtonStyle` mismatch failed the resource-link step).
- In night mode, `@color/white` is a dark card color — use `@color/white_constant` for genuinely white text on dark surfaces.
- Some OEMs (Xiaomi/MIUI, Realme, etc.) restrict background launches / full-screen intents even for the default dialer unless Autostart and background-popup settings are enabled.

---

## Roadmap (not yet built)
- Follow-up reminders (due date + overdue badge + notification)
- WhatsApp / SMS quick-send templates
- Export / backup (CSV + JSON) and restore
- Missed-call capture prompt (currently only answered unknown calls prompt)
- Archive / Won–Lost to declutter the active pipeline
- In-call hold / add-call / swap / conference, reject-with-SMS, Bluetooth audio route

---

## Related
A separate Python/Flask job scraper (`connectors.py` company-career JSON APIs + Playwright board scrapers) is the source of job leads; a future direction is to feed scraped jobs into this app for on-device apply-tracking. That scraper lives in its own project and is **not** part of this repo.
