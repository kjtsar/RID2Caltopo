/*
 * Copyright (C) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.opendroneid.android.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Util {

    public static class SimpleMovingAverage {
        private final long[] window;
        private int ix;
        private long sum;

        public SimpleMovingAverage(int size) {
            window = new long[size];
            sum = 0;
        }

        public long next(long val) {
            sum -= window[ix];
            sum += val;
            window[ix++] = val;
            ix = ix % window.length;
            return sum / ((0 == ix) ? window.length : ix);
        }

        public long get() {
            return sum / ((0 == ix) ? window.length : ix);
        }
    }

    public static class SetDifference<T> {
        final Set<T> added;
        final Set<T> removed;

        SetDifference(Set<? extends T> newSet, Set<? extends T> oldSet) {
            added = difference(newSet, oldSet);
            removed = difference(oldSet, newSet);
        }
    }

    public static class SafeJSONObject extends JSONObject {
        private static final String TAG = "QuietJSONObject";

        @Override
        @NonNull
        public SafeJSONObject put(@NonNull String name, boolean value) {
            try {
                super.put(name, value);
            } catch (JSONException e) {
                CTError(TAG, "put() raised: ", e);
            }
            return this;
        }

        @Override
        @NonNull
        public SafeJSONObject put(@NonNull String name, double value) {
            try {
                super.put(name, value);
            } catch (JSONException e) {
                CTError(TAG, "put() raised: ", e);
            }
            return this;
        }

        @Override
        @NonNull
        public SafeJSONObject put(@NonNull String name, long value) {
            try {
                super.put(name, value);
            } catch (JSONException e) {
                CTError(TAG, "put() raised: ", e);
            }
            return this;
        }

        @Override
        @NonNull
        public SafeJSONObject put(@NonNull String name, int value) {
            try {
                super.put(name, value);
            } catch (JSONException e) {
                CTError(TAG, "put() raised: ", e);
            }
            return this;
        }


        @Override
        @NonNull
        public SafeJSONObject put(@NonNull String name, Object value) {
            try {
                super.put(name, value);
            } catch (JSONException e) {
                CTError(TAG, "put() raised: ", e);
            }
            return this;
        }
    }


    /** OTHER - SET */
    private static <E> Set<E> difference(Set<? extends E> set, Set<? extends E> other) {
        HashSet<E> diff = new HashSet<>();
        for (E e : set) {
            if (!other.contains(e)) {
                diff.add(e);
            }
        }
        return diff;
    }

    /**
     * set - other, what is not in other
     */

    public static class DiffObserver<T> implements Observer<Set<T>> {
        Set<T> last = Collections.emptySet();

        @Override
        public void onChanged(@Nullable Set<T> newSet) {
            SetDifference<T> difference = new SetDifference<>(newSet, last);

            if (!difference.added.isEmpty()) {
                onAdded(difference.added);
            }
            if (!difference.removed.isEmpty()) {
                onRemoved(difference.removed);
            }
            last = newSet;
        }

        public void onAdded(Collection<T> added) { }
        public void onRemoved(Collection<T> removed) { }
    }
}
