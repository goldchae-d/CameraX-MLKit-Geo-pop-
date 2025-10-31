package com.example.camerax_mlkit

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.camerax_mlkit.security.WhitelistManager
import java.util.concurrent.ConcurrentHashMap // 👈 [추가]

/**
 * 결제 안내 노출의 단일 진입 게이트.
 * - 상태 소스: 지오펜스, 비콘, 신뢰 Wi-Fi
 * - 시연 정책: 지오펜스가 안 와도 비콘이 정상일 때는 들어온 걸로 취급
 */
object TriggerGate {

    private const val TAG = "TriggerGate"

    // ✨ 시연용: true면 지오펜스를 강제로 만족으로 간주
    private const val FORCE_GEOFENCE = true

    const val ACTION_PAY_PROMPT = "com.example.camerax_mlkit.ACTION_PAY_PROMPT"
    private const val CH_PAY_PROMPT = "pay_prompt"
    private const val NOTI_ID = 2025

    @Volatile private var onTrustedWifi: Boolean = false
    @Volatile private var inGeofence: Boolean = false
    // @Volatile private var nearBeacon: Boolean = false (이제 activeBeacons.isNotEmpty()로 대체)
    @Volatile private var lastFenceId: String? = null

    //data class BeaconMeta(...)
    // private val currentBeaconRef = AtomicReference<BeaconMeta?>(null)

    // 1단계에서 whitelist.json에 추가한 "name" 필드를 포함하는 새 데이터 클래스
    data class ActiveBeacon(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val name: String?, //  사용자가 볼 매장 이름
        val locationId: String?,
        val merchantId: String?,
        val nonce: String?,
        val rssi: Int,
        var lastSeen: Long = 0L // 타임아웃 처리를 위한 마지막 감지 시각
    )
    //  활성 비콘 목록을 저장할 맵 (Key = "UUID|Major|Minor")
    private val activeBeacons = ConcurrentHashMap<String, ActiveBeacon>()


    private var lastShownAt = 0L
    private const val COOLDOWN_MS = 3000L
    private const val BEACON_NEAR_TIMEOUT_MS = 15000L
    // private var beaconNearUntil = 0L (이제 activeBeacons의 lastSeen으로 개별 관리)

    // QR 경로에서도 동일 정책
    fun allowedForQr(): Boolean {
        return evaluatePolicy().first
    }

    // ─── 지오펜스 ───────────────────────────
    fun onGeofenceChanged(ctx: Context, inZone: Boolean, fenceId: String?) {
        inGeofence = inZone
        lastFenceId = fenceId?.lowercase()

        // 👈 [수정] 로그 로직 변경: 활성 비콘 개수로 확인
        Log.d(
            TAG,
            "Geofence → in=$inGeofence fenceId=$lastFenceId " +
                    "activeBeacons=${activeBeacons.size} wifi=$onTrustedWifi"
        )

        // 👈 [수정] 팝업 로직을 새 함수로 호출
        evaluateAndShow(ctx, reason = "GEOFENCE")
        if (!inZone) cancelHeadsUp(ctx)
    }

