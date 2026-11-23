package com.dshmobile.app;

import android.content.Context;

import java.io.File;
import java.util.Arrays;

/**
 * node-pty native module (pty.node) check and repair.
 *
 * When npm installs dsh, node-pty has to be compiled on the spot by node-gyp. A failed
 * compile (blocked header download, npm running as root and dropping privileges, ...) is
 * treated as an optional dependency, skipped silently and the install still "succeeds" —
 * but dsh web then dies on start because its subprocess plugin cannot load pty.node.
 * Both the install flow and the foreground service call this class as a safety net.
 */
public final class NodePtyFixer {

    private static final String PTY_DIR =
            "opt/node/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty";
    private static final String NODE_GYP =
            "/opt/node/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js";

    private NodePtyFixer() {
    }

    /** True when dsh is installed but pty.node is missing (none of the three locations node-pty checks has it). */
    public static boolean needsFix(File rootfs) {
        File dir = new File(rootfs, PTY_DIR);
        if (!dir.isDirectory()) {
            return false;
        }
        return !new File(dir, "build/Release/pty.node").isFile()
                && !new File(dir, "build/Debug/pty.node").isFile()
                && !new File(dir, "prebuilds/linux-arm64/pty.node").isFile();
    }

    /**
     * Rebuilds pty.node with node-gyp inside the container.
     * It first tries the local headers (--nodedir=/opt/node, fully offline) and falls back to
     * downloading them from the Node mirror (--dist-url, the same source as Node itself, which
     * the install already proved reachable). The toolchain (python3/make/g++) is installed by
     * the install flow beforehand.
     *
     * @param logFile file the node-gyp output is appended to
     * @return true when the repair worked (pty.node exists)
     */
    public static boolean fix(Context ctx, File logFile) {
        File rootfs = ProotRunner.rootfsDir(ctx);
        attempt(ctx, "--nodedir=/opt/node", logFile);
        if (!needsFix(rootfs)) {
            return true;
        }
        attempt(ctx, "--dist-url=" + Prefs.of(ctx).getNodeMirror(), logFile);
        return !needsFix(rootfs);
    }

    private static void attempt(Context ctx, String gypArgs, File logFile) {
        try {
            Process p = ProotRunner.exec(ctx, Arrays.asList("/bin/bash", "-c",
                    "cd /" + PTY_DIR + " && exec /opt/node/bin/node "
                            + NODE_GYP + " rebuild " + gypArgs), logFile);
            p.waitFor();
        } catch (Exception ignored) {
            // The caller re-checks with needsFix when this fails.
        }
    }
}
