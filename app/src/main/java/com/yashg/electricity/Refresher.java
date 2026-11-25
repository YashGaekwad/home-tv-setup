package com.yashg.electricity;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One off-screen WebView for the whole app, so the dashboard and the background job share a refresh and
 * the site's sign-in cookie. Results go to the cache, the home card's provider, and any listeners.
 */
final class Refresher implements Scraper.Listener {
    static final long PERIOD_MS = 2 * 60 * 60 * 1000L;
    private static final int JOB_ID = 1;
    private static Refresher instance;

    private final Context app;
    private WebView web;
    private Scraper scraper;
    private final List<Scraper.Listener> listeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String lastStatus = "";
    /** Between refreshes the page and its web engine (about 150 MB) are let go; the sign-in cookie stays. */
    private final Runnable release = this::release;

    private Refresher(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized Refresher get(Context c) {
        if (instance == null) instance = new Refresher(c);
        return instance;
    }

    /** Every two hours with a network, also after a reboot. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    WebView webView() {
        if (web == null) {
            web = new WebView(app);
            // Not attached to a window: give it a desktop-sized layout so the site's chart renders its points.
            web.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            web.layout(0, 0, 1280, 720);
            scraper = new Scraper(web, this);
        }
        return web;
    }

    /** The page if it exists, without creating one. */
    WebView peekWebView() {
        return web;
    }

    boolean busy() {
        return scraper != null && scraper.busy();
    }

    private void release() {
        if (web == null || busy() || web.getParent() != null) return;   // in use, or on screen (Menu)
        CookieManager.getInstance().flush();
        web.stopLoading();
        web.loadUrl("about:blank");
        web.destroy();
        web = null;
        scraper = null;
    }

    void releaseSoon() {
        main.removeCallbacks(release);
        main.postDelayed(release, 3000);
    }

    String lastStatus() {
        return lastStatus;
    }

    void addListener(Scraper.Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Scraper.Listener l) {
        listeners.remove(l);
    }

    /** Returns false when nothing could be started (no login saved). */
    boolean refresh() {
        main.removeCallbacks(release);
        webView();
        if (scraper.busy()) return true;
        String[] login = Creds.load(app);
        if (login == null) {
            onError("No MGVCL login saved on this TV yet. Run tools\\set-login.ps1 on the PC once.");
            return false;
        }
        scraper.start(login[0], login[1], Store.dashStale(app));
        return true;
    }

    @Override
    public void onStatus(String text) {
        lastStatus = text;
        for (Scraper.Listener l : new ArrayList<>(listeners)) l.onStatus(text);
    }

    @Override
    public void onResult(JSONObject dash, JSONObject usage) {
        try {
            Store.save(app, dash, usage);
            app.getContentResolver().notifyChange(UtilitiesProvider.ROWS, null);
            lastStatus = "Up to date";
        } catch (Exception e) {
            lastStatus = "Could not save: " + e.getMessage();
        }
        for (Scraper.Listener l : new ArrayList<>(listeners)) l.onResult(dash, usage);
        releaseSoon();
    }

    @Override
    public void onError(String text) {
        lastStatus = text;
        for (Scraper.Listener l : new ArrayList<>(listeners)) l.onError(text);
        releaseSoon();
    }
}
