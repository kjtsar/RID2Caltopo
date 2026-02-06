/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;

import androidx.annotation.NonNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

public class CaltopoCredentials implements Serializable {

    @Serial
    private static final long serialVersionUID = 2L;
    public String teamId;
    public String credentialId;
    public String credentialSecret; //
    public CaltopoCredentials() {
		initialize();
	}
	private void initialize() {
		teamId = null;
		credentialId = null;
		credentialSecret = null;
	}

	public CaltopoCredentials(String teamId, String credentialId, String credentialSecret) {
		this.teamId = teamId;
		this.credentialId = credentialId;
		this.credentialSecret = credentialSecret;
	}

    private static void CheckField(@NonNull String fieldName, @NonNull String fieldContents) {
        final String TAG = "CaltopoCredentials";
        if (fieldContents.matches("[iIoO]+")) {
            CTWarn(TAG, String.format(Locale.US,
                    "Value for %s contains letter I or O instead of number.\n  " +
                            "This may prevent credentials from working.", fieldName));
        }
    }

	/**
	 * Check supplied CaltopoCredentials instance to see if it is legal.
	 * Note that this doesn't verify any of the values - just checks to see
	 * that the values are non-null and non-empty.
	 *
	 * @param cfg CaltopoCredentials instance.
	 * @return true if cfg not null and all the values are specified.
	 */
	public static boolean sniffTest(CaltopoCredentials cfg) {
        if ((null == cfg) ||
				(null == cfg.teamId || cfg.teamId.isEmpty()) ||
				(null == cfg.credentialId || cfg.credentialId.isEmpty()) ||
				(null == cfg.credentialSecret || cfg.credentialSecret.isEmpty())) return false;
        CheckField("team_id", cfg.teamId);
        CheckField("credential_id", cfg.teamId);
        CheckField("credential_secret", cfg.teamId);
        return true;
    }

	/**
	 *  Compare to config specs to see if they are equal.
	 * @param cfg1 first config spec
	 * @param cfg2 second config spec
	 * @return  Returns true if they are equal.
	 */
	public static boolean credentialsAreEqual(CaltopoCredentials cfg1, CaltopoCredentials cfg2) {
		if (null == cfg1 || null == cfg2) return false;
        if (!cfg1.teamId.equals(cfg2.teamId)) return false;
        if (!cfg1.credentialId.equals(cfg2.credentialId)) return false;
        return (cfg1.credentialSecret.equals(cfg2.credentialSecret));
    }
} // end of CaltopoSessionConfig class spec.
