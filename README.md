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
| Minimal home screen | A text-only launcher showing only the current mode's apps | Yes |
| App guard | An `AccessibilityService` that puts a pause in front of apps you set aside | Yes |

The last two are genuinely optional. Skip them and everything else still works.

## Five modes ship with it

| Mode | Gets through | Home screen | Delivery |
|---|---|---|---|
| Open | everything | your usual apps | immediately |
| Work | calls, DMs, mentions | work apps | 12:30 and 17:00 |
| Deep focus | calls only | four apps, screen greyscale | when the mode ends |
| Personal | calls, DMs, mentions, stories | social and camera | every 90 minutes |
| Sleep | calls from starred contacts only | clock, phone, messages | 07:30 |

Edit any of them in the app. Nothing here is hardcoded.

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

`CALL`, `DIRECT`, `MENTION`, `STORY`, `SOCIAL`, `PROMO`, `SYSTEM`, `OTHER`

A mode names the classes that ring through. Everything else is held.

Holding is done by **snoozing**, not cancelling, so the original comes back with
its real icon, its real tap target and its real reply box. At the delivery
window the app also posts a stand-in copy of each held item carrying the
original's tap target and actions, so the batch lands the moment the window
opens rather than whenever the system's snooze timer expires. When the genuine
notification later comes out of snooze, the gate recognises it and takes the
stand-in down. You get one batch, at a time you chose, with nothing lost.

Some things are never touched at all: alarms, calls, navigation, media
transports, and anything ongoing.

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
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

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
core/Defaults.kt          the five modes, the schedule, the Instagram rules
core/repo/ModeRepository  the cached PolicySnapshot the gate reads
schedule/ScheduleResolver which mode should be running, and when that changes
schedule/ModeScheduler    alarms, the periodic worker, digest windows
apply/ZenController       one AutomaticZenRule per mode
apply/ModeApplier         turns a decision into actual phone behaviour
notify/Classifier         what a notification is, and whether it gets through
notify/NotificationGate   the listener service
notify/DigestPublisher    batching and release
guard/AppGuardService     the foreground app watcher
launcher/LauncherActivity the minimal home screen
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
- **The app guard only acts on a mode's blocklist,** never on "anything not on
  the home screen". Guarding by omission would trap you out of apps you never
  thought to list. Settings, Phone, Messages, Contacts, Clock, the system UI and
  the permission controller are never guarded, so there is always a way out.
- **Database migrations are destructive** (`fallbackToDestructiveMigration`).
  Fine while iterating. Change it before you have settings worth keeping.

## Tests

`app/src/test/` covers the two pieces most likely to be subtly wrong and hardest
to debug on a phone: schedule windows that cross midnight, and digest window
maths. Run them before trusting a change to either.
