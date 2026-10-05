# Focus Launcher

A minimalist Android home-screen launcher built with Kotlin and Jetpack Compose. Pure black
background, white text, monospace font, no app icons, no clutter — just a clock, two
gesture-driven radial dials for quick app launching, and a full-text search.

## Features

- **Dot-matrix clock** — a 5×3 segmented-display clock (`HH:mm`), with the date
  (`DD/MM/YY`) and today's screen time flanking it at the bottom-left/right. Everything
  below the clock — that row and the calendar block — is pinned to the clock's own
  measured width, so long text truncates with an ellipsis instead of stretching the block
  wider than the clock.
- **Two radial dial menus**, stacked in the bottom-right corner:
  - Pull from either handle and rotate through the arc to light up an app; the selected
    app's name glides into a left-side callout box as you rotate. Release to launch.
  - The bottom dial sweeps a 90° quarter-circle; the upper dial sweeps a full 180°.
  - Node spacing grows automatically so apps never overlap, however many are assigned.
  - The pull handle has a generous invisible touch zone around it (much bigger than the
    visible dot) so it's easy to find without looking, and once you've pulled far enough
    from the anchor, releasing always launches the nearest app — a direction that's
    slightly outside the arc still resolves to the nearest edge app instead of launching
    nothing.
- **Swipe up** anywhere on the home screen to open search. Results appear instantly as
  you type, with the top (most likely) match highlighted and launched immediately once
  typing narrows to a single app (no debounce — it opens the moment it's unambiguous) or
  on pressing Enter.
- **Swipe left / right** on the home screen trigger quick actions — Camera and Contacts
  by default, both overridable. **Swipe down** pulls down the system notification shade,
  same as it would from any other app.
- **Next calendar event** — shown in a bordered box below the date/screen-time row: title
  (truncated with an ellipsis if long) and a `> `-prefixed start–end time, read straight
  from the device's calendar provider (whatever's synced from a Google account already
  on-device — no separate sign-in).
- **Long-press an app** in search to reveal an inline action panel — filled monochrome
  icons (same pack, same size) for Uninstall, Rename, Hide, and Close, sliding in from the
  right edge of that same row (no separate popup), separated from the app name by a thin
  divider. A long name is simply covered by the panel's own opaque background rather than
  being clipped right at the divider. While held, the row's border fills in white (or black
  on the highlighted top match) proportional to how long you've been pressing, fading out
  on release.
  - Rename sets a launcher-only display name used everywhere the app appears.
  - Hide removes it from search while keeping any dial/gesture assignment working.
- **Settings** (long-press the clock) — grouped into labeled sections, in order: Expense
  tracker, Gestures, Dial position, Upper dial, Bottom dial, Hidden apps, each under a thin
  divider. The two dial sections show a small radial replica of the actual on-screen dial —
  the same arc, in the same corner — instead of a plain list, so it's immediately obvious
  which pull direction launches which app. Tap a node to assign/replace that slot;
  long-press a filled one to clear it. A slider above them nudges both dials up together
  (keeping the spacing between them) for devices/preferences where the default position
  isn't ideal. Gesture overrides and unhiding apps are still plain rows.
- Status bar is hidden, and system window/activity-open animations are disabled for this
  activity, so the home screen reads as the persistent OS shell rather than an app that
  keeps opening and closing on top of it.
