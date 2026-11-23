package com.dshmobile.app;

import android.content.Context;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First-run bootstrap: downloads the Ubuntu rootfs, proot and Node.js, then installs dsh
 * inside the container. Every method runs on the calling thread; the caller puts it on a
 * background thread.
 */
public final class BootstrapInstaller {

    public interface Listener {
        void onStage(String stage, int percent);

        void onLog(String line);

        void onDone(boolean success, String error);
    }

    private static final String TERMUX_POOL =
            "https://mirrors.ustc.edu.cn/termux/apt/termux-main/pool/main/";
    private static final String PROOT_POOL = TERMUX_POOL + "p/proot/";
    private static final String LIBTALLOC_POOL = TERMUX_POOL + "libt/libtalloc/";
    private static final String LIBANDROID_SHMEM_POOL = TERMUX_POOL + "liba/libandroid-shmem/";
    private static final String NODE_SERIES = "latest-v22.x";
    private static final String DSH_PACKAGE = "@deepseek-ai/dsh";
    /**
     * The pinned dsh version.
     *
     * A bare package name (`npm install -g @deepseek-ai/dsh`) means the range `@*` to npm, and
     * `*` never matches a prerelease; every version this package publishes is a prerelease
     * (0.2.2-rc.5, 0.2.3-alpha.2), so the first install always failed with
     * `ETARGET No matching version found for @deepseek-ai/dsh@*`. Pinning the version skips
     * range resolution entirely, and an upgrade only touches this line.
     */
    private static final String DSH_VERSION = "0.2.2-rc.5";
    /** Falls back to the official registry when the configured mirror has no such package (blocked, or not synced). */
    private static final String FALLBACK_NPM_REGISTRY = "https://registry.npmjs.org";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;

    private final Context ctx;
    private final Prefs prefs;
    private final Listener listener;
    private volatile boolean cancelled;

    public BootstrapInstaller(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = Prefs.of(ctx);
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
    }

    private void stage(String s, int p) {
        listener.onStage(s, p);
    }

    private void log(String s) {
        listener.onLog(s);
    }

    private void checkCancelled() throws IOException {
        if (cancelled) throw new IOException("Cancelled");
    }

