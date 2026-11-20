# Bluetooth advertisement support

Scales reads these broadcast formats directly from foreground Bluetooth scan results. The decoder implements the documented wire formats independently. The tests run on published example bytes and constructed edge cases. The zero-address Yoda0 has separate [physical capture evidence](../PROTOCOL.md).

| Format | Identification | Data used |
| --- | --- | --- |
| Xiaomi Mi Smart Scale, v1 layout | Complete name `MI_SCALE` or `MI SCALE2`, plus 10 bytes of service data under UUID `181D` | Live or settled weight in kg or lb |
| Xiaomi Mi Body Composition Scale, v2 layout | Complete name `MIBFS`, `MIBCS` or `MI SCALE2`, plus 13 bytes of service data under UUID `181B` | Live or settled weight in kg or lb. Settled impedance in ohms when its status and value agree. |
| Published Chipsea C0 product | 15 manufacturer bytes starting with `C0`, product `0A01`, address prefix `B4:56:5D`, flags `24/25`. When the radio supplies an address, it must match the embedded one. | Weight in kg. The auxiliary word stays in diagnostics. |
| Address-bearing plain OKOK C0 rebrands | Exact name `Yoda0`, `Yoda1`, `OMIYA-C39-HW`, `Chipsea-BLE` or `ADV`, 15-byte C0 frame, flags `24/25` and all six embedded address bytes matching the actual advertiser | Weight in kg. The LEDINUO Yoda0 example passes this branch. |
| Icomon / Fitdays AAA boards | Exact names `AAA002`, `AAA007`, `AAA013` or `AAA07A`, a reversed copy of the actual advertiser address, the weight frame type and a matching 5-bit checksum | Live or settled weight encoded in whole grams. The identifier for `AAA07A` includes the Posture FG220LB reported by openScale. |

“V1” and “v2” describe packet layouts. The retail name “Mi Smart Scale 2” alone gives insufficient information about its protocol. openScale documents a weight-only model that shares a name with a composition model. Scales uses both the device name and the measurement payload. A name from the same advertiser's scan response can supply an absent name. A service-list advertisement alone carries no weight. [openScale Mi handler](https://github.com/oliexdev/openScale/blob/master/android_app/app/src/main/java/com/health/openscale/core/bluetooth/scales/MiScaleHandler.kt) · [Weight-only model report](https://github.com/oliexdev/openScale/issues/1489)

## Xiaomi

The saved radio record uses advertising data type `16` for service data. The two little-endian UUID bytes precede the fields below. Offsets start at the first byte after the UUID.

| Family | Offset | Interpretation |
| --- | --- | --- |
| v1 | 0 | Bit 0 selects pounds. Bit 5 marks a settled weight. Bit 7 indicates a removed load in openScale's original investigation. Scales excludes bit-7 packets from history. |
| v1 | 1–2, 3–9 | Little-endian weight followed by a local calendar reading. The supported controls use base `02` for kg and `03` for lb. |
| v2 | 0–1 | Unit code `02` kg or `03` lb. In byte 1, bit 5 indicates settled, bit 7 indicates load removed and bit 1 indicates impedance present. |
| v2 | 2–8, 9–10, 11–12 | Local calendar reading, little-endian raw impedance, then little-endian weight. |

