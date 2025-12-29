package com.urovo.templatedetector.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * 动画工具类
 */
public class AnimationUtils {

    private static final int DURATION_SHORT = 200;
    private static final int DURATION_MEDIUM = 300;
    private static final int DURATION_LONG = 500;

    private AnimationUtils() {}

    /**
     * 淡入动画
     */
    public static void fadeIn(View view) {
        fadeIn(view, DURATION_MEDIUM, null);
    }

    public static void fadeIn(View view, int duration, Runnable onEnd) {
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator());
        
        if (onEnd != null) {
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onEnd.run();
                }
            });
        }
        
        animator.start();
    }

    /**
     * 淡出动画
     */
    public static void fadeOut(View view) {
        fadeOut(view, DURATION_MEDIUM, null);
    }

    public static void fadeOut(View view, int duration, Runnable onEnd) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator());
        
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setVisibility(View.GONE);
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
        
        animator.start();
    }

    /**
     * 缩放弹入动画
     */
    public static void scaleIn(View view) {
        scaleIn(view, DURATION_MEDIUM, null);
    }

    public static void scaleIn(View view, int duration, Runnable onEnd) {
        view.setScaleX(0.8f);
        view.setScaleY(0.8f);
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);

        PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat("scaleX", 0.8f, 1f);
        PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat("scaleY", 0.8f, 1f);
        PropertyValuesHolder alpha = PropertyValuesHolder.ofFloat("alpha", 0f, 1f);

        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY, alpha);
        animator.setDuration(duration);
        animator.setInterpolator(new OvershootInterpolator(1.2f));

        if (onEnd != null) {
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onEnd.run();
                }
            });
        }

        animator.start();
    }

    /**
     * 缩放弹出动画
     */
    public static void scaleOut(View view) {
        scaleOut(view, DURATION_SHORT, null);
    }

    public static void scaleOut(View view, int duration, Runnable onEnd) {
        PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat("scaleX", 1f, 0.8f);
        PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat("scaleY", 1f, 0.8f);
        PropertyValuesHolder alpha = PropertyValuesHolder.ofFloat("alpha", 1f, 0f);

        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY, alpha);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator());

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setVisibility(View.GONE);
                // 重置状态
                view.setScaleX(1f);
                view.setScaleY(1f);
                view.setAlpha(1f);
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });

        animator.start();
    }

    /**
     * 脉冲动画（用于选中效果）
     */
    public static void pulse(View view) {
        PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat("scaleX", 1f, 1.1f, 1f);
        PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat("scaleY", 1f, 1.1f, 1f);

        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY);
        animator.setDuration(DURATION_SHORT);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.start();
    }

    /**
     * 抖动动画（用于错误提示）
     */
    public static void shake(View view) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, "translationX", 
                0, 10, -10, 10, -10, 5, -5, 0);
        animator.setDuration(DURATION_MEDIUM);
        animator.start();
    }

    /**
     * 交叉淡入淡出（切换两个View）
     */
    public static void crossFade(View fadeOutView, View fadeInView) {
        crossFade(fadeOutView, fadeInView, DURATION_MEDIUM);
    }

    public static void crossFade(View fadeOutView, View fadeInView, int duration) {
        // 淡入
        fadeInView.setAlpha(0f);
        fadeInView.setVisibility(View.VISIBLE);
        fadeInView.animate()
                .alpha(1f)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // 淡出
        fadeOutView.animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> fadeOutView.setVisibility(View.GONE))
                .start();
    }
}
