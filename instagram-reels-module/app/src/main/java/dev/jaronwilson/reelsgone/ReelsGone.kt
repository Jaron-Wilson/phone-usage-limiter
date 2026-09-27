package dev.jaronwilson.reelsgone

import android.app.Activity
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Removes Reels from Instagram, from the inside.
 *
 * Instagram builds its bottom bar in code, so there is no layout file to edit.
 * Instead this runs inside Instagram as a module and, every time a screen
 * settles, finds the Reels tab and the in-feed reels row by Instagram's own
 * resource ids and sets them gone. Those ids, clips_tab and
 * reels_tray_container, are stable across translations and most redesigns; if
 * Instagram ever renames them, only the two strings below need to change.
 *
 * It touches Instagram and nothing else: the module is scoped to that one
 * package, and every hook checks the package first.
 */
class ReelsGone : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != INSTAGRAM) return

        // Every activity, on resume, gets the reels views hidden and a one-time
        // layout listener so they stay hidden as the screen changes under them.
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    hide(activity)
                    attachOnce(activity)
                }
            }
        )
    }

    private fun attachOnce(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        // A tag guards against stacking a new listener on every resume.
        if (decor.getTag(TAG_KEY) == true) return
        decor.setTag(TAG_KEY, true)
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            hide(activity)
        }
    }

    private fun hide(activity: Activity) {
        // Always gone: the Reels tab and the reels row in the feed.
        for (name in TARGET_IDS) hideById(activity, name)

        // The Explore grid is the other doom-scroll surface. Its content view is
        // a generic recycler_view, so only hide it when the Explore action bar
        // is on screen: that way the feed and other lists are untouched, and the
        // search bar stays so you can still look things up, just without the
        // endless grid under it.
        if (viewPresent(activity, "explore_action_bar")) {
            hideById(activity, "recycler_view")
            hideById(activity, "swipeable_nav_view_pager_inner_recycler_view")
        }
    }

    private fun idOf(activity: Activity, name: String): Int =
        runCatching { activity.resources.getIdentifier(name, "id", INSTAGRAM) }.getOrDefault(0)

    private fun hideById(activity: Activity, name: String) {
        val id = idOf(activity, name)
        if (id == 0) return
        val view = runCatching { activity.findViewById<View>(id) }.getOrNull() ?: return
        if (view.visibility != View.GONE) view.visibility = View.GONE
    }

    private fun viewPresent(activity: Activity, name: String): Boolean {
        val id = idOf(activity, name)
        if (id == 0) return false
        val view = runCatching { activity.findViewById<View>(id) }.getOrNull() ?: return false
        return view.visibility == View.VISIBLE
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        val TARGET_IDS = arrayOf("clips_tab", "reels_tray_container")
        // Any stable, unused resource id works as a view tag key.
        const val TAG_KEY = 0x7ee15
    }
}
