package com.github.k1rakishou.chan.core.usecase

import com.github.k1rakishou.chan.core.manager.PostFilterManager
import com.github.k1rakishou.chan.core.manager.PostHideManager
import com.github.k1rakishou.common.mutableListWithCap
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.post.ChanPostHide

class FilterOutHiddenImagesUseCase(
  private val postHideManager: PostHideManager,
  private val postFilterManager: PostFilterManager
) {

  fun<T> filter(parameter: Input<T>): Output<T> {
    val images = parameter.images

    var prevSelectedImageIndex = parameter.index
    if (prevSelectedImageIndex >= images.size) {
      prevSelectedImageIndex = images.lastIndex
    }

    if (prevSelectedImageIndex < 0) {
      return Output<T>(parameter.images, parameter.index)
    }

    val isOpeningAlbum = parameter.isOpeningAlbum
    val shouldBypassFilters = parameter.shouldBypassFilters

    Logger.d(TAG, "filter() called with ${images.size} images, index=$prevSelectedImageIndex, isOpeningAlbum=$isOpeningAlbum, shouldBypassFilters=$shouldBypassFilters")

    // If filters should be bypassed (archive threads), return all images unchanged
    if (shouldBypassFilters) {
      Logger.d(TAG, "filter() shouldBypassFilters=true, returning all ${images.size} images unchanged - FILTERING BYPASSED!")
      return Output<T>(parameter.images, parameter.index)
    }

    // Group images by thread and get hide/filter data once per thread to avoid repeated database calls
    val groupedImages = images.groupBy { image -> parameter.postDescriptorSelector(image)?.threadDescriptor() }
    
    val threadHideMaps = mutableMapOf<ChanDescriptor.ThreadDescriptor, Map<PostDescriptor, ChanPostHide>>()
    groupedImages.keys.forEach { threadDescriptor ->
      if (threadDescriptor != null) {
        threadHideMaps[threadDescriptor] = postHideManager.getHiddenPostsForThread(threadDescriptor)
          .associateBy { chanPostHide -> chanPostHide.postDescriptor }
      }
    }

    val resultList = mutableListWithCap<T>(images.size / 2)
    // Track which original images were filtered out to properly adjust index
    val originalToFilteredIndexMap = mutableMapOf<Int, Int>()

    var filteredIndex = 0
    images.forEachIndexed { originalIndex, image ->
      val threadDescriptor = parameter.postDescriptorSelector(image)?.threadDescriptor()
      
      if (threadDescriptor == null) {
        resultList += image
        originalToFilteredIndexMap[originalIndex] = filteredIndex
        filteredIndex++
        return@forEachIndexed
      }

      val postDescriptor = parameter.postDescriptorSelector(image)
        ?: return@forEachIndexed

      val chanPostHidesMap = threadHideMaps[threadDescriptor] ?: emptyMap()
      val chanPostHide = chanPostHidesMap[postDescriptor]

      if (chanPostHide != null && !chanPostHide.manuallyRestored) {
        // Hidden or removed - skip this image
        Logger.d(TAG, "filter() skipping image at originalIndex=$originalIndex (post hidden/removed)")
        return@forEachIndexed
      }

      if (postFilterManager.getFilterStubOrRemove(postDescriptor)) {
        // Filtered out - skip this image
        Logger.d(TAG, "filter() skipping image at originalIndex=$originalIndex (post filtered)")
        return@forEachIndexed
      }

      // Image passes filters - add to result
      resultList += image
      originalToFilteredIndexMap[originalIndex] = filteredIndex
      Logger.d(TAG, "filter() mapping originalIndex=$originalIndex -> filteredIndex=$filteredIndex")
      filteredIndex++
    }

    Logger.d(TAG, "filter() after filtering: ${resultList.size} images remain, originalToFilteredIndexMap=$originalToFilteredIndexMap")

    if (resultList.isEmpty()) {
      return Output(emptyList(), 0)
    }

    if (isOpeningAlbum) {
      var newIndex = 0

      // Since the image index we were about to scroll to may happen to be a hidden image, we need
      // to find the next image that exists in resultList (meaning it's not hidden).
      for (index in prevSelectedImageIndex until images.size) {
        val mappedIndex = originalToFilteredIndexMap[index]
        if (mappedIndex != null) {
          newIndex = mappedIndex
          break
        }
      }

      Logger.d(TAG, "filter() album mode: originalIndex=$prevSelectedImageIndex -> newIndex=$newIndex")
      return Output(resultList, newIndex)
    }

    // For media viewer (non-album), use the mapping to find the correct filtered index
    var newSelectedImageIndex = originalToFilteredIndexMap[prevSelectedImageIndex]
    if (newSelectedImageIndex == null) {
      Logger.d(TAG, "filter() originalIndex=$prevSelectedImageIndex was filtered out, finding fallback")
      
      // The originally selected image was filtered out, find the next available image
      var fallbackIndex: Int? = null
      
      // First try to find an image after the original selection
      for (index in (prevSelectedImageIndex + 1) until images.size) {
        val mappedIndex = originalToFilteredIndexMap[index]
        if (mappedIndex != null) {
          fallbackIndex = mappedIndex
          Logger.d(TAG, "filter() found fallback after: originalIndex=$index -> filteredIndex=$mappedIndex")
          break
        }
      }
      
      // If no image found after, try before the original selection
      if (fallbackIndex == null) {
        for (index in (prevSelectedImageIndex - 1) downTo 0) {
          val mappedIndex = originalToFilteredIndexMap[index]
          if (mappedIndex != null) {
            fallbackIndex = mappedIndex
            Logger.d(TAG, "filter() found fallback before: originalIndex=$index -> filteredIndex=$mappedIndex")
            break
          }
        }
      }
      
      // Last resort: use first image
      newSelectedImageIndex = fallbackIndex ?: 0
      Logger.d(TAG, "filter() final fallback index: $newSelectedImageIndex")
    } else {
      Logger.d(TAG, "filter() direct mapping: originalIndex=$prevSelectedImageIndex -> filteredIndex=$newSelectedImageIndex")
    }

    Logger.d(TAG, "filter() returning ${resultList.size} images with newSelectedImageIndex=$newSelectedImageIndex")
    return Output(resultList, newSelectedImageIndex)
  }

  data class Input<T>(
    val images: List<T>,
    val index: Int,
    val isOpeningAlbum: Boolean,
    val postDescriptorSelector: (T) -> PostDescriptor?,
    val shouldBypassFilters: Boolean = false
  )

  data class Output<T>(
    val images: List<T>,
    val index: Int
  )

  companion object {
    private const val TAG = "FilterOutHiddenImagesUseCase"
  }

}