    // ─── 비콘 ───────────────────────────────
    fun setBeaconMeta(
        ctx: Context,
        uuid: String,
        major: Int,
        minor: Int,
        nonce: String?,
        rssi: Int
    ) {
        val entry = WhitelistManager.findBeacon(uuid, major, minor)
        // nearBeacon = entry != null (더 이상 사용 안함)

        if (entry != null) {
            // 맵에 저장할 고유 키
            val key = "$uuid|$major|$minor"
            // ActiveBeacon 객체 생성 (whitelist.json의 "name" 포함)
            val activeBeacon = ActiveBeacon(
                uuid = uuid,
                major = major,
                minor = minor,
                name = entry.name, //  1단계에서 추가한 매장 이름
                locationId = entry.locationId,
                merchantId = entry.merchantId,
                nonce = nonce,
                rssi = rssi,
                lastSeen = System.currentTimeMillis() // 현재 시각
            )
            // 맵에 추가 또는 갱신
            activeBeacons[key] = activeBeacon

            //  팝업 로직을 새 함수로 호출
            evaluateAndShow(ctx, reason = "BEACON")

        } else {
            // ⛔ [삭제] 'else' 블록: 화이트리스트에 없는 비콘은 무시.
            // 기존 비콘의 제거는 evaluateAndShow의 타임아웃 로직이 담당.
        }

        val fenceLoc = lastFenceId?.lowercase()
        val beaconLoc = entry?.locationId?.lowercase()
        // 👈 [수정] 로그 로직 변경
        Log.d(
            TAG,
            "Beacon → active=${entry != null} uuid=$uuid major=$major minor=$minor rssi=$rssi " +
                    "beaconLoc=$beaconLoc fenceLoc=$fenceLoc"
        )
    }

    // ⛔ [삭제] private fun markBeaconNearForAWhile(ctx: Context) { ... }
    // (이 로직은 아래 evaluateAndShow 함수로 통합/대체되었습니다.)

    // ─── Wi-Fi ─────────────────────────────
    fun setTrustedWifi(ok: Boolean, ctx: Context) {
        onTrustedWifi = ok
        if (!ok) {
            cancelHeadsUp(ctx)
        } else {
            // 👈 [수정] 팝업 로직을 새 함수로 호출
            evaluateAndShow(ctx, reason = "WIFI")
        }
        Log.d(TAG, "TrustedWiFi → $onTrustedWifi")
    }

    fun onAppResumed(ctx: Context) {
        // 앱이 앞으로 올 때는 "조건 되네? 그러면 바로 띄워야지" 를 하지 않는다.
        // 필요하면 여기서 로그만 남긴다.
        val (allow, beaconLoc, fenceLoc) = evaluatePolicy()
        Log.d(TAG, "onAppResumed → allow=$allow beaconLoc=$beaconLoc fenceLoc=$fenceLoc")
        // 끝
    }

    //fun getCurrentBeacon(): BeaconMeta? = currentBeaconRef.get()
    // (PaymentPromptActivity는 이제 Intent로부터 직접 비콘 정보를 전달받아야 합니다.)

    //  (필요시 사용) 현재 활성 비콘 목록을 반환하는 함수
    fun getActiveBeacons(): List<ActiveBeacon> = activeBeacons.values.toList()

    // ─── 정책 평가 ─────────────────────────
    fun evaluatePolicy(): Triple<Boolean, String?, String?> {
        //  단일 비콘(currentBeaconRef) 대신, 활성 비콘 목록의 첫 번째를 기준으로 평가
        val firstBeacon = activeBeacons.values.firstOrNull()
        val beaconLoc = firstBeacon?.locationId?.lowercase()
        val fenceLocRaw = lastFenceId?.lowercase()

        // 시연모드: 비콘이 있으면 그 비콘 위치로 지오펜스를 맞춘다
        val fenceLoc = if (FORCE_GEOFENCE) {
            beaconLoc ?: fenceLocRaw
        } else {
            fenceLocRaw
        }

        val locMatch = beaconLoc != null && fenceLoc != null && beaconLoc == fenceLoc
        val geoOk = if (FORCE_GEOFENCE) true else inGeofence

        // 👈 [수정] nearBeacon 대신 activeBeacons 맵의 크기로 판별
        val allow = activeBeacons.isNotEmpty() || onTrustedWifi

        return Triple(allow, beaconLoc, fenceLoc)
    }

    // ─── 팝업 노출 ─────────────────────────

    // ⛔ [삭제] @Synchronized private fun maybeShow(ctx: Context, reason: String) { ... }
    // (아래의 evaluateAndShow 함수로 대체됩니다.)

