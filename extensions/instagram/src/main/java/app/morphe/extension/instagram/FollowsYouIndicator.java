package app.morphe.extension.instagram;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Adds an idempotent "Follows you" suffix to the username TextView on an Instagram profile.
 *
 * This intentionally avoids compile-time references to Instagram's obfuscated classes.
 */
public final class FollowsYouIndicator {
    private static final String SUFFIX = " • Follows you";
    private static final int MAX_ATTEMPTS = 16;
    private static final long RETRY_DELAY_MS = 150L;

    private FollowsYouIndicator() {
    }

    public static void update(Object fragment, Object user) {
        if (fragment == null || user == null) {
            return;
        }

        boolean followsYou = readFollowsYou(user);
        String username = readUsername(user);
        if (username == null || username.length() == 0) {
            return;
        }

        schedule(fragment, username, followsYou, 0);
    }

    private static boolean readFollowsYou(Object user) {
        try {
            Class<?> helper = Class.forName("com.instagram.user.model.UserExtKt");
            for (Method method : helper.getDeclaredMethods()) {
                if (!"A0V".equals(method.getName()) || method.getParameterTypes().length != 1
                        || method.getReturnType() != boolean.class) {
                    continue;
                }
                method.setAccessible(true);
                Object result = method.invoke(null, user);
                return result instanceof Boolean && (Boolean) result;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String readUsername(Object user) {
        // Instagram's public model API, when present.
        try {
            Method method = user.getClass().getMethod("getUsername");
            Object value = method.invoke(user);
            if (value instanceof String && !((String) value).isEmpty()) {
                return (String) value;
            }
        } catch (Throwable ignored) {
        }

        // Instagram 439's obfuscated User.A07() returns the username string.
        try {
            Method method = user.getClass().getDeclaredMethod("A07");
            method.setAccessible(true);
            Object value = method.invoke(user);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void schedule(final Object fragment, final String username,
                                 final boolean followsYou, final int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return;
        }

        final View root = getRootView(fragment);
        if (root == null) {
            post(fragment, username, followsYou, attempt + 1);
            return;
        }

        if (!applyToTree(root, username, followsYou)) {
            post(fragment, username, followsYou, attempt + 1);
        }
    }

    private static void post(final Object fragment, final String username,
                             final boolean followsYou, final int nextAttempt) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                schedule(fragment, username, followsYou, nextAttempt);
            }
        }, RETRY_DELAY_MS);
    }

    private static View getRootView(Object fragment) {
        try {
            Method method = fragment.getClass().getMethod("getView");
            Object view = method.invoke(fragment);
            return view instanceof View ? (View) view : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean applyToTree(View view, String username, boolean followsYou) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            CharSequence value = textView.getText();
            if (value != null) {
                String text = value.toString();
                String base = stripSuffix(text);
                if (matchesUsername(base, username)) {
                    String desired = followsYou ? base + SUFFIX : base;
                    if (!desired.equals(text)) {
                        textView.setText(desired);
                    }
                    return true;
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (applyToTree(group.getChildAt(i), username, followsYou)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stripSuffix(String text) {
        int suffixIndex = text.indexOf(SUFFIX);
        return suffixIndex >= 0 ? text.substring(0, suffixIndex).trim() : text.trim();
    }

    private static boolean matchesUsername(String text, String username) {
        String left = normalizeUsername(text);
        String right = normalizeUsername(username);
        return left.equals(right);
    }

    private static String normalizeUsername(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
