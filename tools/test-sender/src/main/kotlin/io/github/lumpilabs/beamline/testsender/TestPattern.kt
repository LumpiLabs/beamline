package io.github.lumpilabs.beamline.testsender

import io.github.lumpilabs.beamline.protocol.VideoSize
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.jcodec.codecs.h264.H264Encoder
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Picture
import org.jcodec.scale.AWTUtil
import org.jcodec.scale.ColorUtil

class EncodedFrame(val data: ByteArray, val keyframe: Boolean)

class EncodedClip(val codecConfig: ByteArray, val frames: List<EncodedFrame>)

/** A moving test pattern, encoded once as an H.264 Annex-B clip that starts with a keyframe. */
object TestPattern {
  fun encode(size: VideoSize, fps: Int, frameCount: Int): EncodedClip {
    val encoder = H264Encoder.createH264Encoder()
    encoder.setKeyInterval(frameCount)
    val toYuv = ColorUtil.getTransform(ColorSpace.RGB, ColorSpace.YUV420J)
    val output = ByteBuffer.allocate(size.width * size.height * 3)
    val frames = (0 until frameCount).map { index ->
      val rgb = AWTUtil.fromBufferedImageRGB(draw(size, index, fps))
      val yuv = Picture.create(size.width, size.height, ColorSpace.YUV420J)
      toYuv.transform(rgb, yuv)
      output.clear()
      val encoded = encoder.encodeFrame(yuv, output)
      val data = ByteArray(encoded.data.remaining()).also { encoded.data.get(it) }
      EncodedFrame(data, encoded.isKeyFrame)
    }
    return EncodedClip(parameterSets(frames.first().data), frames)
  }

  private fun draw(size: VideoSize, index: Int, fps: Int): BufferedImage {
    val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_3BYTE_BGR)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color(0x0E, 0x1A, 0x2B)
    g.fillRect(0, 0, size.width, size.height)
    val barWidth = size.width / 8
    val x = (index * size.width / fps / 2) % (size.width + barWidth) - barWidth
    g.color = Color(0x3D, 0xD6, 0xF5)
    g.fillRect(x, 0, barWidth, size.height)
    g.color = Color.WHITE
    g.font = Font(Font.SANS_SERIF, Font.BOLD, size.height / 8)
    g.drawString("Beamline test", size.width / 10, size.height / 3)
    g.font = Font(Font.MONOSPACED, Font.BOLD, size.height / 10)
    g.drawString("frame %04d".format(index), size.width / 10, size.height * 2 / 3)
    g.dispose()
    return image
  }

  /** Collects the SPS and PPS NAL units of an Annex-B keyframe as codec config. */
  private fun parameterSets(keyframe: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    for ((start, end) in nalUnits(keyframe)) {
      val type = keyframe[start].toInt() and 0x1F
      if (type == 7 || type == 8) {
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(keyframe, start, end - start)
      }
    }
    return out.toByteArray()
  }

  /** Start and end offsets of each NAL unit payload, excluding start codes. */
  private fun nalUnits(data: ByteArray): List<Pair<Int, Int>> {
    val starts = mutableListOf<Int>()
    var i = 0
    while (i + 2 < data.size) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
        starts += i + 3
        i += 3
      } else {
        i++
      }
    }
    return starts.mapIndexed { n, start ->
      var end = if (n + 1 < starts.size) starts[n + 1] - 3 else data.size
      while (end > start && data[end - 1].toInt() == 0) end--
      start to end
    }
  }
}
