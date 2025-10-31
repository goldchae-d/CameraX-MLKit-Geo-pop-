package com.example.camerax_mlkit

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.camerax_mlkit.security.WhitelistManager

/**
 * [신규] 2개 이상의 비콘이 감지되었을 때 사용자에게 선택창을 보여주는 Activity.
 * (TriggerGate에서 호출됨)
 */
class BeaconSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_beacon_selection)

        // 잠금화면 등에서 보이도록 설정 (PaymentPromptActivity와 동일)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // TriggerGate가 Intent로 보낸 매장 이름과 식별자(Key) 목록을 받음
        val names = intent.getStringArrayExtra("BEACON_NAMES") ?: emptyArray()
        val keys = intent.getStringArrayExtra("BEACON_KEYS") ?: emptyArray()

        if (names.isEmpty() || keys.isEmpty() || names.size != keys.size) {
            finish() // 유효하지 않은 데이터면 즉시 종료
            return
        }

        val listView = findViewById<ListView>(R.id.listViewBeacons)
        // ListView에 매장 이름 목록을 표시
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.adapter = adapter

        // 사용자가 매장을 선택했을 때
        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedKey = keys[position] // "UUID|Major|Minor"
            val parts = selectedKey.split("|")
            if (parts.size != 3) return@setOnItemClickListener

            val uuid = parts[0]
            val major = parts[1].toIntOrNull() ?: 0
            val minor = parts[2].toIntOrNull() ?: 0

            // Whitelist에서 선택된 비콘의 전체 정보(locationId, merchantId 등)를 다시 조회
            val entry = WhitelistManager.findBeacon(uuid, major, minor)

            // [중요] 4단계에서 수정할 PaymentPromptActivity를 실행
            // 이제 TriggerGate의 전역 상태가 아닌,
            // 사용자가 "선택한" 비콘의 정보를 명시적으로 Intent에 담아 전달
            val intent = Intent(this, PaymentPromptActivity::class.java).apply {
                putExtra(PaymentPromptActivity.EXTRA_TITLE, entry?.name ?: "결제 안내")
                putExtra(PaymentPromptActivity.EXTRA_MESSAGE, "매장이 감지되었습니다.")
                putExtra(PaymentPromptActivity.EXTRA_TRIGGER, "BEACON_SELECTED") // "사용자가 선택함"

                // 선택된 비콘의 상세 정보 전달
                putExtra("beacon", true)
                putExtra("beacon_name", entry?.name)
                putExtra("beacon_locationId", entry?.locationId)
                putExtra("beacon_merchantId", entry?.merchantId)
                putExtra("beacon_uuid", uuid)
                putExtra("beacon_major", major)
                putExtra("beacon_minor", minor)

                // 현재의 Geo/Wi-Fi 상태도 보조적으로 전달
                putExtra("wifi", TriggerGate.allowedForQr())
                putExtra("geo", TriggerGate.evaluatePolicy().first)

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            finish() // 선택창은 닫기
        }

        findViewById<TextView>(R.id.btnCancel).setOnClickListener {
            finish() // 취소
        }
    }
}