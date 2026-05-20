package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when provisioning credentials for an external dependency fails
 * (Azure JWT parse, AWS IAM token signing, GCP IAM client creation). Maps to HTTP 503.
 */
public class CredentialProvisioningException extends ServiceUnavailableException {

    public CredentialProvisioningException(String message) {
        super(message);
    }

    public CredentialProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
