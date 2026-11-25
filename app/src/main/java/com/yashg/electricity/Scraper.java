package com.yashg.electricity;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Drives the MGVCL portal in a WebView the way a person would: open the dashboard, sign in with the saved
 * login if the site asks, read the dashboard, then open Usage, switch it to Daily and read the chart.
 * The portal's own data calls need its JWT and CSRF tokens, so reading the rendered pages is the robust way.
 */
final class Scraper {
    interface Listener {
        void onStatus(String text);
        void onResult(JSONObject dash, JSONObject usage);
        void onError(String text);
    }

    private static final String BASE = "https://mgvclsmartmeter.mgvclinfra.in/portal/";
    private static final long TIMEOUT_MS = 120_000;

    private enum Step { IDLE, DASH, LOGIN_SENT, USAGE }

    private final WebView web;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Step step = Step.IDLE;
    private int loginTries;
    private JSONObject dash;
    private String user, pass;

    private final Runnable timeout = () -> fail("The MGVCL site took too long to answer");
    private final Runnable loginCheck = this::checkLoginOutcome;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    Scraper(WebView web, Listener listener) {
        this.web = web;
        this.listener = listener;
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // Only the text and the chart are read: skip pictures to load faster.
        s.setBlockNetworkImage(true);
        s.setLoadsImagesAutomatically(false);
        s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/138.0.0.0 Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "Bridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded(url == null ? "" : url);
            }
        });
    }

    boolean busy() {
        return step != Step.IDLE;
    }

    /** withDashboard: also read the dashboard (outstanding, billed up to); otherwise go straight to Usage. */
    void start(String user, String pass, boolean withDashboard) {
        this.user = user;
        this.pass = pass;
        loginTries = 0;
        dash = null;
        step = withDashboard ? Step.DASH : Step.USAGE;
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, TIMEOUT_MS);
        listener.onStatus("Connecting to MGVCL…");
        web.loadUrl(BASE + (withDashboard ? "Dashboard.aspx" : "usages.aspx"));
    }

    void stop() {
        step = Step.IDLE;
        main.removeCallbacks(timeout);
        main.removeCallbacks(loginCheck);
        web.stopLoading();
    }

    private void pageLoaded(String url) {
        if (step == Step.IDLE) return;
        String u = url.toLowerCase();
        if (u.contains("error.aspx")) {
            fail("The MGVCL site showed an error page");
            return;
        }
        web.evaluateJavascript(JS_PROBE, value -> {
            String kind = value == null ? "" : value.replace("\"", "");
            if (kind.equals("login")) {
                if (step != Step.LOGIN_SENT || loginTries < 2) doLogin();
            } else if (u.contains("dashboard.aspx") && step == Step.USAGE && dash == null && loginTries == 0) {
                // A session that lands on the dashboard anyway: go on to Usage.
                web.loadUrl(BASE + "usages.aspx");
            } else if (u.contains("dashboard.aspx") && (step == Step.DASH || step == Step.LOGIN_SENT)) {
                main.removeCallbacks(loginCheck);
                step = Step.DASH;
                listener.onStatus("Reading your account…");
                main.postDelayed(() -> web.evaluateJavascript(JS_DASH, this::onDash), 1200);
            } else if (u.contains("usages.aspx") && step == Step.USAGE) {
                listener.onStatus("Reading daily usage…");
                web.evaluateJavascript(JS_USAGE, null);
            }
        });
    }

    private void doLogin() {
        if (user == null || pass == null) {
            fail("No MGVCL login saved on this TV. Run set-login on the PC once.");
            return;
        }
        if (loginTries >= 2) {
            fail("MGVCL did not accept the saved login");
            return;
        }
        loginTries++;
        step = Step.LOGIN_SENT;
        listener.onStatus("Signing in…");
        String js = "(" + JS_FILL + ")(" + JSONObject.quote(user) + "," + JSONObject.quote(pass) + ")";
        main.postDelayed(() -> web.evaluateJavascript(js, null), 1500);
        main.removeCallbacks(loginCheck);
        main.postDelayed(loginCheck, 25_000);
    }

    /** Still on the login page 25 s after pressing Login: report what the page says. */
    private void checkLoginOutcome() {
        if (step != Step.LOGIN_SENT) return;
        web.evaluateJavascript(JS_LOGIN_STATE, value -> {
            String v = value == null ? "" : value.replace("\"", "");
            if (v.startsWith("otp")) fail("MGVCL asked for an OTP. Sign in once on the site, then try again.");
            else if (v.startsWith("error:")) fail("MGVCL: " + v.substring(6));
            else fail("Sign-in did not finish");
        });
    }

    private void onDash(String json) {
        try {
            dash = new JSONObject(new JSONTokener(json).nextValue().toString());
        } catch (Exception e) {
            dash = new JSONObject();
        }
        step = Step.USAGE;
        web.loadUrl(BASE + "usages.aspx");
    }

    private void fail(String text) {
        boolean wasBusy = step != Step.IDLE;
        stop();
        if (wasBusy) listener.onError(text);
    }

    private final class Bridge {
        @JavascriptInterface
        public void usage(String json) {
            main.post(() -> {
                if (step != Step.USAGE) return;
                JSONObject u;
                try {
                    u = new JSONObject(json);
                } catch (Exception e) {
                    u = new JSONObject();
                }
                JSONObject d = dash == null ? new JSONObject() : dash;
                stop();
                if (u.optJSONArray("days") == null && u.optString("soFar").isEmpty()) {
                    listener.onError("Usage page: " + u.optString("error", "no data"));
                } else {
                    listener.onResult(d, u);
                }
            });
        }
    }

    private static final String JS_PROBE =
            "(function(){var l=document.getElementById('txtLogin'),p=document.getElementById('txtpwd');"
            + "return (l&&p&&l.offsetParent!==null)?'login':'page';})()";

    private static final String JS_FILL =
            "function(u,p){function set(e,v){e.focus();e.value=v;"
            + "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));"
            + "e.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true}));e.blur();}"
            + "set(document.getElementById('txtLogin'),u);set(document.getElementById('txtpwd'),p);"
            + "var r=document.getElementById('rmbrme');if(r&&!r.checked)r.click();"
            + "document.getElementById('btnlogin').click();return 'clicked';}";

    private static final String JS_LOGIN_STATE =
            "(function(){var otp=[].slice.call(document.querySelectorAll('input[aria-label=\"Please Enter the OTP\"]'))"
            + ".some(function(e){return e.offsetParent!==null});if(otp)return 'otp';"
            + "var msgs=[].slice.call(document.querySelectorAll('.toast-message,.errormsg,.alert,.error'))"
            + ".filter(function(e){return e.offsetParent!==null&&e.innerText.trim()}).map(function(e){return e.innerText.trim()});"
            + "return msgs.length?'error:'+msgs[0].slice(0,80):'none';})()";

    private static final String JS_DASH =
            "(function(){var t=document.body.innerText;function m(r){var x=t.match(r);return x?x[1].trim():''}"
            + "return JSON.stringify({outstanding:m(/Outstanding Amount\\s*\\n\\s*([^\\n]+)/),"
            + "billedUpto:m(/Billed Upto:\\s*([^\\n]+)/),power:m(/Power Status\\s*:\\s*([^\\n]+)/),"
            + "meter:m(/Meter Status\\s*:\\s*([^\\n]+)/),"
            + "lastMonth:m(/([\\d.]+)\\s*kWh\\s*\\n\\s*[A-Z]{3} \\d{4}\\s*\\n\\s*LAST MONTH USAGE/),"
            + "lastMonthName:m(/[\\d.]+\\s*kWh\\s*\\n\\s*([A-Z]{3} \\d{4})\\s*\\n\\s*LAST MONTH USAGE/)});})()";

    private static final String JS_USAGE =
            "(function(){var t0=Date.now(),clicked=false;"
            + "function txt(r){var x=document.body.innerText.match(r);return x?x[1]:''}"
            + "function extra(o){o.soFar=txt(/SO FAR THIS MONTH\\s*\\n\\s*([\\d.]+)/);"
            + "o.projected=txt(/PROJECTED USAGE\\s*\\n\\s*([\\d.]+)/);return JSON.stringify(o);}"
            + "function tick(){try{"
            + "if(!window.jQuery||!window.Highcharts){if(Date.now()-t0<30000)return setTimeout(tick,500);"
            + "return Bridge.usage(extra({error:'usage page did not load'}));}"
            + "if(!clicked){var a=document.querySelector('#usageMapMode a[mode=D]');"
            + "if(a){jQuery(a).trigger('click');clicked=true;}return setTimeout(tick,1500);}"
            + "var c=Highcharts.charts.filter(Boolean),ch=c[c.length-1],s=ch&&ch.series[0];"
            + "var pts=s?s.points:[];"
            + "if(pts.length>1&&/,\\s*\\d{4}/.test(String(pts[0].name||pts[0].category))){"
            + "var series=ch.series.filter(function(x){return x.yData&&x.yData.length===pts.length})"
            + ".map(function(x){return{name:x.name,data:x.yData}});"
            + "return Bridge.usage(extra({days:pts.map(function(p){return{d:String(p.name||p.category),v:p.y==null?null:p.y}}),"
            + "series:series}));}"
            + "if(Date.now()-t0<45000)return setTimeout(tick,700);"
            + "Bridge.usage(extra({error:'daily data did not appear'}));"
            + "}catch(e){Bridge.usage(extra({error:String(e)}))}}tick();})()";
}
