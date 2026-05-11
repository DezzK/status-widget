/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.app.Application;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;

/**
 * Installs a process-wide uncaught-exception handler that dumps the stacktrace to the cache
 * directory before letting the default handler (which kills the process) take over. On the next
 * launch {@code MainActivity} surfaces the file so users can copy or share the report.
 */
public class StatusWidgetApplication extends Application {
    /** Filename inside {@code getCacheDir()} holding the last crash report. */
    public static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashHandler();
    }

    private void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashLog(thread, throwable);
            } catch (Throwable ignored) {
                // Never let the crash handler itself crash — that would kill the process without
                // delegating to the default handler.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private void writeCrashLog(Thread thread, Throwable throwable) throws Exception {
        File file = new File(getCacheDir(), CRASH_FILE);
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("Status Widget crash report");
            out.println("Time: " + new Date());
            out.println("Thread: " + thread.getName());
            out.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
            out.println("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            out.println("App version: " + VersionGetter.getAppVersionName(this));
            out.println();
            throwable.printStackTrace(out);
        }
    }
}
