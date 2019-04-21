/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
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
package org.floens.chan.ui.toolbar;

public class ToolbarPresenter {
    public enum AnimationStyle {
        NONE,
        PUSH,
        POP,
        FADE
    }

    public enum TransitionAnimationStyle {
        PUSH,
        POP
    }

    private Callback callback;

    private NavigationItem item;
    private NavigationItem transition;

    public ToolbarPresenter(Callback callback) {
        this.callback = callback;
    }

    void set(NavigationItem newItem, AnimationStyle animation) {
        cancelTransitionIfNeeded();
        if (closeSearchIfNeeded()) {
            animation = AnimationStyle.FADE;
        }

        item = newItem;

        callback.showForNavigationItem(item, animation);
    }

    void update(NavigationItem updatedItem) {
        callback.updateViewForItem(updatedItem);
    }

    void startTransition(NavigationItem newItem) {
        cancelTransitionIfNeeded();
        if (closeSearchIfNeeded()) {
            callback.showForNavigationItem(item, AnimationStyle.NONE);
        }

        transition = newItem;

        callback.containerStartTransition(transition, TransitionAnimationStyle.POP);
    }

    void stopTransition(boolean didComplete) {
        if (transition == null) {
            return;
        }

        callback.containerStopTransition(didComplete);

        if (didComplete) {
            item = transition;
            callback.showForNavigationItem(item, AnimationStyle.NONE);
        }
        transition = null;
    }

    void setTransitionProgress(float progress) {
        if (transition == null) {
            return;
        }

        callback.containerSetTransitionProgress(progress);
    }

    void openSearch() {
        if (item == null || item.search) return;

        cancelTransitionIfNeeded();

        item.search = true;
        callback.showForNavigationItem(item, AnimationStyle.FADE);

        callback.onSearchVisibilityChanged(item, true);
    }

    boolean closeSearch() {
        if (item == null || !item.search) return false;

        item.search = false;
        item.searchText = null;
        set(item, AnimationStyle.FADE);

        callback.onSearchVisibilityChanged(item, false);

        return true;
    }

    private void cancelTransitionIfNeeded() {
        if (transition != null) {
            callback.containerStopTransition(false);
            transition = null;
        }
    }

    private boolean closeSearchIfNeeded() {
        // Cancel search, but don't unmark it as a search item so that onback will automatically pull up the search window
        if (item != null && item.search) {
            callback.onSearchVisibilityChanged(item, false);
            return true;
        }
        return false;
    }

    void searchInput(String input) {
        if (!item.search) {
            return;
        }

        item.searchText = input;
        callback.onSearchInput(item, input);
    }

    interface Callback {
        void showForNavigationItem(NavigationItem item, AnimationStyle animation);

        void containerStartTransition(NavigationItem item, TransitionAnimationStyle animation);

        void containerStopTransition(boolean didComplete);

        void containerSetTransitionProgress(float progress);

        void onSearchVisibilityChanged(NavigationItem item, boolean visible);

        void onSearchInput(NavigationItem item, String input);

        void updateViewForItem(NavigationItem item);
    }
}
