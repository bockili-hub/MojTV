package com.moja.aplikacija

object M3u {
    fun parse(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var name = ""
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) name = l.substringAfterLast(",").trim()
            else if (l.isNotEmpty() && !l.startsWith("#")) {
                out.add((if (name.isEmpty()) l else name) to l); name = ""
            }
        }
        return out
    }
}
