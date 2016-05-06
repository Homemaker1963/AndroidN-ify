package tk.wasdennnoch.androidn_ify.notifications;

import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.XModuleResources;
import android.content.res.XResources;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.text.method.AllCapsTransformationMethod;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.DateTimeView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LayoutInflated;
import tk.wasdennnoch.androidn_ify.R;
import tk.wasdennnoch.androidn_ify.XposedHook;
import tk.wasdennnoch.androidn_ify.utils.LayoutUtils;
import tk.wasdennnoch.androidn_ify.utils.ResourceUtils;

public class NotificationsHooks {

    private static final String PACKAGE_ANDROID = XposedHook.PACKAGE_ANDROID;
    private static final String PACKAGE_SYSTEMUI = XposedHook.PACKAGE_SYSTEMUI;

    private static final String TAG = "NotificationsHooks";

    private static boolean darkTheme = false;
    private static boolean fullWidthVolume = false;
    private static boolean allowLoadLabelWithPackageManager = false;

    private static XC_MethodHook inflateViewsHook = new XC_MethodHook() {

        @SuppressWarnings("deprecation")
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Object entry = param.args[0];
            Object row = XposedHelpers.getObjectField(entry, "row");
            Object contentContainer = XposedHelpers.callMethod(row, "getPrivateLayout");
            Object contentContainerPublic = XposedHelpers.callMethod(row, "getPublicLayout");

            View privateView = (View) XposedHelpers.callMethod(contentContainer, "getContractedChild");
            View publicView = (View) XposedHelpers.callMethod(contentContainerPublic, "getContractedChild");

            Context context = publicView.getContext();

            // Try to find app label for notifications without public version
            TextView textView = (TextView) publicView.findViewById(R.id.public_app_name_text);
            if (textView == null) {
                // For notifications with public version
                textView = (TextView) publicView.findViewById(R.id.app_name_text);
            }

            View time = publicView.findViewById(context.getResources().getIdentifier("time", "id", PACKAGE_SYSTEMUI));
            if (time != null) {
                publicView.findViewById(R.id.public_time_divider).setVisibility(time.getVisibility());
            }

            // Try to find icon for notifications without public version
            ImageView icon = (ImageView) publicView.findViewById(context.getResources().getIdentifier("icon", "id", PACKAGE_SYSTEMUI));
            if (icon == null) {
                // For notifications with public version
                icon = (ImageView) publicView.findViewById(R.id.notification_icon);
            }
            if (icon == null) {
                icon = (ImageView) publicView.findViewById(android.R.id.icon);
            }
            if (icon != null) {
                icon.setBackgroundResource(0);
                icon.setBackgroundColor(context.getResources().getColor(android.R.color.transparent));
                icon.setPadding(0, 0, 0, 0);
            }

            TextView privateTextView = (TextView) privateView.findViewById(R.id.app_name_text);
            if(privateTextView != null) {
                int color = privateTextView.getTextColors().getDefaultColor();

                if(textView != null) {
                    textView.setTextColor(privateTextView.getTextColors());
                    textView.setText(privateTextView.getText());
                }

                if (icon != null) {
                    icon.setColorFilter(color);
                }
            }
        }
    };

    private static XC_MethodHook getStandardViewHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            RemoteViews contentView = (RemoteViews) param.getResult();
            Notification.Builder mBuilder = (Notification.Builder) XposedHelpers.getObjectField(param.thisObject, "mBuilder");

            CharSequence overflowText = (CharSequence) (XposedHelpers.getBooleanField(param.thisObject, "mSummaryTextSet")
                ? XposedHelpers.getObjectField(param.thisObject, "mSummaryText")
                : XposedHelpers.getObjectField(mBuilder, "mSubText"));
            if (overflowText != null) {
                contentView.setTextViewText(R.id.notification_summary, (CharSequence) XposedHelpers.callMethod(mBuilder, "processLegacyText", overflowText));
                contentView.setViewVisibility(R.id.notification_summary_divider, View.VISIBLE);
                contentView.setViewVisibility(R.id.notification_summary, View.VISIBLE);
            }
        }
    };

    private static XC_MethodHook applyStandardTemplateHook = new XC_MethodHook() {

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
            Resources res = context.getResources();
            RemoteViews contentView = (RemoteViews) param.getResult();
            int mColor = (int) XposedHelpers.callMethod(param.thisObject, "resolveColor");
            contentView.setInt(R.id.notification_icon, "setColorFilter", mColor);
            try {
                contentView.setTextViewText(R.id.app_name_text, context.getString(context.getApplicationInfo().labelRes));
            } catch (Exception e) {
                if (allowLoadLabelWithPackageManager) {
                    context.getApplicationInfo().loadLabel(context.getPackageManager());
                }
            }
            contentView.setTextColor(R.id.app_name_text, mColor);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Icon mSmallIcon = (Icon) XposedHelpers.getObjectField(param.thisObject, "mSmallIcon");
                contentView.setImageViewIcon(R.id.notification_icon, mSmallIcon);
                Icon mLargeIcon = (Icon) XposedHelpers.getObjectField(param.thisObject, "mLargeIcon");
                if (mLargeIcon != null) {
                    contentView.setImageViewIcon(res.getIdentifier("right_icon", "id", "android"), mLargeIcon);
                }
            } else {
                int mSmallIcon = XposedHelpers.getIntField(param.thisObject, "mSmallIcon");
                contentView.setImageViewResource(R.id.notification_icon, mSmallIcon);
                Bitmap mLargeIcon = (Bitmap) XposedHelpers.getObjectField(param.thisObject, "mLargeIcon");
                if (mLargeIcon != null) {
                    contentView.setImageViewBitmap(res.getIdentifier("right_icon", "id", "android"), mLargeIcon);
                }
            }
            CharSequence mContentInfo = (CharSequence) XposedHelpers.getObjectField(param.thisObject, "mContentInfo");
            int mNumber = XposedHelpers.getIntField(param.thisObject, "mNumber");
            if (mContentInfo != null) {
                contentView.setViewVisibility(R.id.notification_info_divider, View.VISIBLE);
            } else if (mNumber > 0) {
                contentView.setViewVisibility(R.id.notification_info_divider, View.VISIBLE);
            } else {
                contentView.setViewVisibility(R.id.notification_info_divider, View.GONE);
            }
            if ((boolean) XposedHelpers.callMethod(param.thisObject, "showsTimeOrChronometer")) {
                contentView.setViewVisibility(R.id.time_divider, View.VISIBLE);
            }
            contentView.setInt(res.getIdentifier("right_icon", "id", "android"), "setBackgroundResource", 0);

            int resId = (int) param.args[0];
            if(resId == context.getResources().getIdentifier("notification_template_material_big_media", "layout", PACKAGE_ANDROID) ||
                    resId == context.getResources().getIdentifier("notification_template_material_media", "layout", PACKAGE_ANDROID)) {
                CharSequence mContentText = (CharSequence) XposedHelpers.getObjectField(param.thisObject, "mContentText");
                CharSequence mSubText = (CharSequence) XposedHelpers.getObjectField(param.thisObject, "mSubText");
                if(mContentText != null && mSubText != null) {
                    contentView.setTextViewText(context.getResources().getIdentifier("text", "id", PACKAGE_ANDROID),
                            (CharSequence) XposedHelpers.callMethod(param.thisObject, "processLegacyText", mContentText));
                    contentView.setViewVisibility(context.getResources().getIdentifier("text2", "id", PACKAGE_ANDROID), View.GONE);
                    contentView.setTextViewText(R.id.notification_summary, (CharSequence) XposedHelpers.callMethod(param.thisObject, "processLegacyText", mSubText));
                    contentView.setViewVisibility(R.id.notification_summary, View.VISIBLE);
                    contentView.setViewVisibility(R.id.notification_summary_divider, View.VISIBLE);
                    contentView.setTextColor(R.id.notification_summary, mColor);
                    contentView.setTextColor(R.id.notification_summary_divider, mColor);
                    XposedHelpers.callMethod(param.thisObject, "unshrinkLine3Text");
                }
            }
        }
    };

    private static XC_MethodHook resetStandardTemplateHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            RemoteViews contentView = (RemoteViews) param.args[0];
            contentView.setViewVisibility(R.id.notification_summary_divider, View.GONE);
            contentView.setViewVisibility(R.id.notification_info_divider, View.GONE);
            contentView.setViewVisibility(R.id.time_divider, View.GONE);
            contentView.setTextViewText(R.id.notification_summary, null);
        }
    };

    private static XC_MethodHook processSmallIconAsLargeHook = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
            if (!((boolean) XposedHelpers.callMethod(methodHookParam.thisObject, "isLegacy"))) {
                RemoteViews contentView = (RemoteViews) methodHookParam.args[1];
                int mColor = (int) XposedHelpers.callMethod(methodHookParam.thisObject, "resolveColor");
                XposedHelpers.callMethod(contentView, "setDrawableParameters",
                        android.R.id.icon,
                        false,
                        -1,
                        mColor,
                        PorterDuff.Mode.SRC_ATOP,
                        -1);
            }
            return null;
        }
    };

    private static XC_MethodHook applyStandardTemplateWithActionsHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");

            RemoteViews big = (RemoteViews) param.getResult();
            big.setViewVisibility(context.getResources().getIdentifier("action_divider", "id", PACKAGE_ANDROID), View.GONE);
        }
    };

    private static XC_MethodHook generateActionButtonHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");

            int mColor = (int) XposedHelpers.callMethod(param.thisObject, "resolveColor");
            int textViewId = context.getResources().getIdentifier("action0", "id", PACKAGE_ANDROID);

            RemoteViews button = (RemoteViews) param.getResult();
            button.setTextViewCompoundDrawablesRelative(textViewId, 0, 0, 0, 0);
            button.setTextColor(textViewId, mColor);
        }
    };

    private static XC_MethodHook initConstantsHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            XposedHelpers.setBooleanField(param.thisObject, "mScaleDimmed", false);
        }
    };

    private static XC_MethodHook updateWindowWidthH = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            ViewGroup mDialogView = (ViewGroup) XposedHelpers.getObjectField(param.thisObject, "mDialogView");
            Context context = mDialogView.getContext();
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mDialogView.getLayoutParams();
            lp.setMargins(0, 0, 0, 0);
            if(fullWidthVolume) {
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                lp.width = dm.widthPixels;
            }
            mDialogView.setLayoutParams(lp);
            mDialogView.setBackgroundColor(context.getResources().getColor(context.getResources().getIdentifier("system_primary_color", "color", PACKAGE_SYSTEMUI)));
        }
    };

    private static XC_MethodHook dismissViewButtonConstructorHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Button button = (Button) param.thisObject;

            Drawable mAnimatedDismissDrawable = (Drawable) XposedHelpers.getObjectField(param.thisObject, "mAnimatedDismissDrawable");
            mAnimatedDismissDrawable.setBounds(0, 0, 0, 0);
            Drawable mStaticDismissDrawable = (Drawable) XposedHelpers.getObjectField(param.thisObject, "mStaticDismissDrawable");
            mStaticDismissDrawable.setBounds(0, 0, 0, 0);
            button.setVisibility(View.VISIBLE);
        }
    };

    public static void hookResSystemui(XC_InitPackageResources.InitPackageResourcesParam resparam, XSharedPreferences prefs, String modulePath) {
        try {
            if (prefs.getBoolean("enable_notification_tweaks", true)) {

                XModuleResources modRes = XModuleResources.createInstance(modulePath, resparam.res);
                XResources.DimensionReplacement zero = new XResources.DimensionReplacement(0, TypedValue.COMPLEX_UNIT_DIP);

                // Notifications
                resparam.res.setReplacement(PACKAGE_SYSTEMUI, "dimen", "notification_side_padding", zero);
                resparam.res.setReplacement(PACKAGE_SYSTEMUI, "dimen", "notifications_top_padding", zero);
                resparam.res.setReplacement(PACKAGE_SYSTEMUI, "dimen", "notification_padding", zero);
                resparam.res.setReplacement(PACKAGE_SYSTEMUI, "dimen", "notification_material_rounded_rect_radius", zero);
                resparam.res.setReplacement(PACKAGE_SYSTEMUI, "dimen", "speed_bump_height", zero);
                resparam.res.setReplacement(PACKAGE_SYSTEMUI, "dimen", "notification_min_height", modRes.fwd(R.dimen.notification_min_height));
                resparam.res.setReplacement(PACKAGE_SYSTEMUI, "dimen", "notification_mid_height", modRes.fwd(R.dimen.notification_mid_height));
                resparam.res.setReplacement(PACKAGE_SYSTEMUI, "dimen", "notification_max_height", modRes.fwd(R.dimen.notification_max_height));

                // Drawables
                resparam.res.setReplacement(PACKAGE_SYSTEMUI, "drawable", "notification_header_bg", modRes.fwd(R.drawable.replacement_notification_header_bg));
                resparam.res.setReplacement(PACKAGE_SYSTEMUI, "drawable", "notification_guts_bg", modRes.fwd(R.drawable.replacement_notification_guts_bg));

                // Layouts
                resparam.res.hookLayout(PACKAGE_SYSTEMUI, "layout", "notification_public_default", notification_public_default);

                if(prefs.getBoolean("notification_dismiss_button", false)) {
                    resparam.res.hookLayout(PACKAGE_SYSTEMUI, "layout", "status_bar_notification_dismiss_all", status_bar_notification_dismiss_all);
                    try {
                        resparam.res.hookLayout(PACKAGE_SYSTEMUI, "layout", "recents_dismiss_button", status_bar_notification_dismiss_all);
                    } catch (Exception e) {

                    }
                }

                fullWidthVolume = prefs.getBoolean("notification_full_width_volume", false);
                allowLoadLabelWithPackageManager = prefs.getBoolean("notification_allow_load_label_with_pm", false);
                if (prefs.getBoolean("notification_dark_theme", false)) {
                    darkTheme = true;
                    resparam.res.setReplacement(PACKAGE_SYSTEMUI, "drawable", "notification_material_bg", modRes.fwd(R.drawable.replacement_notification_material_bg_dark));
                    resparam.res.setReplacement(PACKAGE_SYSTEMUI, "drawable", "notification_material_bg_dim", modRes.fwd(R.drawable.replacement_notification_material_bg_dim_dark));
                    resparam.res.setReplacement(PACKAGE_SYSTEMUI, "color", "notification_material_background_low_priority_color", modRes.fwd(R.color.notification_material_background_low_priority_color_dark));
                    resparam.res.setReplacement(PACKAGE_SYSTEMUI, "color", "notification_material_background_media_default_color", modRes.fwd(R.color.notification_material_background_media_default_color_dark));
                    resparam.res.setReplacement(PACKAGE_SYSTEMUI, "color", "notification_ripple_color_low_priority", modRes.fwd(R.color.notification_ripple_color_low_priority_dark));
                } else {
                    resparam.res.setReplacement(PACKAGE_SYSTEMUI, "drawable", "notification_material_bg", modRes.fwd(R.drawable.replacement_notification_material_bg));
                    resparam.res.setReplacement(PACKAGE_SYSTEMUI, "drawable", "notification_material_bg_dim", modRes.fwd(R.drawable.replacement_notification_material_bg_dim));
                }

            }
        } catch (Throwable t) {
            XposedHook.logE(TAG, "Error hooking SystemUI resources", t);
        }
    }

    @SuppressWarnings("unused")
    public static void hook(ClassLoader classLoader, XSharedPreferences prefs) {
        Class classNotificationBuilder = Notification.Builder.class;
        Class classNotificationStyle = Notification.Style.class;
        Class classRemoteViews = RemoteViews.class;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            XposedHelpers.findAndHookMethod(classNotificationBuilder, "processSmallIconAsLarge", Icon.class, classRemoteViews, processSmallIconAsLargeHook);
        } else {
            XposedHelpers.findAndHookMethod(classNotificationBuilder, "processSmallIconAsLarge", int.class, classRemoteViews, processSmallIconAsLargeHook);
        }
        XposedHelpers.findAndHookMethod(classNotificationBuilder, "applyLargeIconBackground", classRemoteViews, XC_MethodReplacement.DO_NOTHING);
        XposedHelpers.findAndHookMethod(classNotificationBuilder, "applyStandardTemplate", int.class, boolean.class, applyStandardTemplateHook);
        XposedHelpers.findAndHookMethod(classNotificationBuilder, "applyStandardTemplateWithActions", int.class, applyStandardTemplateWithActionsHook);
        XposedHelpers.findAndHookMethod(classNotificationBuilder, "resetStandardTemplate", RemoteViews.class, resetStandardTemplateHook);
        XposedHelpers.findAndHookMethod(classNotificationBuilder, "generateActionButton", Notification.Action.class, generateActionButtonHook);
        XposedHelpers.findAndHookMethod(classNotificationStyle, "getStandardView", int.class, getStandardViewHook);
    }

    public static void hookSystemUI(ClassLoader classLoader, XSharedPreferences prefs) {
        try {
            if (prefs.getBoolean("enable_notification_tweaks", true)) {
                Class classBaseStatusBar = XposedHelpers.findClass("com.android.systemui.statusbar.BaseStatusBar", classLoader);
                Class classEntry = XposedHelpers.findClass("com.android.systemui.statusbar.NotificationData.Entry", classLoader);
                Class classStackScrollAlgorithm = XposedHelpers.findClass("com.android.systemui.statusbar.stack.StackScrollAlgorithm", classLoader);
                Class classVolumeDialog = XposedHelpers.findClass("com.android.systemui.volume.VolumeDialog", classLoader);
                Class classDismissViewButton = XposedHelpers.findClass("com.android.systemui.statusbar.DismissViewButton", classLoader);

                XposedHelpers.findAndHookMethod(classBaseStatusBar, "inflateViews", classEntry, ViewGroup.class, inflateViewsHook);
                XposedHelpers.findAndHookMethod(classStackScrollAlgorithm, "initConstants", Context.class, initConstantsHook);
                XposedHelpers.findAndHookMethod(classVolumeDialog, "updateWindowWidthH", updateWindowWidthH);
                if(prefs.getBoolean("notification_dismiss_button", false)) {
                    XposedHelpers.findAndHookConstructor(classDismissViewButton, Context.class, AttributeSet.class, int.class, int.class, dismissViewButtonConstructorHook);
                }
            }
        } catch (Throwable t) {
            XposedHook.logE(TAG, "Error hooking SystemUI", t);
        }
    }

    public static void hookResAndroid(XC_InitPackageResources.InitPackageResourcesParam resparam, XSharedPreferences prefs) {
        try {
            if (prefs.getBoolean("enable_notification_tweaks", true)) {

                //TODO More notification styling in the future

                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_material_action", notification_material_action);
                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_template_icon_group", notification_template_icon_group);
                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_template_material_base", notification_template_material_base);
                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_template_material_media", notification_template_material_media);
                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_template_material_big_base", notification_template_material_base);
                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_template_material_big_media", notification_template_material_big_media);

                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_template_material_big_picture", notification_template_material_big_picture);
                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_template_material_big_text", notification_template_material_base);
                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_template_material_inbox", notification_template_material_base);

                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_template_part_line1", new XC_LayoutInflated() {
                    @Override
                    public void handleLayoutInflated(LayoutInflatedParam liparam) throws Throwable {
                        LinearLayout layout = (LinearLayout) liparam.view;
                        layout.removeViewAt(1);

                        Context context = layout.getContext();
                        ResourceUtils res = ResourceUtils.getInstance(context);

                        int notificationTextMarginEnd = res.getDimensionPixelSize(R.dimen.notification_text_margin_end);

                        TextView title = (TextView) layout.findViewById(context.getResources().getIdentifier("title", "id", PACKAGE_ANDROID));
                        LinearLayout.LayoutParams titleLp = (LinearLayout.LayoutParams) title.getLayoutParams();
                        titleLp.rightMargin = notificationTextMarginEnd;
                        title.setLayoutParams(titleLp);
                    }
                });

                resparam.res.hookLayout(PACKAGE_ANDROID, "layout", "notification_template_part_line3", new XC_LayoutInflated() {
                    @Override
                    public void handleLayoutInflated(LayoutInflatedParam liparam) throws Throwable {
                        LinearLayout layout = (LinearLayout) liparam.view;

                        Context context = layout.getContext();
                        ResourceUtils res = ResourceUtils.getInstance(context);

                        int notificationTextMarginEnd = res.getDimensionPixelSize(R.dimen.notification_text_margin_end);

                        TextView text = (TextView) layout.findViewById(context.getResources().getIdentifier("text", "id", PACKAGE_ANDROID));
                        LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) text.getLayoutParams();
                        textLp.rightMargin = notificationTextMarginEnd;
                        text.setLayoutParams(textLp);

                        layout.removeViewAt(1);
                    }
                });
            }
        } catch (Throwable t) {
            XposedHook.logE(TAG, "Error hooking framework resources", t);
        }
    }

    private static XC_LayoutInflated status_bar_notification_dismiss_all = new XC_LayoutInflated() {

        @Override
        public void handleLayoutInflated(LayoutInflatedParam liparam) throws Throwable {
            FrameLayout layout = (FrameLayout) liparam.view;

            Context context = layout.getContext();
            ResourceUtils res = ResourceUtils.getInstance(context);

            int dismissButtonPadding = res.getDimensionPixelSize(R.dimen.notification_dismiss_button_padding);
            int dismissButtonPaddingTop = res.getDimensionPixelSize(R.dimen.notification_dismiss_button_padding_top);

            Button button = (Button) layout.getChildAt(0);
            button.setTextColor(res.getColor(android.R.color.white));
            button.setText(context.getString(context.getResources().getIdentifier("clear_all_notifications_text", "string", PACKAGE_SYSTEMUI)));
            button.setAllCaps(true);
            button.setBackground(res.getDrawable(R.drawable.ripple_dismiss_all));
            button.setPadding(dismissButtonPadding, dismissButtonPaddingTop, dismissButtonPadding, dismissButtonPadding);

            FrameLayout.LayoutParams buttonLp = (FrameLayout.LayoutParams) button.getLayoutParams();
            buttonLp.width = FrameLayout.LayoutParams.WRAP_CONTENT;
            button.setLayoutParams(buttonLp);
        }
    };

    @SuppressWarnings("deprecation")
    private static XC_LayoutInflated notification_template_icon_group = new XC_LayoutInflated() {
        @Override
        public void handleLayoutInflated(XC_LayoutInflated.LayoutInflatedParam liparam) throws Throwable {
            FrameLayout layout = (FrameLayout) liparam.view;
            layout.removeAllViews();

            Context context = layout.getContext();
            ResourceUtils res = ResourceUtils.getInstance(context);

            int notificationContentPadding = res.getDimensionPixelSize(R.dimen.notification_content_margin_start);
            int notificationHeaderMarginTop = res.getDimensionPixelSize(R.dimen.notification_header_margin_top);
            int iconSize = res.getDimensionPixelSize(R.dimen.notification_icon_size);
            int iconMarginEnd = res.getDimensionPixelSize(R.dimen.notification_icon_margin_end);
            int appNameMarginStart = res.getDimensionPixelSize(R.dimen.notification_app_name_margin_start);
            int appNameMarginEnd = res.getDimensionPixelSize(R.dimen.notification_app_name_margin_end);
            int dividerMarginTop = res.getDimensionPixelSize(R.dimen.notification_divider_margin_top);

            String dividerText = res.getString(R.string.notification_header_divider_symbol);

            LinearLayout linearLayout = new LinearLayout(context);

            FrameLayout.LayoutParams linearLayoutLParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, iconSize);
            linearLayoutLParams.setMargins(0, notificationHeaderMarginTop, 0, notificationContentPadding);

            linearLayout.setLayoutParams(linearLayoutLParams);
            linearLayout.setPadding(notificationContentPadding, 0, notificationContentPadding, 0);

            ImageView fakeIcon = new ImageView(context);
            LinearLayout.LayoutParams fakeIconLParams = new LinearLayout.LayoutParams(0, 0);
            fakeIcon.setLayoutParams(fakeIconLParams);
            fakeIcon.setId(android.R.id.icon);

            ImageView icon = new ImageView(context);
            LinearLayout.LayoutParams iconLParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconLParams.setMarginEnd(iconMarginEnd);
            icon.setLayoutParams(iconLParams);
            icon.setId(R.id.notification_icon);

            TextView textView = new TextView(context);
            LinearLayout.LayoutParams textViewLParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textViewLParams.setMarginStart(appNameMarginStart);
            textViewLParams.setMarginEnd(appNameMarginEnd);
            textView.setLayoutParams(textViewLParams);
            textView.setId(R.id.app_name_text);
            textView.setTextAppearance(context, context.getResources().getIdentifier("TextAppearance.Material.Notification.Info", "style", "android"));

            TextView summaryDivider = new TextView(context);
            LinearLayout.LayoutParams summaryDividerLParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            summaryDividerLParams.setMargins(0, dividerMarginTop, 0, 0);
            summaryDivider.setLayoutParams(summaryDividerLParams);
            summaryDivider.setId(R.id.notification_summary_divider);
            summaryDivider.setText(dividerText);
            summaryDivider.setTextAppearance(context, context.getResources().getIdentifier("TextAppearance.Material.Notification.Info", "style", "android"));
            summaryDivider.setVisibility(View.GONE);

            TextView summaryText = new TextView(context);
            LinearLayout.LayoutParams summaryTextLParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            summaryTextLParams.setMarginStart(appNameMarginStart);
            summaryTextLParams.setMarginEnd(appNameMarginEnd);
            summaryText.setLayoutParams(summaryTextLParams);
            summaryText.setId(R.id.notification_summary);
            summaryText.setTextAppearance(context, context.getResources().getIdentifier("TextAppearance.Material.Notification.Info", "style", "android"));
            summaryText.setVisibility(View.GONE);

            TextView infoDivider = new TextView(context);
            LinearLayout.LayoutParams infoDividerLParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            infoDividerLParams.setMargins(0, dividerMarginTop, 0, 0);
            infoDivider.setLayoutParams(infoDividerLParams);
            infoDivider.setId(R.id.notification_info_divider);
            infoDivider.setText(dividerText);
            infoDivider.setTextAppearance(context, context.getResources().getIdentifier("TextAppearance.Material.Notification.Info", "style", "android"));
            infoDivider.setVisibility(View.GONE);

            TextView infoText = new TextView(context);
            LinearLayout.LayoutParams infoTextLParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            infoTextLParams.setMarginStart(appNameMarginStart);
            infoTextLParams.setMarginEnd(appNameMarginEnd);
            infoText.setLayoutParams(summaryTextLParams);
            infoText.setId(context.getResources().getIdentifier("info", "id", PACKAGE_ANDROID));
            infoText.setTextAppearance(context, context.getResources().getIdentifier("TextAppearance.Material.Notification.Info", "style", "android"));
            infoText.setVisibility(View.GONE);

            TextView divider = new TextView(context);
            LinearLayout.LayoutParams dividerLParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dividerLParams.setMargins(0, dividerMarginTop, 0, 0);
            divider.setLayoutParams(dividerLParams);
            divider.setId(R.id.time_divider);
            divider.setText(res.getString(R.string.notification_header_divider_symbol));
            divider.setTextAppearance(context, context.getResources().getIdentifier("TextAppearance.Material.Notification.Info", "style", "android"));
            divider.setVisibility(View.GONE);

            View timeView = LayoutInflater.from(context).inflate(context.getResources().getIdentifier("notification_template_part_time", "layout", "android"), null);
            LinearLayout.LayoutParams timeViewLParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            timeView.setLayoutParams(timeViewLParams);
            timeView.setId(context.getResources().getIdentifier("time", "id", "android"));
            timeView.setPadding(appNameMarginEnd, 0, 0, 0);

            linearLayout.addView(fakeIcon);
            linearLayout.addView(icon);
            linearLayout.addView(textView);
            linearLayout.addView(summaryDivider);
            linearLayout.addView(summaryText);
            linearLayout.addView(infoDivider);
            linearLayout.addView(infoText);
            linearLayout.addView(divider);
            linearLayout.addView(timeView);

            layout.addView(linearLayout);
        }
    };

    private static XC_LayoutInflated notification_template_material_base = new XC_LayoutInflated() {
        @Override
        public void handleLayoutInflated(XC_LayoutInflated.LayoutInflatedParam liparam) throws Throwable {
            FrameLayout layout = (FrameLayout) liparam.view;
            Context context = layout.getContext();
            ResourceUtils res = ResourceUtils.getInstance(context);

            int headerHeight = res.getDimensionPixelSize(R.dimen.notification_header_height);
            int notificationContentPadding = res.getDimensionPixelSize(R.dimen.notification_content_margin_start);
            int notificationContentPaddingTop = res.getDimensionPixelSize(R.dimen.notification_content_margin_top);
            int rightIconSize = res.getDimensionPixelSize(R.dimen.notification_right_icon_size);
            int rightIconMarginTop = res.getDimensionPixelSize(R.dimen.notification_right_icon_margin_top);
            int rightIconMarginEnd = res.getDimensionPixelSize(R.dimen.notification_right_icon_margin_end);
            int actionsMarginTop = res.getDimensionPixelSize(R.dimen.notification_actions_margin_top);

            FrameLayout headerLayout = (FrameLayout) layout.getChildAt(0);
            LinearLayout notificationMain = (LinearLayout) layout.findViewById(context.getResources().getIdentifier("notification_main_column", "id", "android"));
            ImageView rightIcon = new ImageView(context);

            FrameLayout.LayoutParams headerLayoutLParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight);
            headerLayout.setLayoutParams(headerLayoutLParams);

            FrameLayout.LayoutParams notificationMainLParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            notificationMainLParams.setMargins(0, notificationContentPaddingTop, 0, 0);
            //notificationMainLParams.setMargins(notificationContentPadding, notificationContentPaddingTop, notificationContentPadding, 0);
            notificationMain.setLayoutParams(notificationMainLParams);

            //noinspection SuspiciousNameCombination
            FrameLayout.LayoutParams rightIconLParams = new FrameLayout.LayoutParams(rightIconSize, rightIconSize);
            rightIconLParams.setMargins(0, rightIconMarginTop, rightIconMarginEnd, 0);
            rightIconLParams.gravity = Gravity.TOP | Gravity.END;
            rightIcon.setLayoutParams(rightIconLParams);
            rightIcon.setId(context.getResources().getIdentifier("right_icon", "id", "android"));

            layout.addView(rightIcon);

            ViewGroup.LayoutParams params = layout.getLayoutParams();
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            layout.setLayoutParams(params);

            // Margins for every child except actions container
            int actionsId = context.getResources().getIdentifier("actions", "id", PACKAGE_ANDROID);
            int childCount = notificationMain.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = notificationMain.getChildAt(i);
                if (child.getId() != actionsId) {
                    ViewGroup.MarginLayoutParams childLp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                    childLp.leftMargin += notificationContentPadding;
                    childLp.rightMargin += notificationContentPadding;
                    child.setLayoutParams(childLp);
                } else {
                    ViewGroup.MarginLayoutParams childLp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                    // This only works on Marshmallow as notification templates don't have tag on Lollipop
                    //if (!layout.getTag().equals("inbox")) {

                    if (!liparam.resNames.fullName.contains("notification_template_material_inbox")) {
                        childLp.topMargin += actionsMarginTop;
                    }
                    if (!darkTheme) {
                        child.setBackgroundColor(res.getColor(R.color.notification_action_list));
                    }
                    child.setLayoutParams(childLp);
                    child.setPadding(child.getPaddingLeft() + notificationContentPadding,
                            child.getPaddingTop(),
                            child.getPaddingRight() + notificationContentPadding,
                            child.getPaddingBottom());
                }
            }

            if (layout.getTag().equals("inbox") || layout.getTag().equals("bigText")) {
                // Remove divider
                notificationMain.removeViewAt(notificationMain.getChildCount() - 2);
                // Remove bottom line
                notificationMain.removeViewAt(notificationMain.getChildCount() - 1);
            }
        }
    };

    private static XC_LayoutInflated notification_material_action = new XC_LayoutInflated() {
        @Override
        public void handleLayoutInflated(XC_LayoutInflated.LayoutInflatedParam liparam) throws Throwable {
            Button button = (Button) liparam.view;

            LinearLayout.LayoutParams buttonLp = (LinearLayout.LayoutParams) button.getLayoutParams();
            buttonLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            buttonLp.weight = 0;
            button.setLayoutParams(buttonLp);
        }
    };

    private static XC_LayoutInflated notification_template_material_big_media = new XC_LayoutInflated() {
        @Override
        public void handleLayoutInflated(LayoutInflatedParam liparam) throws Throwable {
            RelativeLayout layout = (RelativeLayout) liparam.view;
            Context context = layout.getContext();
            ResourceUtils res = ResourceUtils.getInstance(context);

            View headerLayout = layout.getChildAt(0);
            LinearLayout notificationMain = (LinearLayout) layout.getChildAt(1);

            int headerHeight = res.getDimensionPixelSize(R.dimen.notification_header_height);
            int headerHeightExcludeTop = res.getDimensionPixelSize(R.dimen.notification_header_height_exclude_top);
            int notificationHeight = res.getDimensionPixelSize(R.dimen.notification_min_height);
            int notificationMidHeight = res.getDimensionPixelSize(R.dimen.notification_mid_height);
            int notificationContentPadding = res.getDimensionPixelSize(R.dimen.notification_content_margin_start);

            LayoutUtils.setHeight(layout, notificationMidHeight);

            ViewGroup.MarginLayoutParams headerLayoutLp = (ViewGroup.MarginLayoutParams) headerLayout.getLayoutParams();
            headerLayoutLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            headerLayoutLp.height = headerHeightExcludeTop;
            headerLayout.setLayoutParams(headerLayoutLp);

            RelativeLayout.LayoutParams notificationMainLp = (RelativeLayout.LayoutParams) notificationMain.getLayoutParams();
            notificationMainLp.topMargin = headerHeight;
            notificationMainLp.leftMargin = notificationContentPadding;
            notificationMain.setLayoutParams(notificationMainLp);
            notificationMain.setMinimumHeight(notificationHeight);
        }
    };

    private static XC_LayoutInflated notification_template_material_media = new XC_LayoutInflated() {
        @Override
        public void handleLayoutInflated(XC_LayoutInflated.LayoutInflatedParam liparam) throws Throwable {
            LinearLayout layout = (LinearLayout) liparam.view;
            Context context = layout.getContext();
            ResourceUtils res = ResourceUtils.getInstance(context);

            layout.setOrientation(LinearLayout.VERTICAL);

            View headerLayout = layout.getChildAt(0);
            LinearLayout notificationMain = (LinearLayout) layout.getChildAt(1);
            LinearLayout mediaActions = (LinearLayout) layout.getChildAt(2);

            layout.removeViewAt(2);
            layout.removeViewAt(1);

            layout.setClipChildren(false);

            int headerHeight = res.getDimensionPixelSize(R.dimen.notification_header_height_exclude_top);
            int notificationContentPadding = res.getDimensionPixelSize(R.dimen.notification_content_margin_start);

            ViewGroup.MarginLayoutParams headerLayoutLp = (ViewGroup.MarginLayoutParams) headerLayout.getLayoutParams();
            headerLayoutLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            headerLayoutLp.height = headerHeight;
            headerLayout.setLayoutParams(headerLayoutLp);

            LinearLayout notificationLayout = new LinearLayout(context);
            LinearLayout.LayoutParams notificationLayoutLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            notificationLayoutLp.setMargins(notificationContentPadding, 0, notificationContentPadding, 0);
            notificationLayout.setLayoutParams(notificationLayoutLp);
            notificationLayout.setOrientation(LinearLayout.HORIZONTAL);

            layout.addView(notificationLayout);

            notificationLayout.addView(notificationMain);
            notificationLayout.addView(mediaActions);
        }
    };

    private static XC_LayoutInflated notification_template_material_big_picture = new XC_LayoutInflated() {
        @Override
        public void handleLayoutInflated(LayoutInflatedParam liparam) throws Throwable {
            XposedHook.logI(TAG, "notification_template_material_big_picture");
            FrameLayout layout = (FrameLayout) liparam.view;
            View pic = layout.getChildAt(0);
            View shadow = layout.getChildAt(1);
            View base = layout.getChildAt(2);

            Context context = layout.getContext();
            ResourceUtils res = ResourceUtils.getInstance(context);

            int notificationHeight = res.getDimensionPixelSize(R.dimen.notification_min_height);

            FrameLayout.LayoutParams picLParams = (FrameLayout.LayoutParams) pic.getLayoutParams();
            FrameLayout.LayoutParams shadowLParams = (FrameLayout.LayoutParams) shadow.getLayoutParams();
            FrameLayout.LayoutParams baseLParams = (FrameLayout.LayoutParams) base.getLayoutParams();

            picLParams.setMargins(0, notificationHeight, 0, 0);
            shadowLParams.setMargins(0, notificationHeight, 0, 0);
            baseLParams.height = notificationHeight;

            pic.setLayoutParams(picLParams);
            shadow.setLayoutParams(shadowLParams);
            base.setLayoutParams(baseLParams);
        }
    };

    @SuppressWarnings("deprecation")
    private static XC_LayoutInflated notification_public_default = new XC_LayoutInflated() {
        @Override
        public void handleLayoutInflated(XC_LayoutInflated.LayoutInflatedParam liparam) throws Throwable {
            RelativeLayout layout = (RelativeLayout) liparam.view;
            Context context = layout.getContext();
            ResourceUtils res = ResourceUtils.getInstance(context);

            int notificationContentPadding = res.getDimensionPixelSize(R.dimen.notification_content_margin_start);
            int notificationHeaderMarginTop = res.getDimensionPixelSize(R.dimen.notification_header_margin_top);
            int iconSize = res.getDimensionPixelSize(R.dimen.notification_icon_size);
            int iconMarginEnd = res.getDimensionPixelSize(R.dimen.notification_icon_margin_end);
            int appNameMarginStart = res.getDimensionPixelSize(R.dimen.notification_app_name_margin_start);
            int appNameMarginEnd = res.getDimensionPixelSize(R.dimen.notification_app_name_margin_end);
            int dividerMarginTop = res.getDimensionPixelSize(R.dimen.notification_divider_margin_top);
            int timeMarginStart = res.getDimensionPixelSize(R.dimen.notification_time_margin_start);

            int iconId = context.getResources().getIdentifier("icon", "id", PACKAGE_SYSTEMUI);
            int timeId = context.getResources().getIdentifier("time", "id", PACKAGE_SYSTEMUI);
            int titleId = context.getResources().getIdentifier("title", "id", PACKAGE_SYSTEMUI);

            ImageView icon = (ImageView) layout.findViewById(iconId);
            DateTimeView time = (DateTimeView) layout.findViewById(timeId);
            TextView title = (TextView) layout.findViewById(titleId);
            TextView textView = new TextView(context);
            TextView divider = new TextView(context);

            RelativeLayout.LayoutParams iconLParams = new RelativeLayout.LayoutParams(iconSize, iconSize);
            iconLParams.setMargins(notificationContentPadding, notificationHeaderMarginTop, iconMarginEnd, 0);

            RelativeLayout.LayoutParams timeLParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            timeLParams.setMargins(timeMarginStart, 0, 0, 0);
            timeLParams.addRule(RelativeLayout.RIGHT_OF, R.id.public_app_name_text);
            timeLParams.addRule(RelativeLayout.ALIGN_TOP, iconId);

            RelativeLayout.LayoutParams titleLParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleLParams.setMargins(notificationContentPadding, 0, 0, 0);
            titleLParams.addRule(RelativeLayout.BELOW, iconId);

            RelativeLayout.LayoutParams textViewLParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textViewLParams.setMarginStart(appNameMarginStart);
            textViewLParams.setMarginEnd(appNameMarginEnd);
            textViewLParams.addRule(RelativeLayout.ALIGN_TOP, iconId);
            textViewLParams.addRule(RelativeLayout.RIGHT_OF, iconId);

            RelativeLayout.LayoutParams dividerLParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dividerLParams.setMargins(0, dividerMarginTop, 0, 0);
            dividerLParams.addRule(RelativeLayout.RIGHT_OF, R.id.public_app_name_text);
            dividerLParams.addRule(RelativeLayout.ALIGN_TOP, iconId);

            time.setGravity(View.TEXT_ALIGNMENT_CENTER);

            textView.setId(R.id.public_app_name_text);
            textView.setTextAppearance(context, context.getResources().getIdentifier("TextAppearance.Material.Notification.Info", "style", "android"));

            divider.setId(R.id.public_time_divider);
            divider.setLayoutParams(dividerLParams);
            divider.setText(res.getString(R.string.notification_header_divider_symbol));
            divider.setTextAppearance(context, context.getResources().getIdentifier("TextAppearance.Material.Notification.Info", "style", "android"));
            divider.setVisibility(View.GONE);

            icon.setLayoutParams(iconLParams);
            time.setLayoutParams(timeLParams);
            title.setLayoutParams(titleLParams);
            textView.setLayoutParams(textViewLParams);

            layout.addView(textView);
            layout.addView(divider);
        }
    };

}