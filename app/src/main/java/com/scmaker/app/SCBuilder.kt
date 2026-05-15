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
 * Builder for Supercell .sc files (Brawl Stars / Clash family).
 *
 * Два режима:
 *   FLAT  — твоя картинка во весь экран как плоский 2D-фон. Все 4-вершинные
 *           регионы шаблона переписываются на полноэкранный прямоугольник
 *           со всей текстурой; полигоны с vc!=4 схлопываются в точку и
 *           перестают рисоваться. Длины тегов не меняются — структура валидна.
 *   ATLAS — старое поведение: только подмена пиксельных байт текстуры,
 *           геометрия шаблона сохраняется (нужен PNG-атлас).
 */
object SCBuilder {

    enum class Mode { FLAT, ATLAS }

    private val TEXTURE_TAGS = setOf(0x01, 0x10, 0x13, 0x18, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x22, 0x24, 0x27, 0x28)
    private const val SHAPE_TAG = 0x12
    private const val REGION_TAG_BITMAP = 0x16
    private const val REGION_TAG_BITMAP_EX = 0x22

    data class TemplateInfo(
        val textureWidth: Int,
        val textureHeight: Int,
        val pixelFormat: Int,
        val originalExportName: String
    )

    fun inspectTemplate(context: Context): TemplateInfo {
        val raw = context.assets.open("template.sc").use { it.readBytes() }
        val body = unwrapSc(raw)
        val parsed = parseHeader(body)
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

    fun build(
        context: Context,
        newBitmap: Bitmap,
        newExportName: String?,
        mode: Mode = Mode.FLAT
    ): ByteArray {
        val rawSc = context.assets.open("template.sc").use { it.readBytes() }
        val body = unwrapSc(rawSc)
        val parsed = parseHeader(body)

        val targetExport = newExportName?.takeIf { it.isNotBlank() }
        val newHeader = rebuildHeader(parsed, targetExport)

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
            } else if (mode == Mode.FLAT && tag == SHAPE_TAG) {
                val originalPayload = body.copyOfRange(off + 5, off + 5 + length)
                val patched = patchShapeForFlatMode(originalPayload)
                tagsOut.write(tag)
                writeU32LE(tagsOut, patched.size)
                tagsOut.write(patched)
            } else {
                tagsOut.write(tag)
                writeU32LE(tagsOut, length)
                tagsOut.write(body, off + 5, length)
            }
            off += 5 + length
        }
        if (off < body.size) tagsOut.write(body, off, body.size - off)

