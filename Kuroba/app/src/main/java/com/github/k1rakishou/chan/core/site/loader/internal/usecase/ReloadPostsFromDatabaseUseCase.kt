package com.github.k1rakishou.chan.core.site.loader.internal.usecase

import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.mapper.ChanPostMapper
import com.github.k1rakishou.chan.core.model.Post
import com.github.k1rakishou.chan.core.site.parser.ChanReaderProcessor
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.chan.utils.Logger
import com.github.k1rakishou.common.mutableListWithCap
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.repository.ChanPostRepository
import com.google.gson.Gson

class ReloadPostsFromDatabaseUseCase(
  private val gson: Gson,
  private val chanPostRepository: ChanPostRepository,
  private val boardManager: BoardManager
) {

  suspend fun reloadPosts(
    chanReaderProcessor: ChanReaderProcessor,
    chanDescriptor: ChanDescriptor
  ): List<Post> {
    BackgroundUtils.ensureBackgroundThread()

    chanPostRepository.awaitUntilInitialized()

    val posts = when (chanDescriptor) {
      is ChanDescriptor.ThreadDescriptor -> {
        val maxCount = chanReaderProcessor.getThreadCap()

        // When in the mode, we can just select every post we have for this thread
        // descriptor and then just sort the in the correct order. We should also use
        // the stickyCap parameter if present.
        chanPostRepository.getThreadPosts(chanDescriptor, maxCount)
      }
      is ChanDescriptor.CatalogDescriptor -> {
        val postsToGet = chanReaderProcessor.getPostNoListOrdered()

        // When in catalog mode, we can't just select posts from the database and then
        // sort them, because the actual order of the posts in the catalog depends on
        // a lot of stuff (thread may be saged/auto-saged by mods etc). So the easiest way
        // is to get every post by it's postNo that we receive from the server. It's
        // already in correct order (the server order) so we don't even need to sort
        // them.
        chanPostRepository.getCatalogOriginalPosts(chanDescriptor, postsToGet).unwrap()
      }
    }.map { post ->
      return@map ChanPostMapper.toPost(
        gson,
        post,
        null
      )
    }

    return when (chanDescriptor) {
      is ChanDescriptor.ThreadDescriptor -> posts
      is ChanDescriptor.CatalogDescriptor -> chanReaderProcessor.getPostsSortedByIndexes(posts)
    }
  }

  suspend fun reloadPosts(chanDescriptor: ChanDescriptor): List<Post> {
    BackgroundUtils.ensureBackgroundThread()

    chanPostRepository.awaitUntilInitialized()

    return when (chanDescriptor) {
      is ChanDescriptor.ThreadDescriptor -> {
        chanPostRepository.preloadForThread(chanDescriptor).safeUnwrap { error ->
          Logger.e(TAG, "reloadPosts() Failed to preload posts for thread ${chanDescriptor}", error)
          return emptyList()
        }

        chanPostRepository.getThreadPosts(chanDescriptor, Int.MAX_VALUE)
      }
      is ChanDescriptor.CatalogDescriptor -> {
        boardManager.awaitUntilInitialized()

        val board = boardManager.byBoardDescriptor(chanDescriptor.boardDescriptor)
          ?: return emptyList()

        val postsToLoadCount = board.pages * board.perPage
        chanPostRepository.getCatalogOriginalPosts(chanDescriptor, postsToLoadCount).unwrap()
      }
    }.map { post ->
      return@map ChanPostMapper.toPost(
        gson,
        post,
        null
      )
    }
  }

  suspend fun reloadCatalogThreads(threadDescriptors: List<ChanDescriptor.ThreadDescriptor>): List<Post> {
    BackgroundUtils.ensureBackgroundThread()
    chanPostRepository.awaitUntilInitialized()

    val mapOfPosts = chanPostRepository.getCatalogOriginalPosts(threadDescriptors)
      .safeUnwrap { error ->
        Logger.e(TAG, "reloadCatalogThreads() reloadCatalogThreads failure", error)
        return emptyList()
      }

    return mutableListWithCap(mapOfPosts.values)
  }

  companion object {
    private const val TAG = "ReloadPostsFromDatabaseUseCase"
  }

}