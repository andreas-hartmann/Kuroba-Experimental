/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.k1rakishou.chan.ui.controller;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.k1rakishou.chan.R;
import com.github.k1rakishou.chan.core.manager.PostPreloadedInfoHolder;
import com.github.k1rakishou.chan.core.model.Post;
import com.github.k1rakishou.chan.core.model.PostImage;
import com.github.k1rakishou.chan.core.presenter.ThreadPresenter;
import com.github.k1rakishou.chan.core.settings.ChanSettings;
import com.github.k1rakishou.chan.ui.cell.PostCellInterface;
import com.github.k1rakishou.chan.ui.helper.PostPopupHelper;
import com.github.k1rakishou.chan.ui.theme.ThemeEngine;
import com.github.k1rakishou.chan.ui.theme.widget.ColorizableRecyclerView;
import com.github.k1rakishou.chan.ui.view.LoadView;
import com.github.k1rakishou.chan.ui.view.ThumbnailView;
import com.github.k1rakishou.chan.utils.AndroidUtils;
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import static com.github.k1rakishou.chan.Chan.inject;
import static com.github.k1rakishou.chan.utils.AndroidUtils.inflate;

public class PostRepliesController
        extends BaseFloatingController implements ThemeEngine.ThemeChangesListener {
    private static final LruCache<Long, Integer> scrollPositionCache = new LruCache<>(128);

    @Inject
    ThemeEngine themeEngine;

    private PostPopupHelper postPopupHelper;
    private ThreadPresenter presenter;
    private LoadView loadView;
    private ColorizableRecyclerView repliesView;
    private PostPopupHelper.RepliesData displayingData;
    private boolean first = true;

    private TextView repliesBackText;
    private TextView repliesCloseText;

    @Override
    protected int getLayoutId() {
        return R.layout.layout_post_replies_container;
    }

    public PostRepliesController(Context context, PostPopupHelper postPopupHelper, ThreadPresenter presenter) {
        super(context);
        inject(this);

        this.postPopupHelper = postPopupHelper;
        this.presenter = presenter;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Clicking outside the popup view
        view.setOnClickListener(v -> postPopupHelper.pop());

        loadView = view.findViewById(R.id.loadview);
        themeEngine.addListener(this);
    }

    @Override
    public void onShow() {
        super.onShow();

        onThemeChanged();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        themeEngine.removeListener(this);
        forceRecycleAllReplyViews();
        repliesView.setAdapter(null);
    }

    @Override
    public void onThemeChanged() {
        if (themeEngine == null) {
            return;
        }

        boolean isDarkColor = AndroidUtils.isDarkColor(themeEngine.chanTheme.getBackColor());

        Drawable backDrawable = themeEngine.getDrawableTinted(context, R.drawable.ic_arrow_back_white_24dp, isDarkColor);
        Drawable doneDrawable = themeEngine.getDrawableTinted(context, R.drawable.ic_done_white_24dp, isDarkColor);

        if (repliesBackText != null) {
            repliesBackText.setTextColor(themeEngine.chanTheme.getTextColorPrimary());
            repliesBackText.setCompoundDrawablesWithIntrinsicBounds(backDrawable, null, null, null);
        }

        if (repliesCloseText != null) {
            repliesCloseText.setTextColor(themeEngine.chanTheme.getTextColorPrimary());
            repliesCloseText.setCompoundDrawablesWithIntrinsicBounds(doneDrawable, null, null, null);
        }

        if (repliesView != null) {
            RecyclerView.Adapter<?> adapter = repliesView.getAdapter();
            if (adapter instanceof RepliesAdapter) {
                ((RepliesAdapter) adapter).refresh();
            }
        }
    }

    private void forceRecycleAllReplyViews() {
        RecyclerView.Adapter<?> adapter = repliesView.getAdapter();
        if (adapter instanceof RepliesAdapter) {
            repliesView.getRecycledViewPool().clear();
            ((RepliesAdapter) adapter).clear();
        }
    }

    public ThumbnailView getThumbnail(PostImage postImage) {
        if (repliesView == null) {
            return null;
        }

        ThumbnailView thumbnail = null;

        for (int i = 0; i < repliesView.getChildCount(); i++) {
            View view = repliesView.getChildAt(i);
            if (view instanceof PostCellInterface) {
                PostCellInterface postView = (PostCellInterface) view;
                Post post = postView.getPost();

                if (post != null) {
                    for (PostImage image : post.getPostImages()) {
                        if (image.equalUrl(postImage)) {
                            thumbnail = postView.getThumbnailView(postImage);
                        }
                    }
                }
            }
        }

        return thumbnail;
    }

    public void setPostRepliesData(ChanDescriptor chanDescriptor, PostPopupHelper.RepliesData data) {
        displayData(chanDescriptor, data);
    }

    public List<Post> getPostRepliesData() {
        return displayingData.posts;
    }

    public void scrollTo(int displayPosition) {
        repliesView.smoothScrollToPosition(displayPosition);
    }

    private void displayData(ChanDescriptor chanDescriptor, final PostPopupHelper.RepliesData data) {
        storeScrollPosition();
        displayingData = data;

        View dataView = inflate(context, R.layout.layout_post_replies_bottombuttons);
        dataView.setId(R.id.post_replies_data_view_id);

        repliesView = dataView.findViewById(R.id.post_list);
        View repliesBack = dataView.findViewById(R.id.replies_back);
        repliesBack.setOnClickListener(v -> postPopupHelper.pop());

        View repliesClose = dataView.findViewById(R.id.replies_close);
        repliesClose.setOnClickListener(v -> postPopupHelper.popAll());

        repliesBackText = dataView.findViewById(R.id.replies_back_icon);
        repliesCloseText = dataView.findViewById(R.id.replies_close_icon);

        PostPreloadedInfoHolder postPreloadedInfoHolder = new PostPreloadedInfoHolder();
        postPreloadedInfoHolder.preloadPostsInfo(data.posts);

        RepliesAdapter repliesAdapter = new RepliesAdapter(
                presenter,
                postPreloadedInfoHolder,
                chanDescriptor,
                themeEngine
        );

        repliesAdapter.setHasStableIds(true);
        repliesView.setLayoutManager(new LinearLayoutManager(context));
        repliesView.setAdapter(repliesAdapter);
        repliesView.getRecycledViewPool().setMaxRecycledViews(RepliesAdapter.POST_REPLY_VIEW_TYPE, 0);
        repliesAdapter.setData(data);

        loadView.setFadeDuration(first ? 0 : 150);
        loadView.setView(dataView);

        first = false;
        restoreScrollPosition(data.forPost.no);

        onThemeChanged();
    }

    private void storeScrollPosition() {
        if (displayingData == null) {
            return;
        }

        RecyclerView.LayoutManager layoutManager = repliesView.getLayoutManager();
        if (layoutManager == null) {
            return;
        }

        if (!(layoutManager instanceof LinearLayoutManager)) {
            return;
        }

        LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
        int position = linearLayoutManager.findFirstVisibleItemPosition();

        if (position == RecyclerView.NO_POSITION) {
            return;
        }

        scrollPositionCache.put(displayingData.forPost.no, position);
    }

    private void restoreScrollPosition(long postNo) {
        RecyclerView.LayoutManager layoutManager = repliesView.getLayoutManager();
        if (layoutManager == null) {
            return;
        }

        if (!(layoutManager instanceof LinearLayoutManager)) {
            return;
        }

        Integer scrollPosition = scrollPositionCache.get(postNo);
        if (scrollPosition == null) {
            return;
        }

        LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
        linearLayoutManager.scrollToPosition(scrollPosition);
    }

    private static class RepliesAdapter extends RecyclerView.Adapter<ReplyViewHolder> {
        public static final int POST_REPLY_VIEW_TYPE = 0;

        private ThreadPresenter presenter;
        private PostPreloadedInfoHolder postPreloadedInfoHolder;
        private ChanDescriptor chanDescriptor;
        private PostPopupHelper.RepliesData data;
        private ThemeEngine themeEngine;

        public RepliesAdapter(
                ThreadPresenter presenter,
                PostPreloadedInfoHolder postPreloadedInfoHolder,
                ChanDescriptor chanDescriptor,
                ThemeEngine themeEngine
        ) {
            this.presenter = presenter;
            this.postPreloadedInfoHolder = postPreloadedInfoHolder;
            this.chanDescriptor = chanDescriptor;
            this.themeEngine = themeEngine;
        }

        @NonNull
        @Override
        public ReplyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = inflate(parent.getContext(), R.layout.cell_post, parent, false);

            return new ReplyViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ReplyViewHolder holder, int position) {
            holder.onBind(
                    presenter,
                    postPreloadedInfoHolder,
                    chanDescriptor,
                    data.posts.get(position),
                    data.forPost.no,
                    position,
                    getItemCount(),
                    themeEngine
            );
        }

        @Override
        public int getItemViewType(int position) {
            return POST_REPLY_VIEW_TYPE;
        }

        @Override
        public int getItemCount() {
            return data.posts.size();
        }

        @Override
        public long getItemId(int position) {
            Post post = data.posts.get(position);
            int repliesFromSize = post.getRepliesFromCount();
            return ((long) repliesFromSize << 32L) + post.no;
        }

        @Override
        public void onViewRecycled(@NonNull ReplyViewHolder holder) {
            if (holder.itemView instanceof PostCellInterface) {
                ((PostCellInterface) holder.itemView).onPostRecycled(true);
            }
        }

        public void setData(PostPopupHelper.RepliesData data) {
            this.data = new PostPopupHelper.RepliesData(data.forPost, new ArrayList<>(data.posts));
            notifyDataSetChanged();
        }

        public void refresh() {
            notifyDataSetChanged();
        }

        public void clear() {
            data.posts.clear();
            notifyDataSetChanged();
        }
    }

    private static class ReplyViewHolder extends RecyclerView.ViewHolder {
        private PostCellInterface postCellInterface;

        public ReplyViewHolder(@NonNull View itemView) {
            super(itemView);

            this.postCellInterface = (PostCellInterface) itemView;
        }

        public void onBind(
                ThreadPresenter presenter,
                PostPreloadedInfoHolder postPreloadedInfoHolder,
                ChanDescriptor chanDescriptor,
                Post post,
                long markedNo,
                int position,
                int itemCount,
                ThemeEngine themeEngine
        ) {
            boolean showDivider = position < itemCount - 1;

            postCellInterface.setPost(
                    chanDescriptor,
                    post,
                    -1,
                    -1,
                    presenter,
                    postPreloadedInfoHolder,
                    true,
                    false,
                    false,
                    markedNo,
                    showDivider,
                    ChanSettings.PostViewMode.LIST,
                    false,
                    themeEngine.getChanTheme()
            );
        }
    }

    @Override
    public boolean onBack() {
        postPopupHelper.pop();
        return true;
    }
}