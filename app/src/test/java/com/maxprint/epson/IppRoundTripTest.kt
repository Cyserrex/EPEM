package com.maxprint.epson

import com.maxprint.epson.ipp.IppOp
import com.maxprint.epson.ipp.IppParser
import com.maxprint.epson.ipp.IppRequestBuilder
import com.maxprint.epson.ipp.IppTag
import com.maxprint.epson.ipp.IppValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The encoder and the parser implement the same spec from opposite ends, so feeding one
 * into the other catches tag, length and multi-value mistakes without a printer present.
 */
class IppRoundTripTest {

    @Test
    fun operationAttributesSurviveTheRoundTrip() {
        val bytes = IppRequestBuilder(IppOp.GET_PRINTER_ATTRIBUTES, 42)
            .operationHeader("ipp://192.168.1.7:631/ipp/print", "tester")
            .attr(IppTag.KEYWORD, "requested-attributes", listOf("media-supported", "sides-supported"))
            .attrInt("copies", 3)
            .attrBool("ipp-attribute-fidelity", true)
            .attrResolution("printer-resolution", 600, 600)
            .build()

        // A request and a response share the same body encoding; only the two bytes after
        // the version differ in meaning (operation id versus status code).
        val parsed = IppParser.parse(ByteArrayInputStream(bytes))

        assertEquals(42, parsed.requestId)
        assertEquals("utf-8", parsed.string("attributes-charset"))
        assertEquals("ipp://192.168.1.7:631/ipp/print", parsed.string("printer-uri"))
        assertEquals("tester", parsed.string("requesting-user-name"))
        assertEquals(
            listOf("media-supported", "sides-supported"),
            parsed.strings("requested-attributes")
        )
        assertEquals(3, parsed.int("copies"))
        assertEquals(true, parsed["ipp-attribute-fidelity"]?.firstBool())

        val res = parsed["printer-resolution"]?.values?.firstOrNull()
        assertTrue(res is IppValue.Resolution)
        assertEquals(600, (res as IppValue.Resolution).dpiX)
        assertEquals(600, res.dpiY)
    }

    @Test
    fun collectionsAreParsedIntoNestedAttributes() {
        val bytes = IppRequestBuilder(IppOp.PRINT_JOB, 7)
            .operationHeader("ipp://printer/ipp/print")
            .group(IppTag.JOB_ATTRS)
            .beginCollection("media-col")
            .member("media-source", IppTag.KEYWORD, "main")
            .memberInt("media-top-margin", 500)
            .endCollection()
            .build()

        val parsed = IppParser.parse(ByteArrayInputStream(bytes))
        val collection = parsed["media-col"]?.values?.firstOrNull() as? IppValue.Collection
        requireNotNull(collection)
        assertEquals("main", collection.members["media-source"]?.firstString())
        assertEquals(500, collection.members["media-top-margin"]?.firstInt())
    }
}
