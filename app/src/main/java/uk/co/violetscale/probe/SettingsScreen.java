package uk.co.violetscale.probe;

import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

final class SettingsScreen {
    private static final String[] MODES = {"track","maintain","gain","lose"};
    private static final String[] MODE_LABELS = {"Just tracking","Maintain weight","Gain weight","Lose weight"};
    private static final String[] UNITS = {"kg","lb","st"};
    private static final String[] UNIT_LABELS = {"Kilograms","Pounds","Stones and pounds"};
    private final HomeActivity home;
    private final Store store;
    private final Ui ui;
    private LinearLayout group;

    static void show(HomeActivity home) { new SettingsScreen(home).render(); }
    private SettingsScreen(HomeActivity home) { this.home=home; this.ui=home.ui; this.store=home.store; }
    private int index(String[] choices, String value) {
        for (int i=0;i<choices.length;i++) if (choices[i].equals(value)) return i;
        return 0;
    }

    private void render() {
        section("Preferences");
        int unit=index(UNITS,store.get("unit","kg"));
        row("Display units",UNIT_LABELS[unit], () -> new AlertDialog.Builder(home)
            .setTitle("Display units").setSingleChoiceItems(UNIT_LABELS,unit,(d,which) -> {
                store.put("unit",UNITS[which]); d.dismiss(); home.refresh();
            }).setNegativeButton("Cancel",null).show());
        int mode=index(MODES,store.get("goalMode","track"));
        String description=MODE_LABELS[mode];
        Double target=number("targetKg"), low=number("rangeLowKg"), high=number("rangeHighKg");
        if (mode==1 && low!=null && high!=null) description+=" · "+home.weight(low)+" to "+home.weight(high);
        if (mode>1 && target!=null) description+=" · "+home.weight(target);
        row("Weight goal",description,() -> chooseGoal(mode));
        Double height=number("heightCm");
        row("Height",height==null?"Optional · used for BMI":decimal(height)+" cm",this::height);
        Store.Entry latest=store.latestWeight();
        if (height!=null && latest!=null) {
            double bmi=latest.kg / Math.pow(height/100,2);
            TextView text=ui.text(String.format(Locale.UK,"Current BMI %.1f · calculated from %s and %s cm.",bmi,home.weight(latest.kg),decimal(height)),14,Ui.MUTED);
            text.setPadding(0,ui.dp(12),0,ui.dp(8)); group.addView(text);
        }
        row("Habits and reminders","Training, meals and weighing days",() -> Habits.settings(home,store,home::refresh));

        section("Your data");
        row("Import, export and backup","CSV history and full Scales backup",home.transfer::menu);
        if (android.os.Build.VERSION.SDK_INT>=34)
            row("Send weights to Health Connect","On demand · weight only",() -> HealthExport.open(home));
        section("Bluetooth scales");
        row("Choose scales again","Select a nearby scale on your next weigh-in",() ->
            new AlertDialog.Builder(home).setTitle("Choose scales again?")
                .setMessage("Scales will ask you to select your scale the next time you weigh.")
                .setNegativeButton("Cancel",null).setPositiveButton("Choose scales",(d,w) -> {
                    home.weigh.forgetDevice(); home.today();
                }).show());
        row("Scale diagnostics","Capture and export a Bluetooth session",() -> home.startActivity(new Intent(home,MainActivity.class)));
        ui.gap(home.content,16);
        TextView privacy=ui.text("Scales 1.3 · Offline, on this phone",13,Ui.MUTED);
        home.content.addView(privacy);
    }

