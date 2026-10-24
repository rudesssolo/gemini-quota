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
    is UsageException.AuthRequired -> ErrorKind.AUTH
    is UsageException.RateLimited -> ErrorKind.RATE_LIMITED
    is UsageException.Network -> ErrorKind.NETWORK
    is UsageException.Server -> ErrorKind.SERVER
    is UsageException.ProtocolChanged -> ErrorKind.PROTOCOL
    else -> ErrorKind.UNKNOWN
}
