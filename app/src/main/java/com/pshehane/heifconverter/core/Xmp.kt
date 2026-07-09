package com.pshehane.heifconverter.core

/** XMP extraction and Google motion-photo XMP construction. */
object Xmp {

    /** Finds an XMP packet anywhere in the file (works for JPEG APP1 and HEIF mime items). */
    fun extract(bytes: ByteArray): String? {
        val latin = String(bytes, Charsets.ISO_8859_1)
        val start = latin.indexOf("<x:xmpmeta")
        if (start < 0) return null
        val end = latin.indexOf("</x:xmpmeta>", start)
        if (end < 0) return null
        return latin.substring(start, end + "</x:xmpmeta>".length)
    }

    fun isMotionPhoto(xmp: String?): Boolean {
        xmp ?: return false
        return Regex("""(GCamera:)?MotionPhoto\s*(=\s*"1"|>\s*1\s*<)""").containsMatchIn(xmp) ||
            Regex("""(GCamera:)?MicroVideo\s*(=\s*"1"|>\s*1\s*<)""").containsMatchIn(xmp)
    }

    /** Length in bytes of the appended motion video, per the XMP container directory. */
    fun motionVideoLength(xmp: String?): Long {
        xmp ?: return 0
        // attribute-form Container:Item, attributes in any order
        for (m in Regex("""<Container:Item\b[^>]*>""").findAll(xmp)) {
            val tag = m.value
            val semantic = Regex("""Item:Semantic\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.get(1)
            if (semantic == "MotionPhoto" || semantic == "MotionVideo") {
                Regex("""Item:Length\s*=\s*"(\d+)"""").find(tag)?.let { return it.groupValues[1].toLong() }
            }
        }
        Regex("""GCamera:MicroVideoOffset\s*=\s*"(\d+)"""").find(xmp)?.let { return it.groupValues[1].toLong() }
        return 0
    }

    fun presentationTimestampUs(xmp: String?): Long {
        xmp ?: return -1
        return Regex("""(MotionPhotoPresentationTimestampUs|MicroVideoPresentationTimestampUs)\s*=\s*"(-?\d+)"""")
            .find(xmp)?.groupValues?.get(2)?.toLong() ?: -1
    }

    /** Builds a Google motion-photo XMP packet for a container with an appended mp4. */
    fun motionPhoto(primaryMime: String, videoLength: Long, presentationTimestampUs: Long): String = """
        |<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="HEIFConverter">
        |  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        |    <rdf:Description rdf:about=""
        |        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        |        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
        |        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        |        GCamera:MotionPhoto="1"
        |        GCamera:MotionPhotoVersion="1"
        |        GCamera:MotionPhotoPresentationTimestampUs="$presentationTimestampUs">
        |      <Container:Directory>
        |        <rdf:Seq>
        |          <rdf:li rdf:parseType="Resource">
        |            <Container:Item Item:Mime="$primaryMime" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
        |          </rdf:li>
        |          <rdf:li rdf:parseType="Resource">
        |            <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/>
        |          </rdf:li>
        |        </rdf:Seq>
        |      </Container:Directory>
        |    </rdf:Description>
        |  </rdf:RDF>
        |</x:xmpmeta>
    """.trimMargin()
}
