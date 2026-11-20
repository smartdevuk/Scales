# Scales 1.2 validation

## Reproduce the checks

Use JDK 17 and Android SDK 34. The JVM fixtures exercise the production code for advertisements, measurement parsing, session decisions, unit conversion, trend calculations, habit calendars and CSV handling. The database suite uses real SQLite files, including independent processes for reopen and schema migration. Windows commands follow.

```powershell
.\test.ps1
.\tests\run-data-tests.ps1
.\build.ps1
```

The standalone build compiles resources and Java sources, generates DEX, aligns the package, signs it and runs Android's signature verifier. The Gradle project provides an additional Android Studio build route.

Android's package tools inspected `Scales-1.2.apk` and reported the label **Scales**, package `uk.co.violetscale.probe`, version name **1.2**, version code **4**, minimum API **23** and target API **34**. The v1, v2 and v3 signatures verified with the retained compatibility certificate. The packaged manifest declares no internet permission. [Signing and updates](BUILDING.md)

## Checked during development

All nine JVM fixture suites passed.

| Suite | Checks passed |
| --- | ---: |
| Broadcast packet decoders | 149 |
| Standard GATT Weight Measurement decoder | 183 |
| Multi-scale routing, sessions and persistence metadata | 41 |
| Yoda0 weighing sessions | 55 |
| Diagnostic decoders | 26 |
| Trend calculations | 59 |
| CSV handling | 19 |
| Habit calendar and reminder times | 15 |
| Weight units and conversions | 20 |

SQLite-backed checks passed for normal operations, a process restart and both historical schema migration paths. A separate simulated Android GATT transport exercise passed 31 assertions covering legacy and modern subscriptions, callback ordering, timeouts, failures and cleanup.

Native Android layout previews covered empty history, one reading, several same-day readings and a multi-day chart, with animations enabled and disabled. The public screen previews use synthetic measurements. The public examples omit private weigh-ins, capture files and machine paths. Source references and relative documentation links were checked. [Screen previews](docs/screenshots/README.md)

The Today preview showed both comparison periods for a multi-week history and only the supported period for a historical saved weight. Checks also covered sparse history and unsaved readings. The trend fixtures cover exact dates, short gaps, repeated readings on a date and both UK clock changes.

## Hardware evidence

Real Yoda0 radio captures establish that the observed weight field matches the scale display and that the scale repeats the final advertisement. A comparison under different contact conditions left its second word unverified. The added Xiaomi, documented Chipsea C0 and Bluetooth SIG formats require acceptance on representative hardware. [Protocol coverage](PROTOCOL.md)

## Check on a phone

| Scenario | Expected result |
| --- | --- |
| Open Today before stepping onto the selected scales | The first accepted weight matches the display, and History gains one entry. |
| Wake the scale again after it sleeps | A second intentional weigh-in is preserved even at the same weight. |
| Open midway through a burst or receive a surprising weight | The app requests confirmation where the session or ownership is uncertain. |
| Restart the app, change display units or install a same-signer update | Previously saved readings retain their values. |
| Turn Bluetooth off, revoke scan access or leave the scale asleep | The weighing screen offers recovery, and manual entry remains available. |
| Add a waist-only entry or change a record | The relevant trend and history update. |
| Enable a reminder or send eligible measurements to Health Connect | Android's permission controls apply, and the optional action works on the selected device. |

Renderings use synthetic data and demonstrate screen layout. A radio capture, a parser fixture and a rendering exercise different parts of the app. Final physical acceptance requires testing the release APK with a real phone and scale.
