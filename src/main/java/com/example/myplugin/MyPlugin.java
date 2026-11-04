/*
 * MyPlugin by You. Licensed software: each proxy needs its own license. Terms: https://example.com/terms
 * Licensing by SunLicense (https://sunlicense.com).
 */
package com.example.myplugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import com.example.myplugin.license.LicenseGuard;
import com.example.myplugin.license.LicenseStatus;
import com.example.myplugin.license.Messages;
import com.example.myplugin.license.SealedCore;
import com.example.myplugin.license.SunLicenseAnalytics;
import com.example.myplugin.license.SunLicenseProtection;

/**
 * A Velocity proxy plugin protected by SunLicense. The license is checked on Velocity's scheduler (never on
 * a network thread) at start-up and every {@code recheck-hours}; your features ({@code core/CoreFeatures})
 * start only once it is valid ({@link #startFeatures()}) and stop if SunLicense later says no
 * ({@link #stopFeatures()}). Release builds seal the core, so only a valid license can run it.
 */
@Plugin(id = "myplugin", name = "MyPlugin", version = BuildInfo.VERSION,
        description = "A Velocity plugin protected by SunLicense.", authors = {"You"})
public final class MyPlugin {
    /**
     * BuiltByBit and Voxel Shop replace this string with the buyer's license key when they download the
     * jar (create a "%%__SUNLICENSE__%%" placeholder in the store, see docs.sunlicense.com/integrations).
     * Builds from anywhere else keep it, and the key comes from config.properties instead.
     */
    private static final String BUILT_IN_KEY = "%%__SUNLICENSE__%%";

    /**
     * When SunLicense can't be reached and there is no valid offline license file: keep running (true,
     * the default: an outage on our side shouldn't take your customers' proxies down) or stop (false).
     */
    private static final boolean ALLOW_WHEN_UNREACHABLE = true;

    /** How soon to try again after no answer, in minutes. */
    private static final long RETRY_MINUTES = 10;

    /** The core's entry class (sealed in release builds). */
    private static final String CORE = "com.example.myplugin.core.CoreFeatures";

    /** The ownership notice logged at start-up: who makes the plugin and where its terms are. */
    private static final String AUTHOR = "You";
    private static final String TERMS = "https://example.com/terms";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private LicenseGuard guard;
    private SunLicenseAnalytics analytics;
    private volatile Features features;
    private volatile String licenseKey;
    private String serverId;
    private volatile LicenseStatus status;

    @Inject
    public MyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        Properties config;
        LicenseGuard.Settings settings;
        try {
            config = loadConfig();
            settings = LicenseGuard.Settings.load(getClass().getResourceAsStream("/sunlicense.properties"));
            guard = new LicenseGuard(settings, dataDirectory.resolve("license.lic"),
                    SunLicenseProtection.builder().product("MyPlugin", AUTHOR).terms(TERMS));
            serverId = serverId();
        } catch (IOException e) {
            logger.error("Can't start the license check: {}", e.getMessage());
            return;
        }
        licenseKey = LicenseGuard.pickKey(BUILT_IN_KEY, config.getProperty("license-key", ""));
        guard.protection().license(licenseKey, serverId);
        logger.info(guard.protection().notice());
        SealedCore.checkEarly(guard.protection(), getClass().getClassLoader());   // so the first check reports a modified jar
        // Anonymous usage statistics for your Analytics tab, every 30 minutes on a background thread (config: analytics).
        analytics = SunLicenseAnalytics.builder().baseUrl(settings.url()).apiKey(settings.apiKey())
                .licenseKey(() -> licenseKey).fingerprint(serverId).productVersion(BuildInfo.VERSION)
                .platform("velocity", proxy.getVersion().getVersion())
                .enabled(!"false".equalsIgnoreCase(config.getProperty("analytics", "true").trim())).start();

        CommandMeta meta = proxy.getCommandManager().metaBuilder("myplugin").plugin(this).build();
        proxy.getCommandManager().register(meta, new StatusCommand());

        checkAsync();
        long hours = Long.parseLong(config.getProperty("recheck-hours", "6").trim());
        if (hours > 0) {
            proxy.getScheduler().buildTask(this, () -> apply(guard.check(licenseKey, serverId)))
                    .delay(hours, TimeUnit.HOURS).repeat(hours, TimeUnit.HOURS).schedule();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        stopFeatures();
        if (analytics != null) {
            analytics.close();
        }
    }

    private void checkAsync() {
        proxy.getScheduler().buildTask(this, () -> apply(guard.check(licenseKey, serverId))).schedule();
    }

