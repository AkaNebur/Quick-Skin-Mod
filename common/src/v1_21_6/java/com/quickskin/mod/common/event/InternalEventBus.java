package com.quickskin.mod.common.event;

import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Simple internal event bus for service-to-service communication
 * This is NOT the Architectury event system - it's for internal mod events
 */
@Environment(EnvType.CLIENT)
public class InternalEventBus {
    private static final InternalEventBus INSTANCE = new InternalEventBus();

    private final Map<Class<?>, List<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    private InternalEventBus() {}

    public static InternalEventBus getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a listener for a specific event type
     * @param eventType The event class
     * @param listener The listener to register
     * @param <T> The event type
     */
    public <T> void register(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new ArrayList<>()).add(listener);
    }

    /**
     * Posts an event to all registered listeners
     * @param event The event to post
     * @param <T> The event type
     */
    @SuppressWarnings("unchecked")
    public <T> void post(T event) {
        Class<?> eventType = event.getClass();
        List<Consumer<?>> eventListeners = listeners.get(eventType);

        if (eventListeners != null && !eventListeners.isEmpty()) {
            for (Consumer<?> listener : eventListeners) {
                try {
                    ((Consumer<T>) listener).accept(event);
                } catch (Exception e) {
                }
            }
        }
    }

}
