package com.dshmobile.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground service: owns the PRoot container process so it survives in the background. */
public class HarnessService extends Service {

    public static final String ACTION_START = "com.dshmobile.app.action.START";
    public static final String ACTION_STOP = "com.dshmobile.app.action.STOP";
    private static final String CHANNEL_ID = "harness";
    private static final int NOTIF_ID = 1001;
    private static final int MAX_RESTART = 5;

    private static Process process;
    private static Process sshdProcess;
    private static boolean running;

    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean wantRun;

    public static boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    public static void startService(Context ctx) {
        Intent i = new Intent(ctx, HarnessService.class);
        i.setAction(ACTION_START);
        ctx.startForegroundService(i);
    }

    public static void stopService(Context ctx) {
        Intent i = new Intent(ctx, HarnessService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // A service that was started with startForegroundService has to enter the foreground
            // before anything else, even when the first command it receives is the stop: the
            // platform kills the process with ForegroundServiceDidNotStartInTimeException
            // otherwise. The stop itself still happens on its own thread right after.
            startForeground(NOTIF_ID, buildNotification(getString(R.string.svc_stopping)));
            wantRun = false;
            // Stopping has to really kill the container and every service in it (the
            // notification's Stop means "shut down"). It must never be queued on the
            // executor: runLoop occupies that single thread forever (a while loop blocked
            // in process.waitFor()), so a queued stop would never run and both the
            // notification and the settings screen would have no effect. Hence a thread.
            new Thread(() -> {
                stopContainer();
                stopForeground(true);
                stopSelf();
            }, "dsh-harness-stop").start();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification(getString(R.string.svc_starting)));
        acquireWakeLock();
        wantRun = true;
        if (!isRunning()) {
            executor.execute(this::runLoop);
        }
        return START_STICKY;
    }

    private void runLoop() {
        Prefs prefs = Prefs.of(this);
        int restarts = 0;
        while (wantRun) {
            File log = new File(ProotRunner.logDir(this), "dsh-web.log");
            try {
                updateNotification(getString(R.string.svc_running, prefs.getPort()));
                startSshd(prefs, log);
                process = ProotRunner.startWeb(this, prefs.getPort(), log);
                running = true;
                int code = process.waitFor();
                running = false;
                if (!wantRun) break;
                restarts++;
                if (restarts > MAX_RESTART) {
                    updateNotification(getString(R.string.svc_gave_up));
                    break;
                }
                updateNotification(getString(R.string.svc_exited_restarting, code));
                Thread.sleep(3000);
            } catch (IOException e) {
                running = false;
                updateNotification(getString(R.string.svc_start_failed, String.valueOf(e.getMessage())));
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running = false;
        releaseWakeLock();
        if (!wantRun) {
            stopForeground(true);
            stopSelf();
        }
    }

    /** Starts sshd inside the container: an older container installs it first, and a failure here never affects the web service. */
    private void startSshd(Prefs prefs, File log) {
        BootstrapInstaller.ensureSshServerInstalled(this, log);
        if (!new File(ProotRunner.rootfsDir(this), "usr/sbin/sshd").isFile()) return;
        if (sshdProcess != null && sshdProcess.isAlive()) return;
        try {
            sshdProcess = ProotRunner.startSshd(this, prefs.getSshPort(), log);
        } catch (IOException e) {
            updateNotification(getString(R.string.svc_ssh_failed, String.valueOf(e.getMessage())));
        }
    }

    private void stopContainer() {
        // State goes false first so the settings screen and the buttons show "stopped"
        // immediately instead of waiting for the hard kill (up to 3s).
        running = false;
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                // Bounded wait: a stuck container must not block forever (the caller may be on the main thread)
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        Process s = sshdProcess;
        sshdProcess = null;
        if (s != null) {
            s.destroy();
            s.destroyForcibly();
        }
        running = false;
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dshmobile:harness");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(24 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
        // The channel is meant to be invisible: Android still requires a notification for the
        // foreground service, but at IMPORTANCE_NONE it never shows or sounds. Re-create it when
        // an older install kept the old, visible channel (importance is only applied on create).
        if (existing != null && existing.getImportance() != NotificationManager.IMPORTANCE_NONE) {
            nm.deleteNotificationChannel(CHANNEL_ID);
            existing = null;
        }
        if (existing == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.svc_channel_name), NotificationManager.IMPORTANCE_NONE);
            ch.setDescription(getString(R.string.svc_channel_description));
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, HarnessService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 31
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this, CHANNEL_ID);
        return b.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, getString(R.string.svc_stop_action), stopPi).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        wantRun = false;
        stopContainer();
        releaseWakeLock();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Last attempt when the task is swiped away: restart the service while the process
        // is still alive. Android 12+ may refuse to start a foreground service from the
        // background (ForegroundServiceStartNotAllowed and friends), and ROMs that kill the
        // whole process on swipe (Honor/MagicOS) can only be handled by the user allowing
        // autostart and background activity, which the settings screen links to.
        if (wantRun) {
            try {
                Intent restart = new Intent(this, HarnessService.class);
                restart.setAction(ACTION_START);
                startForegroundService(restart);
            } catch (Exception e) {
                // Background start restriction: nothing can bypass it without a system grant,
                // which is why START_STICKY and the user's allow-list are the fallbacks.
            }
        }
        super.onTaskRemoved(rootIntent);
    }
}
