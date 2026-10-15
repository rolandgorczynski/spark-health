package local.spark.steps

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

const val POLICY = "Steps only. Synthetic mode requires no permission or phone data. Optional Health Connect preview reads the last 24 hours from the exact package you supply, only while this screen is visible. Verify the package and Mi Fitness export on your phone first; this prototype makes no Mi Fitness availability claim. Preview shows source package and record counts, never step measurements or record IDs. No data leaves this app: no Internet permission, server, token, analytics or persistent storage. Synthetic events use a volatile fake outbox. Leaving the screen or withdrawing consent clears it. Raw records can overlap and are not deduplicated daily totals. A future server export requires separate consent and secure transport. Revoking access cannot erase data previously exported by another version."

class RationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = POLICY; setPadding(24, 48, 24, 24) })
    }
}

class MainActivity : ComponentActivity() {
    private val scopes = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private val outbox = FakeOutbox()
    private lateinit var status: TextView
    private lateinit var consent: CheckBox
    private lateinit var origin: EditText
    private var job: Job? = null
    private val permissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        status.text = if (it.containsAll(scopes)) "Steps permission granted. Press preview to read." else "Steps permission denied; nothing read."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(layout) }
        layout.addView(TextView(this).apply { text = POLICY })
        status = TextView(this).apply { text = "Synthetic mode ready; no Health Connect access." }
        origin = EditText(this).apply {
            hint = "Owner-verified Health Connect origin package"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        consent = CheckBox(this).apply { text = "I consent to a foreground steps preview from this package" }
        layout.addView(origin)
        layout.addView(consent)
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Run synthetic fixture") {
            outbox.clear()
            outbox.enqueue(Fixtures.steps())
            val transport = FakeTransport(listOf(null, 201))
            outbox.deliver(transport)
            outbox.deliver(transport)
            status.text = "Synthetic origin: ${Fixtures.ORIGIN}; records: 1; fake queue: ${outbox.size}; acknowledged: ${outbox.snapshot().count { it.state == FakeOutbox.State.ACKNOWLEDGED }}. No network."
        }
        button("Request steps permission") {
            if (!consent.isChecked) status.text = "Consent required."
            else if (providerAvailable()) permissions.launch(scopes)
        }
        button("Preview last 24 hours") { preview() }
        button("Withdraw preview consent / clear") {
            consent.isChecked = false
            job?.cancel()
            outbox.clear()
            status.text = "Cleared. Revoke system access in Health Connect settings if desired."
        }
        consent.setOnCheckedChangeListener { _, checked ->
            if (!checked) { job?.cancel(); outbox.clear(); status.text = "Consent withdrawn; memory cleared." }
        }
        layout.addView(status)
        setContentView(scroll)
    }
    private fun providerAvailable(): Boolean {
        val available = HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE
        if (!available) status.text = "Health Connect unavailable or update required. Synthetic mode remains available."
        return available
    }
    private fun preview() {
        if (job?.isActive == true) return
        val selected = origin.text.toString().trim()
        if (!consent.isChecked || !selected.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
            status.text = "Consent and an owner-verified origin package are required."
            return
        }
        if (!providerAvailable()) return
        job = lifecycleScope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val end = Instant.now()
                val start = end.minus(Duration.ofHours(24))
                var count = 0
                var invalid = 0
                readPages(selected, active = {
                    consent.isChecked && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        client.permissionController.getGrantedPermissions().containsAll(scopes)
                }, page = { token ->
                    val response = client.readRecords(ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        dataOriginFilter = setOf(DataOrigin(selected)),
                        pageSize = 100,
                        pageToken = token
                    ))
                    response.records.map { SourceSteps(it.metadata.dataOrigin.packageName,
                        it.metadata.id, it.startTime, it.endTime, it.count) } to response.pageToken
                }, consume = { records ->
                    records.forEach {
                        try { StepMapper.map(it); count++ } catch (_: IllegalArgumentException) { invalid++ }
                    }
                })
                status.text = "Requested origin: $selected; matching records: $count; invalid: $invalid. Window: last 24 hours. No records means absent in this window, not zero steps. Preview discarded; nothing queued or sent."
            } catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { status.text = "Steps permission denied or revoked; preview stopped." }
            catch (_: Exception) { status.text = "Preview stopped: provider/read unavailable or permission/foreground lost. Retry explicitly." }
        }
    }
    override fun onPause() {
        job?.cancel()
        outbox.clear()
        if (::status.isInitialized) status.text = "Screen left; preview stopped and fake memory cleared."
        super.onPause()
    }
}
