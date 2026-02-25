/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Registry of known debug tags for UI discovery/filtering.
 */
public final class DebugTagRegistry {
    private static final Object Lock = new Object();
    private static final Set<String> Tags = new TreeSet<>();

    private DebugTagRegistry() {
    }

    public static void registerTag(@Nullable String tag) {
        if (tag == null) return;
        String t = tag.trim();
        if (t.isEmpty()) return;
        synchronized (Lock) {
            Tags.add(t);
        }
    }

    public static void registerTags(@Nullable Collection<String> tags) {
        if (tags == null) return;
        synchronized (Lock) {
            for (String tag : tags) {
                if (tag == null) continue;
                String t = tag.trim();
                if (!t.isEmpty()) Tags.add(t);
            }
        }
    }

    @NonNull
    public static List<String> getTags() {
        synchronized (Lock) {
            return new ArrayList<>(Tags);
        }
    }
}
