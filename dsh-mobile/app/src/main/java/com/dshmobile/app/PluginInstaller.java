package com.dshmobile.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Plugin installation inside the container: the same as the desktop command
 * `dsh plugin --profile web add <pkg>` (dsh forwards it to the pnpm in the profile directory
 * and then reconciles the packages that declare dsh.bundle into the profile's bundles list).
 * pnpm is not preinstalled: the first install generates a shim with `corepack enable`
 * (corepack ships with Node 22; the first pnpm run downloads pnpm itself).
 */
public final class PluginInstaller {

    /** Loose allow-list for npm package/git/path specs, which rules out shell injection (no shell is used anyway). */
    private static final Pattern SPEC = Pattern.compile("^[a-zA-Z0-9@][a-zA-Z0-9._/@:+\\-~]{0,127}$");
    private static final long TIMEOUT_MS = 10 * 60 * 1000;

    private PluginInstaller() {
    }

    public static final class Result {
        public final boolean ok;
        public final String output;

        Result(boolean ok, String output) {
            this.ok = ok;
            this.output = output;
        }
    }

    public static Result install(Context ctx, String spec) {
        if (spec == null || !SPEC.matcher(spec).matches()) {
            return new Result(false, "Invalid plugin package name: " + spec);
        }
        try {
            String pnpmErr = ensurePnpm(ctx);
            if (pnpmErr != null) return new Result(false, pnpmErr);
            List<String> cmd = Arrays.asList("dsh", "plugin", "--profile", "web", "add", spec);
            return run(ctx, cmd);
        } catch (Exception e) {
            return new Result(false, String.valueOf(e));
        }
    }

    /** Generates the pnpm shim with corepack when it is missing (writes rootfs/opt/node/bin, idempotent). */
    private static String ensurePnpm(Context ctx) throws Exception {
        File pnpm = new File(ProotRunner.rootfsDir(ctx), "opt/node/bin/pnpm");
        if (pnpm.isFile()) return null;
        Result r = run(ctx, Arrays.asList("corepack", "enable"), 120_000);
        if (!r.ok) return "pnpm setup failed:\n" + r.output;
        return null;
    }

    private static Result run(Context ctx, List<String> inner) throws Exception {
        return run(ctx, inner, TIMEOUT_MS);
    }

    private static Result run(Context ctx, List<String> inner, long timeoutMs) throws Exception {
        Process p = ProotRunner.execPiped(ctx, inner);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                InputStream in = p.getInputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            } catch (Exception ignored) {
            }
        }, "dsh-plugin-install-read");
        reader.setDaemon(true);
        reader.start();
        boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!done) {
            // Ask nicely, then insist: pnpm can ignore SIGTERM while it downloads or compiles.
            // Kill it and confirm it exited, or a leftover process keeps the container's
            // resources and the npm lock.
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
            reader.join(1500);
            return new Result(false, "Install timed out (" + (timeoutMs / 60000) + " minutes)\n" + tail(buf));
        }
        reader.join(3000);
        int code = p.exitValue();
        return new Result(code == 0, tail(buf));
    }

    /** Returns the tail only: pnpm's full output can be long and the page shows the end. */
    private static String tail(ByteArrayOutputStream buf) {
        String s = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        return s.length() > 4000 ? s.substring(s.length() - 4000) : s;
    }
}
