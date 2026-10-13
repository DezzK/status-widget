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

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import android.util.Log;

/**
 * Reads the update channel in the background, every {@link UpdatePolicy#CHECK_INTERVAL_MS} while a network
 * is up, whether or not anything of the app runs — the widget works as a service the person rarely opens the
 * settings of. A persisted periodic job, so it outlives reboots; what a found release leads to (the card over
 * other apps, the settings section) is the {@link Updater}'s listeners'.
 */
public final class UpdateCheckJob extends JobService {

    /** The app's only job. */
    static final int JOB_ID = 0xD20;

    /** Arm the job unless it is armed already (called at every start of whatever runs the app). */
    public static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, UpdateCheckJob.class))
                .setPeriodic(UpdatePolicy.CHECK_INTERVAL_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)   // needs RECEIVE_BOOT_COMPLETED, which the manifest declares
                .build();
        int rc = js.schedule(job);
        Log.i(Updater.TAG, "Background check armed, rc=" + rc);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Updater.get(this).checkIfDue(() -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;   // the period brings the next attempt
    }
}
