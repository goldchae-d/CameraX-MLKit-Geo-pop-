package com.example.camerax_mlkit

import android.Manifest
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
import java.util.concurrent.atomic.AtomicReference

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
    @Volatile private var nearBeacon: Boolean = false
    @Volatile private var lastFenceId: String? = null

    data class BeaconMeta(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val locationId: String?,
        val merchantId: String?,
        val nonce: String?,
        val rssi: Int
    )
    private val currentBeaconRef = AtomicReference<BeaconMeta?>(null)

    private var lastShownAt = 0L
    private const val COOLDOWN_MS = 3000L
    private const val BEACON_NEAR_TIMEOUT_MS = 15000L
    private var beaconNearUntil = 0L

    // QR 경로에서도 동일 정책
    fun allowedForQr(): Boolean {
        return evaluatePolicy().first
    }

    // ─── 지오펜스 ───────────────────────────
    fun onGeofenceChanged(ctx: Context, inZone: Boolean, fenceId: String?) {
        inGeofence = inZone
        lastFenceId = fenceId?.lowercase()

        val beaconLoc = currentBeaconRef.get()?.locationId?.lowercase()
        Log.d(
            TAG,
            "Geofence → in=$inGeofence fenceId=$lastFenceId " +
                    "beaconNear=$nearBeacon beaconLoc=$beaconLoc wifi=$onTrustedWifi"
        )

        maybeShow(ctx, reason = "GEOFENCE")
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
        nearBeacon = entry != null

        if (entry != null) {
            currentBeaconRef.set(
                BeaconMeta(
                    uuid = uuid,
                    major = major,
                    minor = minor,
                    locationId = entry.locationId,
                    merchantId = entry.merchantId,
                    nonce = nonce,
                    rssi = rssi
                )
            )
            markBeaconNearForAWhile(ctx)
        } else {
            currentBeaconRef.set(null)
            nearBeacon = false
            cancelHeadsUp(ctx)
        }

        val fenceLoc = lastFenceId?.lowercase()
        val beaconLoc = entry?.locationId?.lowercase()
        Log.d(
            TAG,
            "Beacon → near=$nearBeacon uuid=$uuid major=$major minor=$minor rssi=$rssi " +
                    "beaconLoc=$beaconLoc fenceLoc=$fenceLoc"
        )
    }

    private fun markBeaconNearForAWhile(ctx: Context) {
        beaconNearUntil = System.currentTimeMillis() + BEACON_NEAR_TIMEOUT_MS
        maybeShow(ctx, reason = "BEACON")
        Handler(Looper.getMainLooper()).postDelayed({
            if (System.currentTimeMillis() >= beaconNearUntil) {
                nearBeacon = false
                currentBeaconRef.set(null)
                cancelHeadsUp(ctx)
                Log.d(TAG, "Beacon near timeout → near=false")
            }
        }, BEACON_NEAR_TIMEOUT_MS)
    }

    // ─── Wi-Fi ─────────────────────────────
    fun setTrustedWifi(ok: Boolean, ctx: Context) {
        onTrustedWifi = ok
        if (!ok) {
            cancelHeadsUp(ctx)
        } else {
            maybeShow(ctx, reason = "WIFI")
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

    fun getCurrentBeacon(): BeaconMeta? = currentBeaconRef.get()
    // ─── 정책 평가 ─────────────────────────
    fun evaluatePolicy(): Triple<Boolean, String?, String?> {
        val beaconLoc = currentBeaconRef.get()?.locationId?.lowercase()
        val fenceLocRaw = lastFenceId?.lowercase()

        // 시연모드: 비콘이 있으면 그 비콘 위치로 지오펜스를 맞춘다
        val fenceLoc = if (FORCE_GEOFENCE) {
            beaconLoc ?: fenceLocRaw
        } else {
            fenceLocRaw
        }

        val locMatch = beaconLoc != null && fenceLoc != null && beaconLoc == fenceLoc
        val geoOk = if (FORCE_GEOFENCE) true else inGeofence

        // 최종 정책
        val allow = nearBeacon || onTrustedWifi
        //val allow =
        //    (geoOk && nearBeacon && locMatch) ||
        //            onTrustedWifi
        return Triple(allow, beaconLoc, fenceLoc)
    }

    // ─── 팝업 노출 ─────────────────────────
    @Synchronized
    private fun maybeShow(ctx: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShownAt <= COOLDOWN_MS) return

        val (allow, beaconLoc, fenceLoc) = evaluatePolicy()
        val locMatch = (beaconLoc != null && fenceLoc != null && beaconLoc == fenceLoc)

        if (!allow) {
            Log.d(
                TAG,
                "Popup BLOCK → geo=$inGeofence beacon=$nearBeacon wifi=$onTrustedWifi " +
                        "beaconLoc=$beaconLoc fenceLoc=$fenceLoc locMatch=$locMatch"
            )
            return
        }

        // ✅ 여기: 이미 한 번 보여줬으면 또 안 띄움
        if (detectedNotiShown) {
            Log.d(TAG, "Popup skipped (already shown once)")
            return
        }

        // 여기까지 왔으면 이번이 첫 노출
        detectedNotiShown = true
        lastShownAt = now

        // 알림 문구: 비콘 또는 Wi-Fi면 공통으로 사용
        val message = when (reason) {
            "WIFI",
            "BEACON"   -> "정상 매장이 감지되었습니다."
            "GEOFENCE" -> "매장 반경에 진입했습니다."
            else       -> "결제 안내"
        }

        postHeadsUp(ctx, title = "결제 안내", message = message, reason = reason)

        if (isAppForeground()) {
            ctx.sendBroadcast(Intent(ACTION_PAY_PROMPT).apply {
                putExtra("reason", reason)
                putExtra("geo", inGeofence)
                putExtra("beacon", nearBeacon)
                putExtra("wifi", onTrustedWifi)
                putExtra("fenceId", fenceLoc ?: "unknown")
            })
        }
    }


    @Volatile private var detectedNotiShown = false

    // ─── 알림 유틸 ─────────────────────────
    private fun postHeadsUp(ctx: Context, title: String, message: String, reason: String) {
        ensureHighChannel(ctx)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skip notification")
            return
        }

        // 👇 사용자가 알림을 탭했을 때 열릴 화면
        val intent = Intent(ctx, PaymentPromptActivity::class.java).apply {
            putExtra(PaymentPromptActivity.EXTRA_TITLE, title)
            putExtra(PaymentPromptActivity.EXTRA_MESSAGE, message)
            putExtra(PaymentPromptActivity.EXTRA_TRIGGER, reason)
            putExtra("geo", inGeofence)
            putExtra("beacon", nearBeacon)
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

        NotificationCompat.Builder(ctx, CH_PAY_PROMPT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pi)     // ← 이거 넣어야 '탭 → 결제창'
            .setAutoCancel(true)      // 탭하면 알림 사라지게
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
            .also { NotificationManagerCompat.from(ctx).notify(NOTI_ID, it) }
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
