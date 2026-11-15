package com.globalannotate.official.helper;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Minimal launcher activity so the module APK is installable and inspectable. */
public final class ModuleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText(getString(R.string.module_status));
        text.setTextSize(16f);
        text.setPadding(48, 64, 48, 64);
        setContentView(text);
    }
}
