package com.maxprint.epson.ipp

/** Tag values from RFC 8010 section 3.5. */
object IppTag {
    // Delimiter tags
    const val OPERATION_ATTRS = 0x01
    const val JOB_ATTRS = 0x02
    const val END_OF_ATTRS = 0x03
    const val PRINTER_ATTRS = 0x04
    const val UNSUPPORTED_ATTRS = 0x05

    // Out-of-band values
    const val UNSUPPORTED_VALUE = 0x10
    const val UNKNOWN = 0x12
    const val NO_VALUE = 0x13

    // Value tags
    const val INTEGER = 0x21
    const val BOOLEAN = 0x22
    const val ENUM = 0x23
    const val OCTET_STRING = 0x30
    const val DATE_TIME = 0x31
    const val RESOLUTION = 0x32
    const val RANGE_OF_INTEGER = 0x33
    const val BEG_COLLECTION = 0x34
    const val TEXT_WITH_LANG = 0x35
    const val NAME_WITH_LANG = 0x36
    const val END_COLLECTION = 0x37
    const val TEXT = 0x41
    const val NAME = 0x42
    const val KEYWORD = 0x44
    const val URI = 0x45
    const val URI_SCHEME = 0x46
    const val CHARSET = 0x47
    const val NATURAL_LANGUAGE = 0x48
    const val MIME_MEDIA_TYPE = 0x49
    const val MEMBER_ATTR_NAME = 0x4A

    fun isString(tag: Int): Boolean = tag in 0x40..0x4F || tag == OCTET_STRING
}

/** Operation ids from RFC 8011 section 4.4.15. */
object IppOp {
    const val PRINT_JOB = 0x0002
    const val VALIDATE_JOB = 0x0004
    const val CREATE_JOB = 0x0005
    const val SEND_DOCUMENT = 0x0006
    const val CANCEL_JOB = 0x0008
    const val GET_JOB_ATTRIBUTES = 0x0009
    const val GET_PRINTER_ATTRIBUTES = 0x000B
    const val IDENTIFY_PRINTER = 0x003C
}

object IppStatus {
    const val OK = 0x0000
    const val OK_IGNORED_OR_SUBSTITUTED = 0x0001
    const val OK_CONFLICTING = 0x0002
    const val CLIENT_ERROR_BAD_REQUEST = 0x0400
    const val CLIENT_ERROR_NOT_FOUND = 0x0406
    const val CLIENT_ERROR_DOCUMENT_FORMAT_NOT_SUPPORTED = 0x040A

    fun isSuccess(code: Int): Boolean = code in 0x0000..0x00FF

    fun describe(code: Int): String = when (code) {
        OK -> "successful-ok"
        OK_IGNORED_OR_SUBSTITUTED -> "successful-ok-ignored-or-substituted-attributes"
        OK_CONFLICTING -> "successful-ok-conflicting-attributes"
        0x0401 -> "client-error-forbidden"
        0x0402 -> "client-error-not-authenticated"
        0x0403 -> "client-error-not-authorized"
        0x0405 -> "client-error-not-possible"
        0x0406 -> "client-error-not-found"
        0x0407 -> "client-error-gone"
        0x0408 -> "client-error-request-entity-too-large"
        0x040A -> "client-error-document-format-not-supported"
        0x040B -> "client-error-attributes-or-values-not-supported"
        0x040C -> "client-error-uri-scheme-not-supported"
        0x0500 -> "server-error-internal-error"
        0x0501 -> "server-error-operation-not-supported"
        0x0502 -> "server-error-service-unavailable"
        0x0505 -> "server-error-busy"
        0x0506 -> "server-error-job-canceled"
        0x0509 -> "server-error-printer-is-deactivated"
        else -> "ipp-status-0x%04X".format(code)
    }
}

/** `job-state` values, RFC 8011 section 5.3.7. */
object IppJobState {
    const val PENDING = 3
    const val PENDING_HELD = 4
    const val PROCESSING = 5
    const val PROCESSING_STOPPED = 6
    const val CANCELED = 7
    const val ABORTED = 8
    const val COMPLETED = 9

    fun isTerminal(state: Int) = state >= CANCELED
}
