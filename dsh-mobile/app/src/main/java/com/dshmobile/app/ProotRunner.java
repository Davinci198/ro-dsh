package com.dshmobile.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Builds and starts the PRoot container processes. */
public final class ProotRunner {

    public static File baseDir(Context ctx) {
        return new File(ctx.getFilesDir(), "bootstrap");
    }

    /**
     * The harness log directory. The external files directory wins: a dsh-web.log inside the
     * app's private directory can only be read by the app itself, which leaves nothing to look
     * at when the token cannot be found or the service will not start, while the external one
     * is readable with adb and any file manager. Falls back to the private directory when the
     * external volume is unavailable.
     */
    public static File logDir(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        return ext != null ? ext : baseDir(ctx);
    }

    public static File rootfsDir(Context ctx) {
        return new File(baseDir(ctx), "rootfs");
    }

    public static File prootBin(Context ctx) {
        return new File(baseDir(ctx), "proot");
    }

    /** Directory holding proot's dynamic dependencies (libtalloc.so.2 / libandroid-shmem.so). */
    public static File libDir(Context ctx) {
        return new File(baseDir(ctx), "lib");
    }

    /** Sets the host-side environment the proot process needs. */
    public static void applyEnv(Context ctx, ProcessBuilder pb) {
        pb.environment().put("PROOT_TMP_DIR", tmpDir(ctx).getAbsolutePath());
        if (libDir(ctx).isDirectory()) {
            pb.environment().put("LD_LIBRARY_PATH", libDir(ctx).getAbsolutePath());
        }
        // The loader has to be given explicitly: the path compiled into Termux's proot points
        // at Termux's private directory, which another uid cannot read.
        File loader = new File(baseDir(ctx), "loader");
        if (loader.isFile()) {
            pb.environment().put("PROOT_LOADER", loader.getAbsolutePath());
        }
    }

