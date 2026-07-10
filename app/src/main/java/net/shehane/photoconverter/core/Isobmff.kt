package net.shehane.photoconverter.core

import java.io.ByteArrayOutputStream

/**
 * Minimal ISOBMFF/HEIF editor: injects an Exif item and/or an XMP ("mime") item into
 * an existing HEIC file, since neither HeifWriter nor ExifInterface can write metadata
 * into HEIF containers.
 *
 * Approach: rebuild the 'meta' box with extended iinf/iloc/iref, shift all absolute
 * file offsets that point past the original meta box by the growth delta, and append
 * the new item payloads in a fresh 'mdat' box at the end of the file.
 */
object Isobmff {

    // ---------- byte helpers ----------

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)
    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or
            (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()
    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

    private fun readUInt(b: ByteArray, o: Int, size: Int): Long = when (size) {
        0 -> 0
        2 -> u16(b, o).toLong()
        4 -> u32(b, o)
        8 -> u64(b, o)
        else -> throw IllegalArgumentException("unsupported field size $size")
    }

    private class Writer {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        fun u32(v: Long) { u16(((v shr 16) and 0xFFFF).toInt()); u16((v and 0xFFFF).toInt()) }
        fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
        fun uInt(v: Long, size: Int) = when (size) {
            0 -> {}
            2 -> u16(v.toInt())
            4 -> u32(v)
            8 -> u64(v)
            else -> throw IllegalArgumentException("size $size")
        }
        fun fourcc(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun bytes(b: ByteArray) = out.write(b)
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val w = Writer()
        w.u32((payload.size + 8).toLong())
        w.fourcc(type)
        w.bytes(payload)
        return w.toByteArray()
    }

    // ---------- box parsing ----------

    data class Box(val type: String, val start: Int, val end: Int, val payloadStart: Int) {
        val size get() = end - start
    }

    fun parseBoxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val res = ArrayList<Box>()
        var i = from
        while (i + 8 <= to) {
            var size = u32(b, i)
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (i + 16 > to) break
                size = u64(b, i + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - i).toLong()
            }
            if (size < header || i + size > to) break
            res += Box(type, i, (i + size).toInt(), i + header)
            i += size.toInt()
        }
        return res
    }

    fun isHeif(b: ByteArray): Boolean =
        b.size > 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    // ---------- iloc model ----------

    private class IlocExtent(var index: Long, var offset: Long, var length: Long)
    private class IlocItem(
        val id: Long,
        val constructionMethod: Int,
        val dataRefIndex: Int,
        var baseOffset: Long,
        val extents: MutableList<IlocExtent>,
    )

    private class Iloc(
        val version: Int,
        var offsetSize: Int,
        var lengthSize: Int,
        val baseOffsetSize: Int,
        val indexSize: Int,
        val items: MutableList<IlocItem>,
    )

    private fun parseIloc(b: ByteArray, boxRegion: Box): Iloc {
        var o = boxRegion.payloadStart
        val version = u8(b, o)
        o += 4 // version + flags
        val sizes = u16(b, o); o += 2
        val offsetSize = (sizes shr 12) and 0xF
        val lengthSize = (sizes shr 8) and 0xF
        val baseOffsetSize = (sizes shr 4) and 0xF
        val indexSize = if (version in 1..2) sizes and 0xF else 0
        val itemCount: Long
        if (version < 2) { itemCount = u16(b, o).toLong(); o += 2 } else { itemCount = u32(b, o); o += 4 }
        val items = ArrayList<IlocItem>()
        repeat(itemCount.toInt()) {
            val id: Long
            if (version < 2) { id = u16(b, o).toLong(); o += 2 } else { id = u32(b, o); o += 4 }
            var method = 0
            if (version in 1..2) { method = u16(b, o) and 0xF; o += 2 }
            val dataRef = u16(b, o); o += 2
            val base = readUInt(b, o, baseOffsetSize); o += baseOffsetSize
            val extentCount = u16(b, o); o += 2
            val extents = ArrayList<IlocExtent>()
            repeat(extentCount) {
                var index = 0L
                if (version in 1..2 && indexSize > 0) { index = readUInt(b, o, indexSize); o += indexSize }
                val off = readUInt(b, o, offsetSize); o += offsetSize
                val len = readUInt(b, o, lengthSize); o += lengthSize
                extents += IlocExtent(index, off, len)
            }
            items += IlocItem(id, method, dataRef, base, extents)
        }
        return Iloc(version, offsetSize, lengthSize, baseOffsetSize, indexSize, items)
    }

    private fun buildIloc(iloc: Iloc, versionFlags: ByteArray): ByteArray {
        val w = Writer()
        w.bytes(versionFlags)
        w.u16((iloc.offsetSize shl 12) or (iloc.lengthSize shl 8) or (iloc.baseOffsetSize shl 4) or
            (if (iloc.version in 1..2) iloc.indexSize else 0))
        if (iloc.version < 2) w.u16(iloc.items.size) else w.u32(iloc.items.size.toLong())
        for (it in iloc.items) {
            if (iloc.version < 2) w.u16(it.id.toInt()) else w.u32(it.id)
            if (iloc.version in 1..2) w.u16(it.constructionMethod)
            w.u16(it.dataRefIndex)
            w.uInt(it.baseOffset, iloc.baseOffsetSize)
            w.u16(it.extents.size)
            for (e in it.extents) {
                if (iloc.version in 1..2 && iloc.indexSize > 0) w.uInt(e.index, iloc.indexSize)
                w.uInt(e.offset, iloc.offsetSize)
                w.uInt(e.length, iloc.lengthSize)
            }
        }
        return box("iloc", w.toByteArray())
    }

    // ---------- infe / iinf / iref ----------

    private fun buildInfe(id: Long, itemType: String, contentType: String?): ByteArray {
        val w = Writer()
        val version = if (id <= 0xFFFF) 2 else 3
        w.u8(version); w.u8(0); w.u16(0) // version + flags
        if (version == 2) w.u16(id.toInt()) else w.u32(id)
        w.u16(0) // item_protection_index
        w.fourcc(itemType)
        w.u8(0)  // empty item_name, null-terminated
        if (contentType != null) {
            w.bytes(contentType.toByteArray(Charsets.ISO_8859_1)); w.u8(0)
        }
        return box("infe", w.toByteArray())
    }

    private fun maxItemId(b: ByteArray, iinf: Box): Long {
        var o = iinf.payloadStart
        val version = u8(b, o)
        o += 4
        val count: Long
        if (version == 0) { count = u16(b, o).toLong(); o += 2 } else { count = u32(b, o); o += 4 }
        var max = 0L
        for (infe in parseBoxes(b, o, iinf.end)) {
            if (infe.type != "infe") continue
            val v = u8(b, infe.payloadStart)
            val id = if (v >= 3) u32(b, infe.payloadStart + 4) else u16(b, infe.payloadStart + 4).toLong()
            if (id > max) max = id
        }
        return max
    }

    private fun rebuildIinf(b: ByteArray, iinf: Box, newEntries: List<ByteArray>): ByteArray {
        var o = iinf.payloadStart
        val version = u8(b, o)
        val versionFlags = b.copyOfRange(o, o + 4)
        o += 4
        val count: Long
        val countFieldEnd: Int
        if (version == 0) { count = u16(b, o).toLong(); countFieldEnd = o + 2 } else { count = u32(b, o); countFieldEnd = o + 4 }
        val w = Writer()
        w.bytes(versionFlags)
        if (version == 0) w.u16((count + newEntries.size).toInt()) else w.u32(count + newEntries.size)
        w.bytes(b.copyOfRange(countFieldEnd, iinf.end)) // existing infe boxes verbatim
        for (e in newEntries) w.bytes(e)
        return box("iinf", w.toByteArray())
    }

    private fun buildCdscRef(idSize: Int, fromId: Long, toId: Long): ByteArray {
        val w = Writer()
        w.uInt(fromId, idSize)
        w.u16(1)
        w.uInt(toId, idSize)
        return box("cdsc", w.toByteArray())
    }

    private fun rebuildIref(b: ByteArray, iref: Box?, newRefs: List<Pair<Long, Long>>): ByteArray {
        return if (iref == null) {
            val w = Writer()
            w.u32(0) // version 0 + flags
            for ((from, to) in newRefs) w.bytes(buildCdscRef(2, from, to))
            box("iref", w.toByteArray())
        } else {
            val version = u8(b, iref.payloadStart)
            val idSize = if (version == 0) 2 else 4
            val w = Writer()
            w.bytes(b.copyOfRange(iref.payloadStart, iref.end))
            for ((from, to) in newRefs) w.bytes(buildCdscRef(idSize, from, to))
            box("iref", w.toByteArray())
        }
    }

    /**
     * Wraps an appended motion video in an 'mpvd' box (Samsung-style) so the HEIC
     * remains one valid ISOBMFF box stream. The XMP locator length counts only the
     * video bytes, which end at EOF, so length-from-end lookup still works.
     */
    fun mpvdBox(video: ByteArray): ByteArray = box("mpvd", video)

    /** Just the 8-byte mpvd box header, so the video body can be streamed after it. */
    fun mpvdHeader(videoLength: Long): ByteArray {
        val w = Writer()
        w.u32(videoLength + 8)
        w.fourcc("mpvd")
        return w.toByteArray()
    }

    // ---------- AVIF container construction ----------

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray {
        val w = Writer()
        w.u8(version)
        w.u8((flags shr 16) and 0xFF); w.u8((flags shr 8) and 0xFF); w.u8(flags and 0xFF)
        w.bytes(payload)
        return box(type, w.toByteArray())
    }

    /**
     * Builds a minimal single-item AVIF: ftyp + meta(hdlr/pitm/iloc/iinf/iprp) + mdat.
     * The result has a normal meta box, so [injectMetadata] can add Exif/XMP afterwards.
     */
    fun buildAvif(width: Int, height: Int, av1c: ByteArray, itemData: ByteArray): ByteArray {
        val ftyp = box("ftyp", Writer().apply {
            fourcc("avif"); u32(0); fourcc("avif"); fourcc("mif1"); fourcc("miaf")
        }.toByteArray())

        val hdlr = fullBox("hdlr", 0, 0, Writer().apply {
            u32(0); fourcc("pict"); u32(0); u32(0); u32(0); u8(0)
        }.toByteArray())

        val pitm = fullBox("pitm", 0, 0, Writer().apply { u16(1) }.toByteArray())

        val iinf = fullBox("iinf", 0, 0, Writer().apply {
            u16(1)
            bytes(buildInfe(1, "av01", null))
        }.toByteArray())

        val ispe = fullBox("ispe", 0, 0, Writer().apply {
            u32(width.toLong()); u32(height.toLong())
        }.toByteArray())
        val pixi = fullBox("pixi", 0, 0, Writer().apply { u8(3); u8(8); u8(8); u8(8) }.toByteArray())
        val av1C = box("av1C", av1c)
        val ipco = box("ipco", ispe + pixi + av1C)
        val ipma = fullBox("ipma", 0, 0, Writer().apply {
            u32(1)      // entry_count
            u16(1)      // item_ID
            u8(3)       // association_count
            u8(0x01)    // ispe
            u8(0x02)    // pixi
            u8(0x80 or 0x03) // av1C, essential
        }.toByteArray())
        val iprp = box("iprp", ipco + ipma)

        fun buildIlocBox(offset: Long) = fullBox("iloc", 0, 0, Writer().apply {
            u16(0x4400) // offset_size=4, length_size=4, base_offset_size=0
            u16(1)      // item_count
            u16(1)      // item_ID
            u16(0)      // data_reference_index
            u16(1)      // extent_count
            u32(offset)
            u32(itemData.size.toLong())
        }.toByteArray())

        fun buildMeta(offset: Long) =
            fullBox("meta", 0, 0, hdlr + pitm + buildIlocBox(offset) + iinf + iprp)

        val metaSize = buildMeta(0).size
        val itemOffset = (ftyp.size + metaSize + 8).toLong() // + mdat header
        val meta = buildMeta(itemOffset)
        require(meta.size == metaSize) { "meta size instability" }

        val out = java.io.ByteArrayOutputStream(ftyp.size + meta.size + itemData.size + 8)
        out.write(ftyp)
        out.write(meta)
        out.write(box("mdat", itemData))
        return out.toByteArray()
    }

    /**
     * Builds a grid AVIF: N av01 tile items + a 'grid' derived item as the primary,
     * for images larger than the encoder's frame capability. Tiles are raster order,
     * all [tileW]x[tileH]; the grid crops to [width]x[height].
     */
    fun buildAvifGrid(
        width: Int,
        height: Int,
        tileW: Int,
        tileH: Int,
        rows: Int,
        cols: Int,
        av1c: ByteArray,
        tiles: List<ByteArray>,
    ): ByteArray {
        val n = tiles.size
        require(n == rows * cols) { "tile count mismatch" }
        val gridId = (n + 1).toLong()

        val ftyp = box("ftyp", Writer().apply {
            fourcc("avif"); u32(0); fourcc("avif"); fourcc("mif1"); fourcc("miaf")
        }.toByteArray())

        val hdlr = fullBox("hdlr", 0, 0, Writer().apply {
            u32(0); fourcc("pict"); u32(0); u32(0); u32(0); u8(0)
        }.toByteArray())

        val pitm = fullBox("pitm", 0, 0, Writer().apply { u16(gridId.toInt()) }.toByteArray())

        val iinf = fullBox("iinf", 0, 0, Writer().apply {
            u16(n + 1)
            for (i in 1..n) bytes(buildInfe(i.toLong(), "av01", null))
            bytes(buildInfe(gridId, "grid", null))
        }.toByteArray())

        val iref = fullBox("iref", 0, 0, Writer().apply {
            bytes(box("dimg", Writer().apply {
                u16(gridId.toInt())
                u16(n)
                for (i in 1..n) u16(i)
            }.toByteArray()))
        }.toByteArray())

        val ispeTile = fullBox("ispe", 0, 0, Writer().apply {
            u32(tileW.toLong()); u32(tileH.toLong())
        }.toByteArray())
        val pixi = fullBox("pixi", 0, 0, Writer().apply { u8(3); u8(8); u8(8); u8(8) }.toByteArray())
        val av1C = box("av1C", av1c)
        val ispeFull = fullBox("ispe", 0, 0, Writer().apply {
            u32(width.toLong()); u32(height.toLong())
        }.toByteArray())
        val ipco = box("ipco", ispeTile + pixi + av1C + ispeFull)
        val ipma = fullBox("ipma", 0, 0, Writer().apply {
            u32((n + 1).toLong())
            for (i in 1..n) {
                u16(i)
                u8(3)
                u8(0x01)          // ispe (tile)
                u8(0x02)          // pixi
                u8(0x80 or 0x03)  // av1C, essential
            }
            u16(gridId.toInt())
            u8(2)
            u8(0x04)              // ispe (full)
            u8(0x02)              // pixi
        }.toByteArray())
        val iprp = box("iprp", ipco + ipma)

        // ImageGrid payload (16-bit output dimensions)
        val gridPayload = Writer().apply {
            u8(0); u8(0)
            u8(rows - 1); u8(cols - 1)
            u16(width); u16(height)
        }.toByteArray()

        fun buildIlocBox(base: Long) = fullBox("iloc", 0, 0, Writer().apply {
            u16(0x4400) // offset_size=4, length_size=4, base_offset_size=0
            u16(n + 1)
            var off = base
            u16(gridId.toInt()); u16(0); u16(1); u32(off); u32(gridPayload.size.toLong())
            off += gridPayload.size
            for (i in 1..n) {
                u16(i); u16(0); u16(1); u32(off); u32(tiles[i - 1].size.toLong())
                off += tiles[i - 1].size
            }
        }.toByteArray())

        fun buildMeta(base: Long) =
            fullBox("meta", 0, 0, hdlr + pitm + buildIlocBox(base) + iinf + iref + iprp)

        val metaSize = buildMeta(0).size
        val base = (ftyp.size + metaSize + 8).toLong() // + mdat header
        val meta = buildMeta(base)
        require(meta.size == metaSize) { "meta size instability" }

        val mdatContent = java.io.ByteArrayOutputStream(gridPayload.size + tiles.sumOf { it.size })
        mdatContent.write(gridPayload)
        for (t in tiles) mdatContent.write(t)

        val out = java.io.ByteArrayOutputStream(ftyp.size + meta.size + mdatContent.size() + 8)
        out.write(ftyp)
        out.write(meta)
        out.write(box("mdat", mdatContent.toByteArray()))
        return out.toByteArray()
    }

    // ---------- Exif extraction ----------

    private fun isTiffHeader(b: ByteArray, o: Int): Boolean {
        if (o + 4 > b.size) return false
        return (b[o] == 'I'.code.toByte() && b[o + 1] == 'I'.code.toByte() && b[o + 2].toInt() == 0x2A) ||
            (b[o] == 'M'.code.toByte() && b[o + 1] == 'M'.code.toByte() && b[o + 3].toInt() == 0x2A)
    }

    /** TIFF bytes from the Exif item of a HEIF/AVIF file, or null. */
    fun extractExifTiff(file: ByteArray): ByteArray? {
        if (!isHeif(file)) return null
        return try {
            val top = parseBoxes(file, 0, file.size)
            val meta = top.firstOrNull { it.type == "meta" } ?: return null
            val kids = parseBoxes(file, meta.payloadStart + 4, meta.end)
            val iinf = kids.firstOrNull { it.type == "iinf" } ?: return null
            val ilocBox = kids.firstOrNull { it.type == "iloc" } ?: return null

            var o = iinf.payloadStart
            val version = u8(file, o)
            o += 4
            o += if (version == 0) 2 else 4
            var exifId = -1L
            for (infe in parseBoxes(file, o, iinf.end)) {
                if (infe.type != "infe") continue
                val v = u8(file, infe.payloadStart)
                val id = if (v >= 3) u32(file, infe.payloadStart + 4) else u16(file, infe.payloadStart + 4).toLong()
                val typeOff = infe.payloadStart + 4 + (if (v >= 3) 4 else 2) + 2
                if (String(file, typeOff, 4, Charsets.ISO_8859_1) == "Exif") { exifId = id; break }
            }
            if (exifId < 0) return null

            val iloc = parseIloc(file, ilocBox)
            val item = iloc.items.firstOrNull { it.id == exifId } ?: return null
            val extent = item.extents.firstOrNull() ?: return null
            val start: Int = when (item.constructionMethod) {
                0 -> (item.baseOffset + extent.offset).toInt()
                1 -> {
                    val idat = kids.firstOrNull { it.type == "idat" } ?: return null
                    idat.payloadStart + (item.baseOffset + extent.offset).toInt()
                }
                else -> return null
            }
            val len = extent.length.toInt()
            if (start < 0 || len < 8 || start + len > file.size) return null
            val payload = file.copyOfRange(start, start + len)

            // payload: u32 exif_tiff_header_offset, then usually "Exif\0\0", then TIFF
            var t = 4 + u32(payload, 0).toInt()
            if (!isTiffHeader(payload, t)) {
                t = -1
                for (i in 0..minOf(payload.size - 4, 64)) {
                    if (isTiffHeader(payload, i)) { t = i; break }
                }
                if (t < 0) return null
            }
            payload.copyOfRange(t, payload.size)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- main entry ----------

    /**
     * Returns a new HEIF file with [exifPayload] (Exif item) and/or [xmpPayload]
     * (mime item, application/rdf+xml) injected and linked to the primary item.
     */
    fun injectMetadata(file: ByteArray, exifPayload: ByteArray?, xmpPayload: ByteArray?): ByteArray {
        if (exifPayload == null && xmpPayload == null) return file
        require(isHeif(file)) { "not an ISOBMFF file" }

        val top = parseBoxes(file, 0, file.size)
        val meta = top.firstOrNull { it.type == "meta" } ?: throw IllegalStateException("no meta box")
        val kids = parseBoxes(file, meta.payloadStart + 4, meta.end)
        val pitm = kids.firstOrNull { it.type == "pitm" } ?: throw IllegalStateException("no pitm box")
        val iinf = kids.firstOrNull { it.type == "iinf" } ?: throw IllegalStateException("no iinf box")
        val ilocBox = kids.firstOrNull { it.type == "iloc" } ?: throw IllegalStateException("no iloc box")
        val irefBox = kids.firstOrNull { it.type == "iref" }

        val pitmVersion = u8(file, pitm.payloadStart)
        val primaryId =
            if (pitmVersion == 0) u16(file, pitm.payloadStart + 4).toLong()
            else u32(file, pitm.payloadStart + 4)

        var nextId = maxItemId(file, iinf) + 1
        val newEntries = ArrayList<ByteArray>()   // infe boxes
        val newRefs = ArrayList<Pair<Long, Long>>() // cdsc from -> to
        data class NewItem(val id: Long, val payload: ByteArray)
        val newItems = ArrayList<NewItem>()

        if (exifPayload != null) {
            val id = nextId++
            newEntries += buildInfe(id, "Exif", null)
            newRefs += id to primaryId
            newItems += NewItem(id, exifPayload)
        }
        if (xmpPayload != null) {
            val id = nextId++
            newEntries += buildInfe(id, "mime", "application/rdf+xml")
            newRefs += id to primaryId
            newItems += NewItem(id, xmpPayload)
        }

        val iloc = parseIloc(file, ilocBox)
        // ensure we can address offsets/lengths anywhere in the grown file
        if (iloc.offsetSize !in intArrayOf(4, 8)) iloc.offsetSize = 4
        if (iloc.lengthSize !in intArrayOf(4, 8)) iloc.lengthSize = 4
        val ilocVersionFlags = file.copyOfRange(ilocBox.payloadStart, ilocBox.payloadStart + 4)

        // placeholder entries for new items; offsets patched after delta is known
        for (item in newItems) {
            iloc.items += IlocItem(
                id = item.id,
                constructionMethod = 0,
                dataRefIndex = 0,
                baseOffset = 0,
                extents = mutableListOf(IlocExtent(0, 0, item.payload.size.toLong())),
            )
        }

        val newIinf = rebuildIinf(file, iinf, newEntries)
        val newIref = rebuildIref(file, irefBox, newRefs)

        // meta rebuild pass; iloc size is independent of the offset *values*
        fun buildMeta(): ByteArray {
            val w = Writer()
            w.bytes(file.copyOfRange(meta.payloadStart, meta.payloadStart + 4)) // meta version/flags
            var irefWritten = false
            for (k in kids) {
                when (k.type) {
                    "iinf" -> {
                        w.bytes(newIinf)
                        if (irefBox == null) { w.bytes(newIref); irefWritten = true }
                    }
                    "iloc" -> w.bytes(buildIloc(iloc, ilocVersionFlags))
                    "iref" -> { w.bytes(newIref); irefWritten = true }
                    else -> w.bytes(file.copyOfRange(k.start, k.end))
                }
            }
            require(irefWritten) { "iref not emitted" }
            return box("meta", w.toByteArray())
        }

        val delta = (buildMeta().size - meta.size).toLong()

        // shift absolute offsets pointing past the original meta box
        for (it in iloc.items.dropLast(newItems.size)) {
            if (it.constructionMethod != 0) continue
            if (iloc.baseOffsetSize > 0) {
                if (it.baseOffset >= meta.end) it.baseOffset += delta
            } else {
                for (e in it.extents) if (e.offset >= meta.end) e.offset += delta
            }
        }

        // new payloads live in an appended mdat at the end of the grown file
        val mdatStart = file.size + delta
        var payloadOffset = mdatStart + 8
        val mdatContent = Writer()
        for ((i, item) in newItems.withIndex()) {
            val ilocEntry = iloc.items[iloc.items.size - newItems.size + i]
            ilocEntry.extents[0].offset = payloadOffset
            mdatContent.bytes(item.payload)
            payloadOffset += item.payload.size
        }

        val newMeta = buildMeta()
        require(newMeta.size - meta.size == delta.toInt()) { "meta size instability" }

        val out = ByteArrayOutputStream(file.size + newMeta.size)
        out.write(file, 0, meta.start)
        out.write(newMeta)
        out.write(file, meta.end, file.size - meta.end)
        out.write(box("mdat", mdatContent.toByteArray()))
        return out.toByteArray()
    }
}
