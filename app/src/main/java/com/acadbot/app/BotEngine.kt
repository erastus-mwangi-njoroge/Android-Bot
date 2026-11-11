package com.acadbot.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebStorage
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

    private const val SNAPSHOT_JS = """(function(){var q=function(s){return document.querySelector(s)};var vis=function(e){return !!e&&(e.offsetWidth>0||e.offsetHeight>0||e.getClientRects().length>0)};var g=q('a.b-btn.b-btn_red.b-get-order__btn');var l=document.getElementsByClassName('b-table__order-link'),h=[];for(var i=0;i<l.length;i++)h.push(l[i].href);return JSON.stringify({u:location.href,rs:document.readyState,l:h,login:!!q('input#loginform-login'),bid:!!q('#btn-request'),get:!!g&&vis(g)&&!g.disabled&&!/disabled/i.test(g.className||'')&&g.getAttribute('aria-disabled')!=='true',m:window.__bz===1,in:Array.prototype.slice.call(document.querySelectorAll('input')).slice(0,10).map(function(i){return (i.name||i.id||i.type)}).join(','),fr:document.getElementsByTagName('iframe').length,rx:!!q("input[name='read']"),rd:!!(q("input[name='read']")||{}).checked,t:(document.title||'').slice(0,60),a:((q('.alert,.b-alert,.error,.b-error')||{}).innerText||'').replace(/\s+/g,' ').slice(0,100),taken:!!q('input#w0')||!!q('.b-upload__browse-label')});})()"""
    // Makes sure the "I have read the instructions" checkbox is really checked (idempotent: never unticks).
    // Tries a normal click, then its label, then sets the state and fires the events the site listens to.
    private const val ENSURE_READ_JS = """(function(){var r=document.querySelector("input[name='read']");if(!r)return 'none';if(!r.checked)r.click();if(!r.checked){var l=(r.id?document.querySelector("label[for='"+r.id+"']"):null)||r.closest('label')||r.parentElement;if(l)l.click()}if(!r.checked){r.checked=true;r.dispatchEvent(new Event('input',{bubbles:true}));r.dispatchEvent(new Event('change',{bubbles:true}))}return r.checked?'1':'0'})()"""
    // Background check of the order list, independent of the page's own live-update machinery.
    // Keeps the page believing it is on screen (a hidden WebView otherwise pauses the site's live updates).
    private const val VISIBLE_JS = "(function(){try{Object.defineProperty(document,'hidden',{get:function(){return false},configurable:true});Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'},configurable:true});document.hasFocus=function(){return true};var b=function(e){e.stopImmediatePropagation()};document.addEventListener('visibilitychange',b,true);window.addEventListener('visibilitychange',b,true);window.addEventListener('blur',b,true)}catch(e){}})()"
    private const val ACTION_JS = """(function(){window.__bz=1;var b=document.querySelector("a[type='button']");if(b)b.click();return b?1:0})()"""
    private const val CLICK_GET_JS = """(function(){var g=document.querySelector('a.b-btn.b-btn_red.b-get-order__btn');if(g){window.__bz=1;g.click();return 1}return 0})()"""

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
    private val bidIds = HashSet<String>()
    private val handledAt = HashMap<String, Long>() // orders already processed -> when (short cooldown only)
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
    private var readAt = 0L
    private var clickedAt = 0L
    private var readDone = false
    private var clickTries = 0
    private var repliedAt = 0L
    private var preClickUrl = ""
    private var preClickMarkers = false
    private var startedAt = 0L
    private var lastRefresh = 0L
    private var nextHeartbeat = 0L
    private var lastAlive = 0L
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
        seen.clear(); seenLogged.clear(); bidIds.clear(); handledAt.clear()
        startedAt = now; lastRefresh = now; lastAlive = now; offPageSince = 0L
        nextHeartbeat = 0L
        loginAttempts = 0; loginSubmittedAt = 0L
        phase = Phase.SCAN; phaseSince = now; curId = null
        sound = Sound(app)

        log("🚀 Starting bot...")
        publish("Starting…")
        createWebView()
        webView?.loadUrl(LOGIN_URL)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 10_000)
        kick(500)
    }

    fun stop() {
        val wasRunning = running
        running = false
        gen++
        main.removeCallbacks(watchdog)
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
        // Keep the browser process at high priority even when the app is not in front (default lowers it,
        // which freezes the page's scripts and made the bot deaf to new orders).
        if (Build.VERSION.SDK_INT >= 26) wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
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
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(VISIBLE_JS, null)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(VISIBLE_JS, null)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                main.post { if (running) { log("⚠️ Browser crashed - restarting it"); rebuild() } }
                return true
            }
        }
        wv.layout(0, 0, VIEW_W, VIEW_H)
        wv.onResume()
        wv.resumeTimers()
        webView = wv
        main.post { if (webView === wv && wv.parent == null) park() }
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
        lastAlive = phaseSince
        kick(300)
    }

    /** Show the hidden browser inside the app (e.g. to see what the bot sees). */
    fun attachTo(container: FrameLayout) {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Tiny always-on-screen host owned by MainActivity. A WebView that is attached to a window is
     *  treated as visible by Chromium, so the site's own timers/live updates keep running at full speed. */
    @Volatile var parkHost: FrameLayout? = null

    fun detach() = park()

    /** Keep the browser attached (1x1px clipped window onto a full-size page) instead of orphaned. */
    fun park() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        val host = parkHost
        if (host != null) {
            host.addView(wv, FrameLayout.LayoutParams(VIEW_W, VIEW_H))
        } else {
            wv.layout(0, 0, VIEW_W, VIEW_H)
        }
    }

    fun unpark(host: FrameLayout) {
        if (parkHost === host) {
            parkHost = null
            val wv = webView ?: return
            if (wv.parent === host) { host.removeView(wv); wv.layout(0, 0, VIEW_W, VIEW_H) }
        }
    }

    // ------------------------------------------------------------- loop
    // Silent safety net: if a tick's JS callback never came back, start the loop again.
    // The browser is NOT restarted and nothing is logged.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - lastAlive > Timing.WATCHDOG_MS) {
                lastAlive = SystemClock.elapsedRealtime()
                kick(0)
            }
            main.postDelayed(this, 5_000)
        }
    }

    private fun kick(delay: Long) {
        gen++
        val g = gen
        main.postDelayed({ tick(g) }, delay)
    }

    private fun tick(g: Int) {
        if (!running || g != gen) return
        housekeeping(SystemClock.elapsedRealtime())
        val wv = webView ?: return
        wv.evaluateJavascript(SNAPSHOT_JS) { raw ->
            if (!running || g != gen) return@evaluateJavascript
            val now = SystemClock.elapsedRealtime()
            lastAlive = now
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
        // One refresh per interval, measured from the previous refresh. If an order is being
        // processed when it falls due, it is done as soon as the bot is back on the order list.
        if (phase == Phase.SCAN && now - lastRefresh >= refreshMin * 60_000L) autoRefresh(now)
    }

    private fun autoRefresh(now: Long) {
        lastRefresh = now                      // set first: guarantees a single refresh per interval
        seen.removeAll(bidIds); bidIds.clear() // bids are re-checked after a refresh; handled orders stay handled
        seenLogged.clear()
        if (Timing.FULL_RELOGIN_ON_REFRESH) {
            // Same effect as pressing Stop then Start: brand-new browser, session cleared, log in again.
            log("🔄 Refresh: restarting browser and logging in again")
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) { }
            destroyWebView()
            loginAttempts = 0
            loginSubmittedAt = 0L
            offPageSince = 0L
            rebuild()
            return
        }
        val wv = webView ?: return
        val u = dec(wv.url ?: "")
        if ("order/index" in u) wv.reload() else wv.loadUrl(ORDERS_URL)
        dbg("🔄 Page refreshed")
    }

    // ------------------------------------------------------------- decisions
    private fun handle(s: JSONObject, now: Long) {
        val url = dec(s.optString("u"))
        val loginForm = s.optBoolean("login")
        val onOrders = "order/index" in url

        if (onOrders) {
            loginAttempts = 0
            offPageSince = 0L
            if (status != "Online") publish("Online")
        }

        if (loginForm) {
            phase = Phase.SCAN
            doLogin(now)
            nextDelay = 300
            return
        }

        when (phase) {
            Phase.SCAN -> scan(s, url, onOrders, now)
            Phase.OPENING -> opening(s, url, now)
            Phase.ORDER -> order(s, now)
            Phase.GET_WAIT -> getWait(s, now)
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
        // Orders are read from the live page only; the site populates it by itself (no extra requests).
        val domLinks = s.optJSONArray("l")
        val all = ArrayList<String>()
        val inDom = HashSet<String>()
        for (i in 0 until (domLinks?.length() ?: 0)) { val h = domLinks!!.getString(i); all.add(h); inDom.add(h) }
        val n = all.size
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
        var targetHref: String? = null
        var targetId: String? = null
        for (href in all) {
            val id = idOf(href)
            // Like the desktop bot, only bid requests are remembered. A processed order is ignored just
            // for a short cooldown (list lag); if it is posted again later it must be picked up again.
            val doneAt = if (id != null) handledAt[id] else null
            if (id != null && doneAt != null && now - doneAt >= Timing.HANDLED_COOLDOWN_MS) {
                handledAt.remove(id); seenLogged.remove(id)
            }
            if (id != null && (id in seen || handledAt.containsKey(id))) {
                if (seenLogged.add(id)) log("⏭️ SKIPPED - Order $id (" + (if (id in seen) "BID REQUEST" else "JUST PROCESSED") + ")")
                continue
            }
            targetHref = href; targetId = id
            break
        }
        val href = targetHref ?: return

        sound?.play(R.raw.default_notification)
        curId = targetId
        t0 = now
        lastSeen = timeFmt.format(Date())
        publish()
        phase = Phase.OPENING
        phaseSince = now
        val clickJs = "(function(h){var l=document.getElementsByClassName('b-table__order-link');" +
            "for(var i=0;i<l.length;i++){if(l[i].href===h){l[i].click();return 1}}return 0})(" +
            JSONObject.quote(href) + ")"
        webView?.evaluateJavascript(clickJs) { r ->
            if (r != "1" && running && phase == Phase.OPENING) webView?.loadUrl(href)
        }
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
        if (s.optBoolean("bid")) {
            curId?.let { seen.add(it); bidIds.add(it) }
            log("⏭️ SKIPPED - Order $curId (BID REQUEST) - Time: ${secs(now)}s")
            goIndex(now)
            return
        }
        // The 60s lock starts the moment the order opens. The "read" checkbox is handled inside
        // GET_WAIT, whenever it becomes available during that window (not immediately on open).
        phase = Phase.GET_WAIT
        phaseSince = now
        repliedAt = 0L
        readAt = t0   // lock is counted from the moment the order was opened, so page-load time isn't added on top
        readDone = false
        clickTries = 0
        dbg("⏳ Order $curId opened (+${(now - t0) / 1000.0}s to load) - lock counting from open")
        nextDelay = Timing.GET_ORDER_POLL_MS
    }

    private fun getWait(s: JSONObject, now: Long) {
        val waited = now - readAt
        // At the READ_TICK_AT_MS mark of the lock (not on open): tick "read" (+ action button) once.
        // If the order has no checkbox at that point there is no "Get Order" to wait for: abort the lock.
        if (!readDone) {
            // Upload section already on the page and no checkbox: this order is already ours. Nothing to wait for.
            if (!s.optBoolean("rx") && s.optBoolean("taken")) {
                readDone = true
                curId?.let { handledAt[it] = now }
                log("⏭️ SKIPPED - Order $curId (ALREADY YOURS - upload section present)")
                goIndex(now)
                return
            }
            if (waited < Timing.READ_TICK_AT_MS) {
                nextDelay = Timing.AFTER_CLICK_POLL_MS
                return
            }
            readDone = true
            if (!s.optBoolean("rx")) {
                curId?.let { handledAt[it] = now }
                log("⏭️ SKIPPED - Order $curId (NO READ CHECKBOX) - Time: ${secs(now)}s")
                dbg("   page: inputs=[${s.optString("in")}] iframes=${s.optInt("fr")} title='${s.optString("t")}'")
                goIndex(now)
                return
            }
            webView?.evaluateJavascript(ENSURE_READ_JS) { r ->
                dbg("☑️ Read checkbox ticked at ${waited / 1000}s: $r")
                webView?.evaluateJavascript(ACTION_JS, null)
            }
            nextDelay = Timing.AFTER_CLICK_POLL_MS
            return
        }
        // The site keeps the order locked for 60s. Never click (or judge) before that.
        if (waited < Timing.GET_ORDER_MIN_WAIT_MS + Timing.GET_ORDER_MARGIN_MS) {
            nextDelay = Timing.AFTER_CLICK_POLL_MS
            return
        }
        // The site refuses "Get Order" unless the instructions are marked as read: verify, retry if not.
        if (readDone && s.optBoolean("rx") && !s.optBoolean("rd")) {
            webView?.evaluateJavascript(ENSURE_READ_JS) { r -> dbg("☑️ Read checkbox re-applied: $r") }
            nextDelay = Timing.AFTER_CLICK_POLL_MS
            return
        }
        if (s.optBoolean("get")) {
            preClickUrl = dec(s.optString("u"))
            preClickMarkers = s.optBoolean("taken")
            webView?.evaluateJavascript(CLICK_GET_JS) { r ->
                if (r != "1") dbg("⚠️ Get Order click found no button")
            }
            phase = Phase.AFTER_CLICK
            phaseSince = now
            clickedAt = now
            clickTries = 1
            dbg("🖱️ Get Order clicked - waiting for site response")
            nextDelay = Timing.AFTER_CLICK_POLL_MS
            return
        }
        if (waited >= Timing.GET_ORDER_TIMEOUT_MS) {
            log("❌ Unable to process Order $curId - Time: ${secs(now)}s")
            goIndex(now)
            return
        }
        nextDelay = Timing.GET_ORDER_POLL_MS
    }

    /**
     * Same verdict as the desktop bot (_check_order_success), taken on the page the click leads to:
     * upload input / upload label / order-view URL. The verdict is given as soon as the site has
     * answered the click (page replaced, or URL changed) instead of after a fixed delay.
     */
    private fun afterClick(s: JSONObject, url: String, now: Long) {
        val replaced = !s.optBoolean("m")                       // marker gone => new document
        val markers = s.optBoolean("taken")
        val state = s.optString("rs")
        // Upload section present => taken: decide at once (no waiting for images/scripts to finish).
        // Page replaced but no upload yet => short grace for it to render, then "taken by another user".
        if (replaced && !markers && repliedAt == 0L) repliedAt = now
        // Site already shows an error/alert on the replaced page and no upload: it is lost, no need to wait.
        val errorShown = replaced && !markers && s.optString("a").isNotBlank()
        val answered = replaced && (markers || errorShown || state == "complete" || now - repliedAt >= Timing.UPLOAD_GRACE_MS)
        val timedOut = now - phaseSince >= Timing.AFTER_CLICK_TIMEOUT_MS
        if (!replaced && s.optBoolean("get") && clickTries < 3 && now - clickedAt >= Timing.CLICK_RETRY_MS) {
            // Click did not register (button not live yet): press it again instead of judging.
            clickTries++
            clickedAt = now
            webView?.evaluateJavascript(CLICK_GET_JS, null)
            dbg("🖱️ Get Order clicked again (try $clickTries)")
        }
        if (!answered && !timedOut) {
            nextDelay = Timing.POST_CLICK_POLL_MS
            return
        }
        repliedAt = 0L
        val taken = markers
        log("ℹ️ Check: replaced=$replaced url=${url.substringAfter("r=").take(40)} " +
            "upload=$markers preUpload=$preClickMarkers read=${s.optBoolean("rd")} " +
            "title='${s.optString("t")}' alert='${s.optString("a")}'")
        curId?.let { handledAt[it] = now }
        if (taken) {
            orders++
            sound?.play(R.raw.whistling)
            log("✅ Order $curId taken successfully - Time: ${secs(now)}s")
        } else {
            log("❌ Order $curId taken by another user - Time: ${secs(now)}s")
        }
        publish()
        goIndex(now)
    }

    private fun goIndex(now: Long) {
        webView?.loadUrl(ORDERS_URL)
        phase = Phase.SCAN
        phaseSince = now
        curId = null
        nextDelay = 50
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
