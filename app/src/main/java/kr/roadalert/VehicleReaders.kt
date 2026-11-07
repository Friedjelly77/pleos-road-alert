package kr.roadalert

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener

class PleosSignals(private val context: Context, private val emit: (Observation) -> Unit,
                   private val status: (String) -> Unit) : VehicleInput {
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private val airbagListeners = mutableMapOf<SeatArea, SeatAirbagDeployedListener>()
    private var brakeListener: BrakePedalStatusListener? = null

    override fun start() {
        val v = Vehicle(context)
        vehicle = v
        fun onMain(action: () -> Unit) { main.post { if (vehicle === v) action() } }
        fun unavailable(domain: String, e: Exception) = onMain {
            Log.i("RoadAlertSDK", "$domain failed " + e.javaClass.simpleName + " " + e.message?.take(100))
            status("$domain · 접근 확인 필요")
        }
        try {
            v.initialize()
            val safety = v.getSafety()
            safety.checkAirbagDeployedCapabilities({ capability, seats -> onMain {
                Log.i("RoadAlertSDK", "deployment capability=" + capability.accessType + " areas=" + seats.size)
                if (capability.accessType in setOf(AccessType.READ, AccessType.READ_WRITE)) {
                    seats.forEachIndexed { index, seat ->
                        val listener = object : SeatAirbagDeployedListener {
                            override fun onSeatAirbagDeployUpdated(deployed: Boolean?) = onMain {
                                if (deployed != null) emit(Observation("deployment", "pleos",
                                    System.currentTimeMillis(), deployed, seat = index.toString()))
                            }
                            override fun onFailed(e: Exception) = unavailable("에어백", e)
                        }
                        // Seed the detector before subscribing. An already deployed bag is not a new crash.
                        safety.hasSeatAirbagBeenDeployed(seat, { baseline -> onMain {
                            listener.onSeatAirbagDeployUpdated(baseline)
                            Log.i("RoadAlertSDK", "deployment initial value received seat=$index")
                            airbagListeners[seat] = listener
                            safety.registerSeatAirbagDeployed(seat, listener)
                        } }, { unavailable("에어백 조회", it) })
                    }
                } else status("에어백 · 읽기 미지원")
            } }, { unavailable("에어백", it) })
            val brake = v.getBrake()
            brake.checkBrakePedalStatusCapabilities({ capability -> onMain {
                Log.i("RoadAlertSDK", "brake capability=" + capability.accessType)
                if (capability.accessType in setOf(AccessType.READ, AccessType.READ_WRITE)) {
                    val listener = object : BrakePedalStatusListener {
                        override fun onBrakePedalStatusUpdated(status: Boolean?) = onMain {
                            if (status != null) emit(Observation("brake", "pleos", System.currentTimeMillis(), status))
                        }
                        override fun onFailed(e: Exception) = unavailable("브레이크", e)
                    }
                    brakeListener = listener
                    brake.registerBrakePedalStatus(listener)
                    brake.getCurrentBrakePedalStatus({ listener.onBrakePedalStatusUpdated(it) },
                        { unavailable("브레이크 조회", it) })
                } else status("브레이크 · 읽기 미지원")
            } }, { unavailable("브레이크", it) })
        } catch (e: Exception) { unavailable("차량 서비스", e) }
    }

    override fun stop() {
        val v = vehicle ?: return
        vehicle = null
        airbagListeners.forEach { (seat, listener) -> runCatching { v.getSafety().unregisterSeatAirbagDeployed(seat, listener) } }
        airbagListeners.clear()
        brakeListener?.let { runCatching { v.getBrake().unregisterBrakePedalStatus(it) } }
        brakeListener = null
        runCatching { v.release() }
    }
}

/** Instantiate only on automotive devices with the optional android.car library. */
class AutomotiveSignals(private val context: Context, private val readSpeed: Boolean,
                        private val emit: (Observation) -> Unit, private val restricted: (Boolean) -> Unit,
                        private val status: (String) -> Unit) : VehicleInput {
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var active = false
    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (!active || value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED ||
                value.status != CarPropertyValue.STATUS_AVAILABLE) return
            val speed = (value.value as? Number)?.toDouble() ?: return
            val measured = value.timestamp / 1_000_000
            val age = SystemClock.elapsedRealtime() - measured
            if (age !in 0..3000) return
            emit(Observation("speed", "aaos", System.currentTimeMillis() - age,
                speedMps = kotlin.math.abs(speed), sampleMs = measured))
        }
        override fun onErrorEvent(propId: Int, zone: Int) { status("차량 속도 · 수신 중단") }
    }

    override fun start() {
        active = true
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), 0L) { connected, ready ->
                if (!active) return@createCar
                if (!ready) { restricted(true); status("차량 서비스 · 연결 대기"); return@createCar }
                runCatching {
                    ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    restricted(ux!!.currentCarUxRestrictions.isRequiresDistractionOptimization)
                    ux!!.registerListener { if (active) restricted(it.isRequiresDistractionOptimization) }
                }.onFailure { restricted(true) }
                if (readSpeed) runCatching {
                    properties = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    val ok = properties!!.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 5f)
                    status(if (ok) "차량 속도 · 구독 요청됨" else "차량 속도 · 구독 불가")
                }.onFailure { status("차량 속도 · 권한 또는 지원 확인 필요") }
            }
        } catch (e: Exception) { restricted(true); status("차량 서비스 · 연결 불가") }
    }

    override fun stop() {
        active = false
        runCatching { properties?.unregisterCallback(callback) }
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        ux = null; properties = null; car = null
    }
}
