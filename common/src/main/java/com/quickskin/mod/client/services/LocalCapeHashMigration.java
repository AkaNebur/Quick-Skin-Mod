package com.quickskin.mod.client.services;

import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.networking.NetworkSecurity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashMap;
import java.util.Map;

/** One-way migration from historical raw cape hashes to domain-separated local cape IDs. */
@Environment(EnvType.CLIENT)
final class LocalCapeHashMigration {
    private LocalCapeHashMigration() {
    }

    static void migrate(Map<String, String> aliases) {
        if (aliases == null || aliases.isEmpty()) return;
        Map<String, String> validAliases = new HashMap<>();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            if (NetworkSecurity.isValidContentId(entry.getKey())
                    && NetworkSecurity.isValidContentId(entry.getValue())
                    && !entry.getKey().equals(entry.getValue())) {
                validAliases.put(entry.getKey(), entry.getValue());
            }
        }
        if (validAliases.isEmpty()) return;

        ClientConfig config = ClientConfig.getInstance();
        boolean changed = false;
        String active = config.activeCapeHash;
        String migrated = validAliases.get(active);
        if (migrated == null && active != null && active.startsWith("local_cape:")) {
            migrated = validAliases.get(active.substring("local_cape:".length()));
            if (migrated != null) migrated = "local_cape:" + migrated;
        }
        if (migrated != null) {
            config.activeCapeHash = migrated;
            changed = true;
        }

        if (config.capeAnimationSpeeds != null) {
            for (Map.Entry<String, String> alias : validAliases.entrySet()) {
                String oldId = "local_cape:" + alias.getKey();
                Float speed = config.capeAnimationSpeeds.remove(oldId);
                if (speed != null) {
                    config.capeAnimationSpeeds.putIfAbsent(
                            "local_cape:" + alias.getValue(), speed);
                    changed = true;
                }
            }
        }
        if (changed) config.save();
    }
}
