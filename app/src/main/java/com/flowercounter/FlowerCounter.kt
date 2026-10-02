/*
 * Flower Counter
 * Himanshu S Mazumdar
 * Dharmsinh Desai University, Nadiad, INDIA
 */

package com.flowercounter

import android.graphics.Bitmap
import android.graphics.Matrix
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.SimpleBlobDetector
import org.opencv.features2d.SimpleBlobDetector_Params
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Slider-driven tuning values. Pixel sizes refer to the working image, which is always
 * scaled so its long side is [FlowerCounter.WORKING_LONG_SIDE] px, so a given setting
 * behaves the same regardless of the camera's native resolution.
 */
data class FlowerParams(
    /** 0..100. Higher accepts dimmer / more tinted pixels as "white". */
    val whiteSensitivity: Int = 60,
    /** Smallest blob (in px²) that still counts as a flower. Also sets the speck-removal size. */
    val minBlobArea: Int = 60,
    /** 0..10, mapped to an odd Gaussian kernel of 2n+1 px (0 = off). */
    val blurAmount: Int = 1,
    /** Add an area-based estimate for touching flowers that merge into one irregular blob. */
    val estimateClusters: Boolean = true,
    /** Draw on top of the binary mask instead of the photo (useful while tuning). */
    val showMask: Boolean = false,
) {
    /** Minimum HSV Value (brightness, 0..255) for a pixel to be considered white. */
    val minBrightness: Int get() = (240 - 0.8f * whiteSensitivity).roundToInt()

    /** Maximum HSV Saturation (0..255): white has almost no colour, leaves are clearly green. */
    val maxSaturation: Int get() = (20 + 0.7f * whiteSensitivity).roundToInt()

    val blurKernel: Int get() = blurAmount * 2 + 1

    /**
     * Opening kernel roughly as wide as a flower petal: wide enough to erase grass blades
     * and petal tips (which ruin circularity), narrow enough to keep the flower's core.
     * Tied to the minimum blob size because both scale with how far away the flowers are.
     */
    val openKernel: Int
        get() {
            val k = sqrt(minBlobArea.toDouble()).toInt().coerceIn(3, 15)
            return if (k % 2 == 0) k - 1 else k
        }
}

data class FlowerResult(
    /** Working-size image with detections drawn on it. */
    val bitmap: Bitmap,
    /** Best estimate: [blobCount] + [clusterEstimate]. */
    val count: Int,
    /** Flowers found individually by SimpleBlobDetector. */
    val blobCount: Int,
    /** Extra flowers estimated from merged clusters (0 when disabled). */
    val clusterEstimate: Int,
)

object FlowerCounter {

    const val WORKING_LONG_SIDE = 1280

    private const val MAX_BLOB_AREA = 5000f
    private const val CLOSE_KERNEL = 5

    private val RED = Scalar(255.0, 0.0, 0.0, 255.0)
    private val ORANGE = Scalar(255.0, 165.0, 0.0, 255.0)

