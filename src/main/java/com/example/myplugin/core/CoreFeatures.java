package com.example.myplugin.core;

import com.example.myplugin.Features;
import com.example.myplugin.MyPlugin;

/**
 * Your plugin's features: register listeners and commands here ({@code plugin.proxy().getEventManager()}).
 * Everything in this package is the "core": release builds encrypt it, and only a valid license unlocks it
 * (see {@code license/SealedCore}). Put the code your plugin can't work without here. The {@code @Plugin} class stays
 * outside the core, because Velocity finds it by name.
 */
public final class CoreFeatures implements Features {
    @Override
    public void start(MyPlugin plugin) {
        // plugin.proxy().getEventManager().register(plugin, new MyListener());
        plugin.logger().info("MyPlugin is running.");
    }

    /** Velocity can't unload a plugin, so this switches the features off instead. */
    @Override
    public void stop() {
        // plugin.proxy().getEventManager().unregisterListener(plugin, myListener);
    }
}
