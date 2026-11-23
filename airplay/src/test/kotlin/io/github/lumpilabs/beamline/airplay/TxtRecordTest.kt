package io.github.lumpilabs.beamline.airplay

import kotlin.test.Test
import kotlin.test.assertEquals

class TxtRecordTest {
  private fun wire(vararg entries: String): ByteArray =
    entries.flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }.toByteArray()

  @Test
  fun parsesEntriesInOrder() {
    val txt = TxtRecord.parse(wire("deviceid=02:11:22:33:44:55", "features=0x5A7FFEE6,0x400", "pw=false"))
    assertEquals(listOf("deviceid", "features", "pw"), txt.keys.toList())
    assertEquals("0x5A7FFEE6,0x400", txt["features"])
  }

  @Test
  fun keepsEqualsSignsInValuesAndSkipsEntriesWithoutKey() {
    assertEquals(mapOf("a" to "b=c"), TxtRecord.parse(wire("a=b=c", "novalue", "=x")))
  }

  @Test
  fun stopsAtATruncatedEntry() {
    val txt = wire("ok=1") + byteArrayOf(10) + "x=2".toByteArray()
    assertEquals(mapOf("ok" to "1", "x" to "2"), TxtRecord.parse(txt))
  }

  @Test
  fun emptyRecordIsEmpty() {
    assertEquals(emptyMap(), TxtRecord.parse(ByteArray(0)))
  }
}
