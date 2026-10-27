package com.acadbot.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * Drives a (hidden) WebView through the same flow as the desktop bot:
 * log in -> watch the order list -> open order -> skip bids -> click "Get Order".
 * Everything runs on the main thread; one JS snapshot per tick keeps it light.
 */
object BotEngine {
    private const val BASE = "https://writers.academia-research.com/index.php"
    private const val LOGIN_URL = "$BASE?r=account%2Flogin"
    private const val ORDERS_URL = "$BASE?r=order%2Findex"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val VIEW_W = 1280
    private const val VIEW_H = 900

    private const val SNAPSHOT_JS = """(function(){var q=function(s){return document.querySelector(s)};var l=document.getElementsByClassName('b-table__order-link'),h=[];for(var i=0;i<l.length;i++)h.push(l[i].href);return JSON.stringify({u:location.href,rs:document.readyState,l:h,login:!!q('input#loginform-login'),bid:!!q('#btn-request'),w0:!!q('input#w0'),lbl:!!q('.b-upload__browse-label'),gc:!!window.__gc});})()"""

    // One call per tick while waiting for "Get Order": tick "read", press the action button once,
    // and click "Get Order" the instant it is visible and enabled.
    private const val WAIT_JS = """(function(){var q=function(s){return document.querySelector(s)};var vis=function(e){return !!e&&(e.offsetWidth>0||e.offsetHeight>0||e.getClientRects().length>0)};var r=q("input[name='read']");if(r&&!r.checked)r.click();var a=q("a[type='button']");if(a&&!window.__abDone){window.__abDone=1;a.click();}var g=q('a.b-btn.b-btn_red.b-get-order__btn');var c=0;if(g&&vis(g)&&!g.disabled){window.__gc=1;g.click();c=1}return JSON.stringify({u:location.href,rs:document.readyState,login:!!q('input#loginform-login'),bid:!!q('#btn-request'),clicked:c});})()"""

    interface Listener {
        fun onLog(line: String)
        fun onStats(running: Boolean, status: String, orders: Int, lastSeen: String)
    }

    @Volatile var listener: Listener? = null
    var onStopped: (() -> Unit)? = null
    var onHeartbeat: (() -> Unit)? = null

    private enum class Phase { SCAN, OPENING, ORDER, GET_WAIT, AFTER_CLICK }

    private val main = Handler(Looper.getMainLooper())
    private val logBuffer = LinkedList<String>()
    private val seen = HashSet<String>()
    private val seenLogged = HashSet<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var app: Context
    var webView: WebView? = null
        private set
    var running = false
        private set
    var orders = 0
        private set
    var status = "Offline"
        private set
    var lastSeen = "--:--:--"
        private set

    private var email = ""
    private var password = ""
    private var blockImages = true
    private var verbose = true
    private var refreshMin = 10
    private var sound: Sound? = null

    private var phase = Phase.SCAN
    private var phaseSince = 0L
    private var curId: String? = null
    private var t0 = 0L
    private var startedAt = 0L
    private var nextRefreshAt = 0L
    private var nextHeartbeat = 0L
    private var offPageSince = 0L
    private var loginAttempts = 0
    private var loginSubmittedAt = 0L
    private var nextDelay = Timing.IDLE_POLL_MS
    private var gen = 0

    fun uptimeSec(): Long = if (running) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0L
    fun logSnapshot(): List<String> = ArrayList(logBuffer)

    // ------------------------------------------------------------- lifecycle
    fun start(ctx: Context, email: String, password: String,
              blockImages: Boolean, verbose: Boolean, refreshMin: Int) {
        if (running) return
        app = ctx.applicationContext
        this.email = email.trim()
        this.password = password
        this.blockImages = blockImages
        this.verbose = verbose
        this.refreshMin = refreshMin.coerceIn(1, 60)

        val now = SystemClock.elapsedRealtime()
        running = true
        orders = 0
        lastSeen = "--:--:--"
        seen.clear(); seenLogged.clear()
        startedAt = now; offPageSince = 0L
        nextRefreshAt = now + this.refreshMin * 60_000L
        nextHeartbeat = 0L
        loginAttempts = 0; loginSubmittedAt = 0L
        phase = Phase.SCAN; phaseSince = now; curId = null
        sound = Sound(app)

        log("🚀 Starting bot...")
        publish("Starting…")
        createWebView()
        webView?.loadUrl(LOGIN_URL)
        kick(500)
    }

    fun stop() {
        val wasRunning = running
        running = false
        gen++
        sound?.stop(); sound = null
        destroyWebView()
        status = "Offline"
        if (wasRunning) log("⏹ Bot stopped")
        publish()
        onStopped?.invoke()
    }

    private fun fail(msg: String) {
        log("❌ ERROR: $msg")
        stop()
    }

