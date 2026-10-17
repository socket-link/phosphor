package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The web targets decode only; everything else in `commonTest` decodes here through [encodeForTest]. */
class WebDecodeOnlyTest {
    @Test
    fun `encode refuses on the web`() {
        assertFailsWith<UnsupportedOperationException> {
            TraceCodec.encode(
                TraceTestData.sampleTrace(formatVersion = 3),
            )
        }
    }
}
