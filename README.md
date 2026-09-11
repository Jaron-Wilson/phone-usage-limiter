# Modes

An Android app that makes one phone behave like several, and switches between
them on a schedule you already keep: your calendar.

Built for a Pixel, sideloaded, no root, no Play Store.

## What it actually does

**Nothing is silenced. Things are batched.** The one rule the whole app is built
around: you should never miss a call, a text, or a DM from someone who matters,
and you should never be pulled out of your afternoon by "12 people liked your
post". So the app sorts notifications by *what they are*, not by which app sent
them, lets the important ones straight through, and holds the rest until a time
you picked.

Four moving parts:

| Part | What it is | Optional? |
|---|---|---|
| Mode engine | Picks the current mode from your calendar and a time schedule | No |
| Notification gate | A `NotificationListenerService` that holds the noise and batches it | No |
| Minimal home screen | A text-only launcher: today's calendar, then the mode's apps in folders | Yes |
| App guard | An `AccessibilityService` that stops you opening what the mode is not for | Yes |

The last two are genuinely optional. Skip them and everything else still works.

## Five modes ship with it

| Mode | Gets through | Home screen | Guard | Delivery |
|---|---|---|---|---|
| Open | everything | everything, in folders | off | immediately |
| Work | calls, DMs, mentions, money | Work, Everyday, Money | allowlist, pause | 12:30 and 17:00 |
| Deep focus | calls and fraud alerts only | Tools, Money, greyscale | allowlist, sent home | when the mode ends |
| Personal | calls, DMs, mentions, stories, money | Everyday, Money, Social | blocklist, pause | every 90 minutes |
| Sleep | calls from starred contacts, fraud alerts | clock, phone, messages, Money | allowlist, pause | 07:30 |

Edit any of them in the app. Nothing here is hardcoded.

## Folders are shared, switches are per mode

There is one folder library. "Social" is defined once and means the same three
apps everywhere. What changes between modes is only which folders are **switched
on**:

| Folder | Open | Work | Deep focus | Personal | Sleep |
|---|---|---|---|---|---|
| Everyday | on | on | off | on | - |
| Work | - | on | - | - | - |
| Money | on | on | on | on | on |
| Social | on | **off** | **off** | on | **off** |
| Media | on | **off** | **off** | on | **off** |
| Tools | - | - | on | - | - |

Edit contents once under **Modes > Edit the folder library**. Flip switches per
mode under **Modes > (a mode) > Arrange home screen**, or long-press any folder
on the home screen to land straight on its switches.

This matters more than tidiness. Under an allowlist guard a folder switched off
is not hidden, it is forbidden: turning "Social" off for Work is the same act as
saying Work may not open Instagram.

## The home screen is the allowlist

Each mode's home screen is a list of rows. A row is either a single app or a
folder from the library:

```
9:41
Thursday 11 September
◑ Work   Sprint planning

 ● 10:00  Sprint planning
 ○ 11:30  1:1
 ○ 14:00  Design review

Phone
Messages
Calendar
Work        5
Everyday    3
Money       9

everything else
```

Tap a folder to expand it in place, long-press to edit which folders this mode
uses. Apps you reach for constantly sit at the top
level, because a folder you open twenty times a day is just friction.

**When a mode's guard is set to allowlist, this screen is also its permission
list.** Anything not reachable from it gets stopped. That is what answers the
awkward case: an app you installed last week, or one a link opened, was never
put in a folder, so the mode will not let you sit in it. You do not have to
predict what will distract you, which is the thing a blocklist gets wrong.

Each mode picks its own scope, under "If you reach for one anyway":

- **Only apps I set aside** (blocklist) - loose. Anything new is allowed.
- **Anything not on my home screen** (allowlist) - strict. Anything new is not.

These are never stopped under any setting, so you cannot lock yourself out:
Phone, Messages, Contacts, Clock, Settings, the authenticator, the system UI,
the permission controller, the installer, the keyboard, and every money app.

## Money

Banking, cards and payments are treated as their own class, `FINANCE`:

- **Their notifications are allowed in Open, Work and Personal.**
- **Fraud alerts break through every mode, including Sleep and Deep focus.**
  So do one-time passcodes. Anything matching fraud, suspicious activity, a
  declined card, a locked account or an overdraft is marked `alwaysThrough` and
  ignores the mode entirely.
- **Ordinary transactions are ordinary.** "You paid $4.50" is held in Deep focus
  and Sleep and arrives with the batch.
- **Money apps are never guarded,** even in Deep focus, and even if you put one
  on a blocklist by accident. A fraud alert you cannot act on is worse than the
  distraction.
- **Allowing an app does not let it advertise at you.** A bank on the allow list
  still gets its "introducing our new credit card" held, because the promo rules
  are checked first.

The shipped list covers the common US apps (Chase, BofA, Wells Fargo, Capital
One, Citi, Ally, Discover, Amex, USAA, PayPal, Venmo, Cash App, Zelle,
Robinhood, Fidelity, Schwab, Chime, SoFi, Credit Karma, Google Wallet). Yours
may differ, which is what the next section is for.