The weight word represents 0.005 kg or 0.01 lb per count. Scales retains the original unit, value and encoding increment. This increment describes the data format. Xiaomi quotes a 50 g minimum displayed division for the Body Composition Scale 2. A stable v2 frame can contribute valid impedance between 1 and 2999 Ω. Missing impedance, zero, overflow and the published `FFFD` sentinel leave useful weight intact. The reviewed jin conversions differ between implementations, so the decoder accepts the evidenced kg and lb branches. [Xiaomi specifications](https://www.mi.com/uk/product/mi-body-composition-scale-2/specs/) · [ESPHome passive parser](https://api-docs.esphome.io/xiaomi__miscale_8cpp_source) · [openScale field notes](https://github.com/oliexdev/openScale/wiki/Xiaomi-Bluetooth-Mi-Scale)

Later v1 documentation labels `A2` differently from the original openScale control-bit observations. A removed-load value can repeat the previous weight. Scales follows the original bit definitions and excludes that packet. Both descriptions agree on the v2 settled and removed bits. The scale's clock can reset to 1970 and lacks a timezone. A valid local calendar token can help detect repeats within a weigh-in, while the phone supplies the recorded instant. [Original v1 investigation](https://github.com/oliexdev/openScale/wiki/Xiaomi-Bluetooth-Mi-Scale) · [V2 capture and worked examples](https://github.com/ipanalytics/Xiaomi-Scale-Unlocked-Reverse-Engineering/blob/main/docs/protocol.md)

## Chipsea C0

The entire manufacturer field starts with the C0 marker and the rolling sequence. Big-endian bytes 2–3 encode hundredths of a kilogram for the accepted `24/25` flags. Byte 8's low bit indicates completion. The sequence can wrap and has meaning only within the device's recent broadcasts. All-zero or all-FF address fields fail the address-bearing rebrand check. The zero-address Yoda0 uses the narrower captured signature documented separately.

The BodyScale author's example includes the product bytes and OUI from the first C0 branch above. The independent LEDINUO report has product bytes `00 00` and an embedded address matching its Yoda0. Its auxiliary value stayed at `1388` in several conditions. BodyScale documents encrypted C0 variants, while openScale documents additional CA/FF variants with distinct layouts. These observations support limiting the public C0 decoder to the plain, identified layouts. [BodyScale capture](https://github.com/qo0p/BodyScale#how-it-works) · [Protocol families](https://github.com/qo0p/BodyScale/blob/main/docs/analysis.md) · [openScale OKOK handler](https://github.com/oliexdev/openScale/blob/master/android_app/app/src/main/java/com/health/openscale/core/bluetooth/scales/OkOkHandler.kt) · [LEDINUO field report](https://17fun.eu.org/posts/ledinuo-cm311-bt/)

For these additional C0 models, a zero-weight `24` frame alone cannot establish that a new load has arrived. The available reports establish the low completion bit and the final weight. A nonzero unsettled frame provides stronger evidence that weighing has begun. A listening session that begins on an already-completed result may require confirmation.

## Icomon / Fitdays AAA series

After the manufacturer ID and reversed address, the next six bytes use the high byte of the manufacturer ID as an XOR mask. The decoded weight packet has type `AD`. Its four-byte big-endian value contains the final flag in bit 31 and the weight in grams in the low 18 bits. Scales checks the low five bits of the data checksum before accepting the weight. The packet carries no timestamp or durable reading identifier. Other Fitdays models use different radios and protocols. [openScale AAA-series protocol](https://github.com/oliexdev/openScale/blob/master/android_app/app/src/main/java/com/health/openscale/core/bluetooth/scales/AAAxHandler.kt) · [Supported model matrix](https://github.com/oliexdev/openScale/wiki/Supported-scales-in-openScale)

## Fixture coverage and hardware checks

The [decoder fixtures](../tests/BroadcastFixtures.java) replay the openScale v1 measurement-layout sample (94.3 kg), the published v2 settled and step-off samples (75 kg and removed load), the BodyScale example (58.4 kg) and LEDINUO's baseline sample (85 kg). These numerical examples come from third-party public reports. Constructed cases cover the Icomon final and live gram fields, checksum and address mismatches, pounds, date validity, missing or implausible impedance, repeated frames, truncation, conflicting advertisements and seeded malformed bytes. The Icomon examples are synthetic.

Actual weighing on each additional device remains unverified. Compare the scale display with Scales during a full step-on and step-off cycle, repeat after the scale sleeps, then check the history count and original units. In a v2 trial, record whether a later impedance reading belongs to the same weighing. Omit whole-device compatibility claims until this physical check passes.
