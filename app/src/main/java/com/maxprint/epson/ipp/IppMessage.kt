package com.maxprint.epson.ipp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** One IPP value. Collections are kept as nested attribute maps. */
sealed class IppValue {
    data class Num(val value: Int) : IppValue()
    data class Flag(val value: Boolean) : IppValue()
    data class Text(val value: String) : IppValue()
    data class Resolution(val x: Int, val y: Int, val units: Int) : IppValue() {
        /** units 3 = dots per inch, 4 = dots per centimetre. */
        val dpiX: Int get() = if (units == 4) (x * 2.54).toInt() else x
        val dpiY: Int get() = if (units == 4) (y * 2.54).toInt() else y
    }

    data class Range(val lower: Int, val upper: Int) : IppValue()
    data class Collection(val members: Map<String, IppAttribute>) : IppValue()
    data class Raw(val tag: Int, val bytes: ByteArray) : IppValue() {
        override fun equals(other: Any?) =
            other is Raw && other.tag == tag && other.bytes.contentEquals(bytes)

        override fun hashCode() = 31 * tag + bytes.contentHashCode()
    }
}

data class IppAttribute(val tag: Int, val name: String, val values: List<IppValue>) {
    fun asStrings(): List<String> = values.mapNotNull {
        when (it) {
            is IppValue.Text -> it.value
            is IppValue.Num -> it.value.toString()
            else -> null
        }
    }

    fun firstString(): String? = asStrings().firstOrNull()

    fun firstInt(): Int? = values.filterIsInstance<IppValue.Num>().firstOrNull()?.value

    fun firstBool(): Boolean? = values.filterIsInstance<IppValue.Flag>().firstOrNull()?.value

    fun resolutions(): List<IppValue.Resolution> = values.filterIsInstance<IppValue.Resolution>()
}

/** Builds the binary form of an IPP request (RFC 8010). */
class IppRequestBuilder(private val operationId: Int, private val requestId: Int) {

    private val buf = ByteArrayOutputStream(1024)
    private var inGroup = false

    init {
        // version-number 2.0, operation-id, request-id
        buf.write(0x02); buf.write(0x00)
        writeShort(operationId)
        writeInt(requestId)
    }

    fun group(tag: Int): IppRequestBuilder = apply {
        buf.write(tag)
        inGroup = true
    }

    /**
     * Every request must open with these three, in this order, or conforming printers
     * reject it outright (RFC 8011 section 4.1.4).
     */
    fun operationHeader(printerUri: String, user: String = "android"): IppRequestBuilder = apply {
        group(IppTag.OPERATION_ATTRS)
        attr(IppTag.CHARSET, "attributes-charset", "utf-8")
        attr(IppTag.NATURAL_LANGUAGE, "attributes-natural-language", "en")
        attr(IppTag.URI, "printer-uri", printerUri)
        attr(IppTag.NAME, "requesting-user-name", user)
    }

    fun attr(tag: Int, name: String, value: String): IppRequestBuilder = apply {
        writeAttrHeader(tag, name)
        writeBytes(value.toByteArray(Charsets.UTF_8))
    }

    fun attr(tag: Int, name: String, values: List<String>): IppRequestBuilder = apply {
        values.forEachIndexed { i, v ->
            writeAttrHeader(tag, if (i == 0) name else "")
            writeBytes(v.toByteArray(Charsets.UTF_8))
        }
    }

    fun attrInt(name: String, value: Int, tag: Int = IppTag.INTEGER): IppRequestBuilder = apply {
        writeAttrHeader(tag, name)
        writeShort(4)
        writeInt(value)
    }

    fun attrBool(name: String, value: Boolean): IppRequestBuilder = apply {
        writeAttrHeader(IppTag.BOOLEAN, name)
        writeShort(1)
        buf.write(if (value) 1 else 0)
    }

    fun attrResolution(name: String, x: Int, y: Int, units: Int = 3): IppRequestBuilder = apply {
        writeAttrHeader(IppTag.RESOLUTION, name)
        writeShort(9)
        writeInt(x)
        writeInt(y)
        buf.write(units)
    }