## Dumping your app list

Package names are the one thing you cannot guess from a desktop, and the shipped
folders are educated guesses about a phone nobody has seen.

**From the phone**, with no computer involved: open the app, go to **Now > Dump
your app list**, and tap **Copy** or **Send**. You get every installed app with
its package name, the folder library, and every mode's home screen with each row
marked on or off. Copy puts it on the clipboard to paste anywhere; Send opens
the share sheet with the report as a text file.

**From a computer**, over adb:

```bash
./tools/pull-apps.sh            # every app, name and package, as a table
./tools/pull-apps.sh --all      # the full report, same as the in-app dump
./tools/pull-apps.sh --kotlin   # constants to paste into Defaults.kt
./tools/pull-apps.sh --json     # machine readable
```

Both routes use the same builder, `AppListExport`. With the app not yet
installed the script falls back to `pm list packages -3`, which gives packages
only.

## How the mode gets chosen

Highest priority wins:

1. **A manual switch** you made in the app, until you clear it
2. **A calendar event** matching a calendar rule. A shorter event beats a longer
   one it sits inside, so a 30 minute block named "deep work" wins over the
   4 hour "Offsite" it lives in
3. **A time of day rule**, e.g. 22:30 to 07:00 is Sleep
4. **The default mode**

Out of the box: any event whose title matches `deep work|focus|heads down|
writing|no meetings|study|blocked` switches you into Deep focus. Any other busy,
non-all-day event switches you into Work. Declined and cancelled events are
ignored, because an invitation you said no to should not reshape your phone.

Transitions are driven by three overlapping mechanisms, because none is reliable
alone: an exact alarm at the next boundary, a 15 minute periodic worker as a
safety net, and a broadcast when the calendar provider changes underneath you.

## How the notification gate works

Every notification is sorted into one class:

`CALL`, `DIRECT`, `MENTION`, `STORY`, `SOCIAL`, `FINANCE`, `PROMO`, `SYSTEM`,
`OTHER`

A mode names the classes that ring through. Everything else is held.

Holding is done by **snoozing**, not cancelling, so the original comes back with
its real icon, its real tap target and its real reply box. At the delivery
window the app also posts a stand-in copy of each held item carrying the
original's tap target and actions, so the batch lands the moment the window
opens rather than whenever the system's snooze timer expires. When the genuine
notification later comes out of snooze, the gate recognises it and takes the
stand-in down. You get one batch, at a time you chose, with nothing lost.

Some things are never touched at all: alarms, calls, navigation, media
transports, and anything ongoing. A rule can also be marked **always through**,
which overrides the mode completely. That is reserved for the short list of
things that are never noise: incoming calls, one-time passcodes, and fraud
alerts.

### Instagram, honestly

There is no Instagram API for DMs or stories, and there will not be one. Every
third party "Instagram companion" either scrapes (bannable) or reads
notifications. This reads notifications.

What that means in practice:

- **DMs are detected reliably.** Not by wording, which is unpredictable, but by
  structure: an Instagram notification carrying an inline reply box is a DM.
  That check lives in `Classifier.classify` and is the single most useful line
  in the app.
- **Stories work, with one setup step.** Instagram only notifies you about a
  story if you have notifications turned on for that person. Turn them on for
  the handful you care about (their profile, the bell icon), and the `STORY`
  class handles the rest. Personal mode lets stories through, Work and Focus
  hold them.
- **Likes, follows, suggestions and "see what X has been up to" are matched by
  text** and classified as `SOCIAL` or `PROMO`. The patterns are in
  `Defaults.notifRules()` and editable in the app under Rules. Instagram changes
  its wording occasionally, so if something starts leaking through, add a rule
  rather than rebuilding.

### People who always get through

Add names under Rules. They are matched against the sender name on the
notification, so one entry covers that person across texts, WhatsApp and
Instagram DMs at once. A match overrides the mode entirely.

## Building it

Needs a JDK 17 or newer and an Android SDK with platform 35.

```bash
./tools/bump-version.sh          # 0.1.1 -> 0.1.2, and the build number
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/Modes-v0.1.2-debug.apk
```

The version lives in `version.properties` at the repo root and the APK is named
after it, so two builds are never confused on the phone. Bump it before every
install: Android refuses to upgrade a package whose `versionCode` has not
increased. `--minor` and `--major` are there when a change deserves it.

If `local.properties` does not point at your SDK, fix that first. Unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

## Installing it

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and tap it.

## First run, in this order

The app has a checklist on the Now screen that shows what is still missing, but
two of these steps are non-obvious on a sideloaded build, so they are written
out here.

1. **Open the app once.** Grant calendar, contacts and notification permissions
   when prompted.

2. **Allow restricted settings.** This is the one that will waste your evening
   if you do not know about it. Android 13 and newer block sideloaded apps from
   the notification-access and accessibility switches until you explicitly
   unlock them:

   > Settings > Apps > Modes > three dots, top right > **Allow restricted
   > settings**

   The switches in the next two steps will be greyed out until you do this.

