package com.globalannotate.official.helper;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Persistence for module switches, owned by system_server.
 *
 * <p>The helper app cannot be relied on to restore settings after a reboot:
 * HyperOS withholds BOOT_COMPLETED from apps without autostart permission, and
 * the module is attached to system_server, which starts long before any app.
 * So the module stores the values itself, in {@code /data/system} — the
 * directory system_server always owns and can read/write, unlike app data
 * directories that SELinux may refuse from this domain. The settings screen
 * keeps its own mirror for display; an app process cannot read this file.</p>
 */
final class ModulePrefs {

    private static final String TAG = "GlobalAnnotate";
    private static final File FILE = new File("/data/system/globalannotate_module.conf");
    private static final String KEY_PEN_GESTURES = "pen_gestures_enabled";

    private static volatile boolean loaded;
    private static volatile boolean penGesturesEnabled = true;

    private ModulePrefs() {
    }

    /** Called once from onSystemServerStarting, before the hooks are installed. */
    static synchronized void init() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!FILE.exists()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(FILE));
            String line;
            while ((line = reader.readLine()) != null) {
                final int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                final String key = line.substring(0, eq).trim();
                final String value = line.substring(eq + 1).trim();
                if (KEY_PEN_GESTURES.equals(key)) {
                    penGesturesEnabled = Boolean.parseBoolean(value);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "module prefs load failed: " + error);
        } finally {
            closeQuietly(reader);
        }
    }

    static boolean penGesturesEnabled() {
        return penGesturesEnabled;
    }

    static synchronized void setPenGesturesEnabled(boolean enabled) {
        penGesturesEnabled = enabled;
        save();
    }

    /**
     * Writes through a temp file and a rename so a crash cannot leave a
     * truncated setting behind; falls back to an in-place write when the
     * rename is refused.
     */
    private static void save() {
        final StringBuilder content = new StringBuilder()
                .append(KEY_PEN_GESTURES).append('=').append(penGesturesEnabled).append('\n');
        try {
            final File tmp = new File(FILE.getPath() + ".tmp");
            writeFile(tmp, content.toString());
            if (tmp.renameTo(FILE)) {
                return;
            }
            writeFile(FILE, content.toString());
            tmp.delete();
        } catch (Throwable error) {
            Log.w(TAG, "module prefs save failed: " + error);
        }
    }

    private static void writeFile(File target, String content) throws Exception {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(target, false));
            writer.write(content);
            writer.flush();
        } finally {
            closeQuietly(writer);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // Nothing useful to do on a close failure.
        }
    }
}
