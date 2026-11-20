package uk.co.violetscale.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.SimpleTimeZone;

public final class HomeActivity extends Activity implements WeighController.Listener {
    public Ui ui;
    public Store store;
    public Transfer transfer;
    public WeighController weigh;
    public LinearLayout content;
    private LinearLayout root, nav, snackbar, listBlock;
    private ScrollView scroll;
    private TrendPlot todayChart;
    private TrendSeries homeWeights;
    private Store.Entry latestHomeWeight, savedHomeWeight, comparisonAnchor;
    private LinearLayout comparisons;
    private String displayUnit="kg";
    private String pendingWeightPoint;
    private String page="Today", weighState="idle", weighMessage="", lastSession, renderedDay;
    private Double liveKg;
    private boolean foreground, editorOpen;
    private String editingId;
    private EditText draftStones, draftWeight, draftWaist, draftNote;
    private Calendar draftTime;
    private int historyLimit=50;
    private TextView heroLabel, heroValue, heroTime, statusTitle, statusMessage;
    private View statusDot;
    private Button weighAction, undoWeigh;
    private static final int MATCH=-1, WRAP=-2;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui=new Ui(this); store=new Store(this);
        transfer=new Transfer(this,store,this::measurementChanged);
        weigh=new WeighController(this,this);
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(state!=null) { page=state.getString("page","Today"); historyLimit=state.getInt("limit",50); }
        draw();
        if(state!=null && state.getBoolean("editing")) {
            String id=state.getString("editingId");
            Store.Entry entry=id==null?null:store.find(id);
            if(id==null || entry!=null) editor(entry,state);
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        boolean onToday=page.equals("Today");
        today();
        if(onToday && "uk.co.violetscale.WEIGH".equals(intent.getAction()) && foreground && !editorOpen) weigh.start();
    }
    @Override protected void onStart() {
        super.onStart(); foreground=true;
        if(!date(System.currentTimeMillis(),"yyyyMMdd Z").equals(renderedDay)) refresh();
        if(page.equals("Today") && !editorOpen) { animateChartArrival(); weigh.start(); }
    }
    @Override protected void onStop() {
        foreground=false; weigh.stop(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onStop();
    }
    @Override protected void onResume() { super.onResume(); Habits.notificationChanged(this); }
    @Override public void onBackPressed() {
        if(!page.equals("Today")) { today(); return; } super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("page",page); state.putInt("limit",historyLimit);
        if(editorOpen && draftWeight!=null) {
            state.putBoolean("editing",true); state.putString("editingId",editingId);
            if(draftStones!=null)state.putString("draftStones",draftStones.getText().toString());
            state.putString("draftWeight",draftWeight.getText().toString());
            state.putString("draftWaist",draftWaist.getText().toString());
            state.putString("draftNote",draftNote.getText().toString());
            state.putLong("draftTime",draftTime.getTimeInMillis());
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(requestCode,permissions,grants);
        if(requestCode==HealthExport.REQUEST) { HealthExport.permissionResult(this); return; }
        if(requestCode==ReminderReceiver.REQUEST) { Habits.notificationChanged(this); return; }
        weigh.permissionsResult(requestCode);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(!transfer.onActivityResult(requestCode,resultCode,data)) weigh.activityResult(requestCode,resultCode);
    }

    public void today() { show("Today"); }
    public void refresh() { int y=scroll==null?0:scroll.getScrollY(); draw(); scroll.post(()->scroll.scrollTo(0,y)); }
    private void show(String next) {
        if(next.equals(page)) { if(scroll!=null) scroll.smoothScrollTo(0,0); return; }
        if(page.equals("Today")) { weigh.stop(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        page=next; draw();
        if(page.equals("Today") && foreground && !editorOpen) { animateChartArrival(); weigh.start(); }
    }
    private void draw() {
        heroValue=null; listBlock=null; todayChart=null; comparisons=null;
        homeWeights=null; latestHomeWeight=null; savedHomeWeight=null; comparisonAnchor=null;
        displayUnit=store.get("unit","kg");
        renderedDay=date(System.currentTimeMillis(),"yyyyMMdd Z");
        root=ui.column(); root.setBackgroundColor(Ui.BG); root.setFocusableInTouchMode(true);
        LinearLayout top=ui.row(); ui.pad(top,22,3,13,1); top.setMinimumHeight(ui.dp(58));
        top.addView(ui.icon("mark",Ui.VIOLET),ui.dp(28),ui.dp(28));
        TextView brand=ui.heading("Scales",23); ui.pad(brand,9,0,0,0); top.addView(brand,Ui.flex());
        View settings=iconAction(page.equals("Settings")?"back":"Settings",page.equals("Settings")?"Back to Today":"Profile and settings",
                ()->{if(page.equals("Settings")) today(); else show("Settings");}); top.addView(settings);
        root.addView(top,MATCH,WRAP);
        scroll=new ScrollView(this); scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false);
        content=ui.column(); ui.pad(content,20,16,20,23); scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(MATCH,0,1));
        snackbar=ui.column(); root.addView(snackbar,MATCH,WRAP);
        ui.divider(root);
        nav=ui.row(); ui.pad(nav,8,3,8,5);
        for(String tab:new String[]{"Today","Trends","History"}) addNav(tab);
        root.addView(nav,MATCH,WRAP);
        setContentView(root);
        if(page.equals("Today")) drawToday();
        else if(page.equals("History")) drawHistory();
        else if(page.equals("Trends")) TrendsScreen.show(this);
        else SettingsScreen.show(this);
    }
    private View iconAction(String icon,String desc,Runnable action) {
        FrameLayout f=new FrameLayout(this); f.setMinimumHeight(ui.dp(48)); f.setMinimumWidth(ui.dp(48));
        f.setContentDescription(desc); f.setFocusable(true); f.setBackground(ui.ripple(Ui.BG,24,0));
        f.setOnClickListener(v->action.run());
        f.addView(ui.icon(icon,Ui.MUTED),new FrameLayout.LayoutParams(ui.dp(23),ui.dp(23),Gravity.CENTER)); return f;
    }
    private void addNav(String tab) {
        boolean selected=page.equals(tab); int colour=selected?Ui.VIOLET:Ui.MUTED;
        LinearLayout button=ui.column(); button.setGravity(Gravity.CENTER); ui.pad(button,0,5,0,4);
        button.setContentDescription(tab); button.setSelected(selected); button.setFocusable(true);
        button.setBackground(ui.ripple(Ui.BG,14,0)); button.setOnClickListener(v->show(tab));
        FrameLayout iconBg=new FrameLayout(this); iconBg.setBackground(ui.shape(selected?0xff30233f:Ui.BG,12,0));
        iconBg.addView(ui.icon(tab,colour),new FrameLayout.LayoutParams(ui.dp(21),ui.dp(21),Gravity.CENTER));
        button.addView(iconBg,ui.dp(52),ui.dp(28)); ui.gap(button,3);
        TextView label=ui.text(tab,12,colour); label.setGravity(Gravity.CENTER); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(label); nav.addView(button,Ui.flex());
    }
    private String date(long time,String pattern) { return new SimpleDateFormat(pattern,Locale.UK).format(new Date(time)); }
    private String recordedDate(Store.Entry entry,String pattern) {
        SimpleDateFormat fmt=new SimpleDateFormat(pattern,Locale.UK);
        fmt.setTimeZone(new SimpleTimeZone(entry.offsetMinutes*60_000,entry.zoneId));
        return fmt.format(new Date(entry.timeMs));
    }
    private static String decimal(double d) { return new DecimalFormat("0.##",DecimalFormatSymbols.getInstance(Locale.UK)).format(d); }
    private String unit() { return displayUnit; }
    public String weight(double kg) { return amount(kg)+" "+suffix(); }
    private String suffix() { return unit().equals("kg")?"kg":"lb"; }
    private String amount(double kg) {
        return WeightUnits.amount(kg,unit());
    }
    private void section(LinearLayout parent,String title,Runnable action,String label) {
        LinearLayout header=ui.row(); header.addView(ui.heading(title,20),Ui.flex());
        if(action!=null) header.addView(link(label,action)); parent.addView(header,MATCH,WRAP);
    }
    private TextView link(String s,Runnable run) {
        TextView t=ui.text(s,14,Ui.VIOLET); t.setGravity(Gravity.CENTER); t.setMinimumHeight(ui.dp(48)); ui.pad(t,8,3,8,3);
        t.setBackground(ui.ripple(Ui.BG,12,0)); t.setFocusable(true); t.setOnClickListener(v->run.run()); return t;
    }
    private void drawToday() {
        List<Store.Entry> entries=store.all();
        homeWeights=TrendsScreen.series(entries,false);
        for(Store.Entry entry:entries) {
            if(entry.kg==null)continue;
            if(latestHomeWeight==null)latestHomeWeight=entry;
            if(entry.id.equals(lastSession))savedHomeWeight=entry;
            if(lastSession==null || savedHomeWeight!=null)break;
        }
        TextView dateLine=ui.label(date(System.currentTimeMillis(),"EEEE, d MMMM").toUpperCase(Locale.UK)); content.addView(dateLine);
        ui.gap(content,5); content.addView(ui.heading("Today",35)); ui.gap(content,16);
        FrameLayout hero=new FrameLayout(this); hero.setBackground(ui.shape(0xff251d30,22,Ui.LINE)); hero.setClipToOutline(true);
        hero.addView(new Ui.Dial(this),new FrameLayout.LayoutParams(MATCH,MATCH));
        LinearLayout inner=ui.column(); ui.pad(inner,20,20,20,19); hero.addView(inner);
        heroLabel=ui.label(""); heroLabel.setTextColor(Ui.VIOLET); inner.addView(heroLabel); ui.gap(inner,3);
        heroValue=ui.text("",56,Ui.INK); heroValue.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        heroValue.setLetterSpacing(-.035f); inner.addView(heroValue);
        heroTime=ui.text("",13,Ui.MUTED); inner.addView(heroTime);
        comparisons=ui.row(); ui.pad(comparisons,0,9,0,0);
        android.content.res.Configuration config=getResources().getConfiguration();
        if(config.fontScale>1.3f || (config.screenWidthDp>0 && config.screenWidthDp<335)) comparisons.setOrientation(LinearLayout.VERTICAL);
        inner.addView(comparisons); ui.gap(inner,16);
        LinearLayout stat=ui.row(); statusDot=new View(this); stat.addView(statusDot,ui.dp(8),ui.dp(8));
        statusTitle=ui.heading("",16); ui.pad(statusTitle,8,0,0,0); stat.addView(statusTitle);
        statusTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); inner.addView(stat);
        statusMessage=ui.text("",14,0xffd4c9de); ui.pad(statusMessage,0,5,0,0); inner.addView(statusMessage);
        LinearLayout actions=ui.row(); ui.pad(actions,0,12,0,0); inner.addView(actions);
        weighAction=ui.button("",true,()->weigh.action()); actions.addView(weighAction);
        undoWeigh=ui.button("Undo",this::undoLastWeigh); LinearLayout.LayoutParams undoParams=new LinearLayout.LayoutParams(WRAP,WRAP);
        undoParams.leftMargin=ui.dp(8); actions.addView(undoWeigh,undoParams);
        content.addView(hero,MATCH,WRAP); updateHero();
        ui.gap(content,10);
        todayChart=TrendsScreen.addSparkline(this,content,homeWeights);
        ui.gap(content,12);
        content.addView(Habits.today(this,store,this::refresh),MATCH,WRAP);
        listBlock=ui.column(); content.addView(listBlock,MATCH,WRAP); recent(entries);
        ui.gap(content,16);
        Button manual=ui.button("Log weight or waist",this::manualEntry); content.addView(manual,MATCH,WRAP);
    }
    private void animateChartArrival() {
        if (todayChart == null) { pendingWeightPoint = null; return; }
        TrendPlot plot = todayChart;
        String newPoint = pendingWeightPoint;
        pendingWeightPoint = null;
        Runnable animate = () -> {
            if (todayChart != plot || !foreground) return;
            if (newPoint != null && plot.hasPoint(newPoint)) {
                android.graphics.Rect bounds = new android.graphics.Rect(0, 0, plot.getWidth(), plot.getHeight());
                plot.requestRectangleOnScreen(bounds, true);
            }
            plot.playArrival(newPoint);
        };
        if (plot.isLaidOut()) animate.run();
        else plot.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                plot.removeOnLayoutChangeListener(this);
                plot.post(animate);
            }
        });
    }
    private void recent(List<Store.Entry> entries) {
        if(listBlock==null)return; listBlock.removeAllViews();
        if(entries.isEmpty()) return;
        ui.gap(listBlock,16); section(listBlock,"Recent entries",()->show("History"),"View all");
        LinearLayout card=ui.card(); ui.pad(card,13,2,13,2); listBlock.addView(card,MATCH,WRAP);
        for(int i=0;i<Math.min(2,entries.size());i++) {if(i>0)ui.divider(card); recordRow(card,entries.get(i));}
    }
    @Override public void onWeighState(String state,String message,Double kg) {
        boolean justSaved="saved".equals(state) && (!"saved".equals(weighState) || pendingWeightPoint!=null);
        weighState=state; weighMessage=message==null?"":message; liveKg=kg;
        if(page.equals("Today") && heroValue!=null) {
            if(justSaved) {
                measurementChanged();
            } else updateHero();
        }
    }
    @Override public boolean hasWeighReading(String sessionId) { return store.find(sessionId)!=null; }
    @Override public boolean onWeighImpedance(String sessionId,double ohms) {
        return store.enrichScaleImpedance(sessionId,ohms);
    }
    @Override public boolean onWeighReading(String sessionId,ScaleReading reading) {
        try {
            boolean fresh=!hasWeighReading(sessionId);
            store.saveScale(sessionId,reading.measuredAt,reading.receivedAt,reading.kg,reading.device,reading.decoder,
                    reading.originalValue,reading.originalUnit,reading.originalResolution,reading.impedanceOhms);
            if(fresh)pendingWeightPoint=sessionId;
            lastSession=sessionId; return true;
        } catch(RuntimeException e) { return false; }
    }
    @Override public Double previousWeightKg() { Store.Entry e=store.latestWeight(); return e==null?null:e.kg; }
    private void updateHero() {
        if(heroValue==null || !page.equals("Today")) return;
        boolean pending="stabilising".equals(weighState) || "confirm".equals(weighState);
        boolean live=liveKg!=null && pending;
        boolean saved="saved".equals(weighState) && savedHomeWeight!=null;
        Store.Entry shown=saved?savedHomeWeight:latestHomeWeight;
        Double value=live?liveKg:shown==null?null:shown.kg;
        heroLabel.setText(live?"LIVE WEIGHT":saved?"RECORDED WEIGHT":value!=null?"LATEST WEIGHT":"YOUR SCALES");
        if(value==null) heroValue.setText("Weigh in");
        else {
            String n=amount(value);
            SpannableString formatted=new SpannableString(n+" "+suffix());
            formatted.setSpan(new RelativeSizeSpan(.48f),n.length(),formatted.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            formatted.setSpan(new ForegroundColorSpan(Ui.VIOLET),n.length(),formatted.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            heroValue.setText(formatted);
        }
        heroValue.setTextSize(value==null?42:unit().equals("st")?40:56);
        boolean showTime=shown!=null && !live;
        heroTime.setVisibility(showTime?View.VISIBLE:View.GONE);
        if(showTime) heroTime.setText(recordedDate(shown,"d MMM · HH:mm"));
        updateComparisons(pending?null:shown);
        String title="Ready to weigh", action=null;
        switch(weighState) {
            case "permission": title="Set up your scales"; action="Allow Bluetooth access";break;
            case "bluetooth": title="Bluetooth is off"; action="Turn on Bluetooth";break;
            case "location": title="Location is off"; action="Open settings";break;
            case "searching": title="Looking for your scales";break;
            case "choose": title="Scales nearby";action="Choose scales";break;
            case "connecting": title="Connecting to your scales";break;
            case "listening": title="Listening for a reading";break;
            case "stabilising": title="Reading settling";break;
            case "confirm": title="Check this reading";action="Confirm my reading";break;
            case "saved": title="Weigh-in saved";action="Weigh again";break;
            case "duplicate": title="Already recorded";action="Weigh again";break;
            case "error": title="Scan paused";action="Try again";break;
            default: action="Start weighing";break;
        }
        boolean active="searching".equals(weighState) || "choose".equals(weighState) ||
                "connecting".equals(weighState) || "listening".equals(weighState) || "stabilising".equals(weighState);
        if(active && foreground && !editorOpen) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        statusTitle.setText(title); statusDot.setBackground(ui.shape(weighState.equals("saved")?Ui.MINT:Ui.VIOLET,8,0));
        statusMessage.setText(weighMessage); statusMessage.setVisibility(weighMessage.isEmpty()?View.GONE:View.VISIBLE);
        weighAction.setVisibility(action==null?View.GONE:View.VISIBLE);
        if(action!=null) weighAction.setText(action);
        undoWeigh.setVisibility("saved".equals(weighState)&&lastSession!=null?View.VISIBLE:View.GONE);
        ((View)weighAction.getParent()).setVisibility(weighAction.getVisibility()==View.VISIBLE || undoWeigh.getVisibility()==View.VISIBLE?View.VISIBLE:View.GONE);
    }
    private void updateComparisons(Store.Entry anchor) {
        if(anchor!=null && anchor!=comparisonAnchor) {
            comparisonAnchor=anchor;
            comparisons.removeAllViews();
            long day=TrendSeries.localDay(anchor.timeMs,anchor.offsetMinutes);
            addComparison(7,homeWeights.changeBefore(day,7));
            addComparison(14,homeWeights.changeBefore(day,14));
        }
        comparisons.setVisibility(anchor!=null && comparisons.getChildCount()>0?View.VISIBLE:View.GONE);
    }
    private void addComparison(int days,Double deltaKg) {
        if(deltaKg==null)return;
        long tenths=WeightUnits.changeTenths(deltaKg,unit());
        String magnitude=WeightUnits.changeMagnitude(tenths,unit());
        String arrow=tenths==0?"→":tenths>0?"↑":"↓";
        LinearLayout item=ui.column();
        TextView period=ui.text(days+"-day trend",12,Ui.MUTED);
        TextView change=ui.text(arrow+" "+magnitude,15,Ui.VIOLET);
        period.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        change.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(period,WRAP,WRAP); ui.gap(item,2); item.addView(change,WRAP,WRAP);
        String spoken=tenths==0?"unchanged at the displayed precision":(tenths>0?"up ":"down ")+magnitude;
        item.setContentDescription(days+"-day weight trend, "+spoken);
        item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(WRAP,WRAP);
        if(comparisons.getChildCount()>0) {
            if(comparisons.getOrientation()==LinearLayout.VERTICAL)lp.topMargin=ui.dp(6);
            else lp.setMarginStart(ui.dp(24));
        }
        comparisons.addView(item,lp);
    }
    private void undoLastWeigh() {
        if(lastSession==null) return;
        try { store.delete(lastSession); lastSession=null; weigh.stop(); weighState="idle"; weighMessage="Weigh-in removed."; liveKg=null; measurementChanged(); }
        catch(RuntimeException ex) { toast("Couldn't remove that weigh-in."); }
    }
    private void drawHistory() {
        List<Store.Entry> list=store.all();
        LinearLayout title=ui.row(); title.addView(ui.heading("History",35),Ui.flex()); title.addView(iconAction("plus","Add an entry",this::manualEntry)); content.addView(title);
        ui.gap(content,6);
        if(list.isEmpty()) {
            ui.gap(content,19); LinearLayout blank=ui.card(); blank.addView(ui.heading("Your first entry",22));
            ui.gap(blank,8); blank.addView(ui.text("Weigh yourself on the Today screen, or enter a weight or waist measurement here.",16,Ui.MUTED));
            ui.gap(blank,16); blank.addView(ui.button("Add an entry",true,this::manualEntry)); content.addView(blank); return;
        }
        content.addView(ui.text(list.size()+(list.size()==1?" entry":" entries"),15,Ui.MUTED));
        String lastMonth=""; LinearLayout group=null;
        for(int i=0;i<Math.min(historyLimit,list.size());i++) {
            Store.Entry entry=list.get(i); String month=recordedDate(entry,"MMMM yyyy");
            if(!month.equals(lastMonth)) {
                ui.gap(content,20); content.addView(ui.label(month.toUpperCase(Locale.UK))); ui.gap(content,8);
                group=ui.card(); ui.pad(group,13,2,13,2); content.addView(group); lastMonth=month;
            } else ui.divider(group);
            recordRow(group,entry);
        }
        if(list.size()>historyLimit) {
            ui.gap(content,12); content.addView(ui.button("Show earlier entries",()->{historyLimit+=50;refresh();}));
        }
    }
    private void recordRow(LinearLayout parent,Store.Entry e) {
        LinearLayout row=ui.row(); ui.pad(row,3,13,3,13); row.setMinimumHeight(ui.dp(64));
        LinearLayout words=ui.column(); words.addView(ui.heading(e.kg==null?decimal(e.waistCm)+" cm waist":weight(e.kg),20));
        ui.gap(words,3); words.addView(ui.text(recordedDate(e,"d MMM · HH:mm")+"  ·  "+source(e),13,Ui.MUTED));
        if(e.kg!=null && e.waistCm!=null) { ui.gap(words,4); words.addView(ui.text(decimal(e.waistCm)+" cm waist",14,Ui.VIOLET)); }
        if(e.note!=null&&!e.note.trim().isEmpty()) {
            ui.gap(words,4); TextView note=ui.text(e.note.trim(),14,Ui.MUTED); note.setMaxLines(2); note.setEllipsize(android.text.TextUtils.TruncateAt.END); words.addView(note);
        }
        row.addView(words,Ui.flex()); row.addView(ui.icon("next",Ui.MUTED),ui.dp(17),ui.dp(17));
        row.setBackground(ui.ripple(Ui.SURFACE,10,0)); row.setFocusable(true); row.setOnClickListener(v->detail(e)); parent.addView(row);
    }
    private String source(Store.Entry e) {
        String origin=e.kg==null?e.waistSource:e.weightSource;
        if("scale".equalsIgnoreCase(origin)) return "Scale";
        if("manual".equalsIgnoreCase(origin)) return "Manual";
        if("imported".equalsIgnoreCase(origin)) return "Imported";
        return origin==null?"Entry":origin;
    }
    private void detail(Store.Entry e) {
        StringBuilder s=new StringBuilder(recordedDate(e,"EEEE d MMMM yyyy · HH:mm Z"));
        s.append("\n\n").append(source(e));
        if("scale".equals(e.weightSource) && !e.device.isEmpty()) s.append(" · ").append(e.device);
        if(e.kg!=null && e.waistCm!=null) s.append("\nWaist  ").append(decimal(e.waistCm)).append(" cm · ").append(e.waistSource);
        if("scale".equals(e.source) && !"scale".equals(e.weightSource)) {
            s.append("\nOriginal scale reading  ").append(e.originalValue).append(' ').append(e.originalUnit);
        }
        if(e.impedanceOhms!=null) s.append("\nScale impedance  ").append(decimal(e.impedanceOhms)).append(" Ω");
        if(e.kg!=null && e.heightCmAtEntry!=null) {
            double h=e.heightCmAtEntry/100;
            s.append("\nCalculated BMI  ").append(String.format(Locale.UK,"%.1f",e.kg/(h*h)))
                    .append(" · height at entry ").append(decimal(e.heightCmAtEntry)).append(" cm");
        }
        if(e.importedMs>0) s.append("\nImported ").append(date(e.importedMs,"d MMM yyyy"));
        if(e.note!=null && !e.note.isEmpty()) s.append("\n\n").append(e.note);
        new AlertDialog.Builder(this).setTitle(e.kg==null?decimal(e.waistCm)+" cm waist":weight(e.kg)).setMessage(s)
            .setPositiveButton("Edit", (d,w)->editor(e)).setNegativeButton("Close",null)
            .setNeutralButton("Delete",(d,w)->confirmDelete(e)).show();
    }
    private void confirmDelete(Store.Entry e) {
        new AlertDialog.Builder(this).setTitle("Delete this entry?").setMessage(recordedDate(e,"d MMMM yyyy · HH:mm"))
                .setPositiveButton("Delete",(d,w)->{
                    try { store.delete(e.id); measurementChanged(); notice("Entry deleted","Undo",()->{store.restore(e);measurementChanged();}); }
                    catch(RuntimeException ex) { toast("Couldn't delete this entry."); }
                }).setNegativeButton("Keep entry",null).show();
    }
    private void notice(String message,String label,Runnable action) {
        snackbar.removeAllViews(); LinearLayout row=ui.row(); ui.pad(row,18,3,13,3); row.setBackgroundColor(Ui.SURFACE);
        row.addView(ui.text(message,15,Ui.INK),Ui.flex()); row.addView(link(label,()->{try{action.run();}catch(RuntimeException ex){toast("Couldn't restore this entry.");}}));
        snackbar.addView(row); row.postDelayed(()->{if(row.getParent() instanceof LinearLayout)((LinearLayout)row.getParent()).removeView(row);},13000);
    }
    public void manualEntry() { editor(null); }
    private EditText field(LinearLayout parent,String name,String value,boolean number) {
        ui.gap(parent,11); TextView label=ui.text(name,14,Ui.MUTED); parent.addView(label);
        EditText f=new EditText(this); f.setId(View.generateViewId()); label.setLabelFor(f.getId());
        f.setInputType(number?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        f.setSingleLine(number); f.setMinimumHeight(ui.dp(48)); if(!number)f.setMaxLines(4);
        f.setTextColor(Ui.INK); f.setTextSize(17); if(!number)f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        f.setText(value); parent.addView(f); return f;
    }
    public void editor(Store.Entry e) { editor(e,null); }
    private void editor(Store.Entry e,Bundle pending) {
        if(editorOpen) return;
        editorOpen=true; if(page.equals("Today")) {
            weigh.stop(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        Calendar when=Calendar.getInstance(); if(e!=null) when.setTimeInMillis(e.timeMs);
        if(pending!=null) when.setTimeInMillis(pending.getLong("draftTime",when.getTimeInMillis()));
        LinearLayout form=ui.column(); ui.pad(form,20,8,20,12); form.setFocusableInTouchMode(true);
        String units=unit(); boolean stone=units.equals("st");
        Double orig=e==null?null:e.kg; boolean has=orig!=null;
        String initialStones=has?Long.toString(WeightUnits.tenthsOfPound(orig)/140):"";
        EditText stones=stone?field(form,"Weight · stones",initialStones,true):null;
        String shown=has?(stone?decimal(WeightUnits.tenthsOfPound(orig)%140/10d):amount(orig)):"";
        EditText weight=field(form,stone?"Weight · remaining pounds":"Weight ("+units+")",shown,true);
        String initialWaist=e==null||e.waistCm==null?"":decimal(e.waistCm);
        EditText waist=field(form,"Waist (cm)",initialWaist,true);
        EditText note=field(form,"Note (optional)",e==null?"":e.note,false);
        if(pending!=null) {
            if(stones!=null)stones.setText(pending.getString("draftStones",""));
            weight.setText(pending.getString("draftWeight","")); waist.setText(pending.getString("draftWaist",""));
            note.setText(pending.getString("draftNote",""));
        }
        editingId=e==null?null:e.id; draftStones=stones; draftWeight=weight; draftWaist=waist; draftNote=note; draftTime=when;
        ui.gap(form,12); LinearLayout dt=ui.row();
        Button date=ui.button(date(when.getTimeInMillis(),"d MMM yyyy"),()->{}), time=ui.button(date(when.getTimeInMillis(),"HH:mm"),()->{});
        date.setOnClickListener(v->new DatePickerDialog(this,(p,y,m,d)->{when.set(y,m,d);date.setText(date(when.getTimeInMillis(),"d MMM yyyy"));},
                when.get(Calendar.YEAR),when.get(Calendar.MONTH),when.get(Calendar.DAY_OF_MONTH)).show());
        time.setOnClickListener(v->new TimePickerDialog(this,(p,h,m)->{when.set(Calendar.HOUR_OF_DAY,h);when.set(Calendar.MINUTE,m);time.setText(date(when.getTimeInMillis(),"HH:mm"));},
                when.get(Calendar.HOUR_OF_DAY),when.get(Calendar.MINUTE),true).show());
        dt.addView(date,Ui.flex()); dt.addView(new View(this),ui.dp(8),1); dt.addView(time); form.addView(dt);
        ScrollView formScroll=new ScrollView(this); formScroll.addView(form);
        AlertDialog dlg=new AlertDialog.Builder(this).setTitle(e==null?"Log a measurement":"Edit measurement")
                .setView(formScroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dlg.setOnDismissListener(d->{
            editorOpen=false; draftStones=draftWeight=draftWaist=draftNote=null; draftTime=null; editingId=null;
            if(foreground && page.equals("Today")&&!isFinishing()) weigh.start();
        });
        dlg.show(); dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                Double value=parse(weight),st=stone?parse(stones):null,cm=parse(waist);
                String raw=stone?(st==null?"0":decimal(st))+" st "+(value==null?"0":decimal(value))+" lb":weight.getText().toString().trim();
                Double kg=WeightUnits.toKg(units,st,value);
                // Editing a note or date must never round a previously measured weight.
                boolean sameWeight=has && shown.equals(weight.getText().toString().trim()) &&
                        (!stone || initialStones.equals(stones.getText().toString().trim()));
                if(sameWeight)kg=orig;
                if(e!=null && e.waistCm!=null && initialWaist.equals(waist.getText().toString().trim()))cm=e.waistCm;
                if(kg==null && cm==null) throw new IllegalArgumentException("Enter a weight, a waist measurement, or both.");
                String memo=note.getText().toString().trim();
                Store.Entry saved=e==null ? store.saveManual(when.getTimeInMillis(),kg,cm,memo,kg==null?"":raw,kg==null?"":units)
                        : store.edit(e.id,when.getTimeInMillis(),kg,cm,memo,kg==null?"":raw,kg==null?"":units);
                if(kg!=null && (e==null || !kg.equals(e.kg))) pendingWeightPoint=saved.id;
                measurementChanged(); toast("Entry saved"); dlg.dismiss();
            } catch(IllegalArgumentException ex) { toast(ex.getMessage()==null?"Check the measurements and try again.":ex.getMessage()); }
              catch(RuntimeException ex) { toast("Couldn't save this entry. Your changes are still here."); }
        });
    }
    private static Double parse(EditText field) {
        String s=field.getText().toString().trim(); if(s.isEmpty())return null;
        try {double d=Double.parseDouble(s.replace(',','.')); if(d<0 || Double.isNaN(d)||Double.isInfinite(d))throw new NumberFormatException(); return d;}
        catch(NumberFormatException e) {throw new IllegalArgumentException("Enter a positive number.");}
    }
    public void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    private void measurementChanged() {
        if(page.equals("Today") && pendingWeightPoint!=null) { draw(); animateChartArrival(); }
        else refresh();
        ReminderReceiver.measurementChanged(this);
    }
}
