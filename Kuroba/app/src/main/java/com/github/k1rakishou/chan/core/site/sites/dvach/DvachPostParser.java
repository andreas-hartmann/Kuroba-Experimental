package com.github.k1rakishou.chan.core.site.sites.dvach;

import android.graphics.Color;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.github.k1rakishou.chan.core.manager.PostFilterManager;
import com.github.k1rakishou.chan.core.model.Post;
import com.github.k1rakishou.chan.core.site.common.DefaultPostParser;
import com.github.k1rakishou.chan.core.site.parser.CommentParser;
import com.github.k1rakishou.chan.ui.theme.ChanTheme;
import com.github.k1rakishou.chan.utils.Logger;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DvachPostParser extends DefaultPostParser {

    private Pattern colorPattern = Pattern.compile("color:rgb\\((\\d+),(\\d+),(\\d+)\\);");
    private static final String TAG = "DvachPostParser";

    public DvachPostParser(CommentParser commentParser, PostFilterManager postFilterManager) {
        super(commentParser, postFilterManager);
    }

    @Override
    public Post parse(@NonNull ChanTheme theme, Post.Builder builder, Callback callback) {
        builder.name = Parser.unescapeEntities(builder.name, false);
        parseNameForColor(builder);
        return super.parse(theme, builder, callback);
    }

    private void parseNameForColor(Post.Builder builder) {
        CharSequence nameRaw = builder.name;

        try {
            String name = nameRaw.toString();

            Document document = Jsoup.parseBodyFragment(name);
            Element span = document.body().getElementsByTag("span").first();
            if (span != null) {
                String style = span.attr("style");
                builder.posterId = span.text();
                builder.name = document.body().textNodes().get(0).text().trim();

                if (!TextUtils.isEmpty(style)) {
                    style = style.replace(" ", "");

                    Matcher matcher = colorPattern.matcher(style);
                    if (matcher.find()) {
                        int r = Integer.parseInt(matcher.group(1));
                        int g = Integer.parseInt(matcher.group(2));
                        int b = Integer.parseInt(matcher.group(3));

                        builder.idColor = Color.rgb(r, g, b);
                    }
                }
            }
        } catch (Exception e) {
            Logger.e(TAG, "Error parsing name html", e);
        }
    }
}