    fun beginCollection(name: String): IppRequestBuilder = apply {
        writeAttrHeader(IppTag.BEG_COLLECTION, name)
        writeShort(0)
    }

    fun member(name: String, tag: Int, value: String): IppRequestBuilder = apply {
        writeAttrHeader(IppTag.MEMBER_ATTR_NAME, "")
        writeBytes(name.toByteArray(Charsets.UTF_8))
        writeAttrHeader(tag, "")
        writeBytes(value.toByteArray(Charsets.UTF_8))
    }

    fun memberInt(name: String, value: Int): IppRequestBuilder = apply {
        writeAttrHeader(IppTag.MEMBER_ATTR_NAME, "")
        writeBytes(name.toByteArray(Charsets.UTF_8))
        writeAttrHeader(IppTag.INTEGER, "")
        writeShort(4)
        writeInt(value)
    }

    fun endCollection(): IppRequestBuilder = apply {
        writeAttrHeader(IppTag.END_COLLECTION, "")
        writeShort(0)
    }

    fun build(): ByteArray {
        buf.write(IppTag.END_OF_ATTRS)
        return buf.toByteArray()
    }

    private fun writeAttrHeader(tag: Int, name: String) {
        buf.write(tag)
        writeBytes(name.toByteArray(Charsets.UTF_8))
    }

    private fun writeBytes(b: ByteArray) {
        writeShort(b.size)
        buf.write(b, 0, b.size)
    }

    private fun writeShort(v: Int) {
        buf.write((v ushr 8) and 0xFF)
        buf.write(v and 0xFF)
    }

    private fun writeInt(v: Int) {
        buf.write((v ushr 24) and 0xFF)
        buf.write((v ushr 16) and 0xFF)
        buf.write((v ushr 8) and 0xFF)
        buf.write(v and 0xFF)
    }
}

class IppResponse(
    val statusCode: Int,
    val requestId: Int,
    val attributes: Map<String, IppAttribute>
) {
    val isSuccess: Boolean get() = IppStatus.isSuccess(statusCode)
    val statusText: String get() = IppStatus.describe(statusCode)

    operator fun get(name: String): IppAttribute? = attributes[name]

    fun strings(name: String): List<String> = attributes[name]?.asStrings() ?: emptyList()

    fun string(name: String): String? = attributes[name]?.firstString()

    fun int(name: String): Int? = attributes[name]?.firstInt()

    override fun toString() = "IppResponse($statusText, ${attributes.size} attrs)"
}

object IppParser {

