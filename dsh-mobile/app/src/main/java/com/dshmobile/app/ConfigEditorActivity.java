package com.dshmobile.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Native editor for the dsh config file (/home/dsh/.dsh/settings.yaml in the container).
 * The dsh web desktop's "open config file" action runs xdg-open, which cannot work here; the
 * page side is intercepted by inject.js, which routes the button through the JS bridge to
 * this screen. The dsh-settings-file plugin watches the file with chokidar and reloads it on
 * save, so no service restart is needed.
 */
public class ConfigEditorActivity extends Activity {

    private EditText editor;
    private TextView status;
    private File configFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        configFile = new File(ProotRunner.homeDir(this), ".dsh/settings.yaml");
        buildUi();
        loadFile();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Ui.bg(this));

        TextView title = Ui.title(this, getString(R.string.ce_title));
        root.addView(title);

        TextView path = Ui.hint(this, configFile.getAbsolutePath());
        LinearLayout.LayoutParams plp = Ui.matchWrap();
        plp.topMargin = Ui.dp(this, 4);
        root.addView(path, plp);

        editor = new EditText(this);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13);
        editor.setTextColor(Ui.text(this));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setHorizontallyScrolling(true);
        editor.setBackgroundColor(Ui.bgSoft(this));
        int epad = Ui.dp(this, 12);
        editor.setPadding(epad, epad, epad, epad);
        ScrollView sv = new ScrollView(this);
        sv.addView(editor, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Ui.dp(this, 12);
        root.addView(sv, slp);

        status = Ui.hint(this, "");
        LinearLayout.LayoutParams stlp = Ui.matchWrap();
        stlp.topMargin = Ui.dp(this, 8);
        root.addView(status, stlp);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = Ui.matchWrap();
        blp.topMargin = Ui.dp(this, 8);
        Button cancel = Ui.outlineButton(this, getString(R.string.ce_back));
        cancel.setOnClickListener(v -> finish());
        btns.addView(cancel);
        Button save = Ui.primaryButton(this, getString(R.string.save));
        LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        s2.leftMargin = Ui.dp(this, 16);
        save.setLayoutParams(s2);
        save.setOnClickListener(v -> saveFile());
        btns.addView(save);
        root.addView(btns, blp);

        setContentView(root);
    }

    private void loadFile() {
        if (!configFile.isFile()) {
            // A missing file gets a commented empty document (dsh's prepareDocument does the same).
            editor.setText(getString(R.string.ce_template));
            status.setText(R.string.ce_not_created);
            return;
        }
        try {
            FileInputStream in = new FileInputStream(configFile);
            byte[] buf = new byte[(int) Math.min(configFile.length(), 1024 * 1024)];
            int n = in.read(buf);
            in.close();
            editor.setText(new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8));
        } catch (IOException e) {
            status.setText(getString(R.string.ce_read_failed, String.valueOf(e.getMessage())));
        }
    }

    /** Writes a temp file and renames it (atomic replace) so dsh's watcher never sees half a file. */
    private void saveFile() {
        try {
            File dir = configFile.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(configFile.getParentFile(), "settings.yaml.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            out.close();
            if (!tmp.renameTo(configFile)) {
                throw new IOException("rename failed");
            }
            status.setText(R.string.ce_saved);
            Toast.makeText(this, R.string.ce_saved_toast, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            status.setText(getString(R.string.ce_save_failed, String.valueOf(e.getMessage())));
        }
    }
}
