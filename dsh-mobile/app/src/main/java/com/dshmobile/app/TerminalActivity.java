package com.dshmobile.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Container terminal, styled and behaving like the Mobile Harness terminal.
 *
 * One persistent bash lives inside the PRoot container and commands are written to
 * its stdin, so `cd`, exported variables and background jobs survive between
 * commands. Every command gets its own block: a green {@code root@pocket:~#} prompt
 * line, then the output, in the error colour when the command exited non-zero.
 *
 * The exit code is not guessed: the command is followed by a printf marker and the
 * terminal reads it back out of the stream, which is what makes "error colour for a
 * failing command" and "the next block starts on a clean line" possible on a pipe.
 */
public class TerminalActivity extends Activity {

    private static final int BG = Color.parseColor("#0B0F17");
    private static final int CONSOLE = Color.parseColor("#090D14");
    private static final int TEXT = Color.parseColor("#F0F6FC");
    private static final int OUTPUT = Color.parseColor("#C9D1D9");
    private static final int DIM = Color.parseColor("#6E7681");
    private static final int ACCENT = Color.parseColor("#22C55E");
    private static final int PROMPT = Color.parseColor("#4ADE80");
    private static final int ERROR = Color.parseColor("#F87171");
    private static final int OUTLINE = Color.parseColor("#30363D");
    private static final int CHIP_BG = Color.parseColor("#131A24");

    /** Marker printed after every command so the terminal knows where output ends. */
    private static final String RC_MARKER = "\u0001DSH_RC=";

    /** Output kept per block and in total: a runaway command must not eat the heap. */
    private static final int MAX_BLOCK = 60_000;
    private static final int MAX_TOTAL = 200_000;

    private static final List<String> QUICK_COMMANDS = Arrays.asList(
            "uname -a", "ls -la", "pwd", "node -v", "df -h", "free -m", "dsh --version");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;

    private ScrollView consoleScroll;
    private LinearLayout consoleColumn;
    private LinearLayout currentBlock;
    private TextView currentOutput;
    private final StringBuilder currentText = new StringBuilder();
    private int blocks;

    private EditText input;
    private TextView promptPrefix;

    private Process shell;
    private OutputStream stdin;
    private boolean reading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        startShell();
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        root.addView(header(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(divider());
        root.addView(quickCommands(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(divider());

        consoleScroll = new ScrollView(this);
        consoleScroll.setFillViewport(true);
        consoleColumn = new LinearLayout(this);
        consoleColumn.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        consoleColumn.setPadding(p, p, p, p);
        consoleScroll.addView(consoleColumn, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout consoleCard = new LinearLayout(this);
        consoleCard.setOrientation(LinearLayout.VERTICAL);
        consoleCard.setBackground(rounded(CONSOLE, 12, OUTLINE, 1));
        consoleCard.addView(consoleScroll);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int m = Ui.dp(this, 14);
        body.setPadding(m, Ui.dp(this, 8), m, Ui.dp(this, 8));
        body.addView(consoleCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.addView(inputRow());
        root.addView(keyRow());
        return root;
    }

    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 16);
        bar.setPadding(pad, Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));

        TextView icon = new TextView(this);
        icon.setText(">_");
        icon.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        icon.setTextSize(13);
        icon.setTextColor(ACCENT);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(withAlpha(ACCENT, 0.12f), 10, 0, 0));
        bar.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titlesParams.leftMargin = Ui.dp(this, 11);
        TextView title = new TextView(this);
        title.setText(getString(R.string.term_title));
        title.setTextColor(TEXT);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView subtitle = new TextView(this);
        subtitle.setText(getString(R.string.term_subtitle));
        subtitle.setTextColor(DIM);
        subtitle.setTextSize(10.5f);
        titles.addView(title);
        titles.addView(subtitle);
        bar.addView(titles, titlesParams);

        bar.addView(headerAction(getString(R.string.term_clear), v -> clearConsole()));
        bar.addView(headerAction(getString(R.string.term_restart), v -> startShell()));
        return bar;
    }

    private TextView headerAction(String text, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(ACCENT);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        tv.setOnClickListener(click);
        return tv;
    }

    private View quickCommands() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Ui.dp(this, 14);
        row.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 6));
        for (String cmd : QUICK_COMMANDS) {
            TextView chip = new TextView(this);
            chip.setText(cmd);
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setTextSize(11);
            chip.setTextColor(TEXT);
            chip.setBackground(rounded(CHIP_BG, 999, OUTLINE, 1));
            chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
            chip.setOnClickListener(v -> runFromChip(cmd));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 8);
            row.addView(chip, lp);
        }
        scroller.addView(row);
        return scroller;
    }

    private View inputRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(CONSOLE);
        int pad = Ui.dp(this, 12);
        row.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 6));

        promptPrefix = new TextView(this);
        promptPrefix.setText(promptText());
        promptPrefix.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        promptPrefix.setTextSize(12);
        promptPrefix.setTextColor(PROMPT);
        row.addView(promptPrefix);

        input = new EditText(this);
        input.setTextColor(TEXT);
        input.setHintTextColor(DIM);
        input.setHint(getString(R.string.term_hint));
        input.setTextSize(12);
        input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        input.setBackground(null);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            submit();
            return true;
        });
        row.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private View keyRow() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Ui.dp(this, 14);
        row.setPadding(pad, 0, pad, Ui.dp(this, 10));

        row.addView(key(getString(R.string.term_key_prev), v -> recallHistory(-1)));
        row.addView(key(getString(R.string.term_key_next), v -> recallHistory(1)));
        row.addView(key(getString(R.string.term_key_left), v -> moveCursor(-1)));
        row.addView(key(getString(R.string.term_key_right), v -> moveCursor(1)));
        row.addView(key(getString(R.string.term_key_esc), v -> input.setText("")));
        row.addView(key(getString(R.string.term_key_ctrl_c), v -> sendRaw("\u0003")));
        row.addView(key(getString(R.string.term_key_tab), v -> input.getText().insert(
                Math.min(input.getSelectionStart(), input.getText().length()), "\t")));
        scroller.addView(row);
        return scroller;
    }

    private TextView key(String label, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextColor(TEXT);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(rounded(Color.TRANSPARENT, 8, OUTLINE, 1));
        tv.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        tv.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 34));
        lp.rightMargin = Ui.dp(this, 6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(OUTLINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f))));
        return v;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusDp == 999 ? 999 : Ui.dp(this, radiusDp));
        if (strokeDp > 0) d.setStroke(Math.max(1, Ui.dp(this, strokeDp)), strokeColor);
        return d;
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb((int) (alpha * 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    // ── console blocks ──────────────────────────────────────────────────────

    private void addBlock(String command) {
        if (blocks == 0) consoleColumn.removeAllViews();
        blocks++;

        currentBlock = new LinearLayout(this);
        currentBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockParams.bottomMargin = Ui.dp(this, 8);
        currentBlock.setLayoutParams(blockParams);

        TextView prompt = new TextView(this);
        prompt.setText(promptLine(command));
        prompt.setTypeface(Typeface.MONOSPACE);
        prompt.setTextSize(12);
        prompt.setLineSpacing(Ui.dp(this, 4), 1f);
        prompt.setTextIsSelectable(true);
        currentBlock.addView(prompt);

        currentOutput = new TextView(this);
        currentOutput.setTextColor(OUTPUT);
        currentOutput.setTypeface(Typeface.MONOSPACE);
        currentOutput.setTextSize(12);
        currentOutput.setLineSpacing(Ui.dp(this, 4), 1f);
        currentOutput.setTextIsSelectable(true);
        currentOutput.setVisibility(View.GONE);
        int left = Ui.dp(this, 8);
        currentOutput.setPadding(left, Ui.dp(this, 2), 0, 0);
        currentBlock.addView(currentOutput);

        consoleColumn.addView(currentBlock);
        currentText.setLength(0);
        afterConsoleChange();
    }

    private CharSequence promptLine(String command) {
        String prefix = promptText();
        SpannableString s = new SpannableString(prefix + command);
        s.setSpan(new ForegroundColorSpan(PROMPT), 0, prefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new StyleSpan(Typeface.BOLD), 0, prefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(TEXT), prefix.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new StyleSpan(Typeface.BOLD), prefix.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private String promptText() {
        return getString(R.string.term_prompt);
    }

    /** Appends raw terminal output to the block that is currently running. */
    private void appendOutput(String text) {
        if (currentOutput == null) {
            // Output with no command of its own (a shell notice): give it a block.
            addBlock("");
        }
        currentText.append(text);
        if (currentText.length() > MAX_BLOCK) {
            currentText.delete(0, currentText.length() - MAX_BLOCK);
        }
        trimTotal();
        currentOutput.setText(currentText.toString());
        if (currentText.length() > 0) currentOutput.setVisibility(View.VISIBLE);
        afterConsoleChange();
    }

    private void trimTotal() {
        while (consoleColumn.getChildCount() > 12) {
            consoleColumn.removeViewAt(0);
        }
    }

    private void afterConsoleChange() {
        consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void markExit(int code) {
        if (currentOutput == null) return;
        String text = currentText.toString();
        if (text.isEmpty()) currentOutput.setVisibility(View.GONE);
        currentOutput.setTextColor(code == 0 ? OUTPUT : ERROR);
        currentOutput = null;
        currentBlock = null;
        currentText.setLength(0);
        afterConsoleChange();
    }

    private void clearConsole() {
        consoleColumn.removeAllViews();
        blocks = 0;
        currentOutput = null;
        currentBlock = null;
        currentText.setLength(0);
        startShell();
    }

    // ── shell ───────────────────────────────────────────────────────────────

    private void startShell() {
        stopShell();
        if (!ProotRunner.prootBin(this).isFile() || !ProotRunner.rootfsDir(this).isDirectory()) {
            addBlock("");
            appendOutput(getString(R.string.term_not_installed));
            markExit(1);
            return;
        }
        try {
            shell = ProotRunner.execPiped(this, Arrays.asList("/bin/bash", "--noprofile", "--norc"));
            stdin = shell.getOutputStream();
            Process p = shell;
            reading = true;
            new Thread(() -> readLoop(p), "dsh-term-read").start();
            addBlock("");
            appendOutput(getString(R.string.term_connected));
            markExit(0);
        } catch (IOException e) {
            addBlock("");
            appendOutput(getString(R.string.term_start_failed, String.valueOf(e.getMessage())));
            markExit(1);
        }
    }

    private void stopShell() {
        reading = false;
        if (shell != null) {
            // The proot is started with --kill-on-exit, so destroying it takes the
            // whole container process tree with it.
            shell.destroy();
            shell = null;
        }
        stdin = null;
    }

    private void readLoop(Process p) {
        StringBuilder pending = new StringBuilder();
        try (InputStreamReader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                pending.append(buf, 0, n);
                int marker;
                while ((marker = pending.indexOf(RC_MARKER)) >= 0) {
                    String before = pending.substring(0, marker);
                    int end = pending.indexOf("\n", marker);
                    if (end < 0) break;
                    String code = pending.substring(marker + RC_MARKER.length(), end).trim();
                    pending.delete(0, end + 1);
                    final String output = before;
                    final int exit = parseCode(code);
                    handler.post(() -> {
                        if (!output.isEmpty()) appendOutput(output);
                        markExit(exit);
                    });
                }
                // A marker can arrive split across two reads: hold back everything from
                // its first byte until its newline shows up, or half a marker would be
                // printed as if it were command output.
                int partial = pending.lastIndexOf(RC_MARKER.charAt(0));
                int flushUntil = partial >= 0 && pending.indexOf("\n", partial) < 0
                        ? partial
                        : pending.length();
                if (flushUntil > 0 && blocks > 0) {
                    final String text = pending.substring(0, flushUntil);
                    pending.delete(0, flushUntil);
                    handler.post(() -> appendOutput(text));
                }
            }
        } catch (IOException ignored) {
            // The process went away; reported below.
        }
        if (p == shell && reading) {
            handler.post(() -> {
                addBlock("");
                appendOutput(getString(R.string.term_session_ended));
                markExit(1);
            });
        }
    }

    private static int parseCode(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void submit() {
        String cmd = input.getText().toString();
        input.setText("");
        if (cmd.trim().isEmpty()) return;
        history.add(cmd);
        historyIndex = -1;
        run(cmd);
    }

    private void runFromChip(String cmd) {
        history.add(cmd);
        run(cmd);
    }

    private void run(String cmd) {
        OutputStream out = stdin;
        if (shell == null || out == null || !shell.isAlive()) {
            addBlock("");
            appendOutput(getString(R.string.term_not_connected));
            markExit(1);
            return;
        }
        addBlock(cmd);
        // The marker turns an opaque byte stream into "this command ended, with this
        // exit code", which is what the block colouring and the next prompt rely on.
        String wire = cmd + "\nprintf '" + RC_MARKER + "%s\\n' \"$?\"\n";
        new Thread(() -> {
            try {
                out.write(wire.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                handler.post(() -> appendOutput(getString(R.string.term_write_failed,
                        String.valueOf(e.getMessage()))));
            }
        }, "dsh-term-write").start();
    }

    private void sendRaw(String text) {
        OutputStream out = stdin;
        if (out == null) return;
        new Thread(() -> {
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
                // Nothing useful to say: the shell is gone and the reader reports it.
            }
        }, "dsh-term-raw").start();
    }

    private void recallHistory(int direction) {
        if (history.isEmpty()) return;
        if (historyIndex < 0) {
            historyIndex = history.size() - 1;
        } else {
            historyIndex = Math.max(0, Math.min(history.size() - 1, historyIndex + direction));
        }
        input.setText(history.get(historyIndex));
        input.setSelection(input.getText().length());
    }

    private void moveCursor(int delta) {
        if (delta == 0) return;
        int pos = Math.max(0, Math.min(input.getText().length(), input.getSelectionStart() + delta));
        input.setSelection(pos);
    }

    @Override
    protected void onDestroy() {
        stopShell();
        super.onDestroy();
    }
}