    public static File tmpDir(Context ctx) {
        File d = new File(baseDir(ctx), "tmp");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Shared-storage fallback directory, /mnt/shared inside the container. */
    public static File sharedDir() {
        File d = new File("/sdcard/dsh-shared");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /**
     * A clean working directory for dsh inside the container (HOME and cwd).
     * /root is not used: installing toolchains, npm caches and dsh config all write hidden
     * files into $HOME, and older versions left debris in /root, which is what made the
     * workspace picker look like "a pile of folders". dsh gets its own /home/dsh, and the SD
     * card and shared directory are mounted underneath it, so the picker only shows the two
     * purposeful entries sd/ and shared/.
     */
    public static File homeDir(Context ctx) {
        File d = new File(rootfsDir(ctx), "home/dsh");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /**
     * Assembles the proot command.
     *
     * @param inner the command to run inside the container, with its arguments
     */
    public static List<String> buildCommand(Context ctx, List<String> inner) {
        Prefs prefs = Prefs.of(ctx);
        List<String> cmd = new ArrayList<>();
        cmd.add(prootBin(ctx).getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("-0");
        cmd.add("--link2symlink");
        cmd.add("-r");
        cmd.add(rootfsDir(ctx).getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add(tmpDir(ctx).getAbsolutePath() + ":/tmp");

        // Clean working directory /home/dsh (install and dsh both use it; /root is left to the system)
        File home = homeDir(ctx);

        // External SD card -> /mnt/sd, and also under the working directory -> /home/dsh/sd so files are easy to reach
        String sd = prefs.getSdPath();
        if (sd != null && new File(sd).isDirectory()) {
            new File(rootfsDir(ctx), "mnt/sd").mkdirs();
            cmd.add("-b");
            cmd.add(sd + ":/mnt/sd");
            new File(home, "sd").mkdirs();
            cmd.add("-b");
            cmd.add(sd + ":/home/dsh/sd");
        }
        // Shared storage fallback -> /mnt/shared, and also -> /home/dsh/shared
        File shared = sharedDir();
        if (shared.isDirectory()) {
            new File(rootfsDir(ctx), "mnt/shared").mkdirs();
            cmd.add("-b");
            cmd.add(shared.getAbsolutePath() + ":/mnt/shared");
            new File(home, "shared").mkdirs();
            cmd.add("-b");
            cmd.add(shared.getAbsolutePath() + ":/home/dsh/shared");
        }

        cmd.add("-w");
        cmd.add("/home/dsh");
        // Drop the host-side LD_LIBRARY_PATH (it points at proot's dependencies and means nothing
        // inside), then hand over to the requested command. This used to be /usr/bin/env -u, but
        // the minimal Ubuntu base has no coreutils env (the 26.04 base has none), so the unset is
        // done by the shell every rootfs is guaranteed to carry.
        cmd.add("/bin/sh");
        cmd.add("-c");
        cmd.add("unset LD_LIBRARY_PATH; "
                + "PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                + "HOME=/home/dsh; TERM=xterm-256color; DEBIAN_FRONTEND=noninteractive; "
                // The dsh command sandbox is disabled: inside a phone PRoot container both bwrap
                // (needs unprivileged user namespaces, normally disabled by the Android kernel) and
                // Landlock (needs kernel 5.13+ with that LSM enabled) fail their probes, so commands
                // under workspace-write are refused with SANDBOX_UNAVAILABLE. Pinning
                // danger-full-access makes bash-sandbox run commands without consulting the runner,
                // leaves fs-sandbox unfenced and stops approval from asking one by one; in the dsh-base
                // composition both sandbox-policy and approval read this variable as a deployment-level
                // override.
                + "DSH_PERMISSION_MODE=danger-full-access; "
                + "export PATH HOME TERM DEBIAN_FRONTEND DSH_PERMISSION_MODE; "
                + "exec \"$@\"");
        cmd.add("sh");
        cmd.addAll(inner);
        return cmd;
    }

    public static Process exec(Context ctx, List<String> inner, File logFile) throws IOException {
        ensureExecutable(ctx);
        ProcessBuilder pb = new ProcessBuilder(buildCommand(ctx, inner));
        applyEnv(ctx, pb);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        return pb.start();
    }

    /** Starts the container process over pipes: the caller owns stdin/stdout (interactive terminal), nothing is logged. */
    public static Process execPiped(Context ctx, List<String> inner) throws IOException {
        ensureExecutable(ctx);
        ProcessBuilder pb = new ProcessBuilder(buildCommand(ctx, inner));
        applyEnv(ctx, pb);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /** Defensive: older versions could install a proot without the executable bit. */
    private static void ensureExecutable(Context ctx) {
        File proot = prootBin(ctx);
        if (proot.isFile()) {
            try {
                android.system.Os.chmod(proot.getAbsolutePath(), 0755);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                proot.setExecutable(true, true);
            }
        }
    }

    /** Starts the dsh web service process. */
    public static Process startWeb(Context ctx, int port, File logFile) throws IOException {
        List<String> inner = new ArrayList<>();
        inner.add("dsh");
        inner.add("web");
        inner.add("--host");
        inner.add("127.0.0.1");
        inner.add("--port");
        inner.add(String.valueOf(port));
        // Without --no-open dsh launches the system browser and a Chrome tab covers the app
        inner.add("--no-open");
        return exec(ctx, inner, logFile);
    }

    /**
     * Starts sshd inside the container (-D foreground, so its lifetime follows the proot
     * process and it dies with the service). It listens on 127.0.0.1 only: local terminals
     * (Termux and friends) connect directly, a computer uses adb forward. Host keys,
     * /run/sshd and the root password are ensured/synced on every start (idempotent).
     */
    public static Process startSshd(Context ctx, int port, File logFile) throws IOException {
        String pw = Prefs.of(ctx).getSshPassword();
        List<String> inner = new ArrayList<>();
        inner.add("/bin/sh");
        inner.add("-c");
        // Normal user dsh (home=/home/dsh): root's login shell has no node/npm on PATH, so
        // daily work runs as dsh; /etc/profile.d adds the node path to every login shell.
        inner.add("mkdir -p /run/sshd && ssh-keygen -A >/dev/null 2>&1; "
                + "id dsh >/dev/null 2>&1 || useradd -d /home/dsh -s /bin/bash dsh; "
                + "echo 'dsh:" + pw + "' | chpasswd && echo 'root:" + pw + "' | chpasswd && "
                + "printf 'export PATH=/opt/node/bin:$PATH\\n' > /etc/profile.d/dsh-node.sh && "
                + "exec /usr/sbin/sshd -D -e"
                + " -o ListenAddress=127.0.0.1:" + port
                + " -o PermitRootLogin=yes -o PasswordAuthentication=yes");
        return exec(ctx, inner, logFile);
    }
}
