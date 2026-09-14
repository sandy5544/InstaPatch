package app.morphe.extension.instagram.patches.userlist;

import com.instagram.user.model.User;
import com.instagram.user.model.UserExtKt;

public final class FollowsYouIndicator {
    private FollowsYouIndicator() {}

    public static String append(Object userObject, String text) {
        try {
            if (userObject instanceof User && UserExtKt.A0V((User) userObject)) {
                if (text == null || text.contains("Follows you")) return text;
                return text + " • Follows you";
            }
        } catch (Throwable ignored) {
        }
        return text;
    }
}
