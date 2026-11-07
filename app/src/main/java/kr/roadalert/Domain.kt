package kr.roadalert

import org.json.JSONObject
import java.util.UUID

data class RoadFix(val road: String, val direction: Int, val level: Int, val offset: Int,
                   val observedAt: Long, val accuracy: Int = 5) {
    fun json(): JSONObject = JSONObject().put("space", "demo").put("road", road)
        .put("direction", direction).put("level", level).put("offsetM", offset)
        .put("observedAt", observedAt).put("accuracyM", accuracy)
}

interface PositionInput { fun current(now: Long): RoadFix? }

enum class Station(val label: String, val offset: Int, val direction: Int = 1,
                   val road: String = "riverside", val level: Int = 0) {
    A("A · 제보 지점", 1000), B("B · 600 m 전방 접근", 400), OPPOSITE("반대 방향", 1450, -1),
    PASSED("이미 통과", 1200), CROSS("교차 도로", 400, road = "crossway"),
    UPPER("고가도로", 400, level = 1), LOWER("하부도로", 400, level = -1),
    STALE("오래된 위치", 400)
}

class DemoPosition(private val station: Station) : PositionInput {
    override fun current(now: Long) = RoadFix(station.road, station.direction, station.level,
        station.offset, if (station == Station.STALE) now - 120_000 else now)
}

// Only authenticated road/level matches should implement this in a road-data integration.
// Raw GNSS coordinates alone cannot identify the deck of a road.
object UnmappedPosition : PositionInput { override fun current(now: Long): RoadFix? = null }

enum class InputMode(val label: String) {
    DEMO("가상 신호 · 가상 도로"), MIXED("차량 SDK 신호 · 가상 도로"), LIVE("차량 SDK 신호 · 실제 도로")
}

data class Observation(val signal: String, val input: String, val at: Long,
                       val active: Boolean = true, val speedMps: Double? = null,
                       val seat: String = "", val sampleMs: Long = at)

interface VehicleInput { fun start(); fun stop() }
class SimulatedSignals(private val deliver: (Observation) -> Unit) : VehicleInput {
    private var running = false
    override fun start() { running = true }
    override fun stop() { running = false }
    fun inject(signal: String) {
        if (!running) return
        val now = System.currentTimeMillis()
        if (signal == "deployment") deliver(Observation(signal, "simulator", now, active = false))
        if (signal == "deceleration") {
            deliver(Observation("speed", "simulator", now - 1000, speedMps = 15.0))
            deliver(Observation("speed", "simulator", now, speedMps = 5.0))
        } else {
            deliver(Observation(signal, "simulator", now))
        }
    }
}

data class Candidate(val signal: String, val input: String, val at: Long, val fix: RoadFix)
enum class Decision { NONE, ASK, DEPLOYED }

/** First airbag value is a baseline. A weak observation can only request a human report. */
class SignalGate {
    private val deployments = mutableMapOf<String, Boolean>()
    private var lastSpeed: Observation? = null
    private var lastPrompt = 0L
    private var lastDeployment = 0L

    fun accept(sample: Observation): Decision {
        if (sample.signal == "deployment") {
            val previous = deployments.put(sample.input + sample.seat, sample.active)
            if (previous == false && sample.active && sample.at - lastDeployment >= 90_000) {
                lastDeployment = sample.at
                return Decision.DEPLOYED
            }
            return Decision.NONE
        }
        var weak = sample.signal == "hazards" && sample.active
        if (sample.signal == "speed") {
            val speed = sample.speedMps ?: return Decision.NONE
            if (!speed.isFinite() || speed !in 0.0..100.0) return Decision.NONE
            val previous = lastSpeed
            if (previous != null && sample.sampleMs <= previous.sampleMs) return Decision.NONE
            lastSpeed = sample
            if (previous != null && previous.input == sample.input) {
                val elapsed = sample.sampleMs - previous.sampleMs
                val drop = (previous.speedMps ?: speed) - speed
                weak = elapsed in 100..3000 && drop >= 4.0 && drop * 1000 / elapsed >= 4.5
            }
        }
        if (weak && sample.at - lastPrompt >= 90_000) {
            lastPrompt = sample.at
            return Decision.ASK
        }
        return Decision.NONE
    }
}

object ReportFactory {
    fun make(kind: String, fix: RoadFix, input: String, signal: String, occurred: Long, now: Long): JSONObject =
        JSONObject().put("requestId", UUID.randomUUID().toString()).put("kind", kind)
            .put("source", if (input == "manual") "driver" else "vehicle").put("input", input)
            .put("signal", signal).put("confirmed", signal != "deployment")
            .put("happenedAt", occurred).put("createdAt", now).put("fix", fix.json())
}

fun kindText(kind: String) = when (kind) { "crash" -> "사고"; "stop" -> "급정차"; else -> "도로 위험" }
fun stateText(state: String) = when (state) {
    "active" -> "진행 중"; "resolved" -> "해소"; "cancelled" -> "취소"; else -> "기간 만료"
}
fun verifiedText(value: String) = when (value) {
    "reporter" -> "제보자 확인"; "corroborated" -> "추가 제보로 확인"; else -> "미확인"
}
