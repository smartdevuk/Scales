# History, exports and backups

Open **Settings → Import, export and backup**. **Save a full backup** writes a readable JSON file to a destination you choose with Android's document picker. Use it before moving phones. Bluetooth diagnostics uses a separate capture format for troubleshooting.

## Restore a backup

**Restore from a backup** previews new, repeated and invalid records. Each weigh-in has a stable ID, so two deliberate readings at the same weight can both survive a restore. A matching ID preserves the current local entry. After confirmation, the app applies the import in a single database transaction.

Select **Apply preferences, goals and habits from this backup** to restore display units, height, targets, habits, dated check-ins and reminder settings. On a new phone, select your scale and grant any required Android permissions there. The version 1 backup format remains compatible with earlier releases. Its historical format identifier is `uk.co.violetscale.backup`.

## Import or export a CSV

Exported CSV includes measurement time and offset, original weight value and unit, current kg, optional waist in cm, measurement sources, notes, stable ID and decoder metadata. An edit to a scale weight retains the original value and marks the edited weight as manual. Optional impedance in ohms is present only where supported and available. Empty optional fields represent missing observations.

A minimal synthetic example follows.

```csv
date_time,weight_kg,waist_cm,note
2025-02-01T08:00:00+00:00,68.4,,Example weight
2025-02-02T08:30:00+00:00,,82.5,Example waist
```

Use ISO date/time with an explicit UTC offset, or `time_ms` with Unix epoch milliseconds. Each row needs at least one of `weight_kg`, `weight_lb` or `waist_cm`. Provide an optional `id` to retain the same identity across files. Without an ID, identical time, measurements and note generate the same identity on later imports.

The importer accepts UTF-8 with an optional byte-order mark, quoted cells and multiline notes. Arbitrary CSV inputs arrive with imported provenance. A full app export can preserve the recorded sources as well. A leading apostrophe protects formula-like free text in spreadsheet exports. The app reverses that escape when importing its own format.

Scales previews up to three records and reports invalid rows and duplicate IDs before import. The document limit is 24 MiB and 50,000 measurement rows.

## Health Connect

On Android 14 or later with Health Connect available, use **Settings → Send weights to Health Connect**. The app requests permission to write weight and sends locally recorded manual or scale weights. Repeating the action publishes eligible edits and removes previously exported entries that you subsequently deleted. Waist measurements, check-ins, notes and imported readings stay in Scales. You can withdraw access in Android's Health Connect settings.

## Persistence checks

On Windows, run `.\tests\run-data-tests.ps1` with Android Studio and SDK 34 installed. The script uses Android Studio's Java runtime, SQLite JDBC driver and layoutlib. Separate Java processes exercise restart and database migration using real SQLite files. The suite also tests rollback, stable IDs, edits, undo, measurement sources, backup merge and CSV round trips.
