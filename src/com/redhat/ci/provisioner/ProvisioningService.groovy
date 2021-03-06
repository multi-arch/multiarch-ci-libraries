package com.redhat.ci.provisioner

import com.redhat.ci.provisioners.LinchPinProvisioner
import com.redhat.ci.provisioners.KubeVirtProvisioner
import com.redhat.ci.provisioners.OpenShiftProvisioner
import com.redhat.ci.hosts.TargetHost
import com.redhat.ci.hosts.ProvisionedHost
import com.redhat.ci.host.Type

/**
 * Utilities for smart provisioning.
 * Attempts to minimize resource footprint.
 */
class ProvisioningService {
    public static final String UNAVAILABLE = 'No available provisioner could provision target host.'

    @SuppressWarnings('NestedForLoop')
    ProvisionedHost provision(TargetHost host, ProvisioningConfig config, Script script) {
        Provisioner provisioner = null

        // Users can override the priority list by manually entering their desired host type
        if (host.type) {
            host.typePriority = [host.type]
        }

        // Users can override the priority list by manually entering their desired provisioner type
        if (host.provisioner) {
            host.provisionerPriority = [host.provisioner]
        }

        // Users can override the priority list by manually entering their desired provider type
        if (host.provider) {
            host.providerPriority = [host.provider]
        }

        // Ensure there is a default set for the host type priority
        host.typePriority = host.typePriority ?: config.hostTypePriority

        // Ensure there is a default set for the provisioner priority
        if (host.provisionerPriority == null) {
            host.provisionerPriority = config.provisionerPriority
        }

        // Ensure there is a default set for the provider priority
        host.providerPriority = host.providerPriority ?: config.providerPriority

        // Loop through each provisioner type by priority
        for (provisionerType in host.provisionerPriority) {
            // Verify that there is an available provisioner of this type
            provisioner = getProvisioner(provisionerType, script)

            // Check if provisioner is available
            if (!provisioner.available) {
                script.echo("Provisioning host with ${provisionerType} provisioner is not possible. " +
                            "Provisioner ${provisionerType} not available.")
                continue
            }

            // Loop through each host type by priority
            for (hostType in host.typePriority) {
                // Check if provisioner supports host type
                if (!provisioner.supportsHostType(hostType)) {
                    script.echo("Provisioning ${hostType} host " +
                                "with ${provisionerType} provisioner is not supported.")
                    continue
                }

                // Now that we've found a suitable provisioner, let's loop through providers
                for (providerType in host.providerPriority) {
                    // Verify that the selected provisioner supports the selected provider
                    if (!provisioner.supportsProvider(providerType)) {
                        script.echo("Provisioning ${hostType} host " +
                                    "with ${provisionerType} provisioner " +
                                    "and ${providerType} provider is not supported.")
                        continue
                    }

                    // Attempt to provision with the selected provisioner and provider pair
                    host.provisioner = provisionerType
                    host.provider = providerType
                    host.type = hostType

                    try {
                        script.echo("Attempting to provision ${hostType} host " +
                                    "with ${provisionerType} provisioner " +
                                    "and ${providerType} provider.")
                        return provisioner.provision(host, config)
                    } catch (e) {
                        // Provisioning failed, so try next provider
                        script.echo("Provisioning ${hostType} host " +
                                    "with ${provisionerType} provisioner " +
                                    "and ${providerType} provider failed.")
                        script.echo("Exception: ${e.message}")
                    }
                }
            }
        }

        // If we haven't returned from the function yet, we are out of available
        // hostType, provisioner, and provider combinations.
        throw new ProvisioningException(UNAVAILABLE)
    }

    void teardown(ProvisionedHost host, ProvisioningConfig config, Script script) {
        Provisioner provisioner = getProvisioner(host.provisioner, script)
        provisioner.teardown(host, config)
    }

    protected Provisioner getProvisioner(String provisioner, Script script) {
        switch (provisioner) {
            case com.redhat.ci.provisioner.Type.LINCHPIN:
                return new LinchPinProvisioner(script)
            case com.redhat.ci.provisioner.Type.OPENSHIFT:
                return new OpenShiftProvisioner(script)
            case com.redhat.ci.provisioner.Type.KUBEVIRT:
                return new KubeVirtProvisioner(script)
            default:
                script.echo("Unrecognized provisioner:${provisioner}")
                throw new ProvisioningException(UNAVAILABLE)
        }
    }
}
