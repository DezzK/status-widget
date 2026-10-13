/*
 * Copyright © 2026 Dezz (https://github.com/DezzK)
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

package dezz.status.widget.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import android.util.Log;
import dezz.status.widget.VersionGetter;

/**
 * The one owner of over-the-air updates in an app process: reads this build's channel ({@link UpdateChannel}),
 * remembers what it offers and what the person decided ({@link UpdateStore}), downloads and verifies the
 * package ({@link PackageFile}) and hands it to the system's installer ({@link SystemInstaller}). The settings
 * section and the card over other apps render its {@link UpdateState} and call its actions; neither asks the
 * network or the installer itself.
 *
 * <p><b>Threading.</b> State and listeners live on the main thread; every action is called there. The
 * network, the files and the installer session run on {@code update-io}, an executor whose only job is to
 * block, and their results are posted back. A process creates this lazily — from the settings, the widget's
 * service, or the background {@link UpdateCheckJob} in a cold process — and it configures itself from the
 * build's resources.
 */
public final class Updater {

    static final String TAG = "Updater";

    /** Hears every change of the state, on the main thread. */
    public interface Listener {
        void onUpdateState(UpdateState state);
    }

    private static volatile Updater instance;

    /** The process's updater. Keyed by the application, so a fresh one (a test's) gets a fresh updater. */
    public static Updater get(Context ctx) {
        Context app = ctx.getApplicationContext();
        Updater u = instance;
        if (u == null || u.app != app) {
            synchronized (Updater.class) {
                u = instance;
                if (u == null || u.app != app) {
                    u = new Updater(app);
                    instance = u;
                }
            }
        }
        return u;
    }

