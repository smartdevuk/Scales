package uk.co.violetscale.probe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    public static final int BG=0xff0d0c13, SURFACE=0xff1a1723, INK=0xfff7f3ff,
            MUTED=0xffb6adbf, VIOLET=0xffcab0ff, LINE=0xff383141, MINT=0xff9cdbc9;
    final Context context;
    public Ui(Context context) { this.context=context; }
    public int dp(float n) { return Math.round(n*context.getResources().getDisplayMetrics().density); }
    public LinearLayout column() {
        LinearLayout v=new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v;
    }
    public LinearLayout row() {
        LinearLayout v=new LinearLayout(context); v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }
    public LinearLayout card() {
        LinearLayout v=column(); pad(v,16,16,16,16); v.setBackground(shape(SURFACE,18,LINE)); return v;
    }
    public void pad(View v,int l,int t,int r,int b) { v.setPadding(dp(l),dp(t),dp(r),dp(b)); }
    public TextView text(String s,float sp,int colour) {
        TextView v=new TextView(context); v.setText(s); v.setTextSize(sp); v.setTextColor(colour);
        v.setIncludeFontPadding(false); v.setFontFeatureSettings("tnum"); v.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        return v;
    }
    public TextView heading(String s,float size) {
        TextView t=text(s,size,INK); t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setLetterSpacing(-0.025f); return t;
    }
    public TextView label(String s) {
        TextView t=text(s,11,MUTED); t.setTypeface(Typeface.MONOSPACE); t.setLetterSpacing(0.08f); return t;
    }
    public Button button(String s,Runnable click) { return button(s,false,click); }
    public Button button(String s,boolean primary,Runnable click) {
        Button b=new Button(context); b.setText(s); b.setAllCaps(false); b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); b.setMinWidth(0);
        b.setMinimumHeight(dp(48)); b.setMinHeight(dp(48)); b.setStateListAnimator(null);
        b.setTextColor(primary?0xff21152f:INK); pad(b,14,8,14,8);
        b.setBackground(ripple(primary?0xffc9afff:0xff2a2433,13,primary?0:LINE)); b.setOnClickListener(v->click.run()); return b;
    }
    public GradientDrawable shape(int colour,int radius,int outline) {
        GradientDrawable g=new GradientDrawable(); g.setColor(colour); g.setCornerRadius(dp(radius));
        if(outline!=0) g.setStroke(dp(1),outline); return g;
    }
    public RippleDrawable ripple(int colour,int radius,int outline) {
        return new RippleDrawable(ColorStateList.valueOf(0x338f74b7),shape(colour,radius,outline),null);
    }
    public void gap(LinearLayout parent,int size) { parent.addView(new View(context),1,dp(size)); }
    public void divider(LinearLayout parent) {
        View line=new View(context); line.setBackgroundColor(LINE); parent.addView(line,-1,dp(1));
    }
    public static LinearLayout.LayoutParams flex() { return new LinearLayout.LayoutParams(0,-2,1); }

    /** Monochrome glyphs share a 24-unit grid with the measurement dial and launcher mark. */
    public View icon(String name,int colour) { return new Glyph(context,name,colour); }

    static final class Glyph extends View {
        final String name; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(Context c,String name,int colour) { super(c); this.name=name; p.setColor(colour); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void line(Canvas c,float... xy) {
            Path path=new Path(); path.moveTo(xy[0],xy[1]);
            for(int i=2;i<xy.length;i+=2) path.lineTo(xy[i],xy[i+1]); c.drawPath(path,p);
        }
        @Override protected void onDraw(Canvas c) {
            float size=Math.min(getWidth(),getHeight()); c.save(); c.translate((getWidth()-size)/2,(getHeight()-size)/2); c.scale(size/24,size/24);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.7f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            switch(name) {
                case "mark":
                    p.setStrokeWidth(2.9f); line(c,3,5,11.4f,20,20.5f,4); p.setAlpha(160); line(c,9,5,12.2f,10.5f); p.setAlpha(255); break;
                case "Today":
                    c.drawRoundRect(3,4,21,21,5,5,p); c.drawArc(7,7,17,16,204,132,false,p); line(c,12,12,14.8f,9.3f); break;
                case "Trends":
                    line(c,3,17,8.5f,11,12.6f,14,21,5.5f); line(c,15.5f,5.5f,21,5.5f,21,11); break;
                case "History":
                    c.drawArc(4,4,21,21,208,316,false,p); line(c,3,4.5f,3,9,7.5f,9); line(c,12,8,12,13,15.5f,15); break;
                case "Settings":
                    line(c,3,7,9,7); line(c,15,7,21,7); c.drawCircle(12,7,3,p);
                    line(c,3,17,5,17); line(c,11,17,21,17); c.drawCircle(8,17,3,p); break;
                case "back": line(c,15,5,8,12,15,19); break;
                case "next": line(c,9,5,16,12,9,19); break;
                case "plus": line(c,5,12,19,12); line(c,12,5,12,19); break;
            }
            c.restore();
        }
    }

    /** Clipped dial engraving behind the live measurement, without invented data ticks or values. */
    static final class Dial extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path clip=new Path();
        RadialGradient glow;
        Dial(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
            float corner=22*getResources().getDisplayMetrics().density;
            clip.reset(); clip.addRoundRect(0,0,w,h,corner,corner,Path.Direction.CW);
            if(w>0 && h>0) glow=new RadialGradient(w*.94f,h*.12f,w*.89f,
                    new int[]{0xff513764,0xff2b2039,0xff1d1828},new float[]{0,.57f,1},Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas c) {
            c.save(); c.clipPath(clip);
            float w=getWidth(), h=getHeight(), cx=w*1.04f, cy=Math.min(h*.53f,w*.34f), r=w*.34f;
            p.setStyle(Paint.Style.FILL);p.setShader(glow); c.drawRect(0,0,w,h,p);p.setShader(null);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.0024f);p.setColor(0x267e6895);
            c.drawCircle(cx,cy,r,p);c.drawCircle(cx,cy,r*.83f,p);c.drawCircle(cx,cy,r*1.24f,p);
            for(int i=0;i<80;i++) {double a=i*Math.PI/40;
                float x=(float)Math.cos(a), y=(float)Math.sin(a), inner=(i%5==0?.91f:.96f)*r;
                c.drawLine(cx+x*inner,cy+y*inner,cx+x*r,cy+y*r,p);
            }
            c.restore();
        }
    }
}