3. **Notification access.** Settings > Notifications > Device and app
   notifications > Modes. Nothing works without this.

4. **Do Not Disturb access.** Grant it from the checklist. Each mode then
   registers its own rule, which is why your modes show up in Android's own
   Modes/Do Not Disturb screen and survive reboots.

5. **Exact alarms**, so switches land on the minute rather than whenever the
   system gets round to it.

6. **Optional: app guard.** Settings > Accessibility > Modes app guard. It reads
   only the package name of the foreground window. `canRetrieveWindowContent` is
   `false` in the service config, so it cannot see anything on your screen.

7. **Optional: the minimal home screen.** Settings > Apps > Default apps > Home
   app > Modes Home. Press home to see it. To go back, set your old launcher
   again from the same screen.

8. **Turn on Instagram notifications for the few people whose stories you
   actually want.** See above.

9. **Check your folders against reality.** Run `./tools/pull-apps.sh`, then edit
   each mode's home screen in the app. The shipped folders reference apps you
   may not have, and under allowlist an app in no folder is one you cannot open.

## Greyscale

Deep focus and Sleep turn the screen greyscale and dim the wallpaper. Colour is
most of what makes a phone hard to put down, so this does more work than it
looks like it should.

This uses `ZenDeviceEffects`, which needs **Android 15 or newer**. On anything
older the modes still work, just without the visual changes. Device effects on
app-owned rules can also be filtered by the system, so the call is wrapped and
degrades quietly rather than crashing.

## Where things live

```
core/model/Models.kt      every entity and enum, start here
core/Defaults.kt          the five modes, the folder library, schedule,
                          Instagram and money rules
core/model/Models.kt      resolveHomeRows joins modes to the folder library
core/repo/ModeRepository  the cached PolicySnapshot the gate reads
schedule/ScheduleResolver which mode should be running, and when that changes
schedule/ModeScheduler    alarms, the periodic worker, digest windows
apply/ZenController       one AutomaticZenRule per mode
apply/ModeApplier         turns a decision into actual phone behaviour
notify/Classifier         what a notification is, and whether it gets through
notify/NotificationGate   the listener service
notify/DigestPublisher    batching and release
guard/GuardPolicy         whether an app may be opened, pure and well tested
guard/AppGuardService     the foreground app watcher
launcher/LauncherActivity the minimal home screen, agenda and folders
tools/AppListExport       builds the dump, shared by the app and the script
tools/ShareDump           clipboard and share sheet
tools/ExportReceiver      lets adb ask for a dump without opening the app
ui/screens/FoldersScreen  the shared folder library
ui/                       Compose settings, four tabs
```

The gate has to decide on the main thread in microseconds, so it reads
`ModeRepository.snapshot`, an immutable object with pre-compiled regexes, rather
than touching the database. If you add anything the gate needs, add it there.

## Things to know before you rely on it

- **The gate cannot un-snooze.** Once a notification is snoozed there is no API
  to bring it back early, which is why "deliver now" posts stand-in copies
  instead. It is a workaround, and it is the reason for `ReleaseLedger`.
- **Holds are chunked to two hours.** Android will not hold a snoozed
  notification indefinitely, so long holds re-snooze each time the item
  reappears. Self-correcting, but it means a held item passes through the gate
  several times overnight.
- **Stand-in copies come from "Modes", not the original app.** Tapping still
  opens the right thread, and reply boxes still work, because the original
  `PendingIntent` and actions are reused. Those are kept in memory, so if the
  listener service is restarted while something is held, that item degrades to
  plain text.
- **Allowlist mode is strict on purpose,** and the escape hatches are what make
  it safe. Read `GuardPolicy.NEVER_GUARD` and `Pkg.ESSENTIAL` before changing
  anything there. `GuardPolicyTest` asserts every one of them stays reachable.
- **The guard reacts to a window change,** so it stops you a moment after the
  app draws rather than before. It is a pause, not a lock, and a determined
  thumb can still get through by turning the service off in Settings. That is
  deliberate: a tool you cannot escape is one you will uninstall.
- **Database migrations are destructive** (`fallbackToDestructiveMigration`),
  and the schema is at version 3. Upgrading from an earlier build resets your
  modes and folders to the defaults. Fine while iterating. Change it before you
  have settings worth keeping.

## Tests

`app/src/test/` covers the three pieces most likely to be subtly wrong and
hardest to debug on a phone:

- **`ScheduleTest`** - schedule windows that cross midnight, and digest maths.
- **`GuardPolicyTest`** - which apps a mode will and will not open, including an
  assertion that every essential and every money app stays reachable in the
  strictest mode, and that a folder switched off really is unreachable rather
  than merely hidden. If you change the guard or the folder model, this is the
  file that stops you locking yourself out.

```bash
./gradlew :app:testDebugUnitTest
```

38 tests, all passing.
