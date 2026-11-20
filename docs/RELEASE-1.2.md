# Scales 1.2

**Today** now places the weight chart directly underneath the current measurement. The chart distinguishes an empty history, a single reading and several measurements from the same day. A saved weigh-in highlights the actual new observation. Chart animation follows Android's animation-scale setting.

The current-weight box shows the change in your smoothed weight trend over the week and fortnight ending on your latest saved weigh-in. Each comparison appears independently as you build up enough history. An arrow indicates the direction and a flat indicator marks changes that round to zero in your chosen units.

The app now displays the name **Scales**. Its application identity and version 1 JSON backup format remain compatible with earlier installations. Existing users can install an update signed with the same key and keep their history, settings and diagnostic tools.

Bluetooth support includes the established Yoda0 broadcast format, documented Xiaomi and Chipsea C0 variants, four named Icomon/Fitdays boards, and the standard Bluetooth Weight Scale Service. Check the compatibility evidence for your model. Additional formats have automated protocol coverage and await physical acceptance on representative scales. [Compatibility guide](../PROTOCOL.md)

To verify an installation, open Today before waking the scale and compare the first reading with the scale display. Let it sleep, weigh again, and check that exactly one entry was saved for each intended weigh-in. [Installation and first weigh-in](../README.md#first-weigh-in) · [Validation](../VALIDATION.md)
