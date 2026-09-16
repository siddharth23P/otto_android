# Otto for Android

An agent that does what you ask on your own phone -- "make my font bigger",
"install Signal", "find 2 litres of milk on Blinkit" -- with
[Otto](https://github.com/siddharth23P/otto_agent) as its brain. It reads the
screen of the app in front through Android's accessibility service, taps,
types and scrolls on your behalf, and stops at anything to do with money:
it never acts inside payment or banking apps, never enters a PIN, OTP, CVV or
password, and hands a purchase to you at the payment page.

## Where Otto runs

One app, three ways to reach the agent, behind one seam (`transport/`):

| mode | what it is | status |
| --- | --- | --- |
| **embedded** | Otto's Python inside the APK (Chaquopy, Python 3.13) | gated on `wheels/`: seven compiled packages Otto needs have no Android wheel on PyPI, and `.github/workflows/wheels.yml` builds them |
| **local serve** | `otto serve` in a Linux userland on the phone, over `ws://127.0.0.1` | planned; the same protocol as below |
| **remote serve** | `otto serve` on a computer on your Wi-Fi | works today: pair from Settings |

The hands are always local: the accessibility service, the guard and the UI
are the same in every mode.

## Build

```bash
./gradlew assembleDebug                      # embedded runtime off (default): the app pairs with otto serve
./gradlew -PembeddedPython=true assembleDebug # once wheels/ carries the Android wheels
./gradlew testDebugUnitTest
pip install -r app/src/main/python/requirements.txt pytest && pytest -q app/src/main/python/tests
```

Android Studio Ladybug or newer, JDK 17, compileSdk 35, minSdk 30.

## Pair with a computer

```bash
git clone https://github.com/siddharth23P/otto_agent && cd otto_agent && uv sync
uv run otto serve --host 0.0.0.0        # prints ws://<ip>:8765/#<token>
```

Paste that line into Settings → "Pair with otto serve" in the app. Keys stay
on the computer; the phone only lends its hands.

## The money guard

`app/src/main/assets/guard_rules.json` is a byte-identical copy of Otto's
`agent/phone/assets/guard_rules.json` (CI checks). The phone enforces it in
`guard/PolicyGuard.kt` before every gesture; Otto pre-checks the same rules so
a refused call costs no round trip. A denied package is never described; a
sensitive screen (an input field asking for a PIN or OTP, a secure window)
hands over; a pay button is never tapped; a Send/Delete/Confirm is tapped
only through `phone_commit`, which Otto's mutation gate holds once.

## Layout

```
app/src/main/python/otto_app/   bootstrap · compat · backend · entry  (the Kotlin ⇄ Python surface)
app/src/main/java/dev/otto/phone/
  transport/   AgentTransport, EmbeddedTransport, ServeTransport, ServeProtocol, EventBus
  bridge/      PyBridge (what Python calls), Envelope
  access/      OttoAccessibilityService, TreeWalker, UiNode, Gestures
  guard/       GuardRules, PolicyGuard
  device/      AppCatalog, SettingsPages, PlayStore
  service/     OttoForegroundService
  data/        Prefs, Crypt (Android Keystore)
  ui/          MainActivity, ChatViewModel, Screens
wheels/                          Android wheels from wheels.yml
.github/workflows/               ci · wheels · compat · emulator
```

## Staying current with Otto

`app/src/main/python/requirements.txt` pins one Otto release; Dependabot bumps
it daily, `compat.yml` runs the bridge against the pin and the last three
releases, and `otto_app/compat.py` refuses an `API_VERSION` it was never
tested against rather than guessing.

MIT licensed.
