# Design and protocol references

## Weighing and tracking

| Design choice in Scales | Reference |
| --- | --- |
| A completed weigh-in can be recorded while the phone is listening, with a visible confirmation after saving. | [Withings measurement synchronisation](https://support.withings.com/hc/en-us/articles/115009124307-Body-Syncing-my-data) |
| Show a smoothed trend alongside individual observations. Let the user choose their own goal and weighing frequency. | [Happy Scale](https://happyscale.com/) and [MacroFactor weight trend](https://help.macrofactorapp.com/en/articles/21-weight-trend) |
| Keep measurements portable as timestamped rows with explicit units. | [Libra CSV documentation](https://libra-app.eu/support/csv/) |

Scales uses its own trend calculation. For each local day, it takes the median of that day's readings, then gives the next observation a weight of `1 − 2^(-elapsedDays / 7)` in the running estimate. A week between observations gives the new daily median half the weight. Each observation remains individually available in History.

## Bluetooth sources

The Bluetooth SIG defines the Weight Scale Service, including the Weight Measurement characteristic and its optional fields. Android's GATT APIs expose service discovery, characteristic properties and indication callbacks. [Weight Scale Service 1.0.1](https://www.bluetooth.com/specifications/specs/weight-scale-service-1-0-1/) · [Android GATT services](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)

BodyScale documents several broadcast packet families used by Chipsea-based scales and provides examples of their layouts. openScale maintains separate handlers for OKOK broadcasts and Chipsea GATT devices. BioScale describes an additional family of connectable scales. These public references helped distinguish similar-looking transports. A model name or service UUID alone does not establish a device's packet layout or the meaning of an unidentified field. [BodyScale protocol notes](https://github.com/qo0p/BodyScale/blob/main/docs/analysis.md) · [openScale](https://github.com/oliexdev/openScale) · [BioScale](https://github.com/brumathey/bioscale-okok-alternative)

The openScale repository lists GPL-3.0, BioScale lists MIT, and BodyScale's author states that a licence has yet to be chosen. These projects are cited for their published protocol observations. [openScale licence](https://github.com/oliexdev/openScale#license) · [BioScale licence](https://github.com/brumathey/bioscale-okok-alternative#license) · [BodyScale licence statement](https://github.com/qo0p/BodyScale#license)

See [the protocol guide](PROTOCOL.md) and [broadcast decoder references](docs/BROADCAST-PROTOCOLS.md) for the formats the app accepts. Body composition requires both an identified input and a suitable interpretation. A vendor app showing a percentage is insufficient evidence that a scale transmits the percentage over Bluetooth.

## Android platform choices

| Choice | Platform reference |
| --- | --- |
| Scanning is bounded to an active session. Modern Android requires Bluetooth permissions, and its `neverForLocation` option can filter some BLE beacons. | [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) · [BLE background guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background) |
| App-private history has explicit backup rules. A person can export a separate file through the document picker. | [Android Auto Backup](https://developer.android.com/identity/data/autobackup) |
| Optional reminders use inexact local alarms and respect notification permission. | [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms) · [Notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission) |
| Optional Health Connect export uses stable weight record IDs and versions. | [Writing Health Connect data](https://developer.android.com/health-and-fitness/health-connect/write-data) |
| The package and signing identity determine whether Android can install one release over another. | [Android app updates](https://developer.android.com/google/play/app-updates) |
