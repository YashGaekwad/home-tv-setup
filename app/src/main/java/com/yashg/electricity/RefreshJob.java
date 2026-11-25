package com.yashg.electricity;

import android.app.job.JobParameters;
import android.app.job.JobService;

import org.json.JSONObject;

/** The two-hourly background refresh. */
public class RefreshJob extends JobService implements Scraper.Listener {
    private JobParameters params;

    @Override
    public boolean onStartJob(JobParameters p) {
        params = p;
        Refresher r = Refresher.get(this);
        r.addListener(this);
        if (!r.refresh()) {
            r.removeListener(this);
            return false;
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        Refresher.get(this).removeListener(this);
        return true;
    }

    private void done() {
        Refresher.get(this).removeListener(this);
        if (params != null) jobFinished(params, false);
        params = null;
    }

    @Override
    public void onStatus(String text) {}

    @Override
    public void onResult(JSONObject dash, JSONObject usage) {
        done();
    }

    @Override
    public void onError(String text) {
        done();
    }
}
