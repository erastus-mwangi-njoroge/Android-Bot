package com.acadbot.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), BotEngine.Listener {
    private val green = Color.parseColor("#2ecc8f")
    private val red = Color.parseColor("#ff5c6c")
    private val amber = Color.parseColor("#ffb547")
    private val blue = Color.parseColor("#5aa5ff")
    private val text = Color.parseColor("#e6edf7")

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private var tab = 0
    private var logLines = 0

    private lateinit var tvStatus: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvOrders: TextView
    private lateinit var tvLast: TextView
    private lateinit var tvAuth: TextView
    private lateinit var tabs: Array<Button>
    private lateinit var panels: Array<View>
    private lateinit var panelBrowser: FrameLayout
    private lateinit var etEmail: EditText
    private lateinit var etPass: EditText
    private lateinit var etToken: EditText
    private lateinit var etRefresh: EditText
    private lateinit var btnShow: Button
    private lateinit var cbRemember: CheckBox
    private lateinit var cbImages: CheckBox
    private lateinit var cbVerbose: CheckBox
    private lateinit var cbKeepOn: CheckBox
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnVerify: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    private val uptimeTick = object : Runnable {
        override fun run() {
            val s = BotEngine.uptimeSec()
            tvUptime.text = "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        bind()
        loadPrefs()
        wire()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        BotEngine.listener = this
        tvLog.text = ""
        logLines = 0
        for (l in BotEngine.logSnapshot()) append(l)
        BotEngine.publishNow()
        showTab(tab)
        ui.post(uptimeTick)
    }

    override fun onStop() {
        super.onStop()
        BotEngine.listener = null
        ui.removeCallbacks(uptimeTick)
        BotEngine.detach()
        savePrefs()
    }

    // ------------------------------------------------------------- setup
    private fun bind() {
        tvStatus = findViewById(R.id.tvStatus)
        tvUptime = findViewById(R.id.tvUptime)
        tvOrders = findViewById(R.id.tvOrders)
        tvLast = findViewById(R.id.tvLast)
        tvAuth = findViewById(R.id.tvAuth)
        tabs = arrayOf(findViewById(R.id.tabDash), findViewById(R.id.tabLog), findViewById(R.id.tabBrowser))
        panelBrowser = findViewById(R.id.panelBrowser)
        panels = arrayOf(findViewById(R.id.panelDash), findViewById(R.id.panelLog), panelBrowser)
        etEmail = findViewById(R.id.etEmail)
        etPass = findViewById(R.id.etPass)
        etToken = findViewById(R.id.etToken)
        etRefresh = findViewById(R.id.etRefresh)
        btnShow = findViewById(R.id.btnShow)
        cbRemember = findViewById(R.id.cbRemember)
        cbImages = findViewById(R.id.cbImages)
        cbVerbose = findViewById(R.id.cbVerbose)
        cbKeepOn = findViewById(R.id.cbKeepOn)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnVerify = findViewById(R.id.btnVerify)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
    }

    private fun wire() {
        for (i in tabs.indices) tabs[i].setOnClickListener { showTab(i) }
        btnShow.setOnClickListener {
            val show = etPass.transformationMethod is PasswordTransformationMethod
            etPass.transformationMethod =
                if (show) HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
            etPass.setSelection(etPass.text.length)
            btnShow.text = if (show) "Hide" else "Show"
        }
        btnVerify.setOnClickListener { verifyToken() }
        btnStart.setOnClickListener { startBot() }
        btnStop.setOnClickListener { stopBot() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            tvLog.text = ""; logLines = 0
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askBattery() }
        cbKeepOn.setOnCheckedChangeListener { _, on -> applyKeepOn(on) }
    }

    private fun loadPrefs() {
        etEmail.setText(prefs.email)
        etPass.setText(prefs.password)
        etToken.setText(prefs.token)
        etRefresh.setText(prefs.refreshMin.toString())
        cbRemember.isChecked = prefs.remember
        cbImages.isChecked = prefs.blockImages
        cbVerbose.isChecked = prefs.verbose
        cbKeepOn.isChecked = prefs.keepScreenOn
        applyKeepOn(prefs.keepScreenOn)
        if (prefs.token.isNotEmpty()) {
            if (Auth.verify(prefs.token, prefs.email)) setAuth("Verified", green)
            else setAuth("Expired / invalid", red)
        } else setAuth("Not verified", red)
    }

    private fun savePrefs() {
        prefs.email = etEmail.text.toString().trim()
        prefs.remember = cbRemember.isChecked
        prefs.password = if (cbRemember.isChecked) etPass.text.toString() else ""
        prefs.token = etToken.text.toString().trim()
        prefs.blockImages = cbImages.isChecked
        prefs.verbose = cbVerbose.isChecked
        prefs.keepScreenOn = cbKeepOn.isChecked
        prefs.refreshMin = (etRefresh.text.toString().toIntOrNull() ?: 10).coerceIn(1, 60)
    }

    private fun applyKeepOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------- tabs
    private fun showTab(i: Int) {
        if (i == 2 && BotEngine.webView == null) {
            Toast.makeText(this, "Start the bot first to see its browser", Toast.LENGTH_SHORT).show()
            return
        }
        tab = i
        for (k in panels.indices) panels[k].visibility = if (k == i) View.VISIBLE else View.GONE
        for (k in tabs.indices) {
            tabs[k].setBackgroundResource(if (k == i) R.drawable.btn_primary else R.drawable.btn_ghost)
        }
        if (i == 2) BotEngine.attachTo(panelBrowser) else BotEngine.detach()
    }

    // ------------------------------------------------------------- actions
    private fun email(): String? {
        val e = etEmail.text.toString().trim()
        if (e.isEmpty() || !e.contains("@")) {
            alert("Invalid email", "Please enter a valid email address.")
            return null
        }
        return e
    }

    private fun verifyToken() {
        val e = email() ?: return
        val t = etToken.text.toString().trim()
        if (t.isEmpty()) { alert("Missing token", "Please paste your authentication token."); return }
        if (Auth.verify(t, e)) {
            setAuth("Verified", green)
            savePrefs()
            Toast.makeText(this, "Token verified", Toast.LENGTH_SHORT).show()
        } else {
            setAuth("Invalid / expired", red)
            alert("Token invalid", "The token is invalid or has expired.\nGenerate a new one in the desktop app.")
        }
    }

    private fun startBot() {
        val e = email() ?: return
        if (etPass.text.isEmpty()) { alert("Missing password", "Please enter your password."); return }
        val t = etToken.text.toString().trim()
        if (t.isEmpty()) {
            alert("Authentication required", "Paste a token generated in the desktop app first.")
            return
        }
        if (!Auth.verify(t, e)) {
            setAuth("Invalid / expired", red)
            alert("Invalid token", "The token is invalid or expired for this email.")
            return
        }
        setAuth("Verified", green)
        savePrefs()
        val i = Intent(this, BotService::class.java).setAction(BotService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        showTab(1)
    }

    private fun stopBot() {
        BotEngine.stop()
        stopService(Intent(this, BotService::class.java))
    }

    private fun askBattery() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Battery optimisation is already off for this app", Toast.LENGTH_SHORT).show()
            }
        } catch (ex: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun alert(title: String, msg: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun setAuth(label: String, color: Int) {
        tvAuth.text = "●  Auth: $label"
        tvAuth.setTextColor(color)
    }

    // ------------------------------------------------------------- engine callbacks
    override fun onLog(line: String) {
        append(line)
        if (++logLines > 400) {
            tvLog.text = ""; logLines = 0
            for (l in BotEngine.logSnapshot()) append(l)
        }
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onStats(running: Boolean, status: String, orders: Int, lastSeen: String) {
        tvStatus.text = status
        tvStatus.setTextColor(when (status) {
            "Online" -> green
            "Offline" -> red
            else -> amber
        })
        tvOrders.text = orders.toString()
        tvLast.text = lastSeen
        btnStart.visibility = if (running) View.GONE else View.VISIBLE
        btnStop.visibility = if (running) View.VISIBLE else View.GONE
        for (v in arrayOf<View>(etEmail, etPass, etToken, etRefresh, cbImages, cbVerbose, btnVerify)) {
            v.isEnabled = !running
        }
        if (!running && tab == 2) showTab(0)
    }

    private fun append(line: String) {
        val body = line.substringAfter("] ", line)
        val color = when {
            body.startsWith("✅") -> green
            body.startsWith("❌") -> red
            body.startsWith("⚠") || body.startsWith("⏭") -> amber
            body.startsWith("🚀") || body.startsWith("🔄") || body.startsWith("📱") -> blue
            else -> text
        }
        val sp = SpannableString(line + "\n")
        sp.setSpan(ForegroundColorSpan(color), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvLog.append(sp)
    }
}
