package com.my.newproject;

public final class CigramAdminAuthProvider extends android.content.ContentProvider {

    /*
     * CIGRAM ADMIN AUTH BRIDGE
     * Keeps the existing RequestNetwork code untouched.
     * It injects the same admin token used by the existing protected admin routes
     * into every RequestNetwork instance after an Activity is created/resumed.
     */
    // The token itself is entered once inside the app and read from CgCfg.TOKEN (never stored in the source).
    private static final String HEADER_AUTH = "Authorization";
    private static final String HEADER_TOKEN = "X-Admin-Token";

    @Override
    public boolean onCreate() {
        try {
            android.content.Context context = getContext();
            if (context == null) return true;

            android.content.Context appContext = context.getApplicationContext();
            if (!(appContext instanceof android.app.Application)) return true;

            final android.app.Application app = (android.app.Application) appContext;

            app.registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(final android.app.Activity activity, android.os.Bundle state) {
                        schedulePatch(activity, 0L);
                        schedulePatch(activity, 250L);
                    }

                    @Override
                    public void onActivityStarted(android.app.Activity activity) {
                        schedulePatch(activity, 0L);
                    }

                    @Override
                    public void onActivityResumed(android.app.Activity activity) {
                        schedulePatch(activity, 0L);
                        schedulePatch(activity, 350L);
                    }

                    @Override
                    public void onActivityPaused(android.app.Activity activity) {}

                    @Override
                    public void onActivityStopped(android.app.Activity activity) {}

                    @Override
                    public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle outState) {}

                    @Override
                    public void onActivityDestroyed(android.app.Activity activity) {}
                }
            );
        } catch (Throwable error) {
            android.util.Log.e("CigramAdminAuth", "Provider initialization failed", error);
        }

        return true;
    }

    private static void schedulePatch(final android.app.Activity activity, long delayMs) {
        if (activity == null) return;

        try {
            android.view.View root = activity.getWindow() == null
                ? null
                : activity.getWindow().getDecorView();

            if (root == null) {
                patchActivity(activity);
                return;
            }

            root.postDelayed(
                new java.lang.Runnable() {
                    @Override
                    public void run() {
                        patchActivity(activity);
                    }
                },
                Math.max(0L, delayMs)
            );
        } catch (Throwable ignored) {
            patchActivity(activity);
        }
    }

    private static void patchActivity(android.app.Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        try {
            java.lang.Class<?> type = activity.getClass();

            while (type != null && type != java.lang.Object.class) {
                java.lang.reflect.Field[] fields;

                try {
                    fields = type.getDeclaredFields();
                } catch (Throwable ignored) {
                    fields = new java.lang.reflect.Field[0];
                }

                for (java.lang.reflect.Field field : fields) {
                    try {
                        field.setAccessible(true);
                        java.lang.Object requestObject = field.get(activity);
                        if (requestObject == null) continue;

                        String className = requestObject.getClass().getName();
                        if (!className.endsWith(".RequestNetwork")
                                && !className.equals("com.my.newproject.RequestNetwork")) {
                            continue;
                        }

                        applyHeaders(requestObject);
                    } catch (Throwable ignored) {
                    }
                }

                type = type.getSuperclass();
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramAdminAuth", "Request patch failed", error);
        }
    }

    private static void applyHeaders(java.lang.Object requestObject) {
        if (requestObject == null) return;

        try {
            java.util.HashMap<String, java.lang.Object> headers =
                new java.util.HashMap<String, java.lang.Object>();

            /* Preserve any headers already set by the caller. */
            java.lang.Class<?> scan = requestObject.getClass();
            while (scan != null && scan != java.lang.Object.class) {
                java.lang.reflect.Field[] fields;
                try {
                    fields = scan.getDeclaredFields();
                } catch (Throwable ignored) {
                    fields = new java.lang.reflect.Field[0];
                }

                for (java.lang.reflect.Field field : fields) {
                    try {
                        String fieldName = field.getName() == null
                            ? ""
                            : field.getName().toLowerCase(java.util.Locale.US);

                        if (!fieldName.contains("header")) continue;

                        field.setAccessible(true);
                        java.lang.Object oldHeaders = field.get(requestObject);

                        if (oldHeaders instanceof java.util.Map) {
                            java.util.Map<?, ?> oldMap = (java.util.Map<?, ?>) oldHeaders;
                            for (java.util.Map.Entry<?, ?> entry : oldMap.entrySet()) {
                                if (entry.getKey() != null) {
                                    headers.put(String.valueOf(entry.getKey()), entry.getValue());
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                scan = scan.getSuperclass();
            }

            headers.put(HEADER_AUTH, "Bearer " + CgCfg.TOKEN);
            headers.put(HEADER_TOKEN, CgCfg.TOKEN);

            java.lang.reflect.Method target = null;

            for (java.lang.reflect.Method method : requestObject.getClass().getMethods()) {
                if ("setHeaders".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    target = method;
                    break;
                }
            }

            if (target == null) {
                java.lang.Class<?> type = requestObject.getClass();
                while (type != null && type != java.lang.Object.class && target == null) {
                    for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                        if ("setHeaders".equals(method.getName()) && method.getParameterTypes().length == 1) {
                            method.setAccessible(true);
                            target = method;
                            break;
                        }
                    }
                    type = type.getSuperclass();
                }
            }

            if (target != null) {
                target.setAccessible(true);
                target.invoke(requestObject, headers);
            } else {
                /* Fallback for RequestNetwork builds exposing only an internal header map. */
                java.lang.Class<?> type = requestObject.getClass();
                while (type != null && type != java.lang.Object.class) {
                    for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                        try {
                            String name = field.getName() == null
                                ? ""
                                : field.getName().toLowerCase(java.util.Locale.US);
                            if (!name.contains("header")) continue;
                            if (!java.util.Map.class.isAssignableFrom(field.getType())) continue;
                            field.setAccessible(true);
                            field.set(requestObject, headers);
                            return;
                        } catch (Throwable ignored) {
                        }
                    }
                    type = type.getSuperclass();
                }
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramAdminAuth", "Could not set admin headers", error);
        }
    }

    @Override
    public android.database.Cursor query(
        android.net.Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        return null;
    }

    @Override
    public String getType(android.net.Uri uri) {
        return null;
    }

    @Override
    public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) {
        return null;
    }

    @Override
    public int delete(android.net.Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(
        android.net.Uri uri,
        android.content.ContentValues values,
        String selection,
        String[] selectionArgs
    ) {
        return 0;
    }
}
