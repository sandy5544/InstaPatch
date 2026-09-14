package app.morphe.extension.instagram;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class FollowsYouIndicator {
    private static final String TAG = "morphe_follows_you_indicator";
    private static final String USER_CLASS = "com.instagram.user.model.User";
    private static final String USER_EXT_CLASS = "com.instagram.user.model.UserExtKt";

    private FollowsYouIndicator() {}

    public static void show(Object profileInfo, Object badge) {
        if (profileInfo == null || badge == null) return;
        try {
            Object user = findUser(profileInfo, Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), 0);
            if (user == null || !followsYou(user)) return;

            View badgeView = getBadgeView(badge);
            if (badgeView == null || !(badgeView.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) badgeView.getParent();

            View existing = parent.findViewWithTag(TAG);
            if (existing != null) {
                existing.setVisibility(View.VISIBLE);
                return;
            }

            TextView indicator = new TextView(badgeView.getContext());
            indicator.setTag(TAG);
            indicator.setText("✓  Follows you");
            indicator.setTextColor(Color.DKGRAY);
            indicator.setTextSize(12f);
            indicator.setGravity(Gravity.CENTER_VERTICAL);
            indicator.setIncludeFontPadding(false);
            indicator.setSingleLine(true);
            indicator.setPadding(dp(badgeView, 8), dp(badgeView, 5), dp(badgeView, 8), dp(badgeView, 5));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.TRANSPARENT);
            bg.setCornerRadius(dp(badgeView, 20));
            bg.setStroke(1, 0x33000000);
            indicator.setBackground(bg);

            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(badgeView, 4), 0, dp(badgeView, 4));
            parent.addView(indicator, Math.min(parent.indexOfChild(badgeView) + 1, parent.getChildCount()), lp);
        } catch (Throwable ignored) {
        }
    }

    private static boolean followsYou(Object user) {
        try {
            Class<?> helper = Class.forName(USER_EXT_CLASS);
            for (Method method : helper.getDeclaredMethods()) {
                if (!"A0V".equals(method.getName()) || method.getParameterTypes().length != 1) continue;
                if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) continue;
                method.setAccessible(true);
                return Boolean.TRUE.equals(method.invoke(null, user));
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static View getBadgeView(Object badge) {
        try {
            for (Class<?> c = badge.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod("getView");
                    m.setAccessible(true);
                    Object value = m.invoke(badge);
                    return value instanceof View ? (View) value : null;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findUser(Object value, Set<Object> visited, int depth) {
        if (value == null || depth > 5 || !visited.add(value)) return null;
        if (USER_CLASS.equals(value.getClass().getName())) return value;
        Class<?> type = value.getClass();
        if (type.isPrimitive() || type.isEnum() || type == String.class || type.isArray()
                || Number.class.isAssignableFrom(type) || type == Boolean.class || type == Character.class) return null;

        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    field.setAccessible(true);
                    Object user = findUser(field.get(value), visited, depth + 1);
                    if (user != null) return user;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