    /** Rotates (EXIF / sensor orientation) and downsizes in a single pass. */
    fun prepare(src: Bitmap, rotationDegrees: Int = 0): Bitmap {
        val scale = minOf(1f, WORKING_LONG_SIDE.toFloat() / max(src.width, src.height))
        if (scale == 1f && rotationDegrees == 0 && src.config == Bitmap.Config.ARGB_8888) return src
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotationDegrees.toFloat())
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return if (out.config == Bitmap.Config.ARGB_8888) out else out.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun count(bitmap: Bitmap, params: FlowerParams): FlowerResult {
        val rgba = Mat()
        val rgb = Mat()
        val hsv = Mat()
        val mask = Mat()
        val inverted = Mat()
        val closeKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(CLOSE_KERNEL.toDouble(), CLOSE_KERNEL.toDouble())
        )
        val openKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(params.openKernel.toDouble(), params.openKernel.toDouble())
        )
        val keypoints = MatOfKeyPoint()
        try {
            // 1. Bitmap -> Mat. Android bitmaps are RGBA; OpenCV's colour conversions want RGB.
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

            // 2. RGB -> HSV.
            // Why HSV instead of RGB: in RGB, "white" is R, G and B all high at the same time,
            // and a shadowed white petal (say 180,180,185) is numerically close to a sunlit
            // leaf highlight (190,215,170). HSV separates *how bright* (V) from *how colourful*
            // (S). White petals are bright AND unsaturated even in shade, while leaf highlights
            // keep a green tint (higher S) and grass is clearly saturated. So "low S + high V"
            // isolates white flowers far more reliably than any single RGB cut-off.
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)

            // 3. Colour threshold: keep pixels that are bright enough and colourless enough.
            // Hue is ignored (0..180) because white has no meaningful hue.
            Core.inRange(
                hsv,
                Scalar(0.0, 0.0, params.minBrightness.toDouble()),
                Scalar(180.0, params.maxSaturation.toDouble(), 255.0),
                mask
            )

            // 4. Morphological CLOSE (dilate then erode): fills the small orange centre of each
            // flower so it becomes one solid white shape instead of a ring.
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, closeKernel)

            // 5. Gaussian blur on the mask, then re-binarise: rounds off jagged petal edges and
            // melts away 1-2 px noise so the blob outlines are smoother. Blurring the mask (not
            // the photo) keeps the flowers' brightest pixels intact for the threshold above;
            // these flowers are only ~15 px wide, so blurring the photo would dim them away.
            if (params.blurAmount > 0) {
                val k = params.blurKernel.toDouble()
                Imgproc.GaussianBlur(mask, mask, Size(k, k), 0.0)
                Imgproc.threshold(mask, mask, 127.0, 255.0, Imgproc.THRESH_BINARY)
            }

            // 6. Morphological OPEN (erode then dilate).
            // Why: erosion deletes anything thinner than the kernel - dust, sparkle on dew,
            // thin bright grass blades, glare lines along leaf veins, and the flower's thin
            // petal tips. Dilation then grows the survivors back to roughly their original size.
            // Net effect: specks vanish and each flower becomes a compact round core, which is
            // exactly what the circularity/convexity/inertia filters below are looking for.
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, openKernel)

            // 7. Blob detection on the clean binary mask. The Java API can't change blobColor
            // from its default (0 = dark blobs), so the detector sees an inverted copy.
            val detector = SimpleBlobDetector.create(blobParams(params.minBlobArea.toFloat()))
            Core.bitwise_not(mask, inverted)
            detector.detect(inverted, keypoints)
            val points = keypoints.toArray()

            // 8. Touching flowers merge into irregular blobs that the shape filters reject.
            val clusters = if (params.estimateClusters) {
                estimateClusters(mask, points.map { it.pt }, params.minBlobArea)
            } else {
                ClusterEstimate(0, emptyList())
            }

            // 9. Draw the result.
            val canvas = if (params.showMask) {
                Mat().also { Imgproc.cvtColor(mask, it, Imgproc.COLOR_GRAY2RGBA) }
            } else {
                rgba
            }
            if (clusters.contours.isNotEmpty()) {
                Imgproc.drawContours(canvas, clusters.contours, -1, ORANGE, 2)
            }
            for (kp in points) {
                val radius = max((kp.size / 2).roundToInt() + 4, 8)
                Imgproc.circle(canvas, kp.pt, radius, RED, 2)
            }
            val out = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(canvas, out)
            if (canvas !== rgba) canvas.release()
            clusters.contours.forEach { it.release() }

            return FlowerResult(
                bitmap = out,
                count = points.size + clusters.extra,
                blobCount = points.size,
                clusterEstimate = clusters.extra,
            )
        } finally {
            rgba.release(); rgb.release(); hsv.release(); mask.release(); inverted.release()
            closeKernel.release(); openKernel.release(); keypoints.release()
        }
    }

    private fun blobParams(minArea: Float) = SimpleBlobDetector_Params().apply {
        // The mask is already binary, so a single threshold pass at 127 is enough.
        set_minThreshold(127f)
        set_maxThreshold(128f)
        set_thresholdStep(1f)
        set_minRepeatability(1)
        set_minDistBetweenBlobs(2f)

        // Only dark blobs (flowers in the inverted mask), not the gaps between them.
        set_filterByColor(true)

        // Area: ignore leftover specks and huge glare patches / merged masses.
        set_filterByArea(true)
        set_minArea(minArea)
        set_maxArea(MAX_BLOB_AREA)

        // Shape: after the opening step a single flower is a compact, roughly round core.
        set_filterByCircularity(true)
        set_minCircularity(0.5f)
        set_filterByConvexity(true)
        set_minConvexity(0.7f)
        set_filterByInertia(true)
        set_minInertiaRatio(0.3f)
    }

    private class ClusterEstimate(val extra: Int, val contours: List<MatOfPoint>)

    /**
     * Blobs that SimpleBlobDetector rejected but which are much larger than a single flower
     * are almost always several flowers touching. We count them as area / typical flower area.
     * The 90th percentile of detected blob areas is used as "one whole flower" because many
     * small detections are flowers that are partly hidden by grass.
     */
    private fun estimateClusters(mask: Mat, detected: List<Point>, minArea: Int): ClusterEstimate {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val hierarchy = Mat()
        try {
            val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
            if (n <= 1 || detected.isEmpty()) return ClusterEstimate(0, emptyList())

            val cols = labels.cols()
            val labelPixels = IntArray(labels.rows() * cols).also { labels.get(0, 0, it) }
            val statValues = IntArray(n * Imgproc.CC_STAT_MAX).also { stats.get(0, 0, it) }
            fun area(label: Int) = statValues[label * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_AREA]

            val hit = BooleanArray(n)
            for (p in detected) {
                val x = p.x.toInt().coerceIn(0, cols - 1)
                val y = p.y.toInt().coerceIn(0, labels.rows() - 1)
                hit[labelPixels[y * cols + x]] = true
            }
            hit[0] = false

            val singleAreas = (1 until n).filter { hit[it] }.map { area(it) }.sorted()
            if (singleAreas.isEmpty()) return ClusterEstimate(0, emptyList())
            val flowerArea = max(singleAreas[(singleAreas.size * 0.9).toInt()], minArea).toFloat()

            val flowersInLabel = IntArray(n)
            var extra = 0
            for (label in 1 until n) {
                if (hit[label]) continue
                val flowers = (area(label) / flowerArea).roundToInt()
                if (flowers >= 2) {
                    flowersInLabel[label] = flowers
                    extra += flowers
                }
            }
            if (extra == 0) return ClusterEstimate(0, emptyList())

            // Outline the cluster regions so the user can see what was estimated.
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val clusterContours = contours.filter { c ->
                val first = c.toArray().firstOrNull()
                val keep = first != null && flowersInLabel[labelPixels[first.y.toInt() * cols + first.x.toInt()]] > 0
                if (!keep) c.release()
                keep
            }
            return ClusterEstimate(extra, clusterContours)
        } finally {
            labels.release(); stats.release(); centroids.release(); hierarchy.release()
        }
    }
}
