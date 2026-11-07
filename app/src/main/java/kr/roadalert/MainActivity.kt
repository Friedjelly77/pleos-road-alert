package kr.roadalert

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity(), SyncView {
    private enum class Screen { HOME, REPORT, DETAIL, SETUP }
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    private lateinit var vault: Vault
    private lateinit var sync: Sync
    private lateinit var voice: ShortVoice
    private lateinit var gnss: DevicePosition
    private var position: PositionInput = UnmappedPosition
    private var mode = InputMode.DEMO
    private var station = Station.A
    private var visible = false
    private val automotive by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) }
    private var limited = false
    private val readers = mutableListOf<VehicleInput>()
    private var simulator: SimulatedSignals? = null
    private var detector = SignalGate()
    private var screen = Screen.HOME
    private var candidate: Candidate? = null
    private var brakeDown = false
    private var brakeTime = 0L
    private var sdkMessage = "SDK 입력 대기"
    private var gpsMessage = "위치 측정 대기"
    private var voiceMessage = ""
    private var connection = "서버 연결 중"
    private var delivery = ""
    private var latest: JSONObject? = null
    private var details: JSONObject? = null
    private var manage = false
    private var detailId = ""
    private var lastReply = 0L
    private var serverOffset = 0L
    private var receiptAt = 0L
    private val painted = mutableSetOf<String>()
    private var detailPending = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!visible) return
            var changed = false
            val now = System.currentTimeMillis()
            if (candidate?.let { now - it.at > 20000 } == true) { candidate = null; changed = true }
            if (lastReply > 0 && SystemClock.elapsedRealtime() - lastReply > 14000 && connection == "연결됨") {
                connection = "정보 갱신 지연"; latest = null; changed = true
            }
            if (latest?.let { it.getLong("expiresAt") <= serverNow() } == true) { latest = null; changed = true }
            if (screen == Screen.DETAIL && details?.optString("state") == "active" &&
                details!!.getLong("expiresAt") <= serverNow()) refreshDetail()
            if (changed) render()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Ink.night; window.navigationBarColor = Ink.night
        mode = InputMode.valueOf(prefs.getString("mode", "DEMO")!!)
        station = Station.valueOf(prefs.getString("station", "A")!!)
        vault = Vault(this)
        sync = Sync(vault, this) { position.current(System.currentTimeMillis()) }
        gnss = DevicePosition(this) { gpsMessage = it; if (screen == Screen.SETUP) render() }
        voice = ShortVoice(this) { message -> runOnUiThread { voiceMessage = message; if (screen == Screen.SETUP) render() } }
        limited = automotive
        render()
    }
    override fun onStart() {
        super.onStart(); visible = true
        configureInputs()
        if (prefs.getBoolean("voice", false)) voice.enable()
        if (vault.pending.length() > 0) delivery = "전송 대기 · 서버 접수 확인 중"
        sync.start(); main.post(ticker)
        if (screen == Screen.DETAIL) refreshDetail()
    }
    override fun onStop() {
        visible = false; main.removeCallbacks(ticker); sync.stop(); stopInputs(); voice.close()
        detailPending = false
        candidate = null; latest = null; lastReply = 0; connection = "서버 연결 중"
        super.onStop()
    }
    override fun onDestroy() { sync.destroy(); super.onDestroy() }

    private fun stopInputs() {
        readers.forEach { runCatching { it.stop() } }; readers.clear(); simulator = null
        gnss.stop(); brakeDown = false
    }
    private fun configureInputs() {
        stopInputs(); detector = SignalGate(); candidate = null; latest = null; lastReply = 0
        position = if (mode == InputMode.LIVE) gnss else DemoPosition(station)
        connection = if (mode == InputMode.LIVE) "도로 구간 연결 대기" else "서버 연결 중"
        if (mode == InputMode.LIVE) gnss.start()
        if (mode == InputMode.DEMO) {
            simulator = SimulatedSignals(::observe).also { readers.add(it) }
            sdkMessage = "가상 입력 사용 중"
        } else {
            sdkMessage = "차량 서비스 연결 중"
            if (automotive) readers.add(PleosSignals(this, ::observe, ::sdkStatus))
            else sdkMessage = "차량 서비스가 없는 기기"
        }
        if (automotive) {
            limited = true
            runCatching { readers.add(AutomotiveSignals(this, mode != InputMode.DEMO, ::observe, { lock ->
                if (lock != limited) {
                    limited = lock
                    if (lock) { screen = Screen.HOME; candidate = null; voice.stop() }
                    render()
                }
            }, ::sdkStatus)) }
        }
        readers.forEach { reader -> runCatching { reader.start() }.onFailure { sdkStatus("차량 입력 연결 불가") } }
        render()
    }
    private fun sdkStatus(message: String) { runOnUiThread { if (visible) {
        sdkMessage = message
        if (screen == Screen.SETUP) render()
    } } }
    private fun observe(sample: Observation) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { observe(sample) }; return }
        if (!visible || (sample.input == "simulator") != (mode == InputMode.DEMO)) return
        if (sample.signal == "brake") {
            brakeDown = sample.active; brakeTime = sample.at
            sdkStatus(if (brakeDown) "브레이크 페달 · 밟음" else "브레이크 페달 · 해제")
            Log.i("RoadAlertSDK", "brake input=${sample.input} pressed=${sample.active}")
            if (screen == Screen.REPORT) render()
        }
        val fix = position.current(sample.at) ?: return
        if (sample.at - fix.observedAt !in 0..20000) return
        when (detector.accept(sample)) {
            Decision.DEPLOYED -> {
                queueReport("crash", fix, sample.input, "deployment", sample.at)
            }
            Decision.ASK -> if (!limited) {
                val signal = if (sample.signal == "speed") "deceleration" else sample.signal
                candidate = Candidate(signal, sample.input, sample.at, fix)
                screen = Screen.REPORT; render()
            }
            Decision.NONE -> Unit
        }
    }
    private fun queueReport(kind: String, fix: RoadFix, input: String, signal: String, at: Long) {
        val now = System.currentTimeMillis()
        if (now - at !in 0..20000 || at - fix.observedAt !in 0..20000) {
            candidate = null; delivery = "위치가 오래되어 접수를 보류했습니다"; screen = Screen.HOME; render(); return
        }
        candidate = null; screen = Screen.HOME
        val payload = ReportFactory.make(kind, fix, input, signal, at, now)
        sync.submit("/v1/events", "POST", payload)
    }
    private fun manual(kind: String) {
        if (limited) return
        val at = System.currentTimeMillis()
        val fix = position.current(at) ?: return
        queueReport(kind, fix, "manual", "manual", at)
    }
    override fun onDelivery(message: String, reply: JSONObject?) {
        delivery = message
        if (reply != null) {
            val e = reply.getJSONObject("event")
            delivery += " · " + stateText(e.getString("state"))
            if (detailId == e.getString("id")) { details = e; manage = reply.optBoolean("canManage") }
        }
        render()
    }
    override fun onWatch(reply: JSONObject?, receivedAt: Long) {
        if (reply == null) {
            connection = "연결 끊김 · 재시도 중"; latest = null
        } else {
            lastReply = SystemClock.elapsedRealtime()
            serverOffset = reply.getLong("serverAt") - lastReply
            connection = if (reply.getBoolean("fresh")) "연결됨" else "위치 정보가 오래됨"
            val next = reply.getJSONArray("events").optJSONObject(0)
            if (next?.optString("id") != latest?.optString("id")) receiptAt = receivedAt
            latest = next?.takeIf { it.getLong("expiresAt") > serverNow() }
        }
        // Keep focus and scroll position in the parked settings and report screens.
        if (screen == Screen.HOME) render()
        if (screen == Screen.DETAIL) refreshDetail()
    }
    private fun serverNow() = if (lastReply == 0L) System.currentTimeMillis() else SystemClock.elapsedRealtime() + serverOffset
    private fun refreshDetail() {
        if (detailPending || detailId.isEmpty()) return
        val id = detailId
        detailPending = true
        sync.detail(id) { reply ->
            detailPending = false
            if (screen != Screen.DETAIL || detailId != id) return@detail
            details = reply?.optJSONObject("event")
            manage = reply?.optBoolean("canManage") == true
            render()
        }
    }
    private fun openDetail(id: String) {
        if (limited || id.isBlank()) return
        detailId = id; screen = Screen.DETAIL; details = null; manage = false; detailPending = false
        render(); refreshDetail()
    }
    private fun go(to: Screen) {
        if (limited && to != Screen.HOME) return
        if (to != Screen.REPORT) candidate = null
        screen = to; render()
    }
    @Deprecated("Back called by Android") override fun onBackPressed() {
        if (screen == Screen.HOME) super.onBackPressed() else go(Screen.HOME)
    }
    private fun change(m: InputMode = mode, s: Station = station) {
        mode = m; station = s; prefs.edit().putString("mode", m.name).putString("station", s.name).apply()
        detailPending = false; configureInputs(); sync.newPosition()
    }
    private fun render() {
        if (isDestroyed) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ink.night)
            val pad = dp(if (resources.configuration.screenWidthDp >= 840) 36 else 20)
            setPadding(pad, dp(12), pad, dp(16))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_road) }, dp(36), dp(36))
        header.addView(label("  Pleos Road Alert", 20, Ink.white, true), LinearLayout.LayoutParams(0, -2, 1f))
        val netColor = if (connection == "연결됨") Ink.mint else Ink.amber
        header.addView(label(connection, 16, netColor))
        root.stack(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        when (screen) {
            Screen.HOME -> home(body)
            Screen.REPORT -> reportPage(body)
            Screen.DETAIL -> detailPage(body)
            Screen.SETUP -> settings(body)
        }
        if (!limited) {
            if (screen == Screen.HOME) root.bar(action("상황 제보", true) { go(Screen.REPORT) },
                action("데모 설정") { go(Screen.SETUP) })
            else root.bar(action("메인으로") { go(Screen.HOME) })
        }
        setContentView(root)
    }
    private fun areaLabel() = if (mode == InputMode.LIVE) "실제 도로 · 구간 미연결" else {
        val road = if (station.road == "riverside") "강변로" else "교차로"
        val deck = when (station.level) { 1 -> "고가"; -1 -> "하부"; else -> "지상" }
        "가상 도로   /   $road · $deck · " + if (station.direction == 1) "정방향" else "역방향"
    }
    private fun card(body: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(Ink.panel)
        setPadding(dp(20), dp(14), dp(20), dp(14)); body.stack(this, 8)
    }
    private fun home(body: LinearLayout) {
        body.stack(label(areaLabel(), 16, Ink.muted))
        val box = card(body)
        val event = latest
        val fix = position.current(System.currentTimeMillis())
        val gap = if (fix != null && event != null) abs(event.getJSONObject("fix").getInt("offsetM") - fix.offset) else null
        if (event != null && gap != null) {
            val compact = resources.configuration.screenHeightDp < 450 && resources.configuration.screenWidthDp >= 620
            fun column(): LinearLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                box.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
            if (compact) box.orientation = LinearLayout.HORIZONTAL
            val headline = if (compact) column() else box
            val approach = if (compact) column() else box
            val kind = kindText(event.getString("kind"))
            val msg = "전방 $kind 제보가 있습니다. 정체 가능성에 유의하세요."
            headline.stack(label("전방 약 ${gap} m", 18, Ink.amber, true))
            headline.stack(label("전방 $kind 제보", 32, Ink.white, true))
            headline.stack(label("정체 가능성에 유의하세요.", 21, Ink.muted), 4)
            val id = event.getString("id")
            val rx = receiptAt
            approach.stack(ApproachView(this, gap) {
                if (painted.add(id)) {
                    val drawn = System.currentTimeMillis()
                    val trace = JSONObject().put("eventId", id).put("createdAt", event.getLong("createdAt"))
                        .put("sentAt", event.getLong("sentAt")).put("serverReceivedAt", event.getLong("receivedAt"))
                        .put("clientReceivedAt", rx).put("viewDrawnAt", drawn)
                    Log.i("RoadAlertTrace", trace.toString())
                }
                main.post {
                    if (visible && !limited && latest?.optString("id") == id &&
                        vault.firstAlert(id, event.getLong("expiresAt"), serverNow()) && prefs.getBoolean("voice", false))
                        voice.speak(msg, id)
                }
            })
            if (!limited) approach.bar(action("사건 상세") { openDetail(id) })
        } else {
            val title = when (connection) {
                "연결됨" -> "전방 제보 없음"; "도로 구간 연결 대기" -> "도로 구간 확인 대기"
                "서버 연결 중" -> "전방 제보 확인 중"; else -> "최신 제보 확인 대기"
            }
            box.stack(label("전방 도로", 17, Ink.mint, true))
            box.stack(label(title, 32, Ink.white, true), 4)
            box.stack(ApproachView(this, null))
        }
        if (delivery.isNotBlank()) body.stack(label(delivery, 17, Ink.amber), 10)
        if (!limited && vault.lastId.isNotBlank()) body.bar(action("최근 제보 상태") { openDetail(vault.lastId) })
        if (limited) body.stack(label("주행 제한 중 · 화면 조작 제한", 17, Ink.muted), 8)
    }
    private fun reportPage(body: LinearLayout) {
        val c = candidate
        body.stack(label(if (c == null) "상황 제보" else "도로 위험을 보셨나요?", 30, Ink.white, true))
        body.stack(label(areaLabel(), 17, Ink.muted), 5)
        if (mode == InputMode.LIVE) { body.stack(label("도로 구간과 진행 방향을 확인한 뒤 제보할 수 있습니다.", 19), 10); return }
        if (c != null) {
            body.stack(label("차량 신호가 감지됐습니다. 위험을 확인한 경우에만 제보해 주세요.", 20), 10)
            body.bar(action("위험 확인 · 제보", true) { queueReport("hazard", c.fix, c.input, c.signal, c.at) },
                action("건너뛰기") { go(Screen.HOME) })
        } else {
            body.stack(label("확인한 상황을 선택하면 서버에 제보합니다.", 19, Ink.muted), 12)
            val assisted = brakeDown && System.currentTimeMillis() - brakeTime in 0..20000
            if (assisted) body.stack(label("브레이크 입력 감지 · 급정차 제보를 선택할 수 있습니다.", 17, Ink.amber), 6)
            body.bar(action("사고 제보", !assisted) { manual("crash") }, action("급정차 제보", assisted) { manual("stop") })
            body.bar(action("도로 위험 제보") { manual("hazard") })
        }
    }
    private fun detailPage(body: LinearLayout) {
        body.stack(label("사건 상세", 30, Ink.white, true))
        val e = details
        if (e == null) { body.stack(label("사건 상태 확인 중 · 연결 상태를 확인하세요.", 19, Ink.muted), 12); return }
        val box = card(body)
        box.stack(label(kindText(e.getString("kind")) + " 제보   ·   " + stateText(e.getString("state")), 25, Ink.white, true))
        box.stack(label(verifiedText(e.getString("verification")) + "   ·   참여 ${e.getInt("participants")}명", 19, Ink.mint), 8)
        val f = e.getJSONObject("fix")
        val road = if (f.getString("road") == "riverside") "강변로" else "교차로"
        val deck = when (f.getInt("level")) { 1 -> "고가"; -1 -> "하부"; else -> "지상" }
        val direction = if (f.getInt("direction") == 1) "정방향" else "역방향"
        box.stack(label("가상 도로 · $road $deck $direction · ${f.getInt("offsetM")} m 지점", 17, Ink.muted), 5)
        fun time(key: String) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(e.getLong(key)))
        box.stack(label("발생 ${time("happenedAt")}   접수 ${time("receivedAt")}", 17, Ink.muted), 4)
        val from = when (e.getString("input")) {
            "pleos" -> "Pleos 차량 신호"; "aaos" -> "Android 차량 신호"; "simulator" -> "가상 차량 신호"; else -> "사용자 제보"
        }
        box.stack(label("입력 출처 · $from", 17, Ink.muted), 4)
        if (manage && e.getString("state") == "active") {
            fun finish(state: String) { sync.submit("/v1/events/$detailId", "PATCH", JSONObject().put("state", state)) }
            body.bar(action("해소 제보", true) { finish("resolved") }, action("제보 취소") { finish("cancelled") })
        }
        if (delivery.isNotBlank()) body.stack(label(delivery, 16, Ink.amber), 6)
    }
    private fun settings(body: LinearLayout) {
        body.stack(label("데모 설정", 29, Ink.white, true))
        body.stack(label(mode.label + "   /   " + station.label, 18, Ink.mint), 4)
        body.stack(label("입력 선택", 18, Ink.muted, true), 12)
        body.bar(action("가상 입력", mode == InputMode.DEMO) { change(InputMode.DEMO) },
            action("SDK + 가상 도로", mode == InputMode.MIXED) { change(InputMode.MIXED) },
            action("실제 도로", mode == InputMode.LIVE) { change(InputMode.LIVE) })
        if (mode != InputMode.LIVE) {
            body.stack(label("차량 위치", 18, Ink.muted, true), 12)
            Station.values().toList().chunked(3).forEach { list ->
                body.bar(*list.map { s -> action(s.label, s == station) { change(s = s) } }.toTypedArray())
            }
        } else {
            body.stack(label("GNSS는 이 기기에서만 확인합니다. 도로·높이 매칭을 연결해야 제보와 수신이 시작됩니다.", 17, Ink.muted), 7)
            body.bar(action("기기 위치 확인 동의") { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 21) })
            body.stack(label(gpsMessage, 16, Ink.muted))
        }
        body.stack(label(sdkMessage, 17, Ink.muted), 8)
        if (mode == InputMode.DEMO) {
            body.stack(label("가상 차량 신호", 18, Ink.muted, true), 10)
            body.bar(action("비상등 켜기") { simulator?.inject("hazards") },
                action("급감속") { simulator?.inject("deceleration") },
                action("에어백 전개") { simulator?.inject("deployment") })
        } else if (automotive) {
            body.bar(action("차량 신호 권한 확인") {
                val permissions = arrayOf("android.car.permission.CAR_SPEED", "pleos.car.permission.CAR_DRIVE",
                    "pleos.car.permission.CAR_SAFETY").filter { granted ->
                    runCatching { packageManager.getPermissionInfo(granted, 0).protection ==
                        android.content.pm.PermissionInfo.PROTECTION_DANGEROUS }.getOrDefault(false) &&
                        checkSelfPermission(granted) != PackageManager.PERMISSION_GRANTED
                }
                if (permissions.isEmpty()) change() else requestPermissions(permissions.toTypedArray(), 22)
            })
        }
        val on = prefs.getBoolean("voice", false)
        body.bar(action(if (on) "정차 시 음성 안내 끄기" else "정차 시 음성 안내 켜기") {
            prefs.edit().putBoolean("voice", !on).apply()
            if (on) { voice.close(); voiceMessage = "" } else voice.enable()
            render()
        })
        if (voiceMessage.isNotEmpty()) body.stack(label(voiceMessage, 16, Ink.muted), 4)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (visible && code in 21..22) { configureInputs(); sync.newPosition() }
    }
}
