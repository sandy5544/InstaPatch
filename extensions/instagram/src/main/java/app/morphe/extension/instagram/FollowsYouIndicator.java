package app.morphe.extension.instagram;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Locale;

public final class FollowsYouIndicator {
    private static final String TAG = "morphe_follows_you_indicator";
    private static final String SUFFIX = " • Follows you";
    private static final int MAX_ATTEMPTS = 20;
    private static final long RETRY_DELAY_MS = 150L;

    private FollowsYouIndicator() {}

    public static void update(Object fragment, Object user) {
        if (fragment == null || user == null) return;
        final boolean followsYou = readFollowsYou(user);
        schedule(fragment, followsYou, 0);
    }

    private static boolean readFollowsYou(Object user) {
        try {
            Class<?> helper = Class.forName("com.instagram.user.model.UserExtKt");
            for (Method method : helper.getDeclaredMethods()) {
                if ("A0V".equals(method.getName())
                        && method.getParameterTypes().length == 1
                        && method.getReturnType() == boolean.class) {
                    method.setAccessible(true);
                    Object result = method.invoke(null, user);
                    return result instanceof Boolean && (Boolean) result;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void schedule(final Object fragment, final boolean followsYou, final int attempt) {
        if (attempt >= MAX_ATTEMPTS) return;
        final View root = getRootView(fragment);
        if (root != null && apply(root, followsYou)) return;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { schedule(fragment, followsYou, attempt + 1); }
        }, RETRY_DELAY_MS);
    }

    private static View getRootView(Object fragment) {
        try {
            Method method = fragment.getClass().getMethod("getView");
            Object value = method.invoke(fragment);
            return value instanceof View ? (View) value : null;
        } catch (Throwable ignored) { return null; }
    }

    private static boolean apply(View root, boolean followsYou) {
        View old = findTagged(root);
        if (old != null) {
            old.setVisibility(followsYou ? View.VISIBLE : View.GONE);
            return true;
        }
        if (!followsYou) return true;

        ViewGroup host = findStatsHost(root);
        if (host == null) host = findUsernameHost(root);
        if (host == null) return false;

        LinearLayout pill = new LinearLayout(host.getContext());
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setTag(TAG);
        pill.setPadding(dp(host, 10), dp(host, 5), dp(host, 10), dp(host, 5));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(host, 18));
        bg.setStroke(dp(host, 1), 0xff3a3a3a);
        pill.setBackground(bg);

        TextView label = new TextView(host.getContext());
        label.setText("✓  Follows you");
        label.setTextSize(14);
        label.setTextColor(0xfff1f1f1);
        pill.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        host.addView(pill, Math.min(findInsertIndex(host), host.getChildCount()));
        return true;
    }

    private static int findInsertIndex(ViewGroup host) {
        int last = -1;
        for (int i = 0; i < host.getChildCount(); i++) {
            if (containsStats(host.getChildAt(i))) last = i;
        }
        return last >= 0 ? last + 1 : host.getChildCount();
    }

    private static ViewGroup findStatsHost(View root) {
        return findStatsHost(root, 0);
    }

    private static ViewGroup findStatsHost(View view, int depth) {
        if (depth > 12 || !(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsStats(group.getChildAt(i))) count++;
        }
        if (count >= 2) return group;
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup result = findStatsHost(group.getChildAt(i), depth + 1);
            if (result != null) return result;
        }
        return null;
    }

    private static boolean containsStats(View view) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText() == null ? "" : ((TextView) view).getText().toString();
            text = text.trim().toLowerCase(Locale.ROOT);
            return "posts".equals(text) || "followers".equals(text) || "following".equals(text);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsStats(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static ViewGroup findUsernameHost(View root) {
        TextView username = findUsername(root);
        if (username == null) return null;
        View current = username;
        for (int i = 0; i < 8 && current.getParent() instanceof ViewGroup; i++) {
            ViewGroup parent = (ViewGroup) current.getParent();
            if (parent.getChildCount() >= 2) return parent;
            current = parent;
        }
        return null;
    }

    private static TextView findUsername(View view) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText() == null ? "" : ((TextView) view).getText().toString().trim();
            if (!text.isEmpty() && !text.contains(" ") && !text.equalsIgnoreCase("Follows you")) return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findUsername(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private static View findTagged(View view) {
        if (TAG.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findTagged(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
