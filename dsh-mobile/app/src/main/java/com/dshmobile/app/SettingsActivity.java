package com.dshmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Settings: SD mapping, ports, mirrors, logs, service control and reset. */
public class SettingsActivity extends Activity {

    private Prefs prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.of(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.bgSoft(this));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);
        fillRows();
    }

    private void fillRows() {
        TextView pageTitle = Ui.title(this, getString(R.string.st_title));
        LinearLayout.LayoutParams tlp = Ui.matchWrap();
        tlp.bottomMargin = Ui.dp(this, 4);
        list.addView(pageTitle, tlp);

        // ---- SD card mapping ----
        addHeader(getString(R.string.st_sd_header));
        LinearLayout sdCard = Ui.card(this);
        boolean allFiles = Environment.isExternalStorageManager();
        addRow(sdCard, getString(R.string.st_all_files),
                getString(allFiles ? R.string.st_granted : R.string.st_not_granted_tap),
                v -> {
                    if (!Environment.isExternalStorageManager()) {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    }
                });
        sdCard.addView(Ui.divider(this));
        addRow(sdCard, getString(R.string.st_sd_dir), sdDisplay(), v -> pickSdDir());
        sdCard.addView(Ui.divider(this));
        addRow(sdCard, getString(R.string.st_shared_fallback),
                getString(R.string.st_shared_fallback_value), null);
        list.addView(sdCard, cardLp());

        // ---- Service ----
        addHeader(getString(R.string.st_service_header));
        LinearLayout svcCard = Ui.card(this);
        addRow(svcCard, getString(R.string.st_web_port), String.valueOf(prefs.getPort()),
                v -> editText(getString(R.string.st_web_port), String.valueOf(prefs.getPort()), t -> {
                    try {
                        int p = Integer.parseInt(t.trim());
                        if (p > 0 && p < 65536) {
                            prefs.setPort(p);
                            toast(getString(R.string.st_port_saved));
                            refresh();
                        }
                    } catch (NumberFormatException ignored) {
                        toast(getString(R.string.st_port_invalid));
                    }
                }));
        svcCard.addView(Ui.divider(this));
        addRow(svcCard, getString(R.string.st_service_state),
                getString(HarnessService.isRunning() ? R.string.st_running : R.string.st_stopped), null);
        list.addView(svcCard, cardLp());

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button toggle = Ui.primaryButton(this,
                getString(HarnessService.isRunning() ? R.string.st_stop_service : R.string.st_start_service));
        toggle.setOnClickListener(v -> {
            if (HarnessService.isRunning()) {
                HarnessService.stopService(this);
            } else {
                HarnessService.startService(this);
            }
            list.postDelayed(this::refresh, 800);
        });
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        btnRow.addView(toggle, tbp);
        Button restart = Ui.outlineButton(this, getString(R.string.st_restart_service));
        restart.setOnClickListener(v -> {
            HarnessService.stopService(this);
            // Stopping runs off the main thread (the hard kill can take ~3s), and the new
            // container may only start once the old one is really gone: starting earlier
            // leaves isRunning() true, so runLoop is never brought up and the service
            // looks alive while it does nothing.
            list.postDelayed(() -> {
                HarnessService.startService(this);
                refresh();
            }, 4000);
        });
        LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        rbp.leftMargin = Ui.dp(this, 12);
        btnRow.addView(restart, rbp);
        LinearLayout.LayoutParams brlp = Ui.matchWrap();
        brlp.topMargin = Ui.dp(this, 12);
        list.addView(btnRow, brlp);

        // ---- Container SSH ----
        addHeader(getString(R.string.st_ssh_header));
        LinearLayout sshCard = Ui.card(this);
        addRow(sshCard, getString(R.string.st_ssh_how),
                getString(R.string.st_ssh_how_value, prefs.getSshPort(), prefs.getSshPort()),
                null);
        sshCard.addView(Ui.divider(this));
        addRow(sshCard, getString(R.string.st_ssh_credentials),
                getString(R.string.st_ssh_credentials_value, prefs.getSshPassword()), v -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ssh", prefs.getSshPassword()));
                    toast(getString(R.string.st_password_copied));
                });
        sshCard.addView(Ui.divider(this));
        addRow(sshCard, getString(R.string.st_ssh_port),
                getString(R.string.st_ssh_port_value, prefs.getSshPort()),
                v -> editText(getString(R.string.st_ssh_port), String.valueOf(prefs.getSshPort()), t -> {
                    try {
                        int p = Integer.parseInt(t.trim());
                        if (p > 0 && p < 65536) {
                            prefs.setSshPort(p);
                            toast(getString(R.string.st_ssh_port_saved));
                            refresh();
                        }
                    } catch (NumberFormatException ignored) {
                        toast(getString(R.string.st_port_invalid));
                    }
                }));
        list.addView(sshCard, cardLp());

        // ---- Staying alive in the background ----
        // Being killed when the task is swiped away is OEM behaviour (Honor/MagicOS kill the
        // whole process by default). The app can only make that less likely: the battery
        // optimisation whitelist, plus pointing the user at autostart/background activity.
        addHeader(getString(R.string.st_keep_header));
        LinearLayout keepCard = Ui.card(this);
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        addRow(keepCard, getString(R.string.st_ignore_battery),
                getString(ignoring ? R.string.st_whitelisted : R.string.st_not_whitelisted),
                v -> {
                    android.os.PowerManager p = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    if (p != null && !p.isIgnoringBatteryOptimizations(getPackageName())) {
                        try {
                            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            toast(getString(R.string.st_no_battery_settings));
                        }
                    }
                });
        keepCard.addView(Ui.divider(this));
        addRow(keepCard, getString(R.string.st_autostart),
                getString(R.string.st_autostart_value),
                v -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        toast(getString(R.string.st_no_app_details));
                    }
                });
        list.addView(keepCard, cardLp());

        // ---- Mirrors ----
        addHeader(getString(R.string.st_mirror_header));
        LinearLayout mirrorCard = Ui.card(this);
        addRow(mirrorCard, getString(R.string.st_rootfs_url), prefs.getRootfsUrl(),
                v -> editText(getString(R.string.st_rootfs_url), prefs.getRootfsUrl(), t -> {
                    prefs.setRootfsUrl(t.trim());
                    refresh();
                }));
        mirrorCard.addView(Ui.divider(this));
        addRow(mirrorCard, getString(R.string.st_node_mirror), prefs.getNodeMirror(),
                v -> editText(getString(R.string.st_node_mirror), prefs.getNodeMirror(), t -> {
                    prefs.setNodeMirror(t.trim());
                    refresh();
                }));
        mirrorCard.addView(Ui.divider(this));
        addRow(mirrorCard, getString(R.string.st_npm_registry), prefs.getNpmRegistry(),
                v -> editText(getString(R.string.st_npm_registry), prefs.getNpmRegistry(), t -> {
                    prefs.setNpmRegistry(t.trim());
                    refresh();
                }));
        list.addView(mirrorCard, cardLp());

        // ---- Maintenance ----
        addHeader(getString(R.string.st_maintenance_header));
        Button fmBtn = Ui.outlineButton(this, getString(R.string.st_file_manager));
        fmBtn.setOnClickListener(v -> startActivity(new Intent(this, FileManagerActivity.class)));
        list.addView(fmBtn, btnLp());
        Button termBtn = Ui.outlineButton(this, getString(R.string.st_open_terminal));
        termBtn.setOnClickListener(v -> startActivity(new Intent(this, TerminalActivity.class)));
        list.addView(termBtn, btnLp());
        Button logBtn = Ui.outlineButton(this, getString(R.string.st_view_log));
        logBtn.setOnClickListener(v -> showLog("dsh-web.log"));
        list.addView(logBtn, btnLp());
        Button installLogBtn = Ui.outlineButton(this, getString(R.string.st_view_install_log));
        installLogBtn.setOnClickListener(v -> showLog("install.log"));
        list.addView(installLogBtn, btnLp());
        Button resetBtn = Ui.outlineButton(this, getString(R.string.st_reset));
        resetBtn.setTextColor(0xFFE54545);
        resetBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.st_reset)
                .setMessage(R.string.st_reset_message)
                .setPositiveButton(R.string.st_reset_confirm, (d, w) -> {
                    HarnessService.stopService(this);
                    new Thread(() -> {
                        BootstrapInstaller.resetToFreshContainer(this);
                        runOnUiThread(() -> {
                            startActivity(new Intent(this, SetupActivity.class));
                            finish();
                        });
                    }).start();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        list.addView(resetBtn, btnLp());
    }

    private String sdDisplay() {
        String sd = prefs.getSdPath();
        if (sd == null) return getString(R.string.st_sd_unset);
        return sd + (new File(sd).isDirectory() ? "" : getString(R.string.st_sd_missing));
    }

    private void pickSdDir() {
        if (!Environment.isExternalStorageManager()) {
            toast(getString(R.string.st_need_all_files));
            return;
        }
        List<String> options = new ArrayList<>();
        options.add(getString(R.string.st_sd_no_mapping));
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                if (v.isDirectory() && !v.getName().equals("emulated") && !v.getName().equals("self")) {
                    options.add(v.getAbsolutePath());
                }
            }
        }
        options.add(getString(R.string.st_sd_manual));
        String[] items = options.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.st_sd_pick)
                .setItems(items, (d, which) -> {
                    String sel = items[which];
                    if (which == 0) {
                        prefs.setSdPath(null);
                        refresh();
                    } else if (sel.startsWith("/storage")) {
                        String dir = sel + "/dsh";
                        //noinspection ResultOfMethodCallIgnored
                        new File(dir).mkdirs();
                        prefs.setSdPath(dir);
                        toast(getString(R.string.st_sd_mapped, dir));
                        refresh();
                    } else {
                        editText(getString(R.string.st_sd_path_title),
                                prefs.getSdPath() == null ? "" : prefs.getSdPath(),
                                t -> {
                                    prefs.setSdPath(t.trim());
                                    refresh();
                                });
                    }
                })
                .show();
    }

    private void showLog(String name) {
        File f = new File(ProotRunner.baseDir(this), name);
        StringBuilder sb = new StringBuilder();
        if (f.isFile()) {
            try {
                byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                int from = Math.max(0, all.length - 60_000);
                sb.append(new String(all, from, all.length - from));
            } catch (Exception e) {
                sb.append(getString(R.string.st_log_read_failed, String.valueOf(e.getMessage())));
            }
        } else {
            sb.append(getString(R.string.st_log_empty));
        }
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int p = Ui.dp(this, 12);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setView(sv)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void editText(String title, String current, OnText cb) {
        EditText et = new EditText(this);
        et.setText(current);
        et.setSingleLine(true);
        int p = Ui.dp(this, 16);
        et.setPadding(p, p / 2, p, p / 2);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton(R.string.save, (d, w) -> cb.accept(et.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private interface OnText {
        void accept(String t);
    }

    private void addHeader(String text) {
        TextView tv = Ui.sectionHeader(this, text);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(this, 20);
        lp.bottomMargin = Ui.dp(this, 8);
        list.addView(tv, lp);
    }

    private void addRow(LinearLayout parent, String title, String subtitle, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        TextView t = Ui.body(this, title);
        row.addView(t);
        if (subtitle != null) {
            TextView s = Ui.hint(this, subtitle);
            s.setTextSize(12);
            row.addView(s);
        }
        if (click != null) {
            row.setOnClickListener(click);
        }
        parent.addView(row, Ui.matchWrap());
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        return lp;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(this, 10);
        return lp;
    }

    private void refresh() {
        list.removeAllViews();
        fillRows();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
