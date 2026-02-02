package com.quickskin.mod.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

public class PremiumDetector {
    public static boolean isPremiumAccount() {
        Minecraft mc = Minecraft.getInstance();

        // Check User object (works in main menu and in-game)
        // Premium accounts have valid access tokens
        User user = mc.getUser();
        return user != null &&
               user.getAccessToken() != null &&
               !user.getAccessToken().isEmpty() &&
               !"0".equals(user.getAccessToken()); // Offline accounts have "0" as token
    }
}