    /** Runs the whole install flow. */
    public void run() {
        try {
            File base = ProotRunner.baseDir(ctx);
            File dl = new File(base, "dl");
            //noinspection ResultOfMethodCallIgnored
            dl.mkdirs();
            File rootfs = ProotRunner.rootfsDir(ctx);

            // 0. Host check: some Android 15/16 devices run a 16KB page kernel, where a
            //    4KB-aligned ELF (the official Ubuntu and Node packages) fails exec with ENOEXEC.
            long pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
            log("Host kernel page size: " + pageSize + " bytes");
            if (pageSize == 16384) {
                log("⚠ This device uses 16KB pages: the Ubuntu rootfs and the official Node.js "
                        + "binaries are 4KB aligned and may fail to run (Exec format error). "
                        + "proot itself comes from a current Termux build (16KB aligned) and is fine.");
            }

            // 1. rootfs
            File rootfsTar = new File(dl, "ubuntu-base.tar.gz");
            if (!new File(rootfs, "bin/bash").isFile()) {
                stage("Downloading the Ubuntu rootfs", 2);
                download(prefs.getRootfsUrl(), rootfsTar, 2, 25);
                checkCancelled();
                stage("Unpacking the rootfs", 26);
                deleteRecursively(rootfs);
                //noinspection ResultOfMethodCallIgnored
                rootfs.mkdirs();
                extractTar(rootfsTar, rootfs, true, 0);
                log("rootfs unpacked");
            } else {
                log("rootfs already present, skipped");
            }

            // 2. proot (with its loader: proot needs it to start any ELF, and the path compiled
            //    into it points at Termux's private directory, which is EACCES across uids, so it
            //    is extracted with the package and PROOT_LOADER is set)
            File proot = ProotRunner.prootBin(ctx);
            File loader = new File(base, "loader");
            if (!proot.isFile() || !loader.isFile()) {
                stage("Downloading proot", 32);
                String debUrl = resolveTermuxDeb(PROOT_POOL, "proot_");
                log("proot package: " + debUrl);
                File deb = new File(dl, "proot.deb");
                download(debUrl, deb, 32, 40);
                checkCancelled();
                stage("Extracting proot", 41);
                extractProotFromDeb(deb, base);
                //noinspection ResultOfMethodCallIgnored
                proot.setExecutable(true, true);
                //noinspection ResultOfMethodCallIgnored
                loader.setExecutable(true, true);
                //noinspection ResultOfMethodCallIgnored
                new File(base, "loader32").setExecutable(true, true);
                log("proot and its loader are ready");
            } else {
                log("proot already present, skipped");
            }
            // On a 16KB page device, verify the proot/loader ELF alignment (a current Termux build is 16384)
            if (pageSize == 16384) {
                long align = elfMaxAlign(proot);
                log("proot ELF alignment: " + align);
                if (align >= 0 && align < 16384) {
                    log("⚠ The proot binary is not 16KB aligned and cannot run on this device; please report this log.");
                }
            }

            // 2b. proot's dependencies (Termux's proot links libtalloc.so.2 and libandroid-shmem.so dynamically)
            File libDir = new File(base, "lib");
            File tallocSo = new File(libDir, "libtalloc.so.2");
            if (!tallocSo.isFile()) {
                stage("Downloading proot's dependencies", 42);
                //noinspection ResultOfMethodCallIgnored
                libDir.mkdirs();
                String tallocUrl = resolveTermuxDeb(LIBTALLOC_POOL, "libtalloc_");
                log("libtalloc package: " + tallocUrl);
                File tallocDeb = new File(dl, "libtalloc.deb");
                download(tallocUrl, tallocDeb, 42, 43);
                extractLibsFromDeb(tallocDeb, libDir);
                checkCancelled();
                String shmemUrl = resolveTermuxDeb(LIBANDROID_SHMEM_POOL, "libandroid-shmem_");
                log("libandroid-shmem package: " + shmemUrl);
                File shmemDeb = new File(dl, "libandroid-shmem.deb");
                download(shmemUrl, shmemDeb, 43, 44);
                extractLibsFromDeb(shmemDeb, libDir);
                log("proot dependencies are ready");
            } else {
                log("proot dependencies already present, skipped");
            }

            // 3. Node.js -> rootfs/opt/node
            File nodeBin = new File(rootfs, "opt/node/bin/node");
            if (!nodeBin.isFile()) {
                stage("Downloading Node.js", 45);
                String nodeUrl = resolveNodeUrl();
                log("Node package: " + nodeUrl);
                File nodeTar = new File(dl, "node.tar.xz");
                download(nodeUrl, nodeTar, 45, 65);
                checkCancelled();
                stage("Unpacking Node.js", 66);
                deleteRecursively(new File(rootfs, "opt/node"));
                //noinspection ResultOfMethodCallIgnored
                new File(rootfs, "opt/node").mkdirs();
                extractTar(nodeTar, new File(rootfs, "opt/node"), false, 1);
                log("Node.js is ready");
            } else {
                log("Node.js already present, skipped");
            }

            // 4. Container basics
            stage("Configuring the container", 72);
            writeContainerConfig(rootfs);

            // 4b. Build toolchain (the node-pty that dsh depends on has to be compiled here)
            File gxx = new File(rootfs, "usr/bin/g++");
            if (!gxx.isFile()) {
                stage("Installing the build toolchain", 74);
                // Safety retry: when a flaky network or mirror breaks it, clean up and retry
                // once, then skip instead of aborting the whole install (HarnessService
                // retries the node-pty build before the service starts).
                boolean toolchainOk = false;
                for (int attempt = 1; attempt <= 2 && !toolchainOk && !cancelled; attempt++) {
                    try {
                        // If a previous attempt was killed by the system (backgrounded, task
                        // cleared), the container's apt-get becomes an orphan that keeps the dpkg
                        // lock and every retry says "Could not get lock". Clear it first.
                        killStaleAptProcesses();
                        runInContainer(Arrays.asList("/usr/bin/apt-get",
                                "-o", "DPkg::Lock::Timeout=180", "update"), 74, 78);
                        checkCancelled();
                        // If the last run was interrupted inside dpkg's configure phase, a plain
                        // install says "dpkg was interrupted": repair the half-configured state
                        // first (it returns 0 when there is nothing to repair).
                        try {
                            runInContainer(Arrays.asList("/usr/bin/dpkg", "--configure", "-a"), 78, 78);
                        } catch (Exception e) {
                            log("dpkg state repair did not finish (ignored, continuing)");
                        }
                        runInContainer(Arrays.asList("/usr/bin/apt-get",
                                "-o", "DPkg::Lock::Timeout=180",
                                "install", "-y", "--no-install-recommends", "python3", "make", "g++",
                                "ca-certificates", "git"), 78, 82);
                        toolchainOk = gxx.isFile();
                    } catch (Exception e) {
                        if (cancelled) throw e;
                        log("Toolchain install failed (attempt " + attempt + "): " + e.getMessage());
                    }
                }
                if (!toolchainOk) {
                    log("⚠ The build toolchain could not be installed and was skipped. node-pty is "
                            + "rebuilt when the service starts; you can also reset the container from "
                            + "Settings once the network is better.");
                }
            } else {
                log("Build toolchain already present, skipped");
            }

            // 5. Install dsh inside the container
            File dshBin = new File(rootfs, "opt/node/bin/dsh");
            if (!dshBin.isFile()) {
                stage("Installing DeepSeek Harness", 83);
                cleanStaleDshInstall(rootfs);
                installDsh();
            } else {
                log("dsh already installed, skipped");
            }

            // 5b. Verify the node-pty native module. While npm installs dsh, node-pty has to be
            // compiled by node-gyp, and a failed compile (usually a blocked header download) is
            // treated as an optional dependency: skipped silently, install "succeeds", and dsh web
            // then dies on start because its subprocess plugin cannot load pty.node. This step is
            // independent of the install above so a broken tree can be repaired by re-running it;
            // HarnessService repeats the same check before starting the service.
            if (NodePtyFixer.needsFix(rootfs)) {
                if (!new File(rootfs, "usr/bin/g++").isFile()) {
                    // The toolchain step was skipped or failed, so this cannot compile. Do not
                    // fail hard: HarnessService retries with the toolchain once the network is back.
                    log("⚠ No build toolchain, skipping the node-pty build (the service retries it)");
                } else {
                    stage("Building the node-pty native module", 97);
                    log("node-pty has no pty.node; rebuilding it in the container…");
                    File installLog = new File(ProotRunner.baseDir(ctx), "install.log");
                    if (!NodePtyFixer.fix(ctx, installLog)) {
                        throw new IOException("Rebuilding the node-pty native module failed; see the log in Settings");
                    }
                    log("The node-pty native module is ready");
                }
            }

            checkCancelled();
            // 6. SSH service, so local terminals (Termux, adb forward) can reach the container
            ensureSshServer(rootfs);
            checkCancelled();
            stage("Done", 100);
            prefs.setSetupDone(true);
            listener.onDone(true, null);
        } catch (Exception e) {
            log("Install failed: " + e.getMessage());
            listener.onDone(false, e.getMessage());
        }
    }

