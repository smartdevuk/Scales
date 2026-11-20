package uk.co.violetscale.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The system picker grants access only to the document selected by the user. */
public final class Transfer {
    private static final int CSV_OUT = 5810, CSV_IN = 5811, BACKUP_OUT = 5812, BACKUP_IN = 5813;
    private static final int LIMIT = 24 * 1024 * 1024;
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Activity host;
    private final Store store;
    private final Runnable changed;

    public Transfer(Activity host, Store store, Runnable changed) {
        this.host = host;
        this.store = store;
        this.changed = changed;
    }
    public void menu() {
        String[] items = {"Export history as CSV", "Import a CSV", "Save a full backup", "Restore from a backup"};
        new AlertDialog.Builder(host).setTitle("Your data").setItems(items,(d,i) -> pick(CSV_OUT+i))
                .setNegativeButton("Close",null).show();
    }
    private void pick(int action) {
        boolean write = action == CSV_OUT || action == BACKUP_OUT;
        boolean csv = action == CSV_OUT || action == CSV_IN;
        Intent i = new Intent(write ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(write ? (csv ? "text/csv" : "application/json") : "*/*");
        if (write) {
            String day = new SimpleDateFormat("yyyy-MM-dd",Locale.UK).format(new Date());
            i.putExtra(Intent.EXTRA_TITLE, (csv ? "scales-history-" : "scales-backup-") + day + (csv ? ".csv" : ".json"));
        }
        try { host.startActivityForResult(i,action); }
        catch (ActivityNotFoundException ex) { error("A document picker isn't available on this phone."); }
    }
    public boolean onActivityResult(int requestCode,int resultCode,Intent data) {
        if (requestCode < CSV_OUT || requestCode > BACKUP_IN) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        io.execute(() -> {
            try {
                TransferCodec codec = new TransferCodec();
                if (requestCode == CSV_OUT || requestCode == BACKUP_OUT) {
                    Store.Snapshot snapshot = store.snapshot();
                    if (snapshot.entries.size() > DataCsv.MAX_ROWS)
                        throw new IOException("The export exceeds the 50,000-record import limit.");
                    String output = requestCode == CSV_OUT ? codec.csv(snapshot.entries) : codec.backup(snapshot);
                    write(uri,output);
                    post(() -> Toast.makeText(host, "Exported " + records(snapshot.entries.size()) + ".",Toast.LENGTH_LONG).show());
                    return;
                }
                String input = read(uri);
                boolean backup = requestCode == BACKUP_IN;
                TransferCodec.Preview preview = backup ? codec.parseBackup(input) : codec.parseCsv(input);
                Set<String> known = new HashSet<>();
                for (Store.Entry entry : store.all()) known.add(entry.id);
                int fresh = 0;
                for (Store.Entry entry : preview.entries) if (!known.contains(entry.id)) fresh++;
                final int newRows = fresh;
                post(() -> preview(preview,backup,newRows));
            } catch (Exception ex) { post(() -> error(detail(ex))); }
        });
        return true;
    }
    private void preview(TransferCodec.Preview p, boolean backup, int fresh) {
        int duplicate = p.duplicates + p.entries.size() - fresh;
        StringBuilder message = new StringBuilder();
        message.append(records(fresh)).append(" to add");
        if (duplicate > 0) message.append("\n").append(duplicate).append(" existing or repeated IDs will be skipped");
        if (p.invalid > 0) {
            message.append("\n").append(p.invalid).append(" invalid ").append(p.invalid == 1 ? "record" : "records").append(" will be skipped");
            for (String issue : p.problems) message.append("\n").append(issue);
        }
        if (p.skippedSettings > 0) message.append("\n").append(p.skippedSettings).append(" unsupported settings will be skipped");
        if (!p.entries.isEmpty()) {
            message.append("\n\nFile preview");
            DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT,Locale.UK);
            for (Store.Entry e : p.entries.subList(0,Math.min(3,p.entries.size()))) {
                dates.setTimeZone(java.util.TimeZone.getTimeZone(e.zoneId));
                message.append("\n").append(dates.format(new Date(e.timeMs))).append("  ·  ");
                if (e.kg != null) message.append(String.format(Locale.UK,"%.2f kg",e.kg));
                if (e.waistCm != null) {
                    if (e.kg != null) message.append(" and ");
                    message.append(String.format(Locale.UK,"%.1f cm waist",e.waistCm));
                }
            }
        }
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(host.getResources().getDisplayMetrics().density * 24);
        panel.setPadding(pad,pad/2,pad,0);
        TextView count = new TextView(host); count.setText(message.toString()); panel.addView(count);
        CheckBox apply = new CheckBox(host);
        if (backup && !p.settings.isEmpty()) {
            apply.setText("Apply preferences, goals and habits from this backup (" + p.settings.size() + ")");
            apply.setChecked(false);
            panel.addView(apply);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(host);
        scroll.addView(panel);
        AlertDialog.Builder dialog = new AlertDialog.Builder(host).setTitle(backup ? "Restore preview" : "Import preview")
                .setView(scroll).setNegativeButton("Cancel",null);
        if (fresh > 0 || (backup && !p.settings.isEmpty()))
            dialog.setPositiveButton(backup ? "Restore" : "Import",(d,w) -> merge(p,apply.isChecked()));
        dialog.show();
    }
    private void merge(TransferCodec.Preview preview, boolean applySettings) {
        io.execute(() -> {
            try {
                Map<String,String> prefs = applySettings ? preview.settings : Collections.emptyMap();
                int n = store.merge(preview.entries,prefs,applySettings);
                post(() -> {
                    if (changed != null) changed.run();
                    String message = "Added " + records(n) + (applySettings ? " and applied backup settings." : ".");
                    Toast.makeText(host,message,Toast.LENGTH_LONG).show();
                });
            } catch (Exception ex) { post(() -> error(detail(ex))); }
        });
    }
    private String read(Uri uri) throws IOException {
        try (InputStream input = host.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("The file could not be opened.");
            byte[] chunk = new byte[8192];
            int size;
            while ((size=input.read(chunk)) >= 0) {
                if (size + buffer.size() > LIMIT) throw new IOException("Files must be 24 MB or smaller.");
                buffer.write(chunk,0,size);
            }
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            String text = decoder.decode(ByteBuffer.wrap(buffer.toByteArray())).toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }
    private void write(Uri uri,String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // Refuse before opening/truncating the destination if it would exceed our restore limit.
        if (bytes.length > LIMIT) throw new IOException("The export exceeds the 24 MB import limit.");
        try (OutputStream out = host.getContentResolver().openOutputStream(uri,"wt")) {
            if (out == null) throw new IOException("The file could not be opened for writing.");
            out.write(bytes); out.flush();
        }
    }
    private void post(Runnable action) {
        host.runOnUiThread(() -> { if (!host.isFinishing() && !host.isDestroyed()) action.run(); });
    }
    private static String records(int n) { return n + (n == 1 ? " record" : " records"); }
    private static String detail(Exception ex) {
        return ex instanceof IllegalArgumentException || ex instanceof IOException ?
                (ex.getMessage() == null ? "The document could not be processed." : ex.getMessage()) :
                "The document could not be processed. Check the format and try again.";
    }
    private void error(String message) {
        new AlertDialog.Builder(host).setTitle("File transfer").setMessage(message).setPositiveButton("OK",null).show();
    }
}