    private void section(String title) {
        ui.gap(home.content,12); home.content.addView(ui.heading(title,20));
        ui.gap(home.content,8); group=ui.card(); home.content.addView(group,-1,-2);
    }
    private void row(String title,String subtitle,Runnable listener) {
        if (group.getChildCount()>0) ui.divider(group);
        LinearLayout line=ui.row();
        line.setGravity(Gravity.CENTER_VERTICAL); line.setMinimumHeight(ui.dp(64));
        line.setBackground(ui.ripple(Ui.SURFACE,9,0)); line.setFocusable(true);
        line.setOnClickListener(v -> listener.run());
        LinearLayout words=ui.column(); ui.pad(words,2,9,4,9);
        words.addView(ui.heading(title,17)); ui.gap(words,3);
        words.addView(ui.text(subtitle,14,Ui.MUTED)); line.addView(words,Ui.flex());
        line.addView(ui.icon("next",Ui.VIOLET),ui.dp(20),ui.dp(20)); group.addView(line,-1,-2);
    }
    private Double number(String key) {
        try { double v=Double.parseDouble(store.get(key,"")); return v>0 && v<=1000?v:null; }
        catch (NumberFormatException e) { return null; }
    }
    private String decimal(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(2,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    private LinearLayout form() { LinearLayout box=ui.column(); ui.pad(box,24,8,24,4); return box; }
    private EditText field(LinearLayout box,String label,String value) {
        TextView caption=ui.text(label,15,Ui.MUTED); box.addView(caption);
        EditText field=new EditText(home); field.setId(View.generateViewId()); caption.setLabelFor(field.getId());
        field.setSingleLine(true); field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(value); field.selectAll(); box.addView(field); return field;
    }
    private AlertDialog editDialog(String title,LinearLayout form) {
        ScrollView scroll=new ScrollView(home); scroll.addView(form);
        return new AlertDialog.Builder(home).setTitle(title).setView(scroll)
            .setPositiveButton("Save",null).setNegativeButton("Cancel",null).show();
    }
    private Double input(EditText field,double min,double max) {
        String value=field.getText().toString().trim().replace(',','.');
        if (value.isEmpty()) return null;
        try { double n=Double.parseDouble(value); if (n>=min && n<=max) return n; }
        catch (NumberFormatException ignored) { }
        field.setError("Enter a value between "+decimal(min)+" and "+decimal(max)); field.requestFocus();
        throw new IllegalArgumentException();
    }
    private void height() {
        LinearLayout box=form(); Double previous=number("heightCm");
        EditText entry=field(box,"Height in centimetres (optional)",previous==null?"":decimal(previous));
        AlertDialog dialog=editDialog("Your height",box);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { Double h=input(entry,30,300); store.put("heightCm",h==null?"":decimal(h)); }
            catch (IllegalArgumentException e) { return; }
            dialog.dismiss(); home.refresh();
        });
    }
    private void chooseGoal(int current) {
        new AlertDialog.Builder(home).setTitle("What are you tracking?")
            .setSingleChoiceItems(MODE_LABELS,current,(d,i) -> {
                d.dismiss();
                if (i==0) {store.put("goalMode","track");home.refresh();}
                else editGoal(i);
            }).setNegativeButton("Cancel",null).show();
    }
    private void editGoal(int mode) {
        boolean range=mode==1;
        boolean pounds=!"kg".equals(store.get("unit","kg"));
        double factor=pounds?0.45359237:1;
        String suffix=pounds?" (lb)":" (kg)";
        LinearLayout box=form();
        box.addView(ui.text(range?"Optionally set a weight range.":"Optionally set a target weight.",15,Ui.MUTED));
        ui.gap(box,12);
        Double a=number(range?"rangeLowKg":"targetKg"), b=number("rangeHighKg");
        EditText first=field(box,(range?"Lower weight":"Target weight")+suffix,a==null?"":decimal(a/factor));
        EditText second=range?field(box,"Upper weight"+suffix,b==null?"":decimal(b/factor)):null;
        AlertDialog dialog=editDialog(MODE_LABELS[mode],box);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Double x=input(first,0.01,1000/factor), y=range?input(second,0.01,1000/factor):null;
                if (range && ((x==null)!=(y==null) || (x!=null && x>=y))) {
                    second.setError("Enter both limits in increasing order, or leave both empty.");return;
                }
                store.put(range?"rangeLowKg":"targetKg",x==null?"":Double.toString(x*factor));
                if(range) store.put("rangeHighKg",y==null?"":Double.toString(y*factor));
                store.put("goalMode",MODES[mode]);
            } catch(IllegalArgumentException e){return;}
            dialog.dismiss();home.refresh();
        });
    }
}
