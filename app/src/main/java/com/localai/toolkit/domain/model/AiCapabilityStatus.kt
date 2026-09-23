package com.localai.toolkit.domain.model

/**
 * Runtime availability of an [AiTask] on *this* device.
 *
 * These values are always derived from a runtime API call, never from manufacturer or
 * model string matching.
 */
enum class AiCapabilityStatus {
    /** Ready to run right now. */
    AVAILABLE,

    /** Supported, but the on-device model still has to be fetched. */
    DOWNLOADABLE,

    /** A download is in flight. */
    DOWNLOADING,

    /** The hardware or system image cannot run this feature at all. */
    UNSUPPORTED,

    /** Supported, but blocked for now (busy engine, quota, background restriction). */
    TEMPORARILY_UNAVAILABLE,

    /** Availability could not be determined because the check itself failed. */
    ERROR,

    /** No check has completed yet. */
    UNKNOWN;

    val isUsable: Boolean get() = this == AVAILABLE

    /** True when a user-initiated download is the next sensible step. */
    val needsDownload: Boolean get() = this == DOWNLOADABLE
}