- **Expense tracker** (optional — enable under Settings → Expense tracker, now the first
  section) — every transaction is logged manually, by describing it in plain language; a
  small LLM running entirely on-device interprets the description into an amount, kind,
  and category — nothing leaves the device, no network calls, no login, no automatic SMS
  classification.
  - **On the home screen itself**, below the calendar block: today's total as the
    headline with this month's total as a smaller line beneath it, a bar graph of
    categories, and a `>`-prefixed breakdown line per category. Below that, a single
    quick-add prompt — tap it and it animates to the center of the screen with the rest
    dimmed, auto-focused and keyboard up (so the keyboard never covers it); type what you
    spent on ("200 on lunch") and Enter logs it immediately via the on-device model, no
    confirmation step. Below the prompt, a subtle grey line reminds you of the last
    bank/UPI debit SMS seen ("Last paid ₹220 · CAFE COFFEE DAY") — purely informational,
    never written to the tracker on its own; it's there so nothing gets forgotten, not to
    track anything automatically.
  - **The dashboard** (Settings → Open dashboard, or tap the home screen summary) adds:
    a free-text command bar at the top — one instruction to the model that can add a
    transaction (cash, say), delete one, or retag one, matched against a plain-language
    description rather than picking from a menu — today's total, a per-category breakdown
    with animated bars, a 14-day line graph, and the recent transaction list. Tap a
    transaction for the inline-retag prompt ("what was this actually?"); hold one down for
    a direct Rename / Modify price / Delete action panel — no model call needed for those.
  - **Settings** also gets a model download row (shows live progress, a reason if it
    stalls, and a cancel action — the download isn't Wi-Fi-restricted, since that silently
    stalls forever if the device isn't on a network Android recognizes as Wi-Fi) and an
    AI mode picker (Low / Medium / High) that controls how many CPU threads inference is
    allowed to use, resolved against the actual device's core count rather than a fixed
    number.
  - A small model running fully offline won't always get a judgment call right (category
    is the one it's most often unsure about) — tap any transaction on the dashboard and
    say what it actually was to fix it.
  - Bank SMS formats vary a lot and there's no universal standard, so parsing (in
    `SmsTransactionParser.kt`, used only for the passive "last paid" reminder) covers
    common Indian bank/UPI debit and credit phrasing — extend its regexes for formats it
    doesn't already catch.

## Project structure

```
app/src/main/java/com/focuslauncher/app/
├── MainActivity.kt                 — navigation between Home / Search / Settings, status bar
├── AppRepository.kt                — queries installed apps, launches/uninstalls/closes apps
├── AppLabelStore.kt                — persists per-app rename overrides
├── HiddenAppsStore.kt              — persists which apps are hidden from search
├── RadialMenuStore.kt              — persists which apps are assigned to each dial's slots
├── GestureAppStore.kt              — persists swipe-left/right overrides
├── DeviceUsage.kt                  — today's screen time via UsageStatsManager
├── CalendarRepository.kt           — next upcoming event via the device's calendar provider
├── PackageChangeReceiver.kt        — refreshes the app list on install/uninstall
├── ExpenseCategory.kt              — the 5 fixed spend categories
├── TransactionKind.kt              — expense/income + who resolved it
├── ExpenseDbHelper.kt              — SQLite schema: transactions (manually logged only)
├── SmsTransactionParser.kt         — regex parsing of bank/UPI debit+credit SMS
├── SmsReceivedReceiver.kt          — updates the passive "last paid" reminder on a new SMS
├── LastPaymentStore.kt             — the last-seen-debit reminder (SharedPreferences, no DB)
├── ExpenseRepository.kt            — manual transaction CRUD, totals, dashboard queries
├── ModelDownloader.kt              — fetches the GGUF model via DownloadManager, no login
├── ClassifierPerformanceStore.kt   — the Low/Medium/High AI mode → CPU thread count
├── LlamaModelLoader.kt             — shared "is the model loaded" check for both LLM features
├── LlamaEngine.kt                  — JNI bridge to the vendored llama.cpp (see cpp/)
├── TransactionClassifier.kt        — free-text retag of an existing transaction
├── DashboardCommandInterpreter.kt  — free-text add/remove/retag, shared by the quick-add
│                                     prompt and the dashboard's command bar
└── ui/
    ├── Theme.kt                 — black/white/grey palette, monospace typography
    ├── DotMatrixClock.kt        — the segmented clock display
    ├── HomeScreen.kt            — clock, gesture handling, the two dials, the quick-add prompt
    ├── RadialAppMenu.kt         — the pull-and-rotate dial menu
    ├── ScrambleText.kt          — hacker-style decrypt text animation
    ├── SearchScreen.kt          — full-screen search overlay + app action menu
    ├── SettingsScreen.kt        — dial slot, gesture, hidden-app, expense-tracker config
    └── ExpenseTrackerScreen.kt  — spend dashboard: command bar, totals, graph, recent log

app/src/main/cpp/                — vendored llama.cpp (MIT) + jni_bridge.cpp, CMake-built
app/src/main/assets/             — GBNF grammars constraining the model's JSON output
```