    /** Runs on Velocity's scheduler threads. */
    private void apply(LicenseStatus result) {
        status = result;
        switch (result.state()) {
            case VALID, VALID_OFFLINE -> {
                if (started.compareAndSet(false, true)) {
                    logger.info("License {}: {}", Messages.mask(licenseKey), result.message());
                    startFeatures();
                }
            }
            case REJECTED, NOT_CONFIGURED -> {
                logger.error("License {}: {}", Messages.mask(licenseKey), result.message());
                logger.error("MyPlugin is stopped until this is fixed (then run /myplugin recheck).");
                if (started.compareAndSet(true, false)) {
                    stopFeatures();
                }
            }
            case UNREACHABLE -> {
                logger.warn("{} Trying again in {} minutes.", result.message(), RETRY_MINUTES);
                if (ALLOW_WHEN_UNREACHABLE) {
                    if (started.compareAndSet(false, true)) {
                        startFeatures();
                    }
                } else if (!started.get()) {
                    logger.error("MyPlugin needs a license check to start; it will start once SunLicense answers.");
                }
                if (retryScheduled.compareAndSet(false, true)) {   // one retry at a time
                    proxy.getScheduler().buildTask(this, () -> {
                        retryScheduled.set(false);
                        apply(guard.check(licenseKey, serverId));
                    }).delay(RETRY_MINUTES, TimeUnit.MINUTES).schedule();
                }
            }
        }
    }

    /** Starts the core (core/CoreFeatures). In a release build it needs the unlock from a valid license check. */
    private void startFeatures() {
        try {
            features = SealedCore.load(guard.protection(), getClass().getClassLoader(), CORE, Features.class);
            features.start(this);
        } catch (SunLicenseProtection.Locked e) {
            started.set(false);   // starts with the next valid check
            logger.error("MyPlugin can't start yet: {} It starts as soon as SunLicense confirms the license "
                    + "(the confirmation is kept for 7 days).", e.getMessage());
        } catch (Exception e) {
            started.set(false);
            logger.error("MyPlugin's core failed to load", e);
        }
    }

    /** Undo startFeatures(): Velocity can't unload a plugin, so it switches its features off instead. */
    private void stopFeatures() {
        Features f = features;
        features = null;
        if (f != null) {
            f.stop();
            logger.info("MyPlugin's features are off.");
        }
    }

    /** For the core: the proxy, to register listeners and commands. */
    public ProxyServer proxy() {
        return proxy;
    }

    /** For the core: the plugin's logger. */
    public Logger logger() {
        return logger;
    }

    /** /myplugin [status|recheck], for staff with the myplugin.admin permission. */
    private final class StatusCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length > 0 && args[0].equalsIgnoreCase("recheck")) {
                try {   // picks up a license key added to config.properties since start-up
                    licenseKey = LicenseGuard.pickKey(BUILT_IN_KEY, loadConfig().getProperty("license-key", ""));
                } catch (IOException e) {
                    logger.warn("Can't read config.properties: {}", e.getMessage());
                }
                invocation.source().sendMessage(Component.text("Checking the license…", NamedTextColor.GRAY));
                checkAsync();
                return;
            }
            LicenseStatus s = status;
            if (s == null) {
                invocation.source().sendMessage(Component.text("The license hasn't been checked yet.", NamedTextColor.GRAY));
                return;
            }
            NamedTextColor color = s.allowed() ? NamedTextColor.GREEN : s.state() == LicenseStatus.State.UNREACHABLE
                    ? NamedTextColor.YELLOW : NamedTextColor.RED;
            invocation.source().sendMessage(Component.text("License " + Messages.mask(licenseKey) + " (" + s.state() + "): " + s.message(), color));
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("myplugin.admin");
        }
    }

    /** plugins/myplugin/config.properties, written from the default on first start. */
    private Properties loadConfig() throws IOException {
        Path file = dataDirectory.resolve("config.properties");
        if (!Files.exists(file)) {
            Files.createDirectories(dataDirectory);
            try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
                Files.copy(in, file);
            }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    /**
     * A random id for this proxy, kept in the plugin's folder, so restarts count as the same machine.
     * Copying the folder to another proxy copies the id too; the license's machine limit still applies
     * to each id.
     */
    private String serverId() throws IOException {
        Path file = dataDirectory.resolve(".server-id");
        if (Files.isRegularFile(file)) {
            String id = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!id.isEmpty()) {
                return id;
            }
        }
        Files.createDirectories(dataDirectory);
        String id = "velocity-" + UUID.randomUUID();
        Files.writeString(file, id, StandardCharsets.UTF_8);
        return id;
    }
}
