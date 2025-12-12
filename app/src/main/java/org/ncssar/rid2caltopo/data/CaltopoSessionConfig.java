/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import java.io.Serial;
import java.io.Serializable;

public class CaltopoSessionConfig implements Serializable {
	@Serial
    private static final long serialVersionUID = 1L;
    public String teamId;
    public String credentialId;
    public String credentialSecret; //
	public String domainAndPort;    // "caltopo.com"

	public CaltopoSessionConfig() {
		initialize();
	}
	private void initialize() {
		domainAndPort = "caltopo.com";
		teamId = null;
		credentialId = null;
		credentialSecret = null;
	}

	public CaltopoSessionConfig(String teamId, String credentialId, String credentialSecret) {
		domainAndPort = "caltopo.com";
		this.teamId = teamId;
		this.credentialId = credentialId;
		this.credentialSecret = credentialSecret;
	}

	/**
	 * Check supplied CaltopSessionConfig instance to see if it is legal.
	 * Note that this doesn't verify any of the values - just checks to see
	 * that the values are non-null and non-empty.
	 *
	 * @param cfg CaltopoSessionConfig instance.
	 * @return true if cfg not null and all the values are specified.
	 */
	public static boolean sniffTest(CaltopoSessionConfig cfg) {
		if ((null == cfg) ||
				(null == cfg.teamId || cfg.teamId.isEmpty()) ||
				(null == cfg.credentialId || cfg.credentialId.isEmpty()) ||
				(null == cfg.credentialSecret || cfg.credentialSecret.isEmpty())) return false;
        return (null != cfg.domainAndPort && !cfg.domainAndPort.isEmpty());
    }

	/**
	 *  Compare to config specs to see if they are equal.
	 * @param cfg1 first config spec
	 * @param cfg2 second config spec
	 * @return  Returns true if they are equal.
	 */
	public static boolean configSpecsAreEqual(CaltopoSessionConfig cfg1, CaltopoSessionConfig cfg2) {
		if (cfg1 == cfg2) return true;
		if (null != cfg1 && null != cfg2) {
			if (null != cfg1.teamId && null != cfg2.teamId) {
				if (!cfg1.teamId.equals(cfg2.teamId)) return false;
			} else if (null != cfg1.teamId || null != cfg2.teamId) return false;

			if (null != cfg1.credentialId && null != cfg2.credentialId) {
				if (!cfg1.credentialId.equals(cfg2.credentialId)) return false;
			} else if (null != cfg1.credentialId || null != cfg2.credentialId) return false;

			if (null != cfg1.credentialSecret && null != cfg2.credentialSecret) {
				if (!cfg1.credentialSecret.equals(cfg2.credentialSecret)) return false;
			} else if (null != cfg1.credentialSecret || null != cfg2.credentialSecret) return false;

			if (null != cfg1.domainAndPort && null != cfg2.domainAndPort) {
                return cfg1.domainAndPort.equals(cfg2.domainAndPort);
			} else return null == cfg1.domainAndPort && null == cfg2.domainAndPort;
		} else return false;
    }
} // end of CaltopoSessionConfig class spec.
