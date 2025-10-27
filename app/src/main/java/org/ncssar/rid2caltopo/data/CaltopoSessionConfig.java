package org.ncssar.rid2caltopo.data;

import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

public class CaltopoSessionConfig implements Serializable {
	@Serial
    private static final long serialVersionUID = 1L;
    public String teamId;
    public String credentialId;
    public String credentialSecret; //
	public String domainAndPort;    // "caltopo.com"
	public static final String UNDEF_STR = "<undefined>";

	public CaltopoSessionConfig() {
		initialize();
	}

	public CaltopoSessionConfig(@Nullable CaltopoSessionConfig aConfig) {
		if (null == aConfig) {
			initialize();
		} else {
			domainAndPort = aConfig.domainAndPort;
			teamId = aConfig.teamId;
			credentialId = aConfig.credentialId;
			credentialSecret = aConfig.credentialSecret;
		}
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
    public void setCred(String teamId, String credentialId, String credentialSecret) {
		this.teamId = teamId;
		this.credentialId = credentialId;
		this.credentialSecret = credentialSecret;
    }

    public String stringRep() {
		return String.format(Locale.US,
			     "teamId:%s, credentialId:%s, credentialSecret:%s, server:%s",
			     null == teamId ? UNDEF_STR : teamId,
			     null == credentialId ? UNDEF_STR : credentialId,
			     null == credentialSecret ? UNDEF_STR : credentialSecret,
			     null == domainAndPort ? UNDEF_STR : domainAndPort);
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
		if (null == cfg) return false;
		if (null == cfg.teamId || cfg.teamId.isEmpty()) return false;
		if (null == cfg.credentialId || cfg.credentialId.isEmpty()) return false;
		if (null == cfg.credentialSecret || cfg.credentialSecret.isEmpty()) return false;
        return null != cfg.domainAndPort && !cfg.domainAndPort.isEmpty();
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