    private final Context app;
    private final UpdateStore store;
    @Nullable private final UpdateChannel channel;
    @Nullable private final ReleaseVersion installed;
    /** Downloaded packages, device-protected like the store. */
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update-io");
        t.setDaemon(true);
        return t;
    });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Raised to stop the download under way. */
    private final AtomicBoolean cancel = new AtomicBoolean();

    // Main thread only.
    @Nullable private UpdateOffer offer;
    private UpdateState.Phase phase = UpdateState.Phase.IDLE;
    private int progress = -1;
    private UpdateState.Problem problem = UpdateState.Problem.NONE;
    @Nullable private String problemDetail;
    /** The installer's confirmation for the session in flight, while it waits for the person. */
    @Nullable private Intent confirmation;
    private UpdateState state;

    Updater(Context app) {
        this.app = app;
        this.store = new UpdateStore(app);
        this.channel = UpdateChannel.of(app);
        this.installed = ReleaseVersion.parse(VersionGetter.getAppVersionName(app));
        this.dir = new File(app.createDeviceProtectedStorageContext().getFilesDir(), "updates");
        UpdateOffer stored = store.offer();
        // The release offered last time is installed by now (the process this replaces did it) or older.
        if (stored != null && (installed == null || !stored.version.isNewerThan(installed))) {
            store.setOffer(null);
            stored = null;
        }
        offer = stored;
        state = snapshot();
        final UpdateOffer keep = offer;
        io.execute(() -> deleteDownloadsExcept(keep));
    }

    // ── Reading the state ───────────────────────────────────────────────────────

    public UpdateState state() {
        return state;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ── Checking ────────────────────────────────────────────────────────────────

    /** Read the channel now, whatever the schedule says — the person asked. */
    public void checkNow() {
        check(null);
    }

    /** Read the channel if checking by itself is on and the schedule says it is time ({@link UpdatePolicy}). */
    public void checkIfDue() {
        checkIfDue(null);
    }

    /** …and run {@code done} on the main thread once there is nothing more to wait for. */
    public void checkIfDue(@Nullable Runnable done) {
        if (!store.autoCheck()
                || !UpdatePolicy.checkDue(System.currentTimeMillis(), store.lastCheckOk(), store.lastCheckFail())) {
            if (done != null) done.run();
            return;
        }
        check(done);
    }

    private void check(@Nullable Runnable done) {
        if (channel == null || installed == null || phase != UpdateState.Phase.IDLE) {
            if (done != null) done.run();
            return;
        }
        phase = UpdateState.Phase.CHECKING;
        problem = UpdateState.Problem.NONE;
        problemDetail = null;
        publish();
        final UpdateChannel ch = channel;
        final ReleaseVersion current = installed;
        // The notes in the language the app runs in now; a release without them in it shows the default ones.
        final String language = app.getResources().getConfiguration().getLocales().get(0).getLanguage();
        io.execute(() -> {
            UpdateOffer found = null;
            IOException failure = null;
            try {
                found = ch.newestAbove(current, language);
            } catch (IOException e) {
                failure = e;
            }
            final UpdateOffer f = found;
            final IOException err = failure;
            main.post(() -> {
                onChecked(f, err);
                if (done != null) done.run();
            });
        });
    }

    private void onChecked(@Nullable UpdateOffer found, @Nullable IOException failure) {
        phase = UpdateState.Phase.IDLE;
        long now = System.currentTimeMillis();
        if (failure != null) {
            store.setCheckFailed(now);
            problem = UpdateState.Problem.CHECK_FAILED;
            problemDetail = failure.getMessage();
            Log.w(TAG, "Channel check failed: " + failure);
        } else {
            store.setCheckOk(now);
            if (found == null) {
                Log.i(TAG, "Channel: nothing above " + installed);
            } else if (offer == null || !offer.version.equals(found.version)) {
                Log.i(TAG, "Channel offers " + found.version + " (installed " + installed + ")");
            }
            offer = found;
            store.setOffer(found);
            final UpdateOffer keep = found;
            io.execute(() -> deleteDownloadsExcept(keep));
        }
        publish();
    }

    // ── Installing ──────────────────────────────────────────────────────────────

    /**
     * Download the offered release (unless it is on disk already, verified), check it, and hand it to the
     * system's installer, which asks the person. While the installer's confirmation waits, this opens it again.
     */
    public void install() {
        if (offer == null) return;
        if (phase == UpdateState.Phase.INSTALLING && confirmation != null) {
            confirmInstall();
            return;
        }
        // INSTALLING with no confirmation is an installer that never answered: a fresh attempt replaces its
        // session (SystemInstaller.commit abandons it), and the package on disk is reused.
        if (phase == UpdateState.Phase.CHECKING || phase == UpdateState.Phase.DOWNLOADING) return;
        final UpdateOffer target = offer;
        final UpdateChannel ch = channel;
        if (ch == null) return;
        cancel.set(false);
        phase = UpdateState.Phase.DOWNLOADING;
        progress = 0;
        problem = UpdateState.Problem.NONE;
        problemDetail = null;
        publish();
        io.execute(() -> {
            boolean handedOver = false;
            try {
                File apk = obtain(ch, target);
                String wrong = PackageFile.packageProblem(app, apk, target);
                if (wrong != null) throw new Failure(UpdateState.Problem.WRONG_PACKAGE, wrong);
                main.post(() -> {
                    phase = UpdateState.Phase.INSTALLING;
                    publish();
                });
                handedOver = true;
                SystemInstaller.commit(app, apk);
                Log.i(TAG, "Committed " + target.version + " to the installer");
            } catch (PackageFile.Canceled e) {
                main.post(this::onCanceled);
            } catch (Failure f) {
                main.post(() -> fail(f.problem, f.getMessage()));
            } catch (IOException | RuntimeException e) {
                UpdateState.Problem p = handedOver ? UpdateState.Problem.INSTALL_FAILED
                        : UpdateState.Problem.DOWNLOAD_FAILED;
                main.post(() -> fail(p, e.getMessage()));
            }
        });
    }

    /** Stop the download under way. */
    public void cancel() {
        if (phase == UpdateState.Phase.DOWNLOADING) cancel.set(true);
    }

    /** Open the installer's waiting confirmation again — it was dismissed, or the system kept it from showing. */
    public void confirmInstall() {
        Intent c = confirmation;
        if (c == null) return;
        try {
            app.startActivity(new Intent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "The installer's confirmation would not open", e);
        }
    }

    /** The installer answered about the session committed here ({@link SystemInstaller.StatusReceiver}). */
    void onInstallerStatus(int status, @Nullable String message, @Nullable Intent confirm) {
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmation = confirm;
                phase = UpdateState.Phase.INSTALLING;
                // Opened before the state goes out: the card is still up at this moment, and from Android 10
                // on a window over other apps is what lets a background service open an activity.
                confirmInstall();
                publish();
            }
            case PackageInstaller.STATUS_SUCCESS -> {
                // The system replaces this process next; nothing after it runs for long.
                Log.i(TAG, "Installed " + (offer != null ? offer.version : "?"));
                confirmation = null;
                phase = UpdateState.Phase.IDLE;
                publish();
            }
            case PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The person declined in the installer's own dialog: that is «not now».
                Log.i(TAG, "Installation declined in the installer");
                confirmation = null;
                phase = UpdateState.Phase.IDLE;
                remindLater();
            }
            default -> fail(UpdateState.Problem.INSTALL_FAILED, message);
        }
    }

    /** The package for {@code target}, verified: the one on disk if it still matches, else a fresh download. */
    private File obtain(UpdateChannel ch, UpdateOffer target) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File apk = new File(dir, target.version + ".apk");
        if (apk.isFile() && PackageFile.matches(target, apk.length(), PackageFile.sha256Of(apk))) return apk;
        File part = new File(dir, target.version + ".part");
        HttpURLConnection conn = Http.open(ch.downloadUrl(target));
        String sha;
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                sha = PackageFile.copy(in, part, target.apkSize, p -> main.post(() -> onProgress(p)), cancel);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            conn.disconnect();
        }
        if (!PackageFile.matches(target, part.length(), sha)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new Failure(UpdateState.Problem.CORRUPT, part.length() + " bytes, sha256 " + sha);
        }
        if (!part.renameTo(apk)) throw new IOException("cannot keep the download");
        return apk;
    }

    private void onProgress(int percent) {
        if (phase != UpdateState.Phase.DOWNLOADING) return;
        progress = percent;
        publish();
    }

    private void onCanceled() {
        phase = UpdateState.Phase.IDLE;
        progress = -1;
        publish();
    }

    private void fail(UpdateState.Problem p, @Nullable String detail) {
        Log.w(TAG, "Update " + p + ": " + detail);
        phase = UpdateState.Phase.IDLE;
        confirmation = null;
        progress = -1;
        problem = p;
        problemDetail = detail;
        publish();
    }

    // ── The person's decisions ──────────────────────────────────────────────────

    /** Never offer the offered release again (a newer one will be). */
    public void skip() {
        if (offer == null) return;
        cancel();
        store.setSkipped(offer.version.toString());
        problem = UpdateState.Problem.NONE;
        publish();
    }

    /** Take back a skip, so the skipped release is offered again. */
    public void unskip() {
        store.setSkipped(null);
        publish();
    }

    /** Put the offered release to the person again in {@link UpdatePolicy#REMIND_LATER_MS}. */
    public void remindLater() {
        if (offer == null) return;
        cancel();
        store.setSnoozed(offer.version.toString(), System.currentTimeMillis() + UpdatePolicy.REMIND_LATER_MS);
        problem = UpdateState.Problem.NONE;
        publish();
    }

    public void setAutoCheck(boolean on) {
        store.setAutoCheck(on);
        publish();
        if (on) checkIfDue();
    }

    // ── Internals ───────────────────────────────────────────────────────────────

    private UpdateState snapshot() {
        return new UpdateState(channel != null && installed != null, installed, offer, phase, progress, problem,
                problemDetail, confirmation != null, store.lastCheckOk(), store.autoCheck(), store.skipped(),
                store.snoozed(), store.snoozedUntil());
    }

    private void publish() {
        state = snapshot();
        for (Listener l : listeners) l.onUpdateState(state);
    }

    /** Packages of releases no longer offered. update-io. */
    private void deleteDownloadsExcept(@Nullable UpdateOffer keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        String kept = keep != null ? keep.version + ".apk" : null;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (!f.getName().equals(kept)) f.delete();
        }
    }

    /** A failure with a problem of its own (the generic ones are told by where they happened). */
    private static final class Failure extends IOException {
        final UpdateState.Problem problem;

        Failure(UpdateState.Problem problem, String detail) {
            super(detail);
            this.problem = problem;
        }
    }
}
