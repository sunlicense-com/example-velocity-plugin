package com.example.myplugin;

/**
 * The plugin's features, started once the license is valid and stopped if SunLicense later says no. The
 * implementation lives in the {@code core} package, which release builds ship sealed (see {@code license/SealedCore}):
 * keep this interface small and outside it.
 */
public interface Features {
    void start(MyPlugin plugin);

    default void stop() {
    }
}