    /**
     * Installs the container's SSH service (idempotent) and logs the stage.
     * Host keys, /run/sshd and the root password are ensured on every sshd start (ProotRunner.startSshd).
     */
    private void ensureSshServer(File rootfs) throws IOException, InterruptedException {
        if (new File(rootfs, "usr/sbin/sshd").isFile()) {
            log("SSH service already installed, skipped");
            return;
        }
        stage("Installing the SSH service", 97);
        // Safety retry: one retry, then skip; ensureSshServerInstalled installs it before the
        // service starts, so the whole install is never held hostage by this step.
        for (int attempt = 1; attempt <= 2 && !cancelled; attempt++) {
            try {
                // Same as the toolchain: clear leftover apt processes and the half-configured dpkg state first
                killStaleAptProcesses();
                runInContainer(Arrays.asList("/usr/bin/apt-get",
                        "-o", "DPkg::Lock::Timeout=180", "update"), 97, 97);
                try {
                    runInContainer(Arrays.asList("/usr/bin/dpkg", "--configure", "-a"), 97, 97);
                } catch (Exception e) {
                    log("dpkg state repair did not finish (ignored, continuing)");
                }
                runInContainer(Arrays.asList("/usr/bin/apt-get",
                        "-o", "DPkg::Lock::Timeout=180",
                        "install", "-y", "--no-install-recommends", "openssh-server"), 97, 99);
                return;
            } catch (Exception e) {
                if (cancelled) throw e;
                log("SSH service install failed (attempt " + attempt + "): " + e.getMessage());
            }
        }
        log("⚠ The SSH service could not be installed and was skipped (the service retries it on start)");
    }

