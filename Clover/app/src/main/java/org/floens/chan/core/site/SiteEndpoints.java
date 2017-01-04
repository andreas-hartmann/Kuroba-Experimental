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
package org.floens.chan.core.site;

import org.floens.chan.core.model.Board;
import org.floens.chan.core.model.Loadable;
import org.floens.chan.core.model.Post;

import java.util.Map;

/**
 * Endpoints for {@link Site}.
 */
public interface SiteEndpoints {
    String catalog(Board board);

    String thread(Board board, Loadable loadable);

    String imageUrl(Post.Builder post, Map<String, String> arg);

    String thumbnailUrl(Post.Builder post, boolean spoiler, Map<String, String> arg);

    String flag(Post.Builder post, String countryCode, Map<String, String> arg);

    String boards();

    String reply(Loadable thread);

    String delete(Post post);

    String report(Post post);

    String login();
}