    // ------------------------------------------------------------- webview
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        destroyWebView()
        val wv = WebView(app)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = blockImages
            userAgentString = UA
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        wv.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                main.post { if (running) { log("⚠️ Browser crashed - restarting it"); rebuild() } }
                return true
            }
        }
        wv.layout(0, 0, VIEW_W, VIEW_H)
        wv.onResume()
        wv.resumeTimers()
        webView = wv
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        try {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        } catch (e: Exception) { }
    }

    private fun rebuild() {
        createWebView()
        webView?.loadUrl(ORDERS_URL)
        phase = Phase.SCAN
        phaseSince = SystemClock.elapsedRealtime()
        nextRefreshAt = phaseSince + refreshMin * 60_000L
        kick(300)
    }

    /** Show the hidden browser inside the app (e.g. to see what the bot sees). */
    fun attachTo(container: FrameLayout) {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun detach() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.layout(0, 0, VIEW_W, VIEW_H)
    }

    // ------------------------------------------------------------- loop
    private fun kick(delay: Long) {
        gen++
        val g = gen
        main.postDelayed({ tick(g) }, delay)
    }

    private fun tick(g: Int) {
        if (!running || g != gen) return
        housekeeping(SystemClock.elapsedRealtime())
        val wv = webView ?: return
        val js = if (phase == Phase.GET_WAIT) WAIT_JS else SNAPSHOT_JS
        wv.evaluateJavascript(js) { raw ->
            if (!running || g != gen) return@evaluateJavascript
            val now = SystemClock.elapsedRealtime()
            nextDelay = Timing.IDLE_POLL_MS
            try {
                parse(raw)?.let { handle(it, now) }
            } catch (e: Exception) {
                dbg("⚠️ Loop error: ${e.message}")
                nextDelay = Timing.ERROR_BACKOFF_MS
            }
            if (running && g == gen) main.postDelayed({ tick(g) }, nextDelay)
        }
    }

    private fun parse(raw: String?): JSONObject? {
        if (raw == null || raw == "null") return null
        val v = JSONTokener(raw).nextValue()
        return (v as? String)?.let { JSONObject(it) }
    }

    private fun housekeeping(now: Long) {
        if (now >= nextHeartbeat) {
            nextHeartbeat = now + Timing.HEARTBEAT_MS
            onHeartbeat?.invoke()
        }
        // Exactly one refresh per interval; if an order is in progress it waits until that is done.
        if (phase == Phase.SCAN && now >= nextRefreshAt) autoRefresh(now)
    }

    private fun autoRefresh(now: Long) {
        val wv = webView ?: return
        val u = dec(wv.url ?: "")
        if ("order/index" in u) wv.reload() else wv.loadUrl(ORDERS_URL)
        seen.clear(); seenLogged.clear()
        nextRefreshAt = now + refreshMin * 60_000L
        dbg("🔄 Page refreshed")
    }

    // ------------------------------------------------------------- decisions
    private fun handle(s: JSONObject, now: Long) {
        val url = dec(s.optString("u"))
        val onOrders = "order/index" in url

        if (onOrders) {
            loginAttempts = 0
            offPageSince = 0L
            if (status != "Online") publish("Online")
        }

        if (s.optBoolean("login")) {
            phase = Phase.SCAN
            doLogin(now)
            nextDelay = 300
            return
        }

        when (phase) {
            Phase.SCAN -> scan(s, url, onOrders, now)
            Phase.OPENING -> opening(s, url, now)
            Phase.ORDER -> order(s, now)
            Phase.GET_WAIT -> getWait(s, url, now)
            Phase.AFTER_CLICK -> afterClick(s, url, now)
        }
    }

    private fun doLogin(now: Long) {
        if (loginAttempts > 0 && now - loginSubmittedAt < Timing.LOGIN_WAIT_MS) return
        if (loginAttempts >= 2) {
            fail("Login failed - check your email and password")
            return
        }
        loginAttempts++
        loginSubmittedAt = now
        log("📱 Logging in...")
        val js = "(function(e,p){var a=document.querySelector('input#loginform-login')," +
            "b=document.querySelector('input#loginform-password');if(!a||!b)return 0;" +
            "a.value=e;b.value=p;var f=document.querySelector('button');" +
            "if(f){f.click();return 1}return 0})(" +
            JSONObject.quote(email) + "," + JSONObject.quote(password) + ")"
        webView?.evaluateJavascript(js, null)
    }

    private fun scan(s: JSONObject, url: String, onOrders: Boolean, now: Long) {
        val links = s.optJSONArray("l")
        val n = links?.length() ?: 0
        if (n == 0) {
            if (!onOrders && "account/login" !in url) {
                if (offPageSince == 0L) offPageSince = now
                else if (now - offPageSince > Timing.OFFPAGE_RECOVER_MS) {
                    offPageSince = 0L
                    webView?.loadUrl(ORDERS_URL)
                }
            }
            return
        }
        var target = -1
        var targetId: String? = null
        for (i in 0 until n) {
            val href = links!!.getString(i)
            val id = idOf(href)
            if (id != null && id in seen) {
                if (seenLogged.add(id)) log("⏭️ SKIPPED - Order $id (PREVIOUSLY SEEN)")
                continue
            }
            target = i; targetId = id
            break
        }
        if (target < 0) return

        sound?.play(R.raw.default_notification)
        curId = targetId
        t0 = now
        lastSeen = timeFmt.format(Date())
        publish()
        phase = Phase.OPENING
        phaseSince = now
        webView?.loadUrl(links!!.getString(target))
        nextDelay = 100
    }

    private fun opening(s: JSONObject, url: String, now: Long) {
        val stillList = "order/index" in url
        val loading = s.optString("rs") == "loading"
        if (stillList || loading) {
            if (now - phaseSince > Timing.PAGE_LOAD_TIMEOUT_MS) {
                log("❌ Unable to open Order $curId")
                goIndex(now)
            }
            nextDelay = 100
            return
        }
        phase = Phase.ORDER
        order(s, now)
    }

    private fun order(s: JSONObject, now: Long) {
        if (skipIfBid(s, now)) return
        // The order page is open: from here WAIT_JS marks "read" and clicks "Get Order" as soon as it can.
        phase = Phase.GET_WAIT
        phaseSince = now
        nextDelay = 0
    }

    private fun skipIfBid(s: JSONObject, now: Long): Boolean {
        if (!s.optBoolean("bid")) return false
        curId?.let { seen.add(it) }
        log("⏭️ SKIPPED - Order $curId (BID REQUEST) - Time: ${secs(now)}s")
        goIndex(now)
        return true
    }

    private fun getWait(s: JSONObject, url: String, now: Long) {
        if (skipIfBid(s, now)) return
        if (s.optInt("clicked") == 1) {
            phase = Phase.AFTER_CLICK
            phaseSince = now
            nextDelay = 100
            dbg("🖱 Get Order clicked - checking result")
            return
        }
        if ("order/index" in url) {
            log("❌ Order $curId is no longer available - Time: ${secs(now)}s")
            goIndex(now)
            return
        }
        if (now - phaseSince >= Timing.GET_ORDER_TIMEOUT_MS) {
            log("❌ Unable to process Order $curId - Time: ${secs(now)}s")
            goIndex(now)
            return
        }
        nextDelay = Timing.GET_ORDER_POLL_MS
    }

    /** Success criteria (same as the desktop bot): draft upload, paper upload label, or order view page. */
    private fun takenReason(s: JSONObject, url: String): String? = when {
        s.optBoolean("w0") -> "draft upload found"
        s.optBoolean("lbl") -> "paper upload found"
        "order/view" in url && "id=" in url -> "on order view page"
        else -> null
    }

    private fun afterClick(s: JSONObject, url: String, now: Long) {
        // Judge only once the page has moved on from the click (new document, no longer loading),
        // otherwise the pre-click order page would always look like a success.
        val navigated = !s.optBoolean("gc")
        val ready = s.optString("rs") != "loading"
        val expired = now - phaseSince >= Timing.AFTER_CLICK_WINDOW_MS
        if (!(navigated && ready) && !expired) {
            nextDelay = 100
            return
        }
        val why = takenReason(s, url)
        if (why != null) {
            orders++
            sound?.play(R.raw.whistling)
            log("✅ Order $curId taken successfully - Time: ${secs(now)}s")
            dbg("   ($why)")
        } else {
            log("❌ Order $curId taken by another user - Time: ${secs(now)}s")
        }
        publish()
        goIndex(now)
    }

    private fun goIndex(now: Long) {
        webView?.loadUrl(ORDERS_URL)
        // Loading the list now counts as this interval's refresh if one was due.
        if (now >= nextRefreshAt) {
            seen.clear(); seenLogged.clear()
            nextRefreshAt = now + refreshMin * 60_000L
        }
        phase = Phase.SCAN
        phaseSince = now
        curId = null
        nextDelay = 200
    }

    // ------------------------------------------------------------- helpers
    private fun secs(now: Long) = "%.2f".format((now - t0) / 1000.0)

    private fun dec(u: String): String =
        try { URLDecoder.decode(u, "UTF-8") } catch (e: Exception) { u }

    private fun idOf(href: String): String? {
        val d = dec(href)
        return if ("id=" in d) d.substringAfter("id=").substringBefore("&") else null
    }

    private fun publish(newStatus: String? = null) {
        if (newStatus != null) status = newStatus
        listener?.onStats(running, status, orders, lastSeen)
    }

    fun publishNow() = publish()

    private fun dbg(msg: String) { if (verbose) log(msg) }

    private fun log(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        logBuffer.add(line)
        while (logBuffer.size > 400) logBuffer.removeFirst()
        listener?.onLog(line)
    }
}