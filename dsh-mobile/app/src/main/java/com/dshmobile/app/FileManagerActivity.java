package com.dshmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Container file manager: the rootfs is a real file tree in the app's private directory,
 * so Java can read and write it directly. Paths are shown the way the container sees them
 * (/ is the rootfs root) and the start directory is /home/dsh, the dsh working directory.
 * Imports go through SAF (no storage permission needed); exports are copied to
 * /sdcard/dsh-shared, which is /mnt/shared inside the container.
 */
public class FileManagerActivity extends Activity {

    private static final int REQ_IMPORT = 41;

    private File rootfs;
    private File current;
    private TextView pathView;
    private ArrayAdapter<String> adapter;
    /** One entry per adapter row; row 0 can be the ".." parent row (a null file). */
    private final List<File> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rootfs = ProotRunner.rootfsDir(this);
        if (!rootfs.isDirectory()) {
            Toast.makeText(this, R.string.fm_not_installed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        File home = ProotRunner.homeDir(this);
        current = home.isDirectory() ? home : rootfs;
        buildUi();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bgSoft(this));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        int bp = Ui.dp(this, 12);
        bar.setPadding(bp, Ui.dp(this, 10), bp, Ui.dp(this, 4));
        bar.setBackgroundColor(Ui.bg(this));
        TextView title = Ui.title(this, getString(R.string.fm_title));
        title.setTextSize(18);
        bar.addView(title);
        pathView = Ui.hint(this, "");
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        bar.addView(pathView);
        root.addView(bar);

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setTextColor(Ui.text(v.getContext()));
                tv.setTextSize(15);
                tv.setPadding(Ui.dp(FileManagerActivity.this, 16),
                        Ui.dp(FileManagerActivity.this, 12),
                        Ui.dp(FileManagerActivity.this, 16),
                        Ui.dp(FileManagerActivity.this, 12));
                return v;
            }
        };
        ListView listView = new ListView(this);
        listView.setBackgroundColor(Ui.bg(this));
        listView.setDivider(null);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> onTap(pos));
        listView.setOnItemLongClickListener((p, v, pos, id) -> onLongTap(pos));
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Bottom bar
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Ui.bg(this));
        bottom.setPadding(bp, bp, bp, bp);
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button importBtn = Ui.primaryButton(this, getString(R.string.fm_import));
        importBtn.setOnClickListener(v -> pickImport());
        btnRow.addView(importBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        android.widget.Button mkdirBtn = Ui.outlineButton(this, getString(R.string.fm_mkdir));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        mlp.leftMargin = Ui.dp(this, 12);
        mkdirBtn.setLayoutParams(mlp);
        mkdirBtn.setOnClickListener(v -> mkdirDialog());
        btnRow.addView(mkdirBtn);
        bottom.addView(btnRow);
        TextView hint = Ui.hint(this, getString(R.string.fm_hint));
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = Ui.matchWrap();
        hlp.topMargin = Ui.dp(this, 8);
        bottom.addView(hint, hlp);
        root.addView(bottom);

        setContentView(root);
    }

    /** Path as the container sees it (/ is the rootfs root). */
    private String containerPath(File f) {
        String r = rootfs.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.equals(r)) return "/";
        return p.startsWith(r + "/") ? p.substring(r.length()) : p;
    }

    private void refresh() {
        pathView.setText(containerPath(current));
        rows.clear();
        adapter.clear();
        if (!current.equals(rootfs)) {
            rows.add(null);
            adapter.add(getString(R.string.fm_parent));
        }
        File[] kids = current.listFiles();
        if (kids != null) {
            Arrays.sort(kids, Comparator
                    .comparing(File::isFile) // Directories first
                    .thenComparing(f -> f.getName().toLowerCase()));
            for (File f : kids) {
                rows.add(f);
                adapter.add(f.isDirectory()
                        ? "\uD83D\uDCC1 " + f.getName() + "/"
                        : "\uD83D\uDCC4 " + f.getName() + "    " + sizeText(f.length()));
            }
        }
        adapter.notifyDataSetChanged();
    }

    private static String sizeText(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        if (n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / 1048576.0);
        return String.format("%.2f GB", n / 1073741824.0);
    }

    private void onTap(int pos) {
        File f = rows.get(pos);
        if (f == null) { // ".."
            current = current.getParentFile();
            refresh();
            return;
        }
        if (f.isDirectory()) {
            current = f;
            refresh();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(new String[]{getString(R.string.fm_export), getString(R.string.fm_delete)}, (d, which) -> {
                    if (which == 0) exportFile(f);
                    else confirmDelete(f);
                })
                .show();
    }

    private boolean onLongTap(int pos) {
        File f = rows.get(pos);
        if (f != null) confirmDelete(f);
        return true;
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.fm_delete)
                .setMessage(getString(R.string.fm_delete_confirm, containerPath(f),
                        f.isDirectory() ? getString(R.string.fm_delete_confirm_dir) : ""))
                .setPositiveButton(R.string.fm_delete, (d, w) -> {
                    BootstrapInstaller.deleteRecursively(f);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Export: copy to /sdcard/dsh-shared, which is /mnt/shared inside the container. */
    private void exportFile(File f) {
        File destDir = ProotRunner.sharedDir();
        File dest = uniqueName(destDir, f.getName());
        try {
            copyFile(f, dest);
            Toast.makeText(this, getString(R.string.fm_exported, dest.getAbsolutePath()), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.fm_export_failed, String.valueOf(e.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static File uniqueName(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + "(" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = Files.newInputStream(src.toPath());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    /** Import: SAF picks the files (no storage permission), then they are copied here. */
    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        int ok = 0;
        int fail = 0;
        for (Uri u : uris) {
            try {
                importUri(u);
                ok++;
            } catch (IOException e) {
                fail++;
            }
        }
        refresh();
        Toast.makeText(this, fail > 0
                        ? getString(R.string.fm_imported_partial, ok, fail)
                        : getString(R.string.fm_imported, ok),
                Toast.LENGTH_SHORT).show();
    }

    private void importUri(Uri uri) throws IOException {
        String name = safeImportName(displayName(uri));
        File dest = uniqueName(current, name);
        // Belt and braces: a document provider can hand back a name that resolves outside the
        // selected folder (for example a bare ".."). Refuse rather than write somewhere else.
        if (!dest.getCanonicalPath().startsWith(current.getCanonicalPath())) {
            throw new IOException(getString(R.string.fm_import_bad_name));
        }
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException(getString(R.string.fm_import_unreadable));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    /**
     * Tightens a provider-reported name so the import can never leave the target folder:
     * separators are flattened, control characters dropped, and ".", ".." or empty names get a
     * placeholder prefix.
     */
    private static String safeImportName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\') c = '_';
            if (c < 0x20) continue;
            sb.append(c);
        }
        String cleaned = sb.toString();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            cleaned = "import-" + System.currentTimeMillis();
        }
        return cleaned;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) name = "import-" + System.currentTimeMillis();
        return name.replace('/', '_');
    }

    private void mkdirDialog() {
        EditText et = new EditText(this);
        et.setHint(R.string.fm_folder_name);
        et.setSingleLine(true);
        int p = Ui.dp(this, 16);
        et.setPadding(p, p / 2, p, p / 2);
        new AlertDialog.Builder(this)
                .setTitle(R.string.fm_mkdir)
                .setView(et)
                .setPositiveButton(R.string.fm_create, (d, w) -> {
                    String name = et.getText().toString().trim().replace("/", "");
                    if (name.isEmpty()) return;
                    File f = new File(current, name);
                    if (f.exists() || !f.mkdirs()) {
                        Toast.makeText(this, R.string.fm_create_failed, Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
