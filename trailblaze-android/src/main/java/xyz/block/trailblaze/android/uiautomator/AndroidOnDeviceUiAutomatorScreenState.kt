package xyz.block.trailblaze.android.uiautomator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import maestro.DeviceInfo
import maestro.Platform
import xyz.block.trailblaze.InstrumentationUtil.withUiAutomation
import xyz.block.trailblaze.InstrumentationUtil.withUiDevice
import xyz.block.trailblaze.android.MaestroUiAutomatorXmlParser
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode.Companion.relabelWithFreshIds
import xyz.block.trailblaze.setofmark.android.AndroidBitmapUtils.scale
import xyz.block.trailblaze.setofmark.android.AndroidBitmapUtils.toByteArray
import xyz.block.trailblaze.setofmark.android.AndroidCanvasSetOfMark
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyTreeNodeUtils
import java.io.ByteArrayOutputStream

/**
 * Snapshot in time of what the screen has in it.
 */
class AndroidOnDeviceUiAutomatorScreenState(
  filterViewHierarchy: Boolean = false,
  maxDimension1: Int? = 1024,
  maxDimension2: Int? = 512,
  private val setOfMarkEnabled: Boolean = true,
  maxAttempts: Int = 1,
  includeScreenshot: Boolean = true,
) : ScreenState {

  override var deviceWidth: Int = -1
  override var deviceHeight: Int = -1
  override var screenshotBytes: ByteArray
  override var viewHierarchyOriginal: ViewHierarchyTreeNode
  override var viewHierarchy: ViewHierarchyTreeNode

  init {
    val (displayWidth, displayHeight) = withUiDevice { displayWidth to displayHeight }
    deviceWidth = displayWidth
    deviceHeight = displayHeight

    var matched = false
    var attempts = 0
    var lastViewHierarchyOriginal: ViewHierarchyTreeNode? = null
    var lastViewHierarchy: ViewHierarchyTreeNode? = null
    var lastScreenshotBytes: ByteArray? = null

    while (!matched && attempts < maxAttempts) {
      val vh1Original = MaestroUiAutomatorXmlParser.getUiAutomatorViewHierarchyAsSerializableTreeNodes(
        xmlHierarchy = dumpViewHierarchy(),
        excludeKeyboardElements = false,
      ).relabelWithFreshIds()

      // Filter the view hierarchy if needed
      val vh1Filtered = if (filterViewHierarchy) {
        val viewHierarchyFilter = ViewHierarchyFilter(screenHeight = deviceHeight, screenWidth = deviceWidth)
        viewHierarchyFilter.filterInteractableViewHierarchyTreeNodes(vh1Original)
      } else {
        vh1Original
      }

      val screenshot = if (includeScreenshot) {
        getScreenshot(vh1Filtered, maxDimension1, maxDimension2)
      } else {
        null
      }

      val vh2Original = MaestroUiAutomatorXmlParser.getUiAutomatorViewHierarchyAsSerializableTreeNodes(
        xmlHierarchy = dumpViewHierarchy(),
        excludeKeyboardElements = false,
      )

      lastViewHierarchyOriginal = vh1Original
      lastViewHierarchy = vh1Filtered
      lastScreenshotBytes = screenshot

      // Create a copy of the view hierarchies with the node ids all set to 0 and then compare
      val vh1Zeroed = vh1Original.copy(nodeId = 0)
      val vh2Zeroed = vh2Original.copy(nodeId = 0)

      if (vh1Zeroed == vh2Zeroed) {
        matched = true
      } else {
        attempts++
        if (attempts < maxAttempts) {
          Thread.sleep((attempts * 100).toLong())
        }
      }
    }

    // Ensure these are set after the loop
    viewHierarchyOriginal = lastViewHierarchyOriginal ?: throw IllegalStateException("Failed to get view hierarchy")
    viewHierarchy = lastViewHierarchy ?: throw IllegalStateException("Failed to get view hierarchy")
    screenshotBytes = lastScreenshotBytes ?: ByteArray(0)
  }

  companion object {
    fun dumpViewHierarchy(): String = ByteArrayOutputStream().use { outputStream ->
      withUiDevice {
        setCompressedLayoutHierarchy(false)
        dumpWindowHierarchy(outputStream)
      }
      outputStream.toString()
    }

    /**
     * Creates a blank bitmap with the current window size.
     *
     * Used when a screenshot comes back as "null" which occurs on secure screens.
     */
    fun createBlankBitmapForCurrentWindowSize(): Bitmap = withUiDevice {
      // Create a bitmap with the specified width and height
      val bitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ARGB_8888)
      // Create a canvas to draw on the bitmap
      val canvas = Canvas(bitmap)
      // Fill the canvas with a black background
      canvas.drawColor(Color.BLACK)
      bitmap
    }

    /**
     * Takes the screenshot with UiAutomator.
     * If the screenshot is blank, we'll draw bounding boxes based on view hierarchy data.
     */
    fun takeScreenshot(
      viewHierarchy: ViewHierarchyTreeNode?,
      setOfMarkEnabled: Boolean = true,
    ): Bitmap? {
      val screenshotBitmap = withUiAutomation { takeScreenshot() }
      try {
        if (setOfMarkEnabled && screenshotBitmap != null) {
          viewHierarchy?.let {
            val markedBitmap = addSetOfMark(screenshotBitmap, viewHierarchy)
            screenshotBitmap.recycle()
            return markedBitmap
          }
        }
        return screenshotBitmap
      } catch (e: Exception) {
        screenshotBitmap.recycle()
        throw e
      }
    }

    private fun addSetOfMark(screenshotBitmap: Bitmap, viewHierarchy: ViewHierarchyTreeNode): Bitmap {
      val mutableBitmap = if (!screenshotBitmap.isMutable) {
        screenshotBitmap.copy(Bitmap.Config.ARGB_8888, true)
      } else {
        screenshotBitmap
      }
      AndroidCanvasSetOfMark.drawSetOfMarkOnBitmap(
        originalScreenshotBitmap = mutableBitmap,
        elements = ViewHierarchyTreeNodeUtils.from(
          viewHierarchy,
          DeviceInfo(
            platform = Platform.ANDROID,
            widthPixels = mutableBitmap.width,
            heightPixels = mutableBitmap.height,
            widthGrid = mutableBitmap.width,
            heightGrid = mutableBitmap.height,
          ),
        ),
        includeLabel = true,
      )
      return mutableBitmap
    }
  }

  private fun getScreenshot(
    viewHierarchy: ViewHierarchyTreeNode?,
    maxDimension1: Int?,
    maxDimension2: Int?,
  ): ByteArray? {
    val screenshotBitmap = takeScreenshot(
      viewHierarchy = viewHierarchy,
      setOfMarkEnabled = setOfMarkEnabled,
    )
    if (screenshotBitmap != null) {
      val scaledBitmap = if (maxDimension1 != null && maxDimension2 != null) {
        screenshotBitmap.scale(
          maxDim1 = maxDimension1,
          maxDim2 = maxDimension2,
        )
      } else {
        screenshotBitmap
      }
      val compressedBitmapBytes: ByteArray = scaledBitmap.toByteArray()
      scaledBitmap.recycle()
      return compressedBitmapBytes
    } else {
      return null
    }
  }
}
