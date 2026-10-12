package com.framework.innolive.feature.live.direct

/**
 * 플랫폼 스트림 키가 들어 있는 RTMP(S) ingest 주소입니다.
 *
 * 키는 방송마다 바뀌지 않아 유출되면 채널로 언제든 송출할 수 있습니다. 문자열 변환,
 * 로그, 예외 메시지에 값을 남기지 않으며 디스크에 저장하지 않습니다.
 */
internal class IngestUrl private constructor(private val value: String) {
    val isSecure: Boolean get() = value.startsWith(RTMPS_PREFIX)

    /** RTMP 연결을 여는 곳에만 넘깁니다. */
    fun reveal(): String = value

    override fun equals(other: Any?): Boolean = other is IngestUrl && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "IngestUrl(<redacted>)"

    companion object {
        private const val RTMP_PREFIX = "rtmp://"
        private const val RTMPS_PREFIX = "rtmps://"

        /**
         * 서버 응답 값을 검증합니다. 실패 메시지와 원인에도 입력 값을 싣지 않으려고
         * URI 파서를 쓰지 않습니다.
         */
        fun parse(raw: Any?): IngestUrl {
            val text = (raw as? String)?.trim()
            require(!text.isNullOrEmpty()) { "ingest URL이 없습니다." }
            val rest = when {
                text.startsWith(RTMPS_PREFIX) -> text.removePrefix(RTMPS_PREFIX)
                text.startsWith(RTMP_PREFIX) -> text.removePrefix(RTMP_PREFIX)
                else -> throw IllegalArgumentException("ingest URL이 RTMP(S) 주소가 아닙니다.")
            }
            require(text.none(Char::isWhitespace)) { "ingest URL 형식이 올바르지 않습니다." }
            val host = rest.substringBefore('/')
            val path = rest.substringAfter('/', missingDelimiterValue = "")
            require(host.isNotEmpty() && path.isNotEmpty() && !path.endsWith('/')) {
                "ingest URL 형식이 올바르지 않습니다."
            }
            return IngestUrl(text)
        }
    }
}
