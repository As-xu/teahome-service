package com.teaplayer.teahome.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Admin console runtime knobs; loaded only with the {@code admin} profile. */
@ConfigurationProperties(prefix = "teahome.admin")
public class AdminProperties {

    private boolean enabled = false;
    private int idleMinutes = 30;
    private int absoluteHours = 12;
    private int maxSessions = 3;
    private int sudoMinutes = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    public void setIdleMinutes(int idleMinutes) {
        this.idleMinutes = idleMinutes;
    }

    public int getAbsoluteHours() {
        return absoluteHours;
    }

    public void setAbsoluteHours(int absoluteHours) {
        this.absoluteHours = absoluteHours;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public int getSudoMinutes() {
        return sudoMinutes;
    }

    public void setSudoMinutes(int sudoMinutes) {
        this.sudoMinutes = sudoMinutes;
    }
}
