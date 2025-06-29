package com.github.k1rakishou.chan.core.helper

import androidx.annotation.VisibleForTesting
import com.github.k1rakishou.chan.core.manager.IPostFilterManager
import com.github.k1rakishou.chan.core.manager.IPostHideManager
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.hashSetWithCap
import com.github.k1rakishou.common.linkedMapWithCap
import com.github.k1rakishou.common.mutableIteration
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.post.ChanPost
import com.github.k1rakishou.model.data.post.ChanPostHide
import com.github.k1rakishou.model.data.post.ChanPostWithFilterResult
import com.github.k1rakishou.model.data.post.PostFilter
import com.github.k1rakishou.model.data.post.PostFilterResult
import com.github.k1rakishou.persist_state.PersistableChanState
import com.github.k1rakishou.persist_state.ManuallyUnhiddenPostsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PostHideHelper(
  private val postHideManager: IPostHideManager,
  private val postFilterManager: IPostFilterManager
) {

  fun countPostHides(posts: List<ChanPost>): Int {
    return postHideManager.countPostHides(posts.map { it.postDescriptor })
  }

  fun countMatchedFilters(posts: List<ChanPost>): Int {
    return postFilterManager.countMatchedFilters(posts.map { it.postDescriptor })
  }

  /**
   * Searches for hidden posts in the PostHide table then checks whether there are posts with a reply
   * to already hidden posts and if there are hides them as well.
   */
  suspend fun processPostFilters(
    chanDescriptor: ChanDescriptor,
    posts: List<ChanPost>,
    additionalPostsToReparse: MutableSet<PostDescriptor>,
    bypassFilters: Boolean = false
  ): ModularResult<List<ChanPost>> {
    return withContext(Dispatchers.IO) {
      return@withContext ModularResult.Try {
        Logger.d(TAG, "processPostFilters($chanDescriptor) called with bypassFilters=$bypassFilters, posts.size=${posts.size}")
        Logger.d(TAG, "processPostFilters: BYPASS FILTERS = $bypassFilters")
        
        // If bypassFilters is true, return all posts unchanged
        if (bypassFilters) {
          Logger.d(TAG, "processPostFilters($chanDescriptor) bypassFilters=true, returning all ${posts.size} posts unchanged - FILTERING BYPASSED!")
          
          // Log details about the first post (OP) to debug visibility issues
          val firstPost = posts.firstOrNull()
          if (firstPost != null) {
            val isOP = firstPost is com.github.k1rakishou.model.data.post.ChanOriginalPost
            Logger.d(TAG, "processPostFilters: First post (OP): postNo=${firstPost.postDescriptor.postNo}, isOP=$isOP, isDeleted=${firstPost.isDeleted}")
          }
          
          return@Try posts
        }

        Logger.d(TAG, "processPostFilters($chanDescriptor) bypassFilters=false, proceeding with normal filtering")

        val postDescriptorSet = posts.map { post -> post.postDescriptor }.toSet()
        val postFilterMap = postFilterManager.getManyPostFilters(postDescriptorSet)
        val hiddenPostsLookupMap = postHideManager.getHiddenPostsMap(postDescriptorSet).toMutableMap()
        val newChanPostHides = mutableMapOf<PostDescriptor, ChanPostHideWrapper>()

        Logger.d(TAG, "processPostFilters($chanDescriptor) start")

        val resultMap = processPostFiltersInternal(
          posts = posts,
          chanDescriptor = chanDescriptor,
          hiddenPostsLookupMap = hiddenPostsLookupMap,
          postFilterMap = postFilterMap,
          newChanPostHides = newChanPostHides
        )

        if (newChanPostHides.isNotEmpty()) {
          val chanPostHides = newChanPostHides.values.map { it.chanPostHide }
          postHideManager.createOrUpdateMany(chanPostHides)

          val postDescriptors = newChanPostHides.values.mapNotNull { chanPostHideWrapper ->
            if (!chanPostHideWrapper.createdByFilter) {
              return@mapNotNull null
            }

            return@mapNotNull chanPostHideWrapper.chanPostHide.postDescriptor
          }.toSet()

          if (postDescriptors.isNotEmpty()) {
            additionalPostsToReparse.addAll(postDescriptors)
          }
        }

        var hiddenPostsCount = 0
        var removedPostsCount = 0
        var normalPostsCount = 0

        for ((_, chanPostWithFilterResult) in resultMap.entries) {
          when (chanPostWithFilterResult.postFilterResult) {
            PostFilterResult.Hide -> ++hiddenPostsCount
            PostFilterResult.Remove -> ++removedPostsCount
            PostFilterResult.Leave -> ++normalPostsCount
          }
        }

        Logger.d(TAG, "processPostFilters($chanDescriptor) end (hiddenPostsCount=$hiddenPostsCount, " +
          "removedPostsCount=$removedPostsCount, normalPostsCount=$normalPostsCount, total=${resultMap.size})")

        Logger.d(TAG, "About to call applyPersistentUnhiding with ${resultMap.size} posts")
        // Apply persistent unhiding - override any filter decisions for manually unhidden posts
        applyPersistentUnhiding(resultMap)
        Logger.d(TAG, "Finished calling applyPersistentUnhiding")

        resultMap.mutableIteration { mutableIterator, entry ->
          val chanPostWithFilterResult = entry.value
          if (chanPostWithFilterResult.postFilterResult == PostFilterResult.Remove) {
            mutableIterator.remove()
          }

          return@mutableIteration true
        }

        if (AppModuleAndroidUtils.isDevBuild()) {
          resultMap.values.forEach { chanPostWithFilterResult ->
            val postDescriptor = chanPostWithFilterResult.chanPost.postDescriptor
            val postFilterResult = chanPostWithFilterResult.postFilterResult

            if (postFilterResult == PostFilterResult.Remove) {
              error("Post with PostFilterResult.Remove found! postDescriptor=${postDescriptor}")
            }
          }
        }

        return@Try resultMap.values.map { it.chanPost }
      }
    }
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  fun processPostFiltersInternal(
    posts: List<ChanPost>,
    chanDescriptor: ChanDescriptor,
    hiddenPostsLookupMap: MutableMap<PostDescriptor, ChanPostHide>,
    postFilterMap: Map<PostDescriptor, PostFilter>,
    newChanPostHides: MutableMap<PostDescriptor, ChanPostHideWrapper>,
  ): MutableMap<PostDescriptor, ChanPostWithFilterResult> {
    val resultMap = linkedMapWithCap<PostDescriptor, ChanPostWithFilterResult>(posts.size)
    val processingCatalog = chanDescriptor is ChanDescriptor.ICatalogDescriptor

    val postsFastLookupMap = linkedMapWithCap<PostDescriptor, ChanPost>(posts.size)
    for (post in posts) {
      postsFastLookupMap[post.postDescriptor] = post
    }

    // First pass, process the posts
    for (post in posts) {
      val postDescriptor = post.postDescriptor
      val postHide = hiddenPostsLookupMap[postDescriptor]
      val postFilter = postFilterMap[postDescriptor]

      if (postFilter != null) {
        check(postFilter.enabled) { "Post filter must be enabled here" }
      }

      val canHideThisPost = canHidePost(processingCatalog, post, postFilter, postHide)
      val canRemoveThisPost = canRemovePost(processingCatalog, post, postFilter, postHide)

      val postFilterResult = when {
        canRemoveThisPost -> PostFilterResult.Remove
        canHideThisPost -> PostFilterResult.Hide
        else -> PostFilterResult.Leave
      }

      if ((canHideThisPost || canRemoveThisPost) && postHide == null && postFilter != null) {
        // TODO(KurobaEx): this probably can be simplified to only "val onlyHide = !canRemoveThisPost"?
        @Suppress("RedundantIf") val onlyHide = if ((canHideThisPost && canRemoveThisPost) || canRemoveThisPost) {
          false
        } else {
          true
        }

        createNewChanPostHide(
          postFilter = postFilter,
          postDescriptor = postDescriptor,
          newChanPostHides = newChanPostHides,
          hiddenPostsLookupMap = hiddenPostsLookupMap,
          onlyHide = onlyHide,
          applyToReplies = postFilter.replies
        )
      }

      resultMap[postDescriptor] = ChanPostWithFilterResult(
        chanPost = post,
        postFilterResult = postFilterResult
      )
    }

    if (!processingCatalog) {
      val alreadyVisited = hashSetWithCap<PostDescriptor>(64)

      // Second pass, process the reply chains (Do not do this in the catalogs)
      for ((sourcePost, _) in resultMap.values) {
        val sourcePostDescriptor = sourcePost.postDescriptor

        val sourceChanPostWithFilterResult = resultMap[sourcePostDescriptor] ?: continue
        if (sourceChanPostWithFilterResult.postFilterResult != PostFilterResult.Leave) {
          // Already processed and we either hide or remove it, no need to process it again
          continue
        }

        val sourcePostHide = hiddenPostsLookupMap[sourcePostDescriptor]
        if (sourcePostHide?.manuallyRestored == true) {
          // This post was manually unhidden/unremoved by the user. Do not auto hide/remove it again.
          sourceChanPostWithFilterResult.postFilterResult = PostFilterResult.Leave
          continue
        }

        for (targetPostDescriptor in sourcePost.repliesTo) {
          alreadyVisited.clear()

          val targetPostHide = findParentNonNullPostHide(
            postDescriptor = targetPostDescriptor,
            hiddenPostsLookupMap = hiddenPostsLookupMap,
            newChanPostHides = newChanPostHides,
            postMap = postsFastLookupMap,
            alreadyVisited = alreadyVisited
          )

          var targetPostFilter = postFilterMap[targetPostDescriptor]
          if (targetPostFilter == null && targetPostHide != null) {
            targetPostFilter = postFilterMap[targetPostHide.postDescriptor]
          }

          val applyToReplies = processingCatalog
            || (targetPostFilter?.replies == true)
            || (targetPostHide?.applyToReplies == true)

          if (!applyToReplies) {
            continue
          }

          val targetChanPostWithFilterResult = resultMap[targetPostDescriptor]
            ?: continue

          if (targetChanPostWithFilterResult.postFilterResult == PostFilterResult.Leave) {
            continue
          }

          val onlyHide = targetChanPostWithFilterResult.postFilterResult == PostFilterResult.Hide

          createNewChanPostHide(
            postFilter = targetPostFilter,
            postDescriptor = sourcePostDescriptor,
            newChanPostHides = newChanPostHides,
            hiddenPostsLookupMap = hiddenPostsLookupMap,
            onlyHide = onlyHide,
            applyToReplies = applyToReplies
          )

          sourceChanPostWithFilterResult.postFilterResult = targetChanPostWithFilterResult.postFilterResult
          break
        }
      }
    }

    return resultMap
  }

  private fun createNewChanPostHide(
    postFilter: PostFilter?,
    postDescriptor: PostDescriptor,
    newChanPostHides: MutableMap<PostDescriptor, ChanPostHideWrapper>,
    hiddenPostsLookupMap: MutableMap<PostDescriptor, ChanPostHide>,
    onlyHide: Boolean,
    applyToReplies: Boolean,
  ) {
    if (newChanPostHides[postDescriptor]?.chanPostHide?.manuallyRestored == true) {
      return
    }

    if (newChanPostHides.containsKey(postDescriptor)) {
      return
    }

    val chanPostHide = ChanPostHide(
      postDescriptor = postDescriptor,
      onlyHide = onlyHide,
      applyToWholeThread = false,
      applyToReplies = applyToReplies,
      manuallyRestored = false
    )

    newChanPostHides[postDescriptor] = ChanPostHideWrapper(
      chanPostHide = chanPostHide,
      createdByFilter = postFilter != null
    )
    hiddenPostsLookupMap[postDescriptor] = chanPostHide
  }

  private fun findParentNonNullPostHide(
    postDescriptor: PostDescriptor,
    hiddenPostsLookupMap: MutableMap<PostDescriptor, ChanPostHide>,
    newChanPostHides: Map<PostDescriptor, ChanPostHideWrapper>,
    postMap: Map<PostDescriptor, ChanPost>,
    alreadyVisited: HashSet<PostDescriptor>
  ): ChanPostHide? {
    var chanPostHide = hiddenPostsLookupMap[postDescriptor]
    if (chanPostHide != null) {
      return chanPostHide
    }

    chanPostHide = newChanPostHides[postDescriptor]?.chanPostHide
    if (chanPostHide != null) {
      return chanPostHide
    }

    val chanPost = postMap[postDescriptor]
    if (chanPost == null) {
      return null
    }

    alreadyVisited.add(postDescriptor)

    for (targetPostDescriptor in chanPost.repliesTo) {
      // In some thread we can end up in a really long reply chains where a post can be checked
      // millions of times if we don't skip already visited posts
      if (alreadyVisited.contains(targetPostDescriptor)) {
        continue
      }

      val parentChanPostHide = findParentNonNullPostHide(
        postDescriptor = targetPostDescriptor,
        hiddenPostsLookupMap = hiddenPostsLookupMap,
        newChanPostHides = newChanPostHides,
        postMap = postMap,
        alreadyVisited = alreadyVisited
      )

      if (parentChanPostHide != null) {
        return parentChanPostHide
      }
    }

    return null
  }

  private fun canRemovePost(
    processingCatalog: Boolean,
    post: ChanPost,
    postFilter: PostFilter?,
    postHide: ChanPostHide?
  ): Boolean {
    if (postFilter == null && postHide == null) {
      return false
    }

    // Check if this post is in the persistent unhidden list
    val unhiddenPostsList = PersistableChanState.manuallyUnhiddenPosts.get()
    val postDescriptorString = post.postDescriptor.serializeToString()
    if (unhiddenPostsList.containsPostString(postDescriptorString)) {
      Logger.d(TAG, "canRemovePost: Post $postDescriptorString is in persistent unhidden list, not removing")
      return false
    }

    if (postFilter != null) {
      val attemptingToHide = (postFilter.enabled && postFilter.remove)
      if (attemptingToHide) {
        return true
      }
    }

    if (postHide != null) {
      if (postHide.manuallyRestored) {
        return false
      }

      if (processingCatalog) {
        if (post.isOP() && !postHide.applyToWholeThread) {
          return false
        }
      } else {
        if (post.isOP()) {
          return false
        }
      }

      if (!postHide.onlyHide) {
        return true
      }
    }

    return false
  }

  private fun canHidePost(
    processingCatalog: Boolean,
    post: ChanPost,
    postFilter: PostFilter?,
    postHide: ChanPostHide?
  ): Boolean {
    if (postFilter == null && postHide == null) {
      return false
    }

    // Check if this post is in the persistent unhidden list
    val unhiddenPostsList = PersistableChanState.manuallyUnhiddenPosts.get()
    val postDescriptorString = post.postDescriptor.serializeToString()
    if (unhiddenPostsList.containsPostString(postDescriptorString)) {
      Logger.d(TAG, "canHidePost: Post $postDescriptorString is in persistent unhidden list, not hiding")
      return false
    }

    if (postFilter != null) {
      val attemptingToHide = (postFilter.enabled && postFilter.stub)
      if (attemptingToHide) {
        return true
      }
    }

    if (postHide != null) {
      if (postHide.manuallyRestored) {
        return false
      }

      if (processingCatalog) {
        if (post.isOP() && !postHide.applyToWholeThread) {
          return false
        }
      } else {
        if (post.isOP()) {
          return false
        }
      }

      if (postHide.onlyHide) {
        return true
      }
    }

    return false
  }

  /**
   * Applies persistent unhiding to posts that were manually unhidden by the user.
   * This ensures that manually unhidden posts remain visible even after filters are applied.
   */
  private fun applyPersistentUnhiding(resultMap: MutableMap<PostDescriptor, ChanPostWithFilterResult>) {
    val unhiddenPostsList = PersistableChanState.manuallyUnhiddenPosts.get()
    
    Logger.d(TAG, "applyPersistentUnhiding: unhiddenPostsList.size=${unhiddenPostsList.size()}, isEmpty=${unhiddenPostsList.isEmpty()}")
    Logger.d(TAG, "applyPersistentUnhiding: resultMap.size=${resultMap.size}")
    
    if (unhiddenPostsList.isEmpty()) {
      Logger.d(TAG, "applyPersistentUnhiding: unhiddenPostsList is empty, returning early")
      return
    }

    // Log all unhidden posts for debugging
    val allUnhiddenPosts = unhiddenPostsList.getAllPostDescriptorStrings()
    Logger.d(TAG, "applyPersistentUnhiding: All unhidden posts: ${allUnhiddenPosts.joinToString(", ")}")

    var unhiddenCount = 0
    for ((postDescriptor, chanPostWithFilterResult) in resultMap.entries) {
      val postDescriptorString = postDescriptor.serializeToString()
      
      if (unhiddenPostsList.containsPostString(postDescriptorString)) {
        Logger.d(TAG, "applyPersistentUnhiding: Found unhidden post: $postDescriptorString, current filter result: ${chanPostWithFilterResult.postFilterResult}")
        // This post was manually unhidden, override any filter decision
        if (chanPostWithFilterResult.postFilterResult != PostFilterResult.Leave) {
          chanPostWithFilterResult.postFilterResult = PostFilterResult.Leave
          unhiddenCount++
          Logger.d(TAG, "applyPersistentUnhiding: Changed filter result to Leave for: $postDescriptorString")
        }
      }
    }

    if (unhiddenCount > 0) {
      Logger.d(TAG, "Applied persistent unhiding to $unhiddenCount posts")
    } else {
      Logger.d(TAG, "applyPersistentUnhiding: No posts were unhidden (no matches found between unhidden list and current posts)")
    }
  }

  /**
   * Adds a post to the persistent unhidden list so it will remain visible across app restarts.
   * Call this when a user manually unhides a post.
   */
  fun addToPersistentUnhiddenList(postDescriptor: PostDescriptor) {
    val existingList = PersistableChanState.manuallyUnhiddenPosts.get()
    val postDescriptorString = postDescriptor.serializeToString()
    
    if (!existingList.containsPostString(postDescriptorString)) {
      // Create a NEW instance to avoid reference equality issues in setSync()
      val newUnhiddenPostsList = ManuallyUnhiddenPostsList(existingList.postDescriptorStrings.toMutableSet())
      newUnhiddenPostsList.addPostString(postDescriptorString)
      PersistableChanState.manuallyUnhiddenPosts.setSync(newUnhiddenPostsList)
      Logger.d(TAG, "Added post to persistent unhidden list: $postDescriptorString")
    }
  }

  /**
   * Removes a post from the persistent unhidden list.
   * Call this when a user manually hides a previously unhidden post.
   */
  fun removeFromPersistentUnhiddenList(postDescriptor: PostDescriptor) {
    val existingList = PersistableChanState.manuallyUnhiddenPosts.get()
    val postDescriptorString = postDescriptor.serializeToString()
    
    if (existingList.containsPostString(postDescriptorString)) {
      // Create a NEW instance to avoid reference equality issues in setSync()
      val newUnhiddenPostsList = ManuallyUnhiddenPostsList(existingList.postDescriptorStrings.toMutableSet())
      newUnhiddenPostsList.removePostString(postDescriptorString)
      PersistableChanState.manuallyUnhiddenPosts.setSync(newUnhiddenPostsList)
      Logger.d(TAG, "Removed post from persistent unhidden list: $postDescriptorString")
    }
  }

  class ChanPostHideWrapper(
    val chanPostHide: ChanPostHide,
    val createdByFilter: Boolean
  )

  companion object {
    private const val TAG = "PostHideHelper"
  }
}