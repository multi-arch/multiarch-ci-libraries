package com.redhat.ci

import com.redhat.ci.hosts.ProvisionedHost
import com.redhat.ci.provisioner.ProvisioningConfig
import com.redhat.ci.provisioner.ProvisioningException
import com.redhat.ci.provisioner.Mode

/**
 * Utility class to perform actions upon CI hosts.
 */
class Utils {
    private static final String SUDO = 'sudo'
    private static final String NO_SUDO = ''
    private static final String INSTALL_FILE = 'install.sh'

    /**
     * Attemps to install Ansible.
     */
    @SuppressWarnings('GStringExpressionWithinString')
    static void installAnsible(Script script, ProvisioningConfig config, ProvisionedHost host = null) {
        genericInstall(script, config, host) {
            privileged, sh ->
            String sudo = privileged ? SUDO : NO_SUDO
            sh("""
                ${sudo} yum install python-devel openssl-devel libffi-devel -y &&
                ${sudo} mkdir -p /home/jenkins &&
                ${sudo} chown --recursive \${USER}:\${USER} /home/jenkins &&
                ${sudo} pip install --upgrade pip &&
                ${sudo} pip install --upgrade setuptools &&
                ${sudo} pip install --upgrade ansible
            """)
            if (host == null) {
                return
            }
            host.ansibleInstalled = true
        }
    }

    /**
     * Attempts to install SSH and Beaker credentials.
     */
    static void installCredentials(Script script, ProvisioningConfig config, ProvisionedHost host = null) {
        genericInstall(script, config, host) {
            privileged, sh ->
            String sudo = privileged ? SUDO : NO_SUDO
            script.withCredentials([
                script.file(credentialsId:config.keytabCredentialId, variable:'KEYTAB'),
                script.usernamePassword(credentialsId:config.krbPrincipalCredentialId,
                                        usernameVariable:'KRB_PRINCIPAL',
                                        passwordVariable:''),
                script.file(credentialsId:config.sshPrivKeyCredentialId, variable:'SSHPRIVKEY'),
                script.file(credentialsId:config.sshPubKeyCredentialId,  variable:'SSHPUBKEY'),
                script.file(credentialsId:config.krbConfCredentialId,    variable:'KRBCONF'),
                script.file(credentialsId:config.bkrConfCredentialId,    variable:'BKRCONF'),
            ]) {
                script.env.HOME = '/home/jenkins'
                sh("""
                    ${sudo} yum install -y krb5-workstation
                    ${sudo} cp ${script.KRBCONF} /etc/krb5.conf
                    ${sudo} mkdir -p /etc/beaker
                    ${sudo} cp ${script.BKRCONF} /etc/beaker/client.conf
                    ${sudo} chmod 644 /etc/krb5.conf
                    ${sudo} chmod 644 /etc/beaker/client.conf
                    kinit ${script.KRB_PRINCIPAL} -k -t ${script.KEYTAB}
                    mkdir -p ~/.ssh
                    cp ${script.SSHPRIVKEY} ~/.ssh/id_rsa
                    cp ${script.SSHPUBKEY} ~/.ssh/id_rsa.pub
                    chmod 600 ~/.ssh/id_rsa
                    chmod 644 ~/.ssh/id_rsa.pub
                    eval "\$(ssh-agent -s)"
                    ssh-add ~/.ssh/id_rsa
                """)
                if (host != null) {
                    host.credentialsInstalled = true
                }
            }
        }
    }

    /**
     * Attempts to install and configure the RHPKG tool.
     */
    @SuppressWarnings('LineLength')
    static void installRhpkg(Script script, ProvisioningConfig config, ProvisionedHost host = null) {
        genericInstall(script, config, host) {
            privileged, sh ->
            String sudo = privileged ? SUDO : NO_SUDO
            sh("""
                echo "pkgs.devel.redhat.com,10.19.208.80 ssh-rsa AAAAB3NzaC1yc2EAAAABIwAAAQEAplqWKs26qsoaTxvWn3DFcdbiBxqRLhFngGiMYhbudnAj4li9/VwAJqLm1M6YfjOoJrj9dlmuXhNzkSzvyoQODaRgsjCG5FaRjuN8CSM/y+glgCYsWX1HFZSnAasLDuW0ifNLPR2RBkmWx61QKq+TxFDjASBbBywtupJcCsA5ktkjLILS+1eWndPJeSUJiOtzhoN8KIigkYveHSetnxauxv1abqwQTk5PmxRgRt20kZEFSRqZOJUlcl85sZYzNC/G7mneptJtHlcNrPgImuOdus5CW+7W49Z/1xqqWI/iRjwipgEMGusPMlSzdxDX4JzIx6R53pDpAwSAQVGDz4F9eQ==" | ${sudo} tee -a /etc/ssh/ssh_known_hosts

                echo "Host pkgs.devel.redhat.com" | ${sudo} tee -a /etc/ssh/ssh_config
                echo "IdentityFile /home/jenkins/.ssh/id_rsa" | ${sudo} tee -a /etc/ssh/ssh_config

                ${sudo} yum install -y yum-utils git
                curl -L -O http://download.devel.redhat.com/rel-eng/internal/rcm-tools-rhel-7-server.repo
                ${sudo} yum-config-manager --add-repo rcm-tools-rhel-7-server.repo
                ${sudo} yum install -y rhpkg
                git config --global user.name "jenkins"
            """)
            if (host != null) {
                host.rhpkgInstalled = true
            }
        }
    }

    /**
     * A utility function that determines the host where the installation will be attempted.
     * If a provisioned host with a non-null displayName is passed in, the install step will be
     * attempted on that host; otherwise, the install with target the current node.
     */
    static void genericInstall(Script script, ProvisioningConfig config, ProvisionedHost host, Closure installWrapper) {
        // Installation should occur on current node
        if (host == null) {
            installWrapper(NO_SUDO) {
                shCommand ->
                script.sh(shCommand)
            }
            return
        }

        // Installation should occur on target host (JNLP)
        if (config.mode == Mode.JNLP) {
            if (!host.displayName) {
                throw new ProvisioningException('Installing in SSH mode but displayName is invalid.')
            }

            script.node(host.displayName) {
                installWrapper(SUDO) {
                    shCommand ->
                    script.sh(shCommand)
                }
            }
            return
        }

        // Installation should occur on target host (SSH)
        if (config.mode == Mode.SSH) {
            if (!host.hostname) {
                throw new ProvisioningException('Installing in SSH mode but hostname is invalid.')
            }

            installWrapper(NO_SUDO) {
                shCommand ->
                script.writeFile(file:INSTALL_FILE, text:shCommand)
                String runCommandOnHost = 'ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no ' +
                    "-i ~/.ssh/id_rsa root@${host.hostname} < ${INSTALL_FILE}"
                script.sh(runCommandOnHost)
            }
        }
    }
}
