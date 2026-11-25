package com.dshmobile.app;

import android.content.Context;
import android.util.Log;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.Arrays;
import java.util.List;

/**
 * Process-wide home of the container shell.
 *
 * <p>The pty used to belong to {@link TerminalActivity}: backing out killed bash, so any long
 * command — apt, npm, a build — died the moment the chat was opened again. The session lives in
 * the application process instead: the screen attaches to it and detaches on the way out, while
 * bash keeps running. Only {@link #restart} ends it, and the process exiting closes the pty.
 *
 * <p>The session object is cheap until a view attaches: the pty child is created by
 * {@link TerminalSession#initializeEmulator} during the first layout pass, which is why an
 * uninitialised session is reported by {@link #isStarted} rather than by {@code isRunning()}.
 */
final class TerminalSessions {

    private static final String TAG = "DshTerminal";
    private static final int TRANSCRIPT_ROWS = 5000;

    private static TerminalSession session;
    /** The screen currently receiving callbacks; identity only, never dereferenced. */
    private static Object attached;
    /** Why the last {@link #create} failed, so the screen can show it in its own language. */
    private static String lastError;

    private TerminalSessions() {
    }

    /**
     * Returns the shell for {@code client}, starting a fresh one when the previous shell exited.
     * Returns null when the container is not installed or the pty cannot be created.
     */
    static synchronized TerminalSession attach(Context ctx, TerminalSessionClient client) {
        if (isStarted() && !session.isRunning()) {
            // The child exited (the user typed exit, or it was killed) — start over.
            session = null;
        }
        if (session == null) {
            session = create(ctx, client);
            if (session == null) return null;
        } else {
            session.updateTerminalSessionClient(client);
        }
        attached = client;
        return session;
    }

    /** True once a view has laid the session out and the pty child was spawned. */
    static synchronized boolean isStarted() {
        return session != null && session.getEmulator() != null;
    }

    /** Stops callbacks from reaching an activity that is going away. */
    static synchronized void detach(Object leaving) {
        if (session == null || attached != leaving) return;
        attached = null;
        session.updateTerminalSessionClient(IDLE);
    }

    /** Kills the shell and starts a new one attached to {@code client}. */
    static synchronized TerminalSession restart(Context ctx, TerminalSessionClient client) {
        if (session != null) session.finishIfRunning();
        session = create(ctx, client);
        attached = session == null ? null : client;
        return session;
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

    /** The failure behind a null return from {@link #attach} or {@link #restart}, or null. */
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
