/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.concurrent.internal;

/**
 * Static repository for plugins that provide extensibility to the concurrent package.
 */
public final class ConcurrentPlugins {
    private static final SinglePluginSet SINGLE_PLUGINS = new SinglePluginSet();
    private static final CompletablePluginSet COMPLETABLE_PLUGINS = new CompletablePluginSet();
    private static final PublisherPluginSet PUBLISHER_PLUGINS = new PublisherPluginSet();

    private ConcurrentPlugins() {
        // no instances
    }

    /**
     * Add a {@link SinglePlugin}.
     * @param plugin the {@link SinglePlugin}.
     * @return {@code true} if the plugin was added.
     */
    public static boolean addPlugin(SinglePlugin plugin) {
        return SINGLE_PLUGINS.add(plugin);
    }

    /**
     * Add a {@link CompletablePlugin}.
     * @param plugin the {@link CompletablePlugin}.
     * @return {@code true} if the plugin was added.
     */
    public static boolean addPlugin(CompletablePlugin plugin) {
        return COMPLETABLE_PLUGINS.add(plugin);
    }

    /**
     * Add a {@link PublisherPlugin}.
     * @param plugin the {@link PublisherPlugin}.
     * @return {@code true} if the plugin was added.
     */
    public static boolean addPlugin(PublisherPlugin plugin) {
        return PUBLISHER_PLUGINS.add(plugin);
    }

    /**
     * Remove a {@link SinglePlugin}.
     * @param plugin the {@link SinglePlugin}.
     * @return {@code true} if the plugin was removed.
     */
    public static boolean removePlugin(SinglePlugin plugin) {
        return SINGLE_PLUGINS.remove(plugin);
    }

    /**
     * Remove a {@link CompletablePlugin}.
     * @param plugin the {@link CompletablePlugin}.
     * @return {@code true} if the plugin was removed.
     */
    public static boolean removePlugin(CompletablePlugin plugin) {
        return COMPLETABLE_PLUGINS.remove(plugin);
    }

    /**
     * Remove a {@link PublisherPlugin}.
     * @param plugin the {@link PublisherPlugin}.
     * @return {@code true} if the plugin was removed.
     */
    public static boolean removePlugin(PublisherPlugin plugin) {
        return PUBLISHER_PLUGINS.remove(plugin);
    }

    /**
     * Get a single representation of all {@link SinglePlugin}s.
     * @return a single representation of all {@link SinglePlugin}s.
     */
    public static SinglePlugin getSinglePlugin() {
        return SINGLE_PLUGINS;
    }

    /**
     * Get a single representation of all {@link CompletablePlugin}s.
     * @return a single representation of all {@link CompletablePlugin}s.
     */
    public static CompletablePlugin getCompletablePlugin() {
        return COMPLETABLE_PLUGINS;
    }

    /**
     * Get a single representation of all {@link PublisherPlugin}s.
     * @return a single representation of all {@link PublisherPlugin}s.
     */
    public static PublisherPlugin getPublisherPlugin() {
        return PUBLISHER_PLUGINS;
    }

    /**
     * Find the index of {@code l} in {@code array}.
     * @param l The element to find.
     * @param array The array to search in.
     * @param <X> The type of object.
     * @return The index of {@code l} in {@code array}, or {@code <0}.
     */
    public static <X> int indexOf(X l, X[] array) {
        for (int i = 0; i < array.length; ++i) {
            // Null elements are not permitted in the array, so no null check is necessary.
            if (array[i].equals(l)) {
                return i;
            }
        }
        return -1;
    }
}