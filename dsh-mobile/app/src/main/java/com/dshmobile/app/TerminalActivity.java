package com.dshmobile.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.util.List;

/**
 * The container terminal, rendered the way Termux renders it: a real pseudo-terminal and a VT
 * emulator view (com.termux.*), not a captured stdout pipe. Everything a shell needs works —
 * colours, cursor, readline line editing, job control, scrollback, Ctrl+C as a real signal,
 * window size changes — because the shell really owns a tty.
 *
 * <p>The pty child is proot itself: JNI.createSubprocess() runs the PRoot binary in a pty and
 * PRoot starts /bin/bash inside the Ubuntu rootfs, so bash sees a tty of the right size.
 */
public class TerminalActivity extends Activity implements TerminalSessionClient, TerminalViewClient {

    private static final String TAG = "DshTerminal";
    /** Terminal font size in sp; TerminalView.setTextSize() wants pixels, so it is converted. */
    private static final float TEXT_SIZE_SP = 12f;

    private TerminalView terminalView;
    private TerminalSession session;
    private int activeTab;
    private TextView statusLine;
    private LinearLayout tabRow;
    private Button ctrlKey;
    private Button altKey;
    private boolean ctrlPressed;
    private boolean altPressed;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(buildUi());
        attachSession();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // When the screen returns, all tabs are still alive; re-assert the active one.
        if (session != null) attachSession();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F17);

        root.addView(buildHeader());
        root.addView(buildTabBar());
        root.addView(buildChips());
        root.addView(buildTerminal(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildStatusLine());
        root.addView(buildKeyRow(new String[]{
                getString(R.string.term_key_esc), getString(R.string.term_key_ctrl),
                getString(R.string.term_key_alt), getString(R.string.term_key_tab),
                getString(R.string.term_key_home), getString(R.string.term_key_end),
                getString(R.string.term_key_pgup)}));
        root.addView(buildKeyRow(new String[]{
                getString(R.string.term_key_left), getString(R.string.term_key_prev),
                getString(R.string.term_key_next), getString(R.string.term_key_right),
                getString(R.string.term_key_del), getString(R.string.term_key_pgdn),
                getString(R.string.term_key_enter)}));
        return root;
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF0B0F17);
        int pad = dp(10);
        bar.setPadding(pad, pad, pad, dp(6));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(0xFFC9D1D9);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(8), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.term_title);
        title.setTextColor(0xFFF0F6FC);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.term_subtitle);
        subtitle.setTextColor(0xFF6E7681);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        titles.addView(title);
        titles.addView(subtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(headerAction(getString(R.string.term_clear), v -> clearScreen()));
        bar.addView(headerAction(getString(R.string.term_restart), v -> restartSession()));
        return bar;
    }

    /**
     * Quick commands above the terminal: one tap runs the command in the shell, which is what
     * makes the screen usable on a phone without typing a whole line.
     */
    private View buildChips() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), 0, dp(10), dp(4));
        String[] commands = {"uname -a", "ls -la", "pwd", "dsh --version", "mtop --adb"};
        for (String command : commands) {
            TextView chip = new TextView(this);
            chip.setText(command);
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            chip.setTextColor(0xFF9B8CFA);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setOnClickListener(v -> {
                if (session == null) return;
                session.write(command + "\r");
                terminalView.onScreenUpdated();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(2), 0, dp(6), 0);
            row.addView(chip, lp);
        }
        return row;
    }

    /**
     * A tab bar above the quick commands: one chip per live shell, an active highlight, a "+"
     * chip to start another shell, and a long press on a chip to close that shell.
     */
    private View buildTabBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xFF0B0F17);
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        scroll.addView(tabRow);
        return scroll;
    }

    private void renderTabs() {
        if (tabRow == null) return;
        tabRow.removeAllViews();
        List<TerminalSession> tabs = TerminalSessions.sessions();
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(getString(R.string.term_tab_label, i + 1));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            chip.setPadding(dp(12), dp(5), dp(12), dp(5));
            boolean active = i == activeTab;
            chip.setTextColor(active ? 0xFF0B0F17 : 0xFFC9D1D9);
            chip.setBackgroundColor(active ? 0xFF22C55E : 0xFF131A24);
            chip.setOnClickListener(v -> switchTab(index));
            chip.setOnLongClickListener(v -> {
                closeTab(index);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(2), 0, dp(2), 0);
            tabRow.addView(chip, lp);
        }
        TextView plus = new TextView(this);
        plus.setText(R.string.term_tab_add);
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        plus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        plus.setTextColor(0xFF9B8CFA);
        plus.setPadding(dp(12), dp(4), dp(12), dp(4));
        plus.setOnClickListener(v -> newTab());
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plusLp.setMargins(dp(4), 0, 0, 0);
        tabRow.addView(plus, plusLp);
    }

    private TextView headerAction(String label, View.OnClickListener onClick) {
        TextView action = new TextView(this);
        action.setText(label);
        action.setTextColor(0xFF22C55E);
        action.setTypeface(Typeface.MONOSPACE);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(10), dp(6), dp(6), dp(6));
        action.setOnClickListener(onClick);
        return action;
    }

    private View buildTerminal() {
        terminalView = new TerminalView(this, null);
        terminalView.setBackgroundColor(0xFF000000);
        // setTextSize() creates the renderer, and setTypeface() dereferences it, so the order
        // matters: a typeface call on a fresh view would throw.
        terminalView.setTextSize(Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP, getResources().getDisplayMetrics())));
        terminalView.setTypeface(Typeface.MONOSPACE);
        terminalView.setTerminalViewClient(this);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        return terminalView;
    }

    private View buildStatusLine() {
        statusLine = new TextView(this);
        statusLine.setTextColor(0xFF6E7681);
        statusLine.setTypeface(Typeface.MONOSPACE);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        statusLine.setPadding(dp(12), dp(2), dp(12), dp(2));
        statusLine.setVisibility(View.GONE);
        return statusLine;
    }

    private View buildKeyRow(String[] labels) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(0xFF0B0F17);
        row.setPadding(dp(4), dp(2), dp(4), dp(2));
        for (String label : labels) {
            Button key = extraKey(label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    dp(38), 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(key, lp);
        }
        return row;
    }

    private Button extraKey(String label) {
        Button key = new Button(this);
        key.setText(label);
        key.setAllCaps(false);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        key.setTypeface(Typeface.MONOSPACE);
        key.setTextColor(0xFFC9D1D9);
        key.setBackgroundColor(0xFF131A24);
        key.setPadding(0, 0, 0, 0);
        key.setMinWidth(0);
        key.setMinimumWidth(0);
        key.setMinHeight(0);
        key.setMinimumHeight(0);
        key.setStateListAnimator(null);
        key.setOnClickListener(v -> onExtraKey(label));
        if (label.equals(getString(R.string.term_key_ctrl))) ctrlKey = key;
        if (label.equals(getString(R.string.term_key_alt))) altKey = key;
        return key;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    // ----------------------------------------------------------- session

    private void attachSession() {
        if (!new File(ProotRunner.rootfsDir(this), "bin/bash").isFile()) {
            toast(getString(R.string.term_not_installed));
            return;
        }
        List<TerminalSession> tabs = TerminalSessions.sessions();
        if (tabs.isEmpty()) {
            session = TerminalSessions.newTab(this, this);
            activeTab = 0;
        } else {
            activeTab = Math.min(activeTab, tabs.size() - 1);
            session = tabs.get(activeTab);
            TerminalSessions.bind(session, this);
        }
        if (session == null) {
            toast(getString(R.string.term_start_failed, TerminalSessions.lastError()));
            return;
        }
        attachView();
        renderTabs();
    }

    private void attachView() {
        terminalView.attachSession(session);
        terminalView.requestFocus();
        showKeyboard();
        refreshStatusLine();
    }

    private void switchTab(int index) {
        List<TerminalSession> tabs = TerminalSessions.sessions();
        if (index < 0 || index >= tabs.size()) return;
        activeTab = index;
        session = tabs.get(index);
        TerminalSessions.bind(session, this);
        attachView();
        renderTabs();
    }

    private void newTab() {
        TerminalSession created = TerminalSessions.newTab(this, this);
        if (created == null) {
            toast(getString(R.string.term_start_failed, TerminalSessions.lastError()));
            return;
        }
        session = created;
        activeTab = TerminalSessions.sessions().size() - 1;
        attachView();
        renderTabs();
    }

    private void closeTab(int index) {
        List<TerminalSession> tabs = TerminalSessions.sessions();
        if (index < 0 || index >= tabs.size()) return;
        TerminalSession closed = tabs.get(index);
        if (tabs.size() == 1) {
            // Closing the last tab would leave no shell; restart it in place instead.
            session = TerminalSessions.restart(closed, this, this);
            if (session == null) {
                toast(getString(R.string.term_start_failed, TerminalSessions.lastError()));
                return;
            }
            attachView();
            renderTabs();
            return;
        }
        TerminalSessions.close(closed);
        int next = index == 0 ? 0 : index - 1;
        activeTab = Math.min(next, TerminalSessions.sessions().size() - 1);
        session = TerminalSessions.sessions().get(activeTab);
        TerminalSessions.bind(session, this);
        attachView();
        renderTabs();
    }

    private void restartSession() {
        if (session == null) return;
        session = TerminalSessions.restart(session, this, this);
        if (session == null) {
            toast(getString(R.string.term_start_failed, TerminalSessions.lastError()));
            return;
        }
        attachView();
        renderTabs();
    }

    /** Mirrors the shell's state: the status line only shows when the shell has ended. */
    private void refreshStatusLine() {
        if (statusLine == null) return;
        boolean ended = session != null && session.getEmulator() != null && !session.isRunning();
        statusLine.setVisibility(ended ? View.VISIBLE : View.GONE);
        if (ended) statusLine.setText(getString(R.string.term_session_ended));
    }

    private void clearScreen() {
        if (session == null) return;
        session.write("clear\r");
        terminalView.onScreenUpdated();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    // -------------------------------------------------------- extra keys

    private void onExtraKey(String label) {
        if (ctrlKey != null && label.equals(getString(R.string.term_key_ctrl))) {
            ctrlPressed = !ctrlPressed;
            styleToggle(ctrlKey, ctrlPressed);
            return;
        }
        if (altKey != null && label.equals(getString(R.string.term_key_alt))) {
            altPressed = !altPressed;
            styleToggle(altKey, altPressed);
            return;
        }
        if (session == null) return;
        if (label.equals(getString(R.string.term_key_esc))) {
            session.write("\u001b");
        } else if (label.equals(getString(R.string.term_key_tab))) {
            session.write("\t");
        } else if (label.equals(getString(R.string.term_key_enter))) {
            session.write("\r");
        } else if (label.equals(getString(R.string.term_key_del))) {
            session.write("\u007f");
        } else if (label.equals(getString(R.string.term_key_prev))) {
            session.write("\u001b[A");
        } else if (label.equals(getString(R.string.term_key_next))) {
            session.write("\u001b[B");
        } else if (label.equals(getString(R.string.term_key_right))) {
            session.write("\u001b[C");
        } else if (label.equals(getString(R.string.term_key_left))) {
            session.write("\u001b[D");
        } else if (label.equals(getString(R.string.term_key_home))) {
            session.write("\u001b[H");
        } else if (label.equals(getString(R.string.term_key_end))) {
            session.write("\u001b[F");
        } else if (label.equals(getString(R.string.term_key_pgup))) {
            session.write("\u001b[5~");
        } else if (label.equals(getString(R.string.term_key_pgdn))) {
            session.write("\u001b[6~");
        }
        terminalView.onScreenUpdated();
        showKeyboard();
    }

    private void styleToggle(Button key, boolean on) {
        key.setTextColor(on ? 0xFF0B0F17 : 0xFFC9D1D9);
        key.setBackgroundColor(on ? 0xFF22C55E : 0xFF131A24);
    }

    // -------------------------------------------------- lifecycle / back

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Only the screen goes away: the shells keep running in the process, so a build keeps
        // going while the chat is open. Closing a tab is the explicit way to end a shell.
        TerminalSessions.release(this);
        session = null;
    }

    @Override
    public void onBackPressed() {
        // Leaving the screen leaves the shell alive; this is what makes the terminal usable.
        super.onBackPressed();
    }

    // ------------------------------------------- TerminalSessionClient

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (terminalView != null) terminalView.onScreenUpdated();
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        main.post(this::refreshStatusLine);
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        if (TextUtils.isEmpty(text)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || session == null) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (!TextUtils.isEmpty(text)) session.write(text.toString());
    }

    @Override
    public void onBell(TerminalSession session) {
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
        if (terminalView != null) terminalView.onScreenUpdated();
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public void setTerminalShellPid(TerminalSession session, int pid) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    @Override
    public void logError(String tag, String message) {
    }

    @Override
    public void logWarn(String tag, String message) {
    }

    @Override
    public void logInfo(String tag, String message) {
    }

    @Override
    public void logDebug(String tag, String message) {
    }

    @Override
    public void logVerbose(String tag, String message) {
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
    }

    // ---------------------------------------------- TerminalViewClient

    @Override
    public float onScale(float scale) {
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        showKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return false;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return ctrlPressed;
    }

    @Override
    public boolean readAltKey() {
        return altPressed;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
        terminalView.requestFocus();
    }
}
