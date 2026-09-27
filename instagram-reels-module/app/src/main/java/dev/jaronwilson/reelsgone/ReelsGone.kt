package dev.jaronwilson.reelsgone

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Removes short-form video from Instagram and YouTube, from the inside.
 *
 * These apps build their bars in code, so there is no layout file to edit.
 * Instead this runs inside the app as a module and, every time a screen settles,
 * finds the short-form surfaces and sets them gone:
 *
 *   Instagram  clips_tab (Reels tab), reels_tray_container (feed row), and the
 *              Explore grid (only on the Explore screen, so search still works).
 *   YouTube    the Shorts tab in the bottom bar, matched by its "Shorts" label
 *              since it carries no resource id.
 *
 * It is scoped to those two packages and touches nothing else. Matching is by
 * the apps' own resource ids and labels, so it needs no grasp of their
 * obfuscated code; if either app renames something, only the strings below
 * change. Short videos inside a chat or a search result still play; only the
 * push surfaces, the tabs and shelves, are removed.
 */
class ReelsGone : IXposedHookLoadPackage {

    private var pkg: String = ""

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != INSTAGRAM && lpparam.packageName != YOUTUBE) return
        pkg = lpparam.packageName

        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    apply(activity)
                    attachOnce(activity)
                }
            }
        )
    }

    private fun attachOnce(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (decor.getTag(TAG_KEY) == true) return
        decor.setTag(TAG_KEY, true)
        decor.viewTreeObserver.addOnGlobalLayoutListener { apply(activity) }
    }

    private fun apply(activity: Activity) {
        when (pkg) {
            INSTAGRAM -> {
                hideById(activity, "clips_tab")
                hideById(activity, "reels_tray_container")
                // The Explore grid is a generic recycler_view, so only hide it
                // when the Explore action bar is present: the feed and other
                // lists stay, and the search bar stays so you can still look
                // things up, just without the endless grid.
                if (viewPresent(activity, "explore_action_bar")) {
                    hideById(activity, "recycler_view")
                    hideById(activity, "swipeable_nav_view_pager_inner_recycler_view")
                }
            }
            YOUTUBE -> {
                // The Shorts tab has no id; find it by its label and hide it.
                hideByDesc(activity, "Shorts")
            }
        }
    }

    private fun idOf(activity: Activity, name: String): Int =
        runCatching { activity.resources.getIdentifier(name, "id", pkg) }.getOrDefault(0)

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

    /** Walk the view tree and hide every view whose description is exactly [desc]. */
    private fun hideByDesc(activity: Activity, desc: String) {
        val root = activity.window?.decorView ?: return
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            val v = stack.removeLast()
            guard++
            if (v.contentDescription?.toString() == desc && v.visibility != View.GONE) {
                v.visibility = View.GONE
                continue
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { stack.addLast(it) }
            }
        }
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val YOUTUBE = "com.google.android.youtube"
        const val TAG_KEY = 0x7ee15
    }
}