    /**
     * Safety net before the service starts (called by HarnessService): an older container has no
     * sshd, so it is installed over the network. A failure only costs SSH, never the web service.
     * The output is appended to logFile.
     */
    public static void ensureSshServerInstalled(Context ctx, File logFile) {
        if (new File(ProotRunner.rootfsDir(ctx), "usr/sbin/sshd").isFile()) return;
        BootstrapInstaller installer = new BootstrapInstaller(ctx, new Listener() {
            @Override
            public void onStage(String stage, int percent) {
                appendLog(logFile, "[ssh] " + stage);
            }

            @Override
            public void onLog(String line) {
                appendLog(logFile, "[ssh] " + line);
            }

            @Override
            public void onDone(boolean success, String error) {
            }
        });
        try {
            installer.ensureSshServer(ProotRunner.rootfsDir(ctx));
            appendLog(logFile, "[ssh] openssh-server installed");
        } catch (Exception e) {
            appendLog(logFile, "[ssh] install failed (the web service is unaffected): " + e.getMessage());
        }
    }

    private static void appendLog(File f, String line) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
            // A log that cannot be written is not worth failing anything over.
        }
    }

    /**
     * Reads VERSION_CODENAME out of the unpacked base, which is what apt sources have to name.
     * Falls back to jammy so a rootfs without os-release keeps the previous behaviour.
     */
    private static String rootfsCodename(File rootfs) {
        File os = new File(rootfs, "etc/os-release");
        if (os.isFile()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(os))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("VERSION_CODENAME=")) {
                        String v = line.substring("VERSION_CODENAME=".length())
                                .replace("\"", "").trim();
                        if (!v.isEmpty()) return v;
                    }
                }
            } catch (IOException ignored) {
                // Falls through to the default below.
            }
        }
        return "jammy";
    }

    private void writeContainerConfig(File rootfs) throws IOException {
        File etc = new File(rootfs, "etc");
        //noinspection ResultOfMethodCallIgnored
        etc.mkdirs();
        File resolv = new File(etc, "resolv.conf");
        // With only 223.5.5.5 (a China DNS) every lookup outside China times out and npm
        // reports EAI_AGAIN. Three public resolvers are written instead: glibc tries them in
        // order and one reachable server is enough.
        String dns = "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 223.5.5.5\n";
        Files.write(resolv.toPath(), dns.getBytes(StandardCharsets.UTF_8));
        // apt sources (arm64 ports) for the node-pty build toolchain.
        // http is required rather than https: a fresh ubuntu-base rootfs has no CA bundle, so
        // the TLS handshake fails (certificate is NOT trusted), the index cannot be fetched and
        // even ca-certificates cannot be installed — a chicken-and-egg problem. apt integrity
        // comes from InRelease signature checks, and http is the documented way to use an Ubuntu
        // mirror.
        File sources = new File(etc, "apt");
        // The suite name follows the unpacked base (jammy for 22.04, resolute for 26.04), so a
        // different rootfs URL does not leave apt pointing at a release that was not installed.
        String codename = rootfsCodename(rootfs);
        log("apt suite: " + codename);
        //noinspection ResultOfMethodCallIgnored
        sources.mkdirs();
        String list = "deb http://ports.ubuntu.com " + codename + " main restricted universe multiverse\n"
                + "deb http://ports.ubuntu.com " + codename + "-updates main restricted universe multiverse\n"
                + "deb http://ports.ubuntu.com " + codename + "-security main restricted universe multiverse\n";
        Files.write(new File(sources, "sources.list").toPath(), list.getBytes(StandardCharsets.UTF_8));
        //noinspection ResultOfMethodCallIgnored
        new File(rootfs, "mnt/sd").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(rootfs, "mnt/shared").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(rootfs, "root").mkdirs();
        log("resolv.conf, apt sources and mount points written");
    }

    /**
     * An interrupted install (process killed, disk full) leaves half-written directories behind,
     * and npm then fails every rename with ENOTEMPTY, permanently. Clear them before installing.
     */
    private void cleanStaleDshInstall(File rootfs) {
        File scopeDir = new File(rootfs, "opt/node/lib/node_modules/@deepseek-ai");
        File[] stale = scopeDir.listFiles();
        if (stale == null) return;
        for (File f : stale) {
            if (f.getName().equals("dsh") || f.getName().startsWith(".dsh-")) {
                deleteRecursively(f);
            }
        }
    }

    /**
     * Installs dsh inside the container.
     *
     * Two-stage fallback: versions [pinned → latest → next] × registries [configured mirror →
     * registry.npmjs.org]. The pinned version works around prerelease range resolution, and the
     * registry fallback covers a mirror that does not carry the package or an unreachable mirror
     * (a China mirror from abroad, for instance). Without it the user only ever saw
     * "install failed: container command failed (1)" with no way to tell version from network.
     */
    private void installDsh() throws IOException, InterruptedException {
        List<String> registries = new ArrayList<>();
        String configured = prefs.getNpmRegistry();
        if (configured != null && !configured.trim().isEmpty()) {
            registries.add(configured.trim());
        }
        if (!registries.contains(FALLBACK_NPM_REGISTRY)) {
            registries.add(FALLBACK_NPM_REGISTRY);
        }
        List<String> specs = Arrays.asList(
                DSH_PACKAGE + "@" + DSH_VERSION,
                DSH_PACKAGE + "@latest",
                DSH_PACKAGE + "@next");
        IOException last = null;
        for (String registry : registries) {
            checkCancelled();
            runInContainer(Arrays.asList("/opt/node/bin/npm", "config", "set", "registry",
                    registry), 83, 85);
            for (String spec : specs) {
                checkCancelled();
                log("Installing " + spec + " (from " + registry + ")");
                try {
                    runInContainer(Arrays.asList("/opt/node/bin/npm", "install", "-g", spec,
                            "--no-audit", "--no-fund"), 85, 96);
                    log("dsh installed: " + spec);
                    return;
                } catch (IOException e) {
                    last = e;
                    log("⚠ Install failed, trying the next version/registry: " + e.getMessage());
                }
            }
        }
        throw last != null ? last : new IOException("dsh could not be installed");
    }

    /** Runs a command in the container, streaming the log out and roughly advancing the percentage from npm's output. */
    private void runInContainer(List<String> inner, int pStart, int pEnd) throws IOException, InterruptedException {
        File logFile = new File(ProotRunner.baseDir(ctx), "install.log");
        ProcessBuilder pb = new ProcessBuilder(ProotRunner.buildCommand(ctx, inner));
        ProotRunner.applyEnv(ctx, pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        long lastDot = 0;
        int shown = pStart;
        try (InputStream in = p.getInputStream();
             OutputStream lf = new FileOutputStream(logFile, true)) {
            byte[] buf = new byte[4096];
            StringBuilder line = new StringBuilder();
            int n;
            while ((n = in.read(buf)) != -1) {
                lf.write(buf, 0, n);
                lf.flush();
                for (int i = 0; i < n; i++) {
                    char c = (char) buf[i];
                    if (c == '\n' || c == '\r') {
                        if (line.length() > 0) {
                            log(line.toString());
                            line.setLength(0);
                        }
                    } else if (line.length() < 300) {
                        line.append(c);
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastDot > 2000 && shown < pEnd) {
                    shown++;
                    lastDot = now;
                    stage(null, shown);
                }
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Container command failed (" + code + "): " + inner);
        }
    }

    /**
     * Clears apt-get/dpkg processes left over from an earlier install attempt.
     * When the app is killed, the container's apt-get/dpkg keeps running as an orphan (with its
     * proot host process, whose cmdline carries the /usr/bin/apt-get arguments) and holds the
     * dpkg lock indefinitely. It runs under the same uid as the app, so /proc finds it and it can
     * be killed directly. Only called before an apt step, when this run has not started any apt
     * process yet, so anything matched here is necessarily a leftover.
     */
    private void killStaleAptProcesses() {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return;
        int myUid = android.os.Process.myUid();
        int myPid = android.os.Process.myPid();
        boolean killed = false;
        for (File entry : entries) {
            int pid;
            try {
                pid = Integer.parseInt(entry.getName());
            } catch (NumberFormatException e) {
                continue; // not a PID directory
            }
            if (pid == myPid) continue;
            try {
                String status = readProcFile(new File(entry, "status"));
                if (status == null || parseUid(status) != myUid) continue;
                String cmdline = readProcFile(new File(entry, "cmdline"));
                if (cmdline == null) continue;
                if (cmdline.contains("apt-get") || cmdline.contains("/dpkg")
                        || cmdline.contains("unattended-upgrade")) {
                    log("Killing a leftover package manager process (pid " + pid + ")");
                    android.os.Process.killProcess(pid);
                    killed = true;
                }
            } catch (Exception ignored) {
                // The process exited in the meantime, and similar races; ignore them
            }
        }
        if (killed) {
            // Let it exit and release the lock files
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Reads a /proc text file; cmdline's NUL separators become spaces (null on failure). */
    private static String readProcFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8).replace('\0', ' ');
        } catch (IOException e) {
            return null;
        }
    }

    /** Parses the real uid out of /proc/PID/status ("Uid:\t10081\t..."), -1 on failure. */
    private static int parseUid(String status) {
        for (String line : status.split("\n")) {
            if (line.startsWith("Uid:")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    // ---------- Download ----------

    private void download(String url, File dest, int pStart, int pEnd) throws IOException {
        checkCancelled();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        HttpURLConnection conn = open(url);
        long total = conn.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) != -1) {
                checkCancelled();
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastReport > 500) {
                    lastReport = now;
                    int p = total > 0
                            ? pStart + (int) ((pEnd - pStart) * done / total)
                            : pStart;
                    stage(null, Math.min(p, pEnd));
                    log(String.format("Downloaded %.1f MB", done / 1048576.0));
                }
            }
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(dest)) {
            throw new IOException("Cannot write " + dest);
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "dsh-mobile/1.0");
        int code = conn.getResponseCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code + ": " + url);
        }
        return conn;
    }

    private String fetchText(String url) throws IOException {
        HttpURLConnection conn = open(url);
        StringBuilder sb = new StringBuilder();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }

    /** Resolves the newest aarch64 deb of the given package from the Termux repository listing. */
    private String resolveTermuxDeb(String pool, String prefix) throws IOException {
        String html = fetchText(pool);
        // An href may be a bare file name (packages.termux.dev) or an absolute path
        // (the fancyindex of mirrors.ustc.edu.cn), so only the last path segment is taken
        Matcher m = Pattern.compile("href=\"(?:[^\"]*/)?(" + Pattern.quote(prefix) + "[^\"]+_aarch64\\.deb)\"")
                .matcher(html);
        String latest = null;
        while (m.find()) {
            latest = m.group(1);
        }
        if (latest == null) {
            throw new IOException("No aarch64 package found for " + prefix);
        }
        return pool + latest;
    }

    /** Resolves the newest Node v22 linux-arm64 tarball. */
    private String resolveNodeUrl() throws IOException {
        String base = prefs.getNodeMirror();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String html = fetchText(base + "/" + NODE_SERIES + "/");
        Matcher m = Pattern.compile("(node-v22\\.\\d+\\.\\d+)-linux-arm64\\.tar\\.xz").matcher(html);
        String latest = null;
        while (m.find()) {
            latest = m.group(1);
        }
        if (latest == null) {
            throw new IOException("No Node linux-arm64 package found");
        }
        return base + "/" + NODE_SERIES + "/" + latest + "-linux-arm64.tar.xz";
    }

    // ---------- Unpacking ----------

    /**
     * Unpacks a tar(.gz/.xz).
     *
     * @param gz         true for gzip, false for xz
     * @param stripComps how many leading directory levels to strip
     */
    private void extractTar(File archive, File dest, boolean gz, int stripComps) throws IOException {
        InputStream fis = new BufferedInputStream(new FileInputStream(archive));
        InputStream cis = gz ? new GzipCompressorInputStream(fis) : new XZCompressorInputStream(fis);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(cis)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                checkCancelled();
                String name = e.getName();
                String[] parts = name.split("/");
                List<String> kept = new ArrayList<>();
                for (String part : parts) {
                    if (!part.isEmpty() && !part.equals(".")) kept.add(part);
                }
                if (kept.size() <= stripComps) continue;
                String rel = String.join("/", kept.subList(stripComps, kept.size()));
                if (rel.isEmpty() || rel.contains("..")) continue;
                File out = new File(dest, rel);
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                } else if (e.isSymbolicLink()) {
                    String target = e.getLinkName();
                    File parent = out.getParentFile();
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                    // A text file cannot preserve symlink semantics, so a real symlink is created
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    try {
                        Files.createSymbolicLink(out.toPath(), new File(target).toPath());
                    } catch (Exception ex) {
                        // Skip when the filesystem does not support symlinks (proot --link2symlink covers it)
                        log("Skipped symlink: " + rel);
                    }
                } else if (e.isLink()) {
                    File parent = out.getParentFile();
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                    File src = new File(dest, e.getLinkName());
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    try {
                        Files.createLink(out.toPath(), src.toPath());
                    } catch (Exception ex) {
                        log("Skipped hard link: " + rel);
                    }
                } else {
                    File parent = out.getParentFile();
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                    try (OutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = tar.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                    }
                    int mode = e.getMode();
                    if ((mode & 0100) != 0) {
                        //noinspection ResultOfMethodCallIgnored
                        out.setExecutable(true, true);
                    }
                }
            }
        }
    }

    /** A deb is an ar archive: take bin/proot and libexec/proot/loader(32) out of its data.tar.*. */
    private void extractProotFromDeb(File deb, File destDir) throws IOException {
        try (ArArchiveInputStream ar = new ArArchiveInputStream(
                new BufferedInputStream(new FileInputStream(deb)))) {
            ArArchiveEntry e;
            byte[] dataTar = null;
            boolean xz = true;
            while ((e = ar.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("data.tar")) {
                    xz = name.endsWith(".xz");
                    dataTar = readAll(ar);
                    break;
                }
            }
            if (dataTar == null) {
                throw new IOException("No data.tar inside the deb");
            }
            InputStream cis = xz
                    ? new XZCompressorInputStream(new java.io.ByteArrayInputStream(dataTar))
                    : new GzipCompressorInputStream(new java.io.ByteArrayInputStream(dataTar));
            int found = 0;
            try (TarArchiveInputStream tar = new TarArchiveInputStream(cis)) {
                TarArchiveEntry te;
                while ((te = tar.getNextEntry()) != null) {
                    String name = te.getName();
                    if (te.isDirectory()) continue;
                    String outName = null;
                    if (name.endsWith("/bin/proot")) {
                        outName = "proot";
                    } else if (name.endsWith("/libexec/proot/loader")) {
                        outName = "loader";
                    } else if (name.endsWith("/libexec/proot/loader32")) {
                        outName = "loader32";
                    }
                    if (outName == null) continue;
                    try (OutputStream fos = new FileOutputStream(new File(destDir, outName))) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = tar.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                    }
                    found++;
                }
            }
            if (found == 0) {
                throw new IOException("No proot binary inside the deb");
            }
        }
    }

    /** Extracts the .so libraries of a deb into libDir (symlinks are written as real files). */
    private void extractLibsFromDeb(File deb, File libDir) throws IOException {
        java.util.Map<String, byte[]> files = new java.util.HashMap<>();
        java.util.Map<String, String> symlinks = new java.util.HashMap<>();
        try (ArArchiveInputStream ar = new ArArchiveInputStream(
                new BufferedInputStream(new FileInputStream(deb)))) {
            ArArchiveEntry e;
            byte[] dataTar = null;
            boolean xz = true;
            while ((e = ar.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("data.tar")) {
                    xz = name.endsWith(".xz");
                    dataTar = readAll(ar);
                    break;
                }
            }
            if (dataTar == null) {
                throw new IOException("No data.tar inside the deb");
            }
            InputStream cis = xz
                    ? new XZCompressorInputStream(new java.io.ByteArrayInputStream(dataTar))
                    : new GzipCompressorInputStream(new java.io.ByteArrayInputStream(dataTar));
            try (TarArchiveInputStream tar = new TarArchiveInputStream(cis)) {
                TarArchiveEntry te;
                while ((te = tar.getNextEntry()) != null) {
                    String name = te.getName();
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    if (!base.contains(".so")) continue;
                    if (te.isSymbolicLink()) {
                        String tgt = te.getLinkName();
                        symlinks.put(base, tgt.substring(tgt.lastIndexOf('/') + 1));
                    } else if (!te.isDirectory()) {
                        // TarArchiveInputStream returns -1 at the end of the current entry
                        files.put(base, readAll(tar));
                    }
                }
            }
        }
        if (files.isEmpty()) {
            throw new IOException("No .so library inside the deb");
        }
        for (java.util.Map.Entry<String, byte[]> en : files.entrySet()) {
            File out = new File(libDir, en.getKey());
            try (OutputStream fos = new FileOutputStream(out)) {
                fos.write(en.getValue());
            }
        }
        // Symlinks (libtalloc.so.2 -> libtalloc.so.2.4.3) are written as real files
        for (java.util.Map.Entry<String, String> en : symlinks.entrySet()) {
            byte[] target = files.get(en.getValue());
            if (target != null && !files.containsKey(en.getKey())) {
                File out = new File(libDir, en.getKey());
                try (OutputStream fos = new FileOutputStream(out)) {
                    fos.write(target);
                }
            }
        }
        log("Extracted libraries: " + files.keySet());
    }

    /** InputStream.readAllBytes needs API 33+, so minSdk 26 reads it by hand. */
    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Reads the largest p_align out of the program headers of a 64-bit little-endian ELF, to tell
     * whether the binary can exec on a 16KB page kernel (some Android 15/16 devices).
     * Returns -1 when it cannot be read.
     */
    static long elfMaxAlign(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] ident = new byte[6];
            raf.readFully(ident);
            if (ident[0] != 0x7F || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F'
                    || ident[4] != 2 /* 64-bit */ || ident[5] != 1 /* little endian */) {
                return -1;
            }
            long phoff = readLeLong(raf, 0x20);
            int phentsize = readLeShort(raf, 0x36);
            int phnum = readLeShort(raf, 0x38);
            long max = 0;
            for (int i = 0; i < phnum; i++) {
                long align = readLeLong(raf, phoff + (long) i * phentsize + 0x30);
                if (align > max) max = align;
            }
            return max;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long readLeLong(java.io.RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) raf.read() & 0xFF) << (8 * i);
        }
        return v;
    }

    private static int readLeShort(java.io.RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        return (raf.read() & 0xFF) | ((raf.read() & 0xFF) << 8);
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursively(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