    // 👈 [추가] 기존 maybeShow와 markBeaconNearForAWhile 로직을 통합한 새 함수
    @Synchronized
    private fun evaluateAndShow(ctx: Context, reason: String) {
        val now = System.currentTimeMillis()

        // 1. 타임아웃된 비콘 정리 (기존 markBeaconNearForAWhile의 타이머 로직 대체)
        val keysToRemove = activeBeacons.filter { (now - it.value.lastSeen) > BEACON_NEAR_TIMEOUT_MS }.keys
        keysToRemove.forEach {
            activeBeacons.remove(it)
            Log.d(TAG, "Beacon timeout → $it removed")
        }

        // 2. 현재 활성 비콘 목록 확인
        val currentActiveList = activeBeacons.values.toList()

        // 3. 정책 평가
        val wifiOnly = onTrustedWifi && currentActiveList.isEmpty()
        val beaconsDetected = currentActiveList.isNotEmpty()
        // 👈 [추가] 지오펜스 단독 트리거 여부 (다른 조건이 없을 때만)
        val geoOnly = reason == "GEOFENCE" && !wifiOnly && !beaconsDetected

        // 4. 팝업 조건 확인
        if (!wifiOnly && !beaconsDetected && !geoOnly) {
            Log.d(TAG, "Popup BLOCK → No active conditions.")
            cancelHeadsUp(ctx) // (선택) 모든 조건이 사라졌으면 알림 취소
            return
        }

        // 5. 쿨다운
        val nowClock = SystemClock.elapsedRealtime()
        if (nowClock - lastShownAt <= COOLDOWN_MS) {
            Log.d(TAG, "Popup BLOCK → Cooldown active.")
            return
        }

        // ⛔ [삭제] 'detectedNotiShown' 관련 로직 (쿨다운이 있으므로 삭제, 매번 알림)
        lastShownAt = nowClock

        // 6. 팝업 분기 (사용자 요청 사항)
        when {
            // 👈 [분기 1] 비콘이 2개 이상 감지됨
            currentActiveList.size > 1 -> {
                Log.d(TAG, "Popup → Multiple beacons (${currentActiveList.size}). Launching selection.")
                // 👈 [추가] 비콘 선택창을 띄우는 새 알림 함수 호출
                postBeaconSelection(ctx, currentActiveList)
            }

            // 👈 [분기 2] 비콘이 1개만 감지됨
            currentActiveList.size == 1 -> {
                val singleBeacon = currentActiveList.first()
                val title = singleBeacon.name ?: "결제 안내" // 👈 1단계에서 추가한 이름 사용
                val msg = "정상 매장이 감지되었습니다."
                Log.d(TAG, "Popup → Single beacon (${title}). Launching payment prompt.")
                // 👈 [수정] postHeadsUp에 비콘 정보를 명시적으로 전달
                postHeadsUp(ctx, title, msg, "BEACON", singleBeacon)
            }

            // 👈 [분기 3] Wi-Fi만 감지됨 (비콘 없음)
            wifiOnly -> {
                Log.d(TAG, "Popup → Trusted Wi-Fi only. Launching payment prompt.")
                postHeadsUp(ctx, "결제 안내", "정상 매장이 감지되었습니다.", "WIFI", null)
            }

            // 👈 [분기 4] 지오펜스만 감지됨 (비콘/Wi-Fi 없음)
            geoOnly -> {
                Log.d(TAG, "Popup → Geofence only. Launching payment prompt.")
                postHeadsUp(ctx, "결제 안내", "매장 반경에 진입했습니다.", "GEOFENCE", null)
            }
        }

        // ... (SendBroadcast 로직은 팝업과 분리되어야 하므로 여기서는 일단 제거, 필요시 1개일때만 추가)
    }

    // ⛔ [삭제] @Volatile private var detectedNotiShown = false

    // 👈 [추가] 비콘 선택창(BeaconSelectionActivity)을 띄우는 알림
    @SuppressLint("MissingPermission")
    private fun postBeaconSelection(ctx: Context, beacons: List<ActiveBeacon>) {
        ensureHighChannel(ctx)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skip notification")
            return // 권한이 없으면 알림을 보내지 않고 함수 종료
        }