    @Throws(IOException::class)
    fun parse(source: InputStream): IppResponse {
        val input = DataInputStream(source)
        input.readUnsignedByte() // major version
        input.readUnsignedByte() // minor version
        val status = input.readUnsignedShort()
        val requestId = input.readInt()

        val attributes = LinkedHashMap<String, IppAttribute>()
        var currentName: String? = null
        var currentTag = 0
        var currentValues = ArrayList<IppValue>()
        // Nested collections: each entry is the map being filled plus the attribute name it
        // will be stored under once END_COLLECTION arrives.
        val collectionStack = ArrayList<Pair<String, LinkedHashMap<String, IppAttribute>>>()
        var pendingMemberName: String? = null

        fun flush() {
            val name = currentName ?: return
            val attr = IppAttribute(currentTag, name, currentValues.toList())
            if (collectionStack.isEmpty()) attributes[name] = attr
            else collectionStack.last().second[name] = attr
            currentName = null
            currentValues = ArrayList()
        }

        loop@ while (true) {
            val tag = try {
                input.readUnsignedByte()
            } catch (e: EOFException) {
                break@loop
            }

            if (tag < 0x10) {
                // Delimiter (or end of attributes).
                flush()
                if (tag == IppTag.END_OF_ATTRS) break@loop
                continue@loop
            }

            val nameLen = input.readUnsignedShort()
            val name = if (nameLen > 0) readString(input, nameLen) else null
            val valueLen = input.readUnsignedShort()
            val value = ByteArray(valueLen)
            input.readFully(value)

            when (tag) {
                IppTag.MEMBER_ATTR_NAME -> {
                    flushMember(collectionStack, pendingMemberName, currentTag, currentValues)
                    currentValues = ArrayList()
                    pendingMemberName = String(value, Charsets.UTF_8)
                    currentName = null
                }

                IppTag.BEG_COLLECTION -> {
                    if (name != null) {
                        flush()
                        collectionStack.add(name to LinkedHashMap())
                    } else if (pendingMemberName != null) {
                        collectionStack.add(pendingMemberName to LinkedHashMap())
                        pendingMemberName = null
                    }
                }

                IppTag.END_COLLECTION -> {
                    flushMember(collectionStack, pendingMemberName, currentTag, currentValues)
                    currentValues = ArrayList()
                    pendingMemberName = null
                    val (collName, members) = collectionStack.removeAt(collectionStack.size - 1)
                    val attr = IppAttribute(
                        IppTag.BEG_COLLECTION,
                        collName,
                        listOf(IppValue.Collection(members))
                    )
                    if (collectionStack.isEmpty()) {
                        // Repeated collections (media-col-database) share one name; merge them.
                        val existing = attributes[collName]
                        attributes[collName] =
                            if (existing == null) attr
                            else existing.copy(values = existing.values + attr.values)
                    } else {
                        val parent = collectionStack.last().second
                        val existing = parent[collName]
                        parent[collName] =
                            if (existing == null) attr
                            else existing.copy(values = existing.values + attr.values)
                    }
                }

                else -> {
                    val decoded = decodeValue(tag, value)
                    if (name != null) {
                        flush()
                        currentName = name
                        currentTag = tag
                        currentValues = arrayListOf(decoded)
                    } else {
                        // nameLen == 0 means "another value for the attribute just seen".
                        currentTag = tag
                        currentValues.add(decoded)
                    }
                }
            }
        }
        flush()
        return IppResponse(status, requestId, attributes)
    }

    private fun flushMember(
        stack: List<Pair<String, LinkedHashMap<String, IppAttribute>>>,
        memberName: String?,
        tag: Int,
        values: List<IppValue>
    ) {
        if (memberName == null || values.isEmpty() || stack.isEmpty()) return
        stack.last().second[memberName] = IppAttribute(tag, memberName, values)
    }

    private fun decodeValue(tag: Int, raw: ByteArray): IppValue = when (tag) {
        IppTag.INTEGER, IppTag.ENUM -> IppValue.Num(readInt(raw, 0))
        IppTag.BOOLEAN -> IppValue.Flag(raw.isNotEmpty() && raw[0].toInt() != 0)
        IppTag.RESOLUTION -> if (raw.size >= 9) {
            IppValue.Resolution(readInt(raw, 0), readInt(raw, 4), raw[8].toInt() and 0xFF)
        } else IppValue.Raw(tag, raw)

        IppTag.RANGE_OF_INTEGER -> if (raw.size >= 8) {
            IppValue.Range(readInt(raw, 0), readInt(raw, 4))
        } else IppValue.Raw(tag, raw)

        IppTag.TEXT_WITH_LANG, IppTag.NAME_WITH_LANG -> {
            // [langLen][lang][textLen][text]
            if (raw.size >= 4) {
                val langLen = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
                val off = 2 + langLen
                if (raw.size >= off + 2) {
                    val textLen = ((raw[off].toInt() and 0xFF) shl 8) or (raw[off + 1].toInt() and 0xFF)
                    IppValue.Text(String(raw, off + 2, minOf(textLen, raw.size - off - 2), Charsets.UTF_8))
                } else IppValue.Raw(tag, raw)
            } else IppValue.Raw(tag, raw)
        }

        else -> if (IppTag.isString(tag)) IppValue.Text(String(raw, Charsets.UTF_8))
        else IppValue.Raw(tag, raw)
    }

    private fun readInt(b: ByteArray, off: Int): Int {
        if (b.size < off + 4) return 0
        return ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
    }

    private fun readString(input: DataInputStream, len: Int): String {
        val b = ByteArray(len)
        input.readFully(b)
        return String(b, Charsets.UTF_8)
    }
}
