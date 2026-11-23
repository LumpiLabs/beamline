package io.github.lumpilabs.beamline.airplay

/** DNS-SD TXT records in DNS wire format: "key=value" strings, each after a length byte. */
object TxtRecord {
  fun parse(txt: ByteArray): Map<String, String> {
    val entries = LinkedHashMap<String, String>()
    var i = 0
    while (i < txt.size) {
      val length = txt[i].toInt() and 0xFF
      val end = minOf(i + 1 + length, txt.size)
      val entry = String(txt, i + 1, end - i - 1, Charsets.UTF_8)
      val equals = entry.indexOf('=')
      if (equals > 0) entries[entry.substring(0, equals)] = entry.substring(equals + 1)
      i = end
    }
    return entries
  }
}
