package com.dshmobile.app;

import android.content.Context;
import android.util.Log;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Process-wide home of the container shells.
 *
 * <p>The pty used to belong to {@link TerminalActivity}: backing out killed bash, so any long
 * command — apt, npm, a build — died the moment the chat was opened again. The sessions live in
 * the application process instead: the screen attaches to one of them and detaches on the way
 * out, while every shell keeps running. Only closing or restarting a tab ends it, and the
 * process exiting closes the ptys.
 *
 * <p>A session object is cheap until a view attaches: the pty child is created by
 * {@link TerminalSession#initializeEmulator} during the first layout pass, which is why an
 * uninitialised session is reported by {@code getEmulator() == null} rather than by
 * {@code isRunning()}.
 */
final class TerminalSessions {

    private static final String TAG = "DshTerminal";
    private static final int TRANSCRIPT_ROWS = 5000;

    private static final List<TerminalSession> sessions = new ArrayList<>();
    /** The screen currently receiving callbacks; identity only, never dereferenced. */
    private static Object attached;
    /** Why the last {@link #create} failed, so the screen can show it in its own language. */
    private static String lastError;

    private TerminalSessions() {
    }

    /** All live tabs, oldest first. Callers must not mutate the list. */
    static synchronized List<TerminalSession> sessions() {
        return sessions;
    }

    /** Creates and registers a fresh tab, bound to {@code client}. */
    static synchronized TerminalSession newTab(Context ctx, TerminalSessionClient client) {
        TerminalSession created = create(ctx, client);
        if (created == null) return null;
        sessions.add(created);
        attached = client;
        return created;
    }

    /** Kills the tab and removes it. */
    static synchronized void close(TerminalSession tab) {
        if (tab != null && sessions.remove(tab)) {
            tab.finishIfRunning();
            if (attached != null) sessions.forEach(s -> s.updateTerminalSessionClient(IDLE));
        }
    }

    /** Replaces the tab in place with a fresh shell, bound to {@code client}. */
    static synchronized TerminalSession restart(TerminalSession tab, Context ctx,
                                                TerminalSessionClient client) {
        int i = tab == null ? -1 : sessions.indexOf(tab);
        if (tab != null) {
            tab.finishIfRunning();
            sessions.remove(tab);
        }
        TerminalSession created = create(ctx, client);
        if (created == null) {
            if (i >= 0 && i < sessions.size()) sessions.add(i, tab);
            return null;
        }
        if (i >= 0 && i < sessions.size()) sessions.add(i, created);
        else sessions.add(created);
        attached = client;
        return created;
    }

    /**
     * Binds the screen to one tab and detaches every other tab from it. The other tabs keep the
     * idle sink, so a closed screen can never be called back through them.
     */
    static synchronized void bind(TerminalSession active, TerminalSessionClient client) {
        attached = client;
        for (TerminalSession s : sessions) {
            s.updateTerminalSessionClient(s == active ? client : IDLE);
        }
    }

    /** Stops callbacks from reaching an activity that is going away. */
    static synchronized void release(Object leaving) {
        if (attached != leaving) return;
        attached = null;
        for (TerminalSession s : sessions) s.updateTerminalSessionClient(IDLE);
    }

    private static TerminalSession create(Context ctx, TerminalSessionClient client) {
        try {
            // Self-healing: an existing container gets the monitor without a reinstall.
            BootstrapInstaller.ensureMtop(ctx, ProotRunner.rootfsDir(ctx));
            List<String> argv = ProotRunner.buildCommand(ctx,
                    Arrays.asList("/bin/bash", "--login"));
            TerminalSession created = new TerminalSession(argv.get(0),
                    ProotRunner.homeDir(ctx).getAbsolutePath(),
                    argv.toArray(new String[0]), ProotRunner.hostEnv(ctx), TRANSCRIPT_ROWS, client);
            lastError = null;
            return created;
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.e(TAG, "cannot create the container shell", e);
            return null;
        }
    }

    /** The failure behind a null return from the create paths, or null. */
    static synchronized String lastError() {
        return lastError;
    }

    /**
     * Sink used while no screen is attached. It holds no context, so a destroyed activity is
     * never called back and never retained by the session's threads.
     */
    private static final TerminalSessionClient IDLE = new TerminalSessionClient() {
        @Override
        public void onTextChanged(TerminalSession changedSession) {
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
        }

        @Override
        public void onBell(TerminalSession session) {
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
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
            Log.e(tag, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Log.w(tag, message);
        }

        @Override
        public void logInfo(String tag, String message) {
            Log.i(tag, message);
        }

        @Override
        public void logDebug(String tag, String message) {
            Log.d(tag, message);
        }

        @Override
        public void logVerbose(String tag, String message) {
            Log.v(tag, message);
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.e(tag, "stack trace", e);
        }
    };
}
