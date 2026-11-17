package androidx.viewbinding;

import android.view.View;

/**
 * Minimal view-binding interface required by the binding classes that CodeAssist generates
 * when viewBinding is enabled. Kept intentionally tiny and stable: it is only {@code View getRoot()}.
 */
public interface ViewBinding {
    View getRoot();
}