        val newBody = newHeader + tagsOut.toByteArray()
        return wrapSc(newBody)
    }

    /**
     * Переписать regions внутри SHAPE-payload'а для FLAT-режима.
     * Длина не меняется — поэтому tag length родителя остаётся прежним.
     */
    private fun patchShapeForFlatMode(p: ByteArray): ByteArray {
        val out = p.copyOf()
        // SHAPE header: [u16 id][u16 regions_count][2b padding] = 6 байт
        var off = 6

        while (off < out.size) {
            if (off + 5 > out.size) break
            val innerTag = out[off].toInt() and 0xFF
            val innerLen = readU32LE(out, off + 1)
            if (innerTag == 0 || innerLen == 0) break
            if (off + 5 + innerLen > out.size) break

            if (innerTag == REGION_TAG_BITMAP || innerTag == REGION_TAG_BITMAP_EX) {
                val bodyOff = off + 5
                val vc = out[bodyOff + 1].toInt() and 0xFF
                val expectedSize = 2 + vc * 8 + vc * 4
                if (expectedSize == innerLen) {
                    if (vc == 4) {
                        // полноэкранный прямоугольник (twips, центр 0,0)
                        // ±9600 twips = ±480px — заведомо больше любого экрана,
                        // игра сама обрежет до viewport.
                        val halfW = 9600
                        val halfH = 9600
                        val xy = intArrayOf(
                            -halfW, -halfH,
                             halfW, -halfH,
                             halfW,  halfH,
                            -halfW,  halfH
                        )
                        val uv = intArrayOf(
                            0x0000, 0x0000,
                            0xFFFF, 0x0000,
                            0xFFFF, 0xFFFF,
                            0x0000, 0xFFFF
                        )
                        var p2 = bodyOff + 2
                        for (i in 0 until 8) {
                            writeI32LEAt(out, p2, xy[i]); p2 += 4
                        }
                        for (i in 0 until 8) {
                            writeU16LEAt(out, p2, uv[i]); p2 += 2
                        }
                    } else {
                        // не-прямоугольник — схлопнем в точку, чтобы был невидим
                        var p2 = bodyOff + 2
                        repeat(vc) {
                            writeI32LEAt(out, p2, 0); p2 += 4
                            writeI32LEAt(out, p2, 0); p2 += 4
                        }
                        repeat(vc) {
                            writeU16LEAt(out, p2, 0); p2 += 2
                            writeU16LEAt(out, p2, 0); p2 += 2
                        }
                    }
                }
            }
            off += 5 + innerLen
        }
        return out
    }

    // =========================================================================
    // SC wrap / unwrap
    // =========================================================================

    private fun unwrapSc(raw: ByteArray): ByteArray {
        require(raw[0] == 'S'.code.toByte() && raw[1] == 'C'.code.toByte())
        val version = readU32BE(raw, 2)
        require(version == 1)
        val hashSize = readU32BE(raw, 6)
        val payloadOff = 10 + hashSize
        val props = raw.copyOfRange(payloadOff, payloadOff + 5)
        val sizeLow = readU32LE(raw, payloadOff + 5)
        val rest = raw.copyOfRange(payloadOff + 9, raw.size)
        val fullStream = ByteArrayOutputStream().apply {
            write(props); writeU32LE(this, sizeLow); writeU32LE(this, 0); write(rest)
        }.toByteArray()
        LZMAInputStream(ByteArrayInputStream(fullStream)).use { return it.readBytes() }
    }

    private fun wrapSc(body: ByteArray): ByteArray {
        val rawOut = ByteArrayOutputStream()
        val options = LZMA2Options().apply { dictSize = 0x40000; lc = 3; lp = 0; pb = 2 }
        LZMAOutputStream(rawOut, options, body.size.toLong()).use { it.write(body) }
        val full = rawOut.toByteArray()
        val scLzma = ByteArrayOutputStream().apply {
            write(full, 0, 5); writeU32LE(this, body.size); write(full, 13, full.size - 13)
        }.toByteArray()
        val md5 = MessageDigest.getInstance("MD5").digest(scLzma)
        return ByteArrayOutputStream().apply {
            write('S'.code); write('C'.code)
            writeU32BE(this, 1); writeU32BE(this, md5.size); write(md5); write(scLzma)
        }.toByteArray()
    }

    // =========================================================================
    // Header parsing
    // =========================================================================

    private data class Parsed(
        val shapesCount: Int, val movieClipsCount: Int, val texturesCount: Int,
        val textFieldsCount: Int, val matricesCount: Int, val colorTransformsCount: Int,
        val reserved: ByteArray, val exportIds: IntArray, val exportNames: List<String>,
        val tagsOffset: Int
    )

    private fun parseHeader(body: ByteArray): Parsed {
        val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val shapes = bb.short.toInt() and 0xFFFF
        val mc = bb.short.toInt() and 0xFFFF
        val tex = bb.short.toInt() and 0xFFFF
        val tf = bb.short.toInt() and 0xFFFF
        val mt = bb.short.toInt() and 0xFFFF
        val ct = bb.short.toInt() and 0xFFFF
        val reserved = ByteArray(5).also { bb.get(it) }
        val ec = bb.short.toInt() and 0xFFFF
        val ids = IntArray(ec) { bb.short.toInt() and 0xFFFF }
        val names = (0 until ec).map {
            val len = bb.get().toInt() and 0xFF
            val nb = ByteArray(len); bb.get(nb); String(nb, Charsets.UTF_8)
        }
        return Parsed(shapes, mc, tex, tf, mt, ct, reserved, ids, names, bb.position())
    }

    private fun rebuildHeader(p: Parsed, newName: String?): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16LE(out, p.shapesCount); writeU16LE(out, p.movieClipsCount)
        writeU16LE(out, p.texturesCount); writeU16LE(out, p.textFieldsCount)
        writeU16LE(out, p.matricesCount); writeU16LE(out, p.colorTransformsCount)
        out.write(p.reserved)
        writeU16LE(out, p.exportIds.size)
        for (id in p.exportIds) writeU16LE(out, id)
        p.exportNames.forEachIndexed { i, original ->
            val name = if (i == 0 && newName != null) newName else original
            val nb = name.toByteArray(Charsets.UTF_8)
            require(nb.size <= 255)
            out.write(nb.size); out.write(nb)
        }
        return out.toByteArray()
    }

    // =========================================================================
    // Pixel encoding
    // =========================================================================

    private fun encodePixels(src: Bitmap, targetW: Int, targetH: Int, pf: Int): ByteArray {
        val scaled = if (src.width == targetW && src.height == targetH) src
                     else Bitmap.createScaledBitmap(src, targetW, targetH, true)
        val argb = IntArray(targetW * targetH)
        scaled.getPixels(argb, 0, targetW, 0, 0, targetW, targetH)
        return when (pf) {
            0, 1 -> encodeRgba8888(argb)
            2    -> encodeRgba4444(argb)
            3    -> encodeRgba5551(argb)
            4    -> encodeRgb565(argb)
            6    -> encodeLa88(argb)
            10   -> encodeL8(argb)
            else -> encodeRgba8888(argb)
        }
    }
    private fun encodeRgba8888(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 4); var j = 0
        for (px in argb) {
            out[j++] = (px shr 16).toByte(); out[j++] = (px shr 8).toByte()
            out[j++] = px.toByte();         out[j++] = (px shr 24).toByte()
        }; return out
    }
    private fun encodeRgba4444(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 2); var j = 0
        for (px in argb) {
            val r = ((px shr 16) and 0xFF) shr 4
            val g = ((px shr 8)  and 0xFF) shr 4
            val b = (px and 0xFF) shr 4
            val a = ((px shr 24) and 0xFF) shr 4
            val v = (r shl 12) or (g shl 8) or (b shl 4) or a
            out[j++] = (v and 0xFF).toByte(); out[j++] = (v shr 8).toByte()
        }; return out
    }
    private fun encodeRgba5551(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 2); var j = 0
        for (px in argb) {
            val r = (((px shr 16) and 0xFF) * 31 / 255)
            val g = (((px shr 8)  and 0xFF) * 31 / 255)
            val b = ((px and 0xFF) * 31 / 255)
            val a = if (((px shr 24) and 0xFF) >= 128) 1 else 0
            val v = (r shl 11) or (g shl 6) or (b shl 1) or a
            out[j++] = (v and 0xFF).toByte(); out[j++] = (v shr 8).toByte()
        }; return out
    }
    private fun encodeRgb565(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 2); var j = 0
        for (px in argb) {
            val r = (((px shr 16) and 0xFF) * 31 / 255)
            val g = (((px shr 8)  and 0xFF) * 63 / 255)
            val b = ((px and 0xFF) * 31 / 255)
            val v = (r shl 11) or (g shl 5) or b
            out[j++] = (v and 0xFF).toByte(); out[j++] = (v shr 8).toByte()
        }; return out
    }
    private fun encodeLa88(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 2); var j = 0
        for (px in argb) {
            val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
            val l = (r * 30 + g * 59 + b * 11) / 100
            out[j++] = l.toByte(); out[j++] = ((px shr 24) and 0xFF).toByte()
        }; return out
    }
    private fun encodeL8(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size); var j = 0
        for (px in argb) {
            val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
            out[j++] = ((r * 30 + g * 59 + b * 11) / 100).toByte()
        }; return out
    }

    // =========================================================================
    // Endian helpers
    // =========================================================================
    private fun readU16LE(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun readU32LE(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or
        ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)
    private fun readU32BE(b: ByteArray, o: Int) =
        ((b[o].toInt()   and 0xFF) shl 24) or ((b[o+1].toInt() and 0xFF) shl 16) or
        ((b[o+2].toInt() and 0xFF) shl 8)  or (b[o+3].toInt() and 0xFF)
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
    private fun writeU16LEAt(b: ByteArray, o: Int, v: Int) {
        b[o]   = (v and 0xFF).toByte()
        b[o+1] = ((v shr 8) and 0xFF).toByte()
    }
    private fun writeI32LEAt(b: ByteArray, o: Int, v: Int) {
        b[o]   = (v and 0xFF).toByte()
        b[o+1] = ((v shr 8) and 0xFF).toByte()
        b[o+2] = ((v shr 16) and 0xFF).toByte()
        b[o+3] = ((v shr 24) and 0xFF).toByte()
    }
}