## Building

Requires Android Studio (or a local Gradle + Android SDK install) plus the Android NDK and
CMake, for the vendored llama.cpp native build. `minSdk 26`, `targetSdk 34`.

1. Open the project in Android Studio — it will fetch the Gradle wrapper, NDK, and CMake
   automatically on first sync. Alternatively, with Gradle, the Android SDK, and NDK
   `27.2.12479018` installed locally, create a `local.properties` file pointing at your SDK
   (`sdk.dir=/path/to/Android/sdk`) and run `gradle assembleDebug`.
2. Install the debug APK on a device or emulator and set it as the default launcher from
   Android Settings.
3. The on-device model (Qwen2.5-1.5B-Instruct, ~1 GB) isn't bundled in the APK — enable
   the expense tracker, then download it from Settings. The quick-add prompt and dashboard
   command bar can't interpret anything until the download completes.

### Permissions

- `QUERY_ALL_PACKAGES` — to list all installed apps (default launchers are generally
  exempt from the stricter package-visibility rules, but this is declared for reliability
  across OEM skins).
- `PACKAGE_USAGE_STATS` — for the screen-time display. This is a special permission the
  user must grant manually under Settings → Apps → Special access → Usage access; the
  launcher prompts for it (tap "Enable screen time" on the home screen).
- `READ_CALENDAR` — for the upcoming-event display, requested at runtime (tap "Enable
  calendar" on the home screen).
- `KILL_BACKGROUND_PROCESSES` — for the "Close" action in an app's long-press menu.
- `EXPAND_STATUS_BAR` — a normal, auto-granted permission used to pull down the
  notification shade on swipe-down, the same way every other custom launcher does (there's
  no public API for this, just a long-standing reflection call against the hidden
  `StatusBarManager`).
- `READ_SMS` / `RECEIVE_SMS` — only used if the expense tracker is enabled, requested at
  runtime from the Settings toggle. The app never sends a message; it only reads/reacts to
  inbox messages that parse as a bank/UPI debit or credit alert, purely to populate the
  passive "last paid" reminder — nothing from SMS is ever written to the tracked expense
  log. `RECEIVE_SMS` is what lets that reminder update the moment a new message arrives
  rather than waiting for the next time the app is opened — it's requested alongside
  `READ_SMS` but optional, the reminder still works via a resume-time inbox scan if only
  that one is denied.
- `INTERNET` — used for exactly one thing: downloading the on-device model file from
  Hugging Face (a public, ungated URL, no account/login/token involved). Once downloaded,
  classification itself makes no network calls at all.

## Known limitations

- Fonts: body text uses Android's built-in monospace typeface (no network dependency).
- Screen time requires the manual Usage Access grant described above; until granted, the
  home screen shows "Enable screen time" instead of a duration.
- The on-device model (Qwen2.5-1.5B, quantized) is small enough to run on a phone CPU but
  isn't always right — category is the judgment call it's most often unsure about. Tap any
  transaction on the dashboard and say what it actually was to fix it.
- "Close" is a best-effort background-process kill (`killBackgroundProcesses`) — Android
  doesn't let one app force-stop another's foreground activity, so this mainly helps with
  apps already in the background.
- The calendar event comes from whatever's already synced to the device's local calendar
  provider; there's no in-app Google account sign-in.
