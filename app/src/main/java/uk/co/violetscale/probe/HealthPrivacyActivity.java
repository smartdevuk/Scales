package uk.co.violetscale.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

public class HealthPrivacyActivity extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        new AlertDialog.Builder(this).setTitle("Scales and your data")
            .setMessage("Your measurements, notes and habits live in this app on your phone. Scales uses Bluetooth to receive scale readings. If you choose Send weights, Scales writes your own weights to Health Connect. You can revoke that access in Android Settings. Scales has no internet permission. You control your exports and backups.")
            .setPositiveButton("Done", (d,w) -> finish()).setOnCancelListener(d -> finish()).show();
    }
}
