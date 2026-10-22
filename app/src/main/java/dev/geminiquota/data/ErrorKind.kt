package dev.geminiquota.data

/** Coarse error categories shared by the widget, the app and stored state. */
enum class ErrorKind {
    AUTH,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    PROTOCOL,
    UNKNOWN,
}

internal fun Throwable.toErrorKind(): ErrorKind = when (this) {
    is GeminiUsageException.AuthRequired -> ErrorKind.AUTH
    is GeminiUsageException.RateLimited -> ErrorKind.RATE_LIMITED
    is GeminiUsageException.Network -> ErrorKind.NETWORK
    is GeminiUsageException.Server -> ErrorKind.SERVER
    is GeminiUsageException.ProtocolChanged -> ErrorKind.PROTOCOL
    else -> ErrorKind.UNKNOWN
}
