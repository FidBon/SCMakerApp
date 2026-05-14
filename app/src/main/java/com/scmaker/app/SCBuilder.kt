package com.scmaker.app

import android.content.Context
import android.graphics.Bitmap
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Builder for Supercell .sc files.
 *
 * Подход: используем готовый template.sc как шаблон. Внутри него:
 *   - SC header  (signature "SC", version, MD5)
 *   - LZMA payload (Supercell variant: 5b props + 4b uncompressed size + stream)
 *   - distilled body содержит: header (shapes/movieclips/textures count),
 *     exports, и серию тегов TLV (u8 tag, u32 LE length, payload).
 *
 * При создании нового фона мы:
 *   1) распаковываем шаблон,
 *   2) находим первый тег-текстуру (TAG 0x01 / 0x10 / 0x18 / ...),
 *   3) масштабируем PNG пользователя до размера этой текстуры,
 *   4) заменяем пиксельные байты, оставляя всё остальное нетронутым,
 *   5) опционально переименовываем экспорт,
 *   6) запаковываем обратно (LZMA + SC header + новый MD5).
 *
 * Геометрия (shapes/movie_clips) при этом остаётся корректной — она
 * описана в UV-координатах прежней текстуры, размер которой не изменился.
 */
object SCBuilder {

    /** Теги, в которых первое поле — pixel-формат, затем width/height/pixels. */
    private val TEXTURE_TAGS = setOf(0x01, 0x10, 0x13, 0x18, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x22, 0x24, 0x27, 0x28)

    data class TemplateInfo(
        val textureWidth: Int,
        val textureHeight: Int,
        val pixelFormat: Int,
        val originalExportName: String
    )

    /** Возвращает базовую инфу о шаблоне: размер текстуры + имя экспорта. */
    fun inspectTemplate(context: Context): TemplateInfo {
        val rawSc = context.assets.open("template.sc").use { it.readBytes() }
        val body = unwrapSc(rawSc)
        val parsed = parseHeader(body)
        // Ищем первый texture tag
        var off = parsed.tagsOffset
        while (off < body.size) {
            val tag = body[off].toInt() and 0xFF
            val length = readU32LE(body, off + 1)
            if (tag == 0) error("в шаблоне нет текстуры")
            if (tag in TEXTURE_TAGS) {
                val pf = body[off + 5].toInt() and 0xFF
                val w = readU16LE(body, off + 6)
                val h = readU16LE(body, off + 8)
                return TemplateInfo(w, h, pf, parsed.exportNames.firstOrNull() ?: "bgr_anime")
            }
            off += 5 + length
        }
        error("texture tag не найден")
    }

    /**
     * Главный метод: создаёт .sc байты с новой текстурой [newBitmap]
     * и (опционально) новым именем экспорта.
     */
    fun build(context: Context, newBitmap: Bitmap, newExportName: String?): ByteArray {
        val rawSc = context.assets.open("template.sc").use { it.readBytes() }
        val body = unwrapSc(rawSc)

        val parsed = parseHeader(body)
        val targetExport = newExportName?.takeIf { it.isNotBlank() }
            ?: parsed.exportNames.firstOrNull()

        // Шаг 1: пересобрать header с новым именем экспорта (если меняется)
        val newHeader = rebuildHeader(parsed, targetExport)

        // Шаг 2: пройтись по тегам, заменить первый texture-тег
        val tagsOut = ByteArrayOutputStream()
        var off = parsed.tagsOffset
        var replaced = false
        while (off < body.size) {
            val tag = body[off].toInt() and 0xFF
            val length = readU32LE(body, off + 1)

            if (tag == 0) {
                tagsOut.write(0)
                writeU32LE(tagsOut, length)
                off += 5
                break
            }

            if (!replaced && tag in TEXTURE_TAGS) {
                val pf = body[off + 5].toInt() and 0xFF
                val w = readU16LE(body, off + 6)
                val h = readU16LE(body, off + 8)
                val pixels = encodePixels(newBitmap, w, h, pf)
                val newLen = 5 + pixels.size
                tagsOut.write(tag)
                writeU32LE(tagsOut, newLen)
                tagsOut.write(pf)
                writeU16LE(tagsOut, w)
                writeU16LE(tagsOut, h)
                tagsOut.write(pixels)
                replaced = true
            } else {
                tagsOut.write(tag)
                writeU32LE(tagsOut, length)
                tagsOut.write(body, off + 5, length)
            }
            off += 5 + length
        }
        // Хвост (если EOF где-то в середине, дописать остаток)
        if (off < body.size) tagsOut.write(body, off, body.size - off)

        val newBody = newHeader + tagsOut.toByteArray()

        // Шаг 3: упаковать обратно в .sc
        return wrapSc(newBody)
    }