        // 👇 3단계에서 만들 'BeaconSelectionActivity'로 인텐트
        val intent = Intent(ctx, BeaconSelectionActivity::class.java).apply {
            // ActiveBeacon은 복잡하므로, 이름과 식별자(key) 배열을 넘김
            val names = beacons.mapNotNull { it.name }.toTypedArray()
            val keys = beacons.map { "${it.uuid}|${it.major}|${it.minor}" }.toTypedArray()

            putExtra("BEACON_NAMES", names)
            putExtra("BEACON_KEYS", keys)

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pi = PendingIntent.getActivity(
            ctx,
            0, // reqCode
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationCompat.Builder(ctx, CH_PAY_PROMPT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("여러 매장이 감지됨")
            .setContentText("탭하여 결제할 매장을 선택하세요.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val notification = NotificationCompat.Builder(ctx, CH_PAY_PROMPT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("여러 매장이 감지됨")
            .setContentText("탭하여 결제할 매장을 선택하세요.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        @SuppressLint("NotificationPermission")
        NotificationManagerCompat.from(ctx).notify(NOTI_ID, notification)

    }


    // ─── 알림 유틸 ─────────────────────────

    // [수정] 함수 시그니처 변경: (ActiveBeacon?)을 파라미터로 받음
    @SuppressLint("MissingPermission")
    private fun postHeadsUp(
        ctx: Context,
        title: String,
        message: String,
        reason: String,
        beacon: ActiveBeacon? //
    ) {
        ensureHighChannel(ctx)

        // 👇 [추가] postBeaconSelection과 동일한 알림 권한 체크
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skip notification")
            return // 권한이 없으면 알림을 보내지 않고 함수 종료
        }

        // 👇 사용자가 알림을 탭했을 때 열릴 화면
        val intent = Intent(ctx, PaymentPromptActivity::class.java).apply {
            putExtra(PaymentPromptActivity.EXTRA_TITLE, title)
            putExtra(PaymentPromptActivity.EXTRA_MESSAGE, message)
            putExtra(PaymentPromptActivity.EXTRA_TRIGGER, reason)

            //  전역 상태 대신, 전달받은 비콘 정보(또는 null)를 Intent에 직접 삽입
            if (beacon != null) {
                putExtra("beacon", true)
                putExtra("beacon_name", beacon.name)
                putExtra("beacon_locationId", beacon.locationId)
                putExtra("beacon_merchantId", beacon.merchantId)
                putExtra("beacon_uuid", beacon.uuid)
                putExtra("beacon_major", beacon.major)
                putExtra("beacon_minor", beacon.minor)
                putExtra("beacon_nonce", beacon.nonce) // (필요시 nonce도 전달)
            } else {
                putExtra("beacon", false)
            }

            // 전역 상태(global state)를 사용
            putExtra("geo", inGeofence)
            putExtra("wifi", onTrustedWifi)
            putExtra("fenceId", lastFenceId ?: "unknown")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pi = PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ... (NotificationCompat.Builder ... )
        NotificationCompat.Builder(ctx, CH_PAY_PROMPT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pi)     // ← 이거 넣어야 '탭 → 결제창'
            .setAutoCancel(true)      // 탭하면 알림 사라지게
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val notification = NotificationCompat.Builder(ctx, CH_PAY_PROMPT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pi)     // ← 이거 넣어야 '탭 → 결제창'
            .setAutoCancel(true)      // 탭하면 알림 사라지게
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        @SuppressLint("NotificationPermission")
        NotificationManagerCompat.from(ctx).notify(NOTI_ID, notification)
    }


    fun cancelHeadsUp(ctx: Context) =
        NotificationManagerCompat.from(ctx).cancel(NOTI_ID)

    private fun ensureHighChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_PAY_PROMPT,
                    "결제 안내",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun isAppForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}