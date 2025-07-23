package host.flux.cli.install

interface Installer {
    /**
     * Installs or upgrades the CLI, detecting the current version automatically.
     * 
     * @return The result of the installation operation
     */
    fun install(): InstallResult
}
