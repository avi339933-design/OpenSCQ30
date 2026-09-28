package com.oppzippy.openscq30.lite;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StringBuilder sb = new StringBuilder();
        sb.append("Android SDK: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("ABI: ").append(Build.CPU_ABI).append("\n");
        try {
            System.loadLibrary("openscq30_android");
            sb.append("OK: libopenscq30_android loaded\n");
        } catch (Throwable t) {
            sb.append("FAIL: ").append(t).append("\n");
        }
        TextView tv = new TextView(this);
        tv.setTextSize(16);
        tv.setPadding(24, 24, 24, 24);
        tv.setText(sb.toString());
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }
}
