package `in`.itantra.offline.session

import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local monotonic observations, not inferred cross-device mouth-to-ear measurements. */
class SessionMetrics(root: File) {
    val file = File(root.apply { mkdirs() }, "${UUID.randomUUID()}.jsonl")
    @Synchronized fun record(stage: String, value: Long, stream: Long? = null) {
        file.appendText(JSONObject().put("stage", stage).put("value", value)
            .put("observedAtNs", SystemClock.elapsedRealtimeNanos()).put("stream", stream ?: JSONObject.NULL).toString() + "\n")
    }
}
