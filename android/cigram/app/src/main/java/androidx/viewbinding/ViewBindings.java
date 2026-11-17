package androidx.viewbinding;

import android.view.View;
import android.view.ViewGroup;

/**
 * Same helper as the androidx.databinding:viewbinding library (Gradle's generated bindings call it). Kept in the source
 * tree next to {@link ViewBinding} so the project builds the same way in CodeAssist and in Gradle.
 */
public class ViewBindings {

    private ViewBindings() { }

    /** Like {@link View#findViewById(int)} but skips the root view itself (only its children are searched). */
    @SuppressWarnings("unchecked")
    public static <T extends View> T findChildViewById(View rootView, int id) {
        if (!(rootView instanceof ViewGroup)) return null;
        final ViewGroup rootViewGroup = (ViewGroup) rootView;
        final int childCount = rootViewGroup.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final T view = (T) rootViewGroup.getChildAt(i).findViewById(id);
            if (view != null) return view;
        }
        return null;
    }
}