    // =========================================================================
    // SC wrap / unwrap
    // =========================================================================

    /** Берёт сырой .sc, проверяет magic, распаковывает LZMA, возвращает body. */
    private fun unwrapSc(raw: ByteArray): ByteArray {
        require(raw[0] == 'S'.code.toByte() && raw[1] == 'C'.code.toByte()) { "not SC file" }
        val version = readU32BE(raw, 2)
        require(version == 1) { "unsupported SC version $version" }
        val hashSize = readU32BE(raw, 6)
        val payloadOff = 10 + hashSize

        // Supercell LZMA header: [5b props][4b LE uncompressed size]
        // -> добавим 4 нулевых байта, чтобы получить стандартный [5b props][8b size]
        val props = raw.copyOfRange(payloadOff, payloadOff + 5)
        val sizeLow = readU32LE(raw, payloadOff + 5)
        val rest = raw.copyOfRange(payloadOff + 9, raw.size)

        val fullStream = ByteArrayOutputStream().apply {
            write(props)
            writeU32LE(this, sizeLow)
            writeU32LE(this, 0) // высокие 32 бита = 0
            write(rest)
        }.toByteArray()

        LZMAInputStream(ByteArrayInputStream(fullStream)).use { lz ->
            return lz.readBytes()
        }
    }

    /** Берёт распакованное body, упаковывает в .sc (LZMA + SC header + MD5). */
    private fun wrapSc(body: ByteArray): ByteArray {
        // 1) LZMA-сжать
        val rawOut = ByteArrayOutputStream()
        val options = LZMA2Options().apply {
            dictSize = 0x40000   // 256 KB — как в Supercell
            lc = 3; lp = 0; pb = 2
        }
        LZMAOutputStream(rawOut, options, body.size.toLong()).use { it.write(body) }
        // XZ-for-Java пишет header [5b props][8b LE size] — конвертируем в SC-вариант.
        val full = rawOut.toByteArray()
        val scLzma = ByteArrayOutputStream().apply {
            write(full, 0, 5)                                  // props
            writeU32LE(this, body.size)                        // 4-байт size
            write(full, 13, full.size - 13)                    // lzma stream
        }.toByteArray()

        // 2) MD5 от сжатого блока (по умолчанию хеш в SC = MD5 от LZMA данных)
        val md5 = MessageDigest.getInstance("MD5").digest(scLzma)

        // 3) Склеить SC header + hash + lzma
        return ByteArrayOutputStream().apply {
            write('S'.code); write('C'.code)
            writeU32BE(this, 1)             // version
            writeU32BE(this, md5.size)      // hash size = 16
            write(md5)
            write(scLzma)
        }.toByteArray()
    }

    // =========================================================================
    // Header / exports parsing
    // =========================================================================

    private data class Parsed(
        val shapesCount: Int,
        val movieClipsCount: Int,
        val texturesCount: Int,
        val textFieldsCount: Int,
        val matricesCount: Int,
        val colorTransformsCount: Int,
        val reserved: ByteArray,
        val exportIds: IntArray,
        val exportNames: List<String>,
        val tagsOffset: Int
    )

    private fun parseHeader(body: ByteArray): Parsed {
        val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val shapes = bb.short.toInt() and 0xFFFF
        val movieClips = bb.short.toInt() and 0xFFFF
        val textures = bb.short.toInt() and 0xFFFF
        val textFields = bb.short.toInt() and 0xFFFF
        val matrices = bb.short.toInt() and 0xFFFF
        val colorTr = bb.short.toInt() and 0xFFFF
        val reserved = ByteArray(5).also { bb.get(it) }
        val exportsCount = bb.short.toInt() and 0xFFFF
        val ids = IntArray(exportsCount) { bb.short.toInt() and 0xFFFF }
        val names = (0 until exportsCount).map {
            val len = bb.get().toInt() and 0xFF
            val nb = ByteArray(len); bb.get(nb); String(nb, Charsets.UTF_8)
        }
        return Parsed(shapes, movieClips, textures, textFields, matrices, colorTr,
            reserved, ids, names, bb.position())
    }

