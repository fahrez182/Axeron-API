package frb.axeron.shell

/**
 * Public only because AxManager's own modules (manager, server) reach it across module bounds,
 * where Kotlin's `internal` can't. Not for apps: it changes with AxManager, without notice.
 *
 * AxManager's builds opt in for every module of theirs (`optIn` in the root build). An app that
 * uses one gets a compile error; it should use the public API instead (Axsh, AxBroker.start,
 * AxShellService). Java callers aren't checked.
 */
@RequiresOptIn(
    message = "AxManager's own API, not for apps: it changes without notice. " +
            "Use Axsh, AxBroker.start or AxShellService instead.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS
)
annotation class InternalAxeronApi
