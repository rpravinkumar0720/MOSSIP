package io.mosip.liveness.api.device;

import io.mosip.liveness.api.frame.PixelFormat;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Biometric capture device capabilities and hardware security specifications.
 */
public record DeviceCapabilities(
        String deviceId,
        String modelName,
        String vendorName,
        SecurityLevel securityLevel,
        List<Resolution> supportedResolutions,
        int maxFps,
        Set<PixelFormat> supportedFormats,
        boolean hasHardwareCrypto,
        boolean hasInfrared
) {
    public DeviceCapabilities {
        Objects.requireNonNull(deviceId, "deviceId cannot be null");
        Objects.requireNonNull(modelName, "modelName cannot be null");
        Objects.requireNonNull(vendorName, "vendorName cannot be null");
        Objects.requireNonNull(securityLevel, "securityLevel cannot be null");
        supportedResolutions = supportedResolutions != null ? List.copyOf(supportedResolutions) : Collections.emptyList();
        supportedFormats = supportedFormats != null ? Set.copyOf(supportedFormats) : Collections.emptySet();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String deviceId = "UNKNOWN";
        private String modelName = "Generic Device";
        private String vendorName = "Generic Vendor";
        private SecurityLevel securityLevel = SecurityLevel.L0;
        private List<Resolution> supportedResolutions = List.of(Resolution.HD_1280_720);
        private int maxFps = 30;
        private Set<PixelFormat> supportedFormats = Set.of(PixelFormat.RGB_888);
        private boolean hasHardwareCrypto = false;
        private boolean hasInfrared = false;

        public Builder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder vendorName(String vendorName) {
            this.vendorName = vendorName;
            return this;
        }

        public Builder securityLevel(SecurityLevel securityLevel) {
            this.securityLevel = securityLevel;
            return this;
        }

        public Builder supportedResolutions(List<Resolution> supportedResolutions) {
            this.supportedResolutions = supportedResolutions;
            return this;
        }

        public Builder maxFps(int maxFps) {
            this.maxFps = maxFps;
            return this;
        }

        public Builder supportedFormats(Set<PixelFormat> supportedFormats) {
            this.supportedFormats = supportedFormats;
            return this;
        }

        public Builder hasHardwareCrypto(boolean hasHardwareCrypto) {
            this.hasHardwareCrypto = hasHardwareCrypto;
            return this;
        }

        public Builder hasInfrared(boolean hasInfrared) {
            this.hasInfrared = hasInfrared;
            return this;
        }

        public DeviceCapabilities build() {
            return new DeviceCapabilities(deviceId, modelName, vendorName, securityLevel,
                    supportedResolutions, maxFps, supportedFormats, hasHardwareCrypto, hasInfrared);
        }
    }
}