    /** Пересобирает байты header'а, опционально подменяя имя единственного экспорта. */
    private fun rebuildHeader(p: Parsed, newName: String?): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16LE(out, p.shapesCount)
        writeU16LE(out, p.movieClipsCount)
        writeU16LE(out, p.texturesCount)
        writeU16LE(out, p.textFieldsCount)
        writeU16LE(out, p.matricesCount)
        writeU16LE(out, p.colorTransformsCount)
        out.write(p.reserved)
        writeU16LE(out, p.exportIds.size)
        for (id in p.exportIds) writeU16LE(out, id)
        val nameToWrite = newName ?: p.exportNames.firstOrNull()
        p.exportNames.forEachIndexed { i, original ->
            val name = if (i == 0 && nameToWrite != null) nameToWrite else original
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            require(nameBytes.size <= 255) { "имя экспорта слишком длинное" }
            out.write(nameBytes.size)
            out.write(nameBytes)
        }
        return out.toByteArray()
    }

    // =========================================================================
    // Pixel encoding (зависит от pixel_format в шаблоне)
    // =========================================================================

    /** Масштабирует bitmap к [targetW]x[targetH] и кодирует в нужный пиксельный формат. */
    private fun encodePixels(src: Bitmap, targetW: Int, targetH: Int, pf: Int): ByteArray {
        val scaled = if (src.width == targetW && src.height == targetH) src
                     else Bitmap.createScaledBitmap(src, targetW, targetH, /*filter=*/true)
        val argb = IntArray(targetW * targetH)
        scaled.getPixels(argb, 0, targetW, 0, 0, targetW, targetH)
        return when (pf) {
            0, 1 -> encodeRgba8888(argb)
            2    -> encodeRgba4444(argb)
            3    -> encodeRgba5551(argb)
            4    -> encodeRgb565(argb)
            6    -> encodeLa88(argb)
            10   -> encodeL8(argb)
            else -> encodeRgba8888(argb) // fallback
        }
    }

    private fun encodeRgba8888(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 4)
        var j = 0
        for (px in argb) {
            out[j++] = (px shr 16).toByte() // R
            out[j++] = (px shr 8).toByte()  // G
            out[j++] = px.toByte()          // B
            out[j++] = (px shr 24).toByte() // A
        }
        return out
    }
    private fun encodeRgba4444(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 2)
        var j = 0
        for (px in argb) {
            val r = ((px shr 16) and 0xFF) shr 4
            val g = ((px shr 8) and 0xFF) shr 4
            val b = (px and 0xFF) shr 4
            val a = ((px shr 24) and 0xFF) shr 4
            val v = (r shl 12) or (g shl 8) or (b shl 4) or a
            out[j++] = (v and 0xFF).toByte()
            out[j++] = (v shr 8).toByte()
        }
        return out
    }
    private fun encodeRgba5551(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 2)
        var j = 0
        for (px in argb) {
            val r = (((px shr 16) and 0xFF) * 31 / 255)
            val g = (((px shr 8)  and 0xFF) * 31 / 255)
            val b = ((px and 0xFF) * 31 / 255)
            val a = if (((px shr 24) and 0xFF) >= 128) 1 else 0
            val v = (r shl 11) or (g shl 6) or (b shl 1) or a
            out[j++] = (v and 0xFF).toByte()
            out[j++] = (v shr 8).toByte()
        }
        return out
    }
    private fun encodeRgb565(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 2)
        var j = 0
        for (px in argb) {
            val r = (((px shr 16) and 0xFF) * 31 / 255)
            val g = (((px shr 8)  and 0xFF) * 63 / 255)
            val b = ((px and 0xFF) * 31 / 255)
            val v = (r shl 11) or (g shl 5) or b
            out[j++] = (v and 0xFF).toByte()
            out[j++] = (v shr 8).toByte()
        }
        return out
    }
    private fun encodeLa88(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 2); var j = 0
        for (px in argb) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val l = (r * 30 + g * 59 + b * 11) / 100
            out[j++] = l.toByte()
            out[j++] = ((px shr 24) and 0xFF).toByte()
        }
        return out
    }
    private fun encodeL8(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size); var j = 0
        for (px in argb) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            out[j++] = ((r * 30 + g * 59 + b * 11) / 100).toByte()
        }
        return out
    }

    // =========================================================================
    // little/big endian helpers
    // =========================================================================
    private fun readU16LE(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF)) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun readU32LE(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or
        ((b[o+1].toInt() and 0xFF) shl 8) or
        ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+3].toInt() and 0xFF) shl 24)
    private fun readU32BE(b: ByteArray, o: Int) =
        ((b[o].toInt()   and 0xFF) shl 24) or
        ((b[o+1].toInt() and 0xFF) shl 16) or
        ((b[o+2].toInt() and 0xFF) shl 8)  or
         (b[o+3].toInt() and 0xFF)

    private fun writeU16LE(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v shr 8) and 0xFF)
    }
    private fun writeU32LE(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v shr 8) and 0xFF)
        o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF)
    }
    private fun writeU32BE(o: ByteArrayOutputStream, v: Int) {
        o.write((v shr 24) and 0xFF); o.write((v shr 16) and 0xFF)
        o.write((v shr 8) and 0xFF); o.write(v and 0xFF)
    }
}
