package com.calltune.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File

/** Cuts [startMs, endMs] out of any audio file and saves it as a small AAC (.m4a) file. */
object Trimmer {
    /** Returns null on success, or an error message. */
    fun trim(ctx: Context, uri: Uri, startMs: Long, endMs: Long, out: File): String? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxStarted = false
        return try {
            extractor.setDataSource(ctx, uri, null)
            var track = -1
            var found: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; found = f; break
                }
            }
            val inFormat = found ?: return "No audio found in this file"
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (channels > 2) return "Only mono or stereo audio is supported"
            extractor.selectTrack(track)
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            val outFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 64000 else 96000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = enc
            enc.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            out.delete()
            val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val dInfo = MediaCodec.BufferInfo()
            val eInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decDone = false
            var encDone = false
            var totalSamples = 0L
            var muxTrack = -1

            fun ptsUs(): Long = totalSamples * 1_000_000L / sampleRate

            fun drainEncoder() {
                while (true) {
                    val o = enc.dequeueOutputBuffer(eInfo, 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxStarted = true
                    } else if (o >= 0) {
                        val b = enc.getOutputBuffer(o)!!
                        if ((eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) eInfo.size = 0
                        if (eInfo.size > 0 && muxStarted) {
                            b.position(eInfo.offset)
                            b.limit(eInfo.offset + eInfo.size)
                            mux.writeSampleData(muxTrack, b, eInfo)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if ((eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encDone = true
                            return
                        }
                    }
                }
            }

            while (!encDone) {
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        if (size < 0 || t > endUs) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, size, t, 0)
                            extractor.advance()
                        }
                    }
                }
                if (!decDone) {
                    val o = dec.dequeueOutputBuffer(dInfo, 10000)
                    if (o >= 0) {
                        val buf = dec.getOutputBuffer(o)!!
                        if (dInfo.size > 0 && dInfo.presentationTimeUs >= startUs) {
                            buf.position(dInfo.offset)
                            buf.limit(dInfo.offset + dInfo.size)
                            val frame = 2 * channels
                            while (buf.hasRemaining()) {
                                val ei = enc.dequeueInputBuffer(10000)
                                if (ei < 0) { drainEncoder(); continue }
                                val eb = enc.getInputBuffer(ei)!!
                                eb.clear()
                                var n = minOf(eb.capacity(), buf.remaining())
                                n -= n % frame
                                if (n <= 0) break
                                val tmp = ByteArray(n)
                                buf.get(tmp)
                                eb.put(tmp)
                                enc.queueInputBuffer(ei, 0, n, ptsUs(), 0)
                                totalSamples += n / frame
                            }
                        }
                        val eos = (dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        dec.releaseOutputBuffer(o, false)
                        if (eos) {
                            decDone = true
                            var ei: Int
                            do {
                                ei = enc.dequeueInputBuffer(10000)
                                if (ei < 0) drainEncoder()
                            } while (ei < 0)
                            enc.queueInputBuffer(ei, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }
                drainEncoder()
            }
            if (totalSamples == 0L) "Nothing was cut. Try a different start point." else null
        } catch (e: Exception) {
            "Trim failed: ${e.message}"
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
