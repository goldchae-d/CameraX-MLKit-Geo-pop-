// app/src/main/java/com/example/camerax_mlkit/PaymentPromptActivity.kt
package com.example.camerax_mlkit

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.camerax_mlkit.crypto.RetrofitProvider
import com.example.camerax_mlkit.crypto.SessionKeyManager
import com.example.camerax_mlkit.security.QrToken
import com.example.camerax_mlkit.security.SignatureVerifier
import com.example.camerax_mlkit.security.WhitelistManager
import com.example.camerax_mlkit.security.SecureQr
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

class PaymentPromptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_CODE = "extra_qr_code"
        const val EXTRA_TITLE   = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_TRIGGER = "extra_trigger"
        private const val TAG   = "PaymentPromptActivity"
    }

    private var dialog: BottomSheetDialog? = null
    private var sheetView: View? = null
    private var latestQrText: String? = null

    // 👈 [수정] Activity의 상태는 전역(TriggerGate)이 아닌 Intent로부터 받아야 함
    private var fenceId: String = "unknown"
    // 👈 [추가] 이 Activity가 처리할 대상 비콘 정보를 Intent에서 받아 저장
    private var targetLocationId: String? = null
    private var targetMerchantId: String? = null
    private var targetUuid: String? = null
    private var targetMajor: Int = 0
    private var targetMinor: Int = 0
    private var targetNonce: String? = null // 👈 [추가] Nonce 검증을 위해 추가

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 화이트리스트 1회 로드(메모리 캐시)
        WhitelistManager.load(this)

        // 👈 [수정] onCreate에서 Intent 파싱 함수 호출
        if (!parseIntent(intent)) {
            finish() // parseIntent가 false를 반환하면 (조건 불충족) Activity 종료
            return
        }

        // ⛔ [삭제] 기존의 컨텍스트 정보 및 정책 검사 (parseIntent로 이동)

        // ⛔ [삭제] (선택 강화) 지오펜스/비콘 불일치 차단 (parseIntent에서 처리)

        // 잠금화면에서도 표시
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val title   = intent.getStringExtra(EXTRA_TITLE)   ?: getString(R.string.title_pay)
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: getString(R.string.subtitle_pay)
        // 👈 [수정] 로그 개선
        Log.d(TAG, "title=$title, message=$message, trigger=${intent.getStringExtra(EXTRA_TRIGGER)}, locId=$targetLocationId, fenceId=$fenceId")

        showOrExpandPayChooser(title, message)

        // 👈 [수정] 세션키/토큰 생성 시, TriggerGate 전역 상태 대신 Intent에서 받은 target 멤버 변수 사용
        lifecycleScope.launch {
            try {
                val (kid, sk) = SessionKeyManager.ensureKey(this@PaymentPromptActivity, RetrofitProvider.keyApi)
                val sid = SessionIdProvider.get(this@PaymentPromptActivity)

                // ⛔ [삭제] val meta = TriggerGate.getCurrentBeacon()
                // ⛔ [삭제] val entry = meta?.let { WhitelistManager.findBeacon(it.uuid, it.major, it.minor) }

                // 👈 [수정] 멤버 변수 사용
                val merchantId = targetMerchantId ?: "merchant_unknown"
                val locId      = targetLocationId ?: fenceId // 비콘 locId를 우선 사용

                val qrText = SecureQr.buildEncryptedToken(
                    kid = kid,
                    sessionKey = sk,
                    sessionId = sid,
                    merchantId = merchantId,
                    amount = null,
                    extra = mapOf(
                        "type"        to "account",
                        "location_id" to locId,         // 레벨2: 지점 고정
                        "fence_id"    to fenceId        // (로그/추적용)
                    )
                )
                latestQrText = qrText
                setTokenTextIfPresent(qrText) // 👈 오류가 발생했던 함수
                Log.d(TAG, "Secure QR generated (merchant=$merchantId, loc=$locId, fenceId=$fenceId)")
            } catch (t: Throwable) {
                Log.e(TAG, "QR 토큰 생성 실패", t)
                latestQrText = null
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        // 👈 [수정] onNewIntent에서도 Intent 파싱 함수 호출
        if (!parseIntent(intent)) {
            finish()
            return
        }

        // ⛔ [삭제] 기존의 컨텍스트 정보 및 정책 검사 (parseIntent로 이동)

        // ⛔ [삭제] (선택 강화) 새 intent에도 불일치 차단 (parseIntent에서 처리)

        val title   = intent?.getStringExtra(EXTRA_TITLE)   ?: getString(R.string.title_pay)
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: getString(R.string.subtitle_pay)
        showOrExpandPayChooser(title, message)
    }

    // 👈 [신규] Intent에서 비콘/컨텍스트 정보를 파싱하여 멤버 변수에 저장하는 함수
    /**
     * Intent에서 컨텍스트 정보를 파싱하고, 이 Activity를 계속 진행할지 여부를 반환합니다.
     * @return true (진행), false (종료)
     */
    private fun parseIntent(intent: Intent?): Boolean {
        if (intent == null) return false

        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "UNKNOWN"
        val geo     = intent.getBooleanExtra("geo", false)
        val beacon  = intent.getBooleanExtra("beacon", false)
        // 👈 [수정] Wi-Fi 상태는 Intent에서 받거나, 없으면 TriggerGate에서 현재 상태를 가져옴
        val wifiOk  = intent.getBooleanExtra("wifi", TriggerGate.allowedForQr())
        fenceId     = intent.getStringExtra("fenceId") ?: "unknown"

        // 👈 [수정] 정책: 3단계에서 추가한 "BEACON_SELECTED"도 허용 조건에 추가
        val allow = ((geo && beacon) || wifiOk || trigger == "USER" || trigger == "BEACON_SELECTED")
        if (!allow) {
            Log.d(TAG, "blocked: need (geo AND beacon) OR trusted Wi-Fi OR USER (trigger=$trigger, geo=$geo, beacon=$beacon, wifi=$wifiOk)")
            return false // 👈 [수정] false 반환
        }

        // 👈 [추가] Intent에서 전달된 비콘 정보를 멤버 변수에 저장
        if (beacon) {
            targetLocationId = intent.getStringExtra("beacon_locationId")
            targetMerchantId = intent.getStringExtra("beacon_merchantId")
            targetUuid       = intent.getStringExtra("beacon_uuid")
            targetMajor      = intent.getIntExtra("beacon_major", 0)
            targetMinor      = intent.getIntExtra("beacon_minor", 0)
            targetNonce      = intent.getStringExtra("beacon_nonce") // 👈 [추가] Nonce
        }

        // 👈 [수정] 지오펜스/비콘 불일치 차단 로직 (Intent 기반으로)
        if (geo && beacon) {
            val loc = targetLocationId
            if (loc != null && fenceId != "unknown" && loc != fenceId) {
                Log.w(TAG, "Geofence/Beacon mismatch → deny (beaconLoc=$loc, fenceId=$fenceId)")
                Toast.makeText(this, "지점 불일치: 결제를 진행할 수 없습니다.", Toast.LENGTH_LONG).show()
                return false // 👈 [수정] false 반환
            }
        }

        return true // 👈 [추가] 모든 검사 통과
    }


    // ─────────────────────────────────────────────────────────────────────────────
    // 보안 QR 검증(시연): 현재 비콘 메타와 토큰(payload)을 대조
    // payload = { merchant_id, location_id, nonce, expiry, ... }
    // ─────────────────────────────────────────────────────────────────────────────
    private fun verifyQrAgainstContext(rawQr: String): Boolean {
        val parsed = QrToken.parse(rawQr) ?: run {
            Log.w(TAG, "QR parse failed"); return false
        }
        val (payload, sig) = parsed

        // ⛔ [삭제] val meta = TriggerGate.getCurrentBeacon() ?: run { ... }
        // 👈 [대체] Intent로부터 받은 target 비콘 정보를 사용
        if (targetUuid == null) {
            Log.w(TAG, "No target beacon context (from Intent); deny"); return false
        }

        val pubPem = WhitelistManager.getMerchantPubKey(payload.merchantId) ?: run {
            Log.w(TAG, "No pubkey for merchant=${payload.merchantId}; deny"); return false
        }

        val msg = QrToken.normalizedMessageForSign(payload)
        if (!SignatureVerifier.verifyEcdsaP256(pubPem, msg, sig)) {
            Log.w(TAG, "Signature invalid; deny"); return false
        }

        // 위치/nonce/만료 확인
        // ⛔ [삭제] val beaconLoc = meta.locationId ?: ""
        // 👈 [대체] 멤버 변수 사용
        val beaconLoc = targetLocationId ?: ""
        if (payload.locationId != beaconLoc) {
            Log.w(TAG, "Location mismatch qr=${payload.locationId} beacon=$beaconLoc"); return false
        }
        // (선택 강화) fenceId도 알면 교차 확인
        if (fenceId != "unknown" && payload.locationId != fenceId) {
            Log.w(TAG, "Fence mismatch qrLoc=${payload.locationId} fenceId=$fenceId"); return false
        }

        // ⛔ [삭제] val beaconNonce = meta.nonce ?: ""
        // 👈 [대체] 멤버 변수(targetNonce) 사용
        val beaconNonce = targetNonce ?: ""
        if (payload.nonce != beaconNonce) {
            // (참고: 현재 원본 코드에서는 비콘 광고에서 Nonce를 파싱하는 부분이 구현되어 있지 않아
            //  targetNonce가 항상 null일 수 있습니다. 이 검증을 사용하려면 BeaconMonitor에서
            //  광고(Adv) 패킷을 파싱하여 Nonce를 추출하고 TriggerGate까지 전달해야 합니다.)
            Log.w(TAG, "Nonce mismatch qr=${payload.nonce} beacon=$beaconNonce (targetNonce)"); return false
        }
        val nowSec = System.currentTimeMillis() / 1000
        if (payload.expiry < nowSec) {
            Log.w(TAG, "Expired token; deny"); return false
        }

        Log.d(TAG, "QR verify OK (merchant=${payload.merchantId}, loc=${payload.locationId}, fence=$fenceId)")
        return true
    }

    // ── 바텀시트 (결제 선택) ─────────────────────────────────────────
    private fun showOrExpandPayChooser(title: String, message: String) {
        dialog?.let { existing ->
            existing.findViewById<TextView>(R.id.tvTitle)?.text = title
            existing.findViewById<TextView>(R.id.tvSubtitle)?.text = message
            val sheet = existing.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
            sheetView = sheet
            latestQrText?.let { setTokenTextIfPresent(it) } // 👈 오류가 발생했던 함수
            return
        }

        val d = BottomSheetDialog(this)
        d.setContentView(R.layout.dialog_pay_chooser)
        d.setDismissWithAnimation(true)

        d.setOnShowListener {
            val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
            sheetView = sheet

            d.findViewById<TextView>(R.id.tvTitle)?.text = title
            d.findViewById<TextView>(R.id.tvSubtitle)?.text = message
            latestQrText?.let { setTokenTextIfPresent(it) } // 👈 오류가 발생했던 함수

            d.findViewById<View>(R.id.btnKakao)?.setOnClickListener {
                d.dismiss(); showKakaoPreview() // 👈 오류가 발생했던 함수
            }
            d.findViewById<View>(R.id.btnNaver)?.setOnClickListener {
                d.dismiss(); showNaverPreview() // 👈 오류가 발생했던 함수
            }
            // Toss 버튼 → 따릉이 앱 유도
            d.findViewById<View>(R.id.btnToss)?.setOnClickListener {
                d.dismiss(); openTtareungi(); finish() // 👈 오류가 발생했던 함수
            }
            d.findViewById<View>(R.id.btnInApp)?.setOnClickListener {
                d.dismiss(); openInAppScanner(); finish() // 👈 오류가 발생했던 함수
            }
            d.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
                d.dismiss(); finish()
            }
        }

        d.setOnCancelListener { finish() }
        d.show()
        dialog = d
    }

    //
    // 👈 [추가]
    //
    //
    //
    // 여기서부터 원본 파일에 있던 헬퍼 함수들입니다.
    //
    //
    //
    //

    // ── QR 이미지(리소스) 길게 눌러 디코딩/검증 ─────────────────────────
    private fun decodeQrFromDrawable(
        @DrawableRes resId: Int,
        onDone: (String?) -> Unit
    ) {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeResource(resources, resId, opts) ?: run { onDone(null); return }
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_AZTEC, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_PDF417)
            .build()
        val scanner = BarcodeScanning.getClient(options)
        val image = InputImage.fromBitmap(bmp, 0)

        scanner.process(image)
            .addOnSuccessListener { list -> onDone(list.firstOrNull()?.rawValue) }
            .addOnFailureListener { onDone(null) }
    }

    private fun openUrlPreferBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) { Log.w(TAG, "openUrlPreferBrowser failed: $url") }
    }

    /** 레이아웃에 tvToken id가 있을 때만 표시 */
    private fun setTokenTextIfPresent(text: String) {
        val root = sheetView ?: return
        val tv = root.findViewById<TextView?>(R.id.tvToken) ?: return
        tv.text = text
        tv.visibility = View.VISIBLE
    }

    private fun showPreview(
        @DrawableRes imgRes: Int,
        onClick: () -> Unit
    ) {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_qr_preview, null, false)
        dialog.setContentView(v)

        val img = v.findViewById<ImageView>(R.id.imgPreview)
        img.setImageResource(imgRes)
        img.setOnClickListener { onClick() }

        img.setOnLongClickListener { view ->
            view.alpha = 0.4f
            Toast.makeText(this, "QR을 분석 중입니다…", Toast.LENGTH_SHORT).show()
            decodeQrFromDrawable(imgRes) { raw ->
                runOnUiThread {
                    view.alpha = 1.0f
                    if (raw == null) {
                        Toast.makeText(this, "QR을 인식하지 못했습니다.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    val isSecureCandidate = raw.contains(".")
                    val verified = if (isSecureCandidate) verifyQrAgainstContext(raw) else false
                    when {
                        verified -> {
                            dialog.dismiss()
                            Toast.makeText(this, "검증 통과: 안전한 결제 QR", Toast.LENGTH_SHORT).show()
                            showOrExpandPayChooser(getString(R.string.title_pay), getString(R.string.subtitle_pay))
                        }
                        raw.startsWith("http://") || raw.startsWith("https://") -> {
                            dialog.dismiss()
                            Toast.makeText(this, "일반 QR: 웹으로 이동합니다", Toast.LENGTH_SHORT).show()
                            openUrlPreferBrowser(raw)
                        }
                        else -> Toast.makeText(this, "검증 실패 또는 지원하지 않는 QR입니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }
        dialog.setOnCancelListener { /* no-op */ }
        dialog.show()
    }

    private fun showKakaoPreview() = showPreview(
        R.drawable.kakao_qr,
        onClick = { /* 시연은 길게 눌러 검증 */ }
    )

    private fun showNaverPreview() = showPreview(
        R.drawable.naver_qr,
        onClick = { /* 시연은 길게 눌러 검증 */ }
    )

    private fun openAccountQr() {
        val token = latestQrText.orEmpty()
        startActivity(Intent(this, AccountQrActivity::class.java).putExtra("qr_token", token))
    }

    private fun openInAppScanner() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("openScanner", true)
        )
    }

    private fun openKakaoPay() {
        val pkgKakaoPay = "com.kakaopay.app"
        val pkgKakaoTalk = "com.kakao.talk"
        val tryUris = listOf("kakaotalk://kakaopay/qr", "kakaotalk://kakaopay/home")
        for (u in tryUris) {
            try { startActivity(Intent(Intent.ACTION_VIEW, u.toUri())); return } catch (_: Exception) {}
        }
        if (launchPackage(pkgKakaoPay)) return
        if (launchPackage(pkgKakaoTalk)) return
        openStoreOrWeb(pkgKakaoPay, "카카오페이")
    }

    private fun openNaverPay() {
        val qrScheme = "naversearchapp://search?qmenu=qrcode&version=1"
        try { startActivity(Intent(Intent.ACTION_VIEW, qrScheme.toUri())); return } catch (_: Exception) {}
        val naverQrHome = "https%3A%2F%2Fm.pay.naver.com%2Fqr%2Fhome"
        val inApp = "naversearchapp://inappbrowser?url=$naverQrHome&target=inpage&version=6"
        try { startActivity(Intent(Intent.ACTION_VIEW, inApp.toUri())); return } catch (_: Exception) {}
        val pkgNaver = "com.nhn.android.search"
        if (launchPackage(pkgNaver)) return
        openStoreOrWeb(pkgNaver, "네이버")
    }

    /** Toss 버튼을 눌렀을 때 따릉이 앱으로 유도 */
    private fun openTtareungi() {
        val pkg = "com.dki.spb_android" // 서울자전거(따릉이)
        if (launchPackage(pkg)) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri()))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri()))
        }
    }

    private fun openTossPay() {
        val tryUris = listOf(
            "supertoss://toss/pay",
            "supertoss://toss/home",
            "supertoss://scan",
            "toss://scan"
        )
        for (u in tryUris) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, u.toUri())
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setPackage("viva.republica.toss")
                startActivity(intent)
                return
            } catch (_: Exception) { /* try next */ }
        }
        val pkgToss = "viva.republica.toss"
        if (launchPackage(pkgToss)) return
        openStoreOrWeb(pkgToss, "토스")
    }

    private fun launchPackage(packageName: String): Boolean = try {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            true
        } else false
    } catch (_: Exception) { false }

    private fun openStoreOrWeb(packageName: String, storeQuery: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, "market://search?q=$storeQuery".toUri()))
            }
        }
    }

    override fun onDestroy() {
        dialog?.setOnShowListener(null)
        dialog?.setOnCancelListener(null)
        dialog = null
        sheetView = null
        super.onDestroy()
    }
}