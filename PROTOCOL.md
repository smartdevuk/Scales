# Scale compatibility and measurements

Bluetooth bathroom scales use several incompatible formats. Scales matches a specific advertised payload or a discovered service and characteristic before accepting a measurement. Compatibility with a listed format depends on the scale's hardware and firmware revision.

| Device or format | How a weight arrives | Evidence |
| --- | --- | --- |
| Yoda0, supported Chipsea C0 layout | Repeated, non-connectable advertising packets. Open Today before stepping on. | Hardware broadcasts were matched against the scale display. |
| Xiaomi Mi Scale v1 and Mi Body Composition Scale v2, supported advertisement formats | Service data in a Bluetooth advertisement. | Documented formats and parser fixtures. Physical acceptance is pending. |
| Documented Chipsea C0 variant with the recognised product and vendor prefix | Manufacturer data in a Bluetooth advertisement. | Published example and parser fixtures. Physical acceptance is pending. |
| Recognised OKOK C0 rebrands with matching device address | Manufacturer data matches both an accepted model name and the address in its broadcast. | Published example and parser fixtures. Physical acceptance is pending. |
| Icomon/Fitdays boards named `AAA002`, `AAA007`, `AAA013` or `AAA07A` | Manufacturer advertisement includes the reversed device address, a checksum and a final-reading flag. Weight is encoded in thousandths of a kilogram. | Public protocol reference and synthetic fixtures. Physical acceptance is pending. |
| Bluetooth SIG Weight Scale Service (`181D`) | Indication from the Weight Measurement characteristic (`2A9D`) of a selected, connectable device. | Standard specification and parser fixtures. Physical acceptance is pending. |

[Broadcast variants and source references](docs/BROADCAST-PROTOCOLS.md) · [Bluetooth SIG Weight Scale Service](https://www.bluetooth.com/specifications/specs/weight-scale-service-1-0-1/)

## Yoda0 broadcast layout

Android separates the first two bytes of manufacturer-specific data into a little-endian identifier. Index the complete manufacturer field, beginning at `C0`, to interpret the offsets below. The length and AD type (`FF`) are outside these offsets.

| Offset | Meaning in this variant |
| --- | --- |
| 0 | Family marker `C0`. |
| 1 | Burst counter. Repeated frames share it, and it can wrap. |
| 2–3 | Weight in hundredths of a kilogram, big endian. For example, synthetic bytes `1A B8` represent 68.40 kg. |
| 4–5 | Additional word whose physical meaning remains unverified for this variant. It is available in diagnostics. |
| 6–7 and 9–14 | Zeroes in the supported Yoda0 layout. Other products can use these positions differently. |
| 8 | `24` for a zero/start packet and `25` for a nonzero final packet. |

The parser also checks the advertising structure and local-name field. For a fresh Yoda0 burst, the app looks for an observed start and repeated matching final readings before accepting a weight. Joining midway, receiving conflicting values, a first weigh-in or a large change can require confirmation. An eight-bit burst counter alone does not identify a weigh-in or a person. The app checks observed time and keeps repeated packets in one session. [Android scan observation timestamps](https://developer.android.com/reference/android/bluetooth/le/ScanResult#getTimestampNanos())

## Optional impedance on the supported Xiaomi v2 format

A final Xiaomi v2 packet can include impedance in ohms. Scales checks the device's impedance-valid flag and the value. If that field arrives after weight is saved, Scales can attach it to the same record for up to 12 seconds when the device, sample timestamp, weight and units match. The app retains the source measurement without deriving a body-fat percentage. [Broadcast field definitions](docs/BROADCAST-PROTOCOLS.md)

## Standard Weight Scale Service

The selected scale must expose the standard service, characteristic and indication property. Scales subscribes through the Client Characteristic Configuration Descriptor (`2902`). The measurement carries unit flags, a weight and optional additional fields. A device-supplied timestamp has no timezone. A scale's user slot identifies a profile on that scale. Follow the confirmation prompt for first, old or ambiguous readings. [Weight Scale Service specification](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/WSS_v1.0.1/out/en/index-en.html)

## Trying a different scale

Use **Settings → Scale diagnostics** to select the device, record one weigh-in and export the diagnostic JSON. Write down the value and units shown on its display. A useful comparison starts with a sleeping scale and continues through the complete measurement. A public compatibility report can provide protocol and firmware identifiers, observed units and what matched. Inspect any file before sharing it because raw advertising bytes may include device identifiers and personal measurements.

The journal stores supported measurements with their source. For Yoda0, the additional word above has not been established as usable impedance. Unknown layouts stay in diagnostics. No body-fat, muscle or water percentages are inferred from weight alone